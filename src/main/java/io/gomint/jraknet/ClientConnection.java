package io.gomint.jraknet;

import io.gomint.jraknet.datastructures.BitQueue;
import io.gomint.jraknet.datastructures.DatagramContentNode;
import io.gomint.jraknet.datastructures.FixedSizeRRBuffer;
import io.gomint.jraknet.datastructures.FreeListObjectPool;
import io.gomint.jraknet.datastructures.InstanceCreator;
import io.gomint.jraknet.datastructures.IntQueue;
import io.gomint.jraknet.datastructures.ObjectPool;
import io.gomint.jraknet.datastructures.OrderingHeap;
import io.gomint.jraknet.datastructures.TriadRangeList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.gomint.jraknet.RakNetConstraints.*;

/**
 * @author BlackyPaw
 * @version 1.0
 */
public class ClientConnection implements Socket, Connection {

	private static final Random random = new Random();

	// Logging
	private final Logger logger = LoggerFactory.getLogger( ClientConnection.class );

	// Address the socket was bound to if already bound:
	private DatagramSocket udpSocket;

	// Threads used for modeling network "events"
	private ThreadFactory eventLoopFactory;
	private Thread        receiveThread;
	private Thread        updateThread;

	// Lifecycle
	private AtomicBoolean running = new AtomicBoolean( false );
	private SocketEventHandler eventHandler;

	// Object-Pooling for vast instance created objects:
	private ObjectPool<DatagramPacket> datagramPool;

	// RakNet data:
	private long                          remoteGuid;
	private long                          guid;
	private BlockingQueue<DatagramPacket> incomingDatagrams;

	// Connection Metadata
	private SocketAddress   remoteAddress;
	private ConnectionState state;

	private boolean hasGuid;
	private int     mtuSize;

	private long lastReceivedPacket;

	// Congestion Management
	private int      expectedReliableMessageNumber;
	private BitQueue reliableMessageQueue;
	private int      nextDatagramSequenceNumber;
	private int      expectedDatagramSequenceNumber;

	// Ordering Channels
	private int[]          orderedReadIndex;
	private int[]          orderedWriteIndex;
	private int[]          highestSequencedReadIndex;
	private int[]          highestSequencedWriteIndex;
	private OrderingHeap[] orderingHeaps;
	private long[]         heapWeightOffsets;

	// Split packets
	private Map<Integer, SplitPacketAssembler> splitPacketChannels;

	// Receiving
	private Queue<EncapsulatedPacket> receiveBuffer;

	// Sending
	private Queue<EncapsulatedPacket> sendBuffer;
	private int                       nextReliableMessageNumber;
	private int                       nextSplitPacketID;
	private TriadRangeList            outgoingACKs;
	private TriadRangeList            outgoingNAKs;
	private List<EncapsulatedPacket>  sendList;
	private IntQueue                  sendListIndices;

	// Resending
	private long                                   resendTimeout;
	private FixedSizeRRBuffer<EncapsulatedPacket>  resendBuffer;
	private Queue<EncapsulatedPacket>              resendQueue;
	private FixedSizeRRBuffer<DatagramContentNode> datagramContentBuffer;

	// Disconnect
	private String disconnectMessage = "Connection closed";

	public ClientConnection() {
		this.state = ConnectionState.UNCONNECTED;
		this.lastReceivedPacket = System.currentTimeMillis();
		this.hasGuid = false;
		this.mtuSize = 0;
		this.guid = 0L;
		this.expectedReliableMessageNumber = 0;
		this.nextDatagramSequenceNumber = 0;
		this.expectedDatagramSequenceNumber = 0;
		this.nextReliableMessageNumber = 0;
		this.nextSplitPacketID = 0;
		this.nextDatagramSequenceNumber = 0;
		this.resendTimeout = ServerConnection.DEFAULT_RESEND_TIMEOUT;
	}

	// ============================================ CLIENT CONNECTION ============================================ //

	/**
	 * Initializes the client connection, i.e. creates an UDP socket but does not yet connect to any system.
	 * After the connection has been initialized one may send unconnected pings and / or connect to remote
	 * systems.
	 *
	 * @throws SocketException Thrown in case the internal socket could not be created
	 */
	public void initialize() throws SocketException {
		if ( this.udpSocket != null ) {
			throw new IllegalStateException( "Cannot re-connect ClientConnection if already initialized" );
		}

		this.udpSocket = new DatagramSocket();

		this.running.set( true );
		this.initializeEventLoopFactory();
		this.createObjectPools();
		this.initializeStructures();
		this.startReceiveThread();
		this.startUpdateThread();
	}

	/**
	 * Checks whether or not this client connection has already been initialized.
	 *
	 * @return Whether or not this client connection has been initialized already
	 */
	public boolean isInitialized() {
		return ( this.udpSocket != null );
	}

	/**
	 * Attempts to connect to the specified remote system. This operation will perform asynchronously. Once
	 * the connection attempt succeeds or fails a socket event will be propagated in order to notify the
	 * end user of the operation's result.
	 *
	 * @param host The hostname of the target system
	 * @param port The port of the target system
	 */
	public void connect( String host, int port ) {
		this.connect( new InetSocketAddress( host, port ) );
	}

	/**
	 * Attempts to connect to the specified remote system. This operation will perform asynchronously. Once
	 * the connection attempt succeeds or fails a socket event will be propagated in order to notify the
	 * end user of the operation's result.
	 *
	 * @param address The address of the target system
	 */
	public void connect( InetSocketAddress address ) {
		if ( !this.isInitialized() ) {
			throw new IllegalStateException( "Cannot connect to remote system without initializing connection first" );
		}

		this.generateGuid();
	}

	/**
	 * Sends an unconnected ping to the specified remote system. One must never assume that the ping packet will
	 * reach its destination as it is transferred unreliably. If an unconnected pong packet is ever going to be
	 * received an {@link io.gomint.jraknet.SocketEvent.Type#UNCONNECTED_PONG} event will be filled out and handed
	 * to the socket's event handler.
	 *
	 * @param host The hostname of the target system
	 * @param port The port of the target system
	 */
	public void pingUnconnected( String host, int port ) {
		this.pingUnconnected( new InetSocketAddress( host, port ) );
	}

	/**
	 * Sends an unconnected ping to the specified remote system. One must never assume that the ping packet will
	 * reach its destination as it is transferred unreliably. If an unconnected pong packet is ever going to be
	 * received an {@link io.gomint.jraknet.SocketEvent.Type#UNCONNECTED_PONG} event will be filled out and handed
	 * to the socket's event handler.
	 *
	 * @param address The address of the target system
	 */
	public void pingUnconnected( InetSocketAddress address ) {
		if ( !this.isInitialized() ) {
			throw new IllegalStateException( "Cannot ping remote system without initializing connection first" );
		}

		this.sendUnconnectedPing( address );
	}


	// ============================================ SOCKET ============================================ //

	/**
	 * Sets the event loop factory to be used for internal threads.
	 * <p>
	 * Must be set before the socket is bound otherwise the call will result in an
	 * IllegalStateException.
	 *
	 * @param factory The factory to be used to create internal threads
	 */
	@Override
	public void setEventLoopFactory( ThreadFactory factory ) {
		if ( this.udpSocket != null ) {
			throw new IllegalStateException( "Cannot set event loop factory if a connection was made already" );
		}
		this.eventLoopFactory = factory;
	}

	/**
	 * Sets the event handler that will be notified of any interesting events
	 * occurring on this socket.
	 *
	 * @param handler The handler to be notified of any events on this socket
	 */
	@Override
	public void setEventHandler( SocketEventHandler handler ) {
		this.eventHandler = handler;
	}

	// ============================================ CONNECTION ============================================ //
	@Override
	public void close() throws Exception {
		// Stop all threads safely:
		this.running.set( false );
		try {
			this.updateThread.join();
		} catch ( InterruptedException ignored ) {
			// ._.
		} finally {
			this.updateThread = null;
		}

		try {
			this.receiveThread.join();
		} catch ( InterruptedException ignored ) {
			// ._.
		} finally {
			this.receiveThread = null;
		}

		// Destroy object pools:
		this.datagramPool = null;

		// Close the UDP socket:
		this.udpSocket.close();
	}

	@Override
	public SocketAddress getAddress() {
		return null;
	}

	@Override
	public ConnectionState getState() {
		return null;
	}

	@Override
	public boolean isConnecting() {
		return false;
	}

	@Override
	public boolean isConnected() {
		return false;
	}

	@Override
	public boolean isDisconnecting() {
		return false;
	}

	@Override
	public void disconnect( String reason ) {

	}

	@Override
	public String getDisconnectMessage() {
		return null;
	}

	@Override
	public int getMtuSize() {
		return 0;
	}

	@Override
	public long getGuid() {
		return 0;
	}

	@Override
	public byte[] receive() {
		return new byte[0];
	}

	@Override
	public void send( byte[] data ) {

	}

	@Override
	public void send( PacketReliability reliability, byte[] data ) {

	}

	@Override
	public void send( PacketReliability reliability, int orderingChannel, byte[] data ) {

	}

	@Override
	public void send( PacketReliability reliability, int orderingChannel, byte[] data, int offset, int length ) {

	}

	@Override
	public void sendCopy( byte[] data ) {

	}

	@Override
	public void sendCopy( PacketReliability reliability, byte[] data ) {

	}

	@Override
	public void sendCopy( PacketReliability reliability, int orderingChannel, byte[] data ) {

	}

	@Override
	public void sendCopy( PacketReliability reliability, int orderingChannel, byte[] data, int offset, int length ) {

	}

	// ============================================ INTERNALS ============================================ //

	/**
	 * Updates all connection that belong to this server socket.
	 */
	private void update() {
		while ( this.running.get() ) {
			long start = System.currentTimeMillis();

			if ( this.state == ConnectionState.INITIALIZING && this.lastReceivedPacket + 10000 < start ) {
				// The connection attempt timed out:
				this.state = ConnectionState.UNCONNECTED;
			}

			// Handle all incoming datagrams:
			DatagramPacket datagram;
			while ( !this.incomingDatagrams.isEmpty() ) {
				try {
					datagram = this.incomingDatagrams.take();
					this.handleConnectedDatagram( datagram );
					this.datagramPool.putBack( datagram );
				} catch ( InterruptedException e ) {
					this.logger.error( "Failed to handle incoming datagram", e );
				}
			}

			// Update this connection:

			long end = System.currentTimeMillis();

			if ( end - start < 10L ) { // Update 100 times per second if possible
				try {
					Thread.sleep( 10L - ( end - start ) );
				} catch ( InterruptedException ignored ) {
					// ._.
				}
			}
		}
	}

	/**
	 * Polls the socket's internal datagram socket and pushes off any received datagrams
	 * to dedicated handlers that will decode the datagram into actual data packets.
	 */
	private void pollUdpSocket() {
		while ( this.running.get() ) {
			DatagramPacket datagram = this.datagramPool.allocate();
			try {
				this.udpSocket.receive( datagram );

				if ( datagram.getLength() == 0 ) {
					return;
				}

				this.handleDatagram( datagram );
			} catch ( IOException e ) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Generates the client's globally unique identifier.
	 */
	private void generateGuid() {
		this.guid = random.nextLong();
	}

	/**
	 * Initializes the socket's event loop factory if it does not yet have one set.
	 */
	private void initializeEventLoopFactory() {
		if ( this.eventLoopFactory != null ) {
			return;
		}

		// Construct default event loop factory:
		this.eventLoopFactory = new ThreadFactory() {
			private ThreadGroup group = new ThreadGroup( "jRakNet-ServerSocket" );
			private AtomicInteger id = new AtomicInteger( 0 );

			public Thread newThread( Runnable r ) {
				return new Thread( this.group, r, "EventLoop-" + Integer.toString( id.incrementAndGet() ) );
			}
		};
	}

	/**
	 * Creates all object pools used internally for reducing the number of created instances
	 * of certain objects.
	 */
	private void createObjectPools() {
		this.datagramPool = new FreeListObjectPool<>( new InstanceCreator<DatagramPacket>() {
			public DatagramPacket createInstance( ObjectPool<DatagramPacket> pool ) {
				// We allocate buffers able to hold twice the maximum MTU size for the reasons listed below:
				//
				// Somehow I noticed receiving datagrams that exceeded their respective connection's MTU by
				// far whenever they contained a Batch Packet. As this behaviour comes out of seemingly
				// nowhere yet we do need to receive these batch packets we must have room enough to actually
				// gather all this data even though it does not seem legit to allocate larger buffers for this
				// reason. But as the underlying DatagramSocket provides no way of grabbing the minimum required
				// buffer size for the datagram we are forced to play this dirty trick. If - at any point in the
				// future - this behaviour changes, please add this buffer size back to its original value:
				// MAXIMUM_MTU_SIZE.
				//
				// Examples of too large datagrams:
				//  - Datagram containing BatchPacket for LoginPacket: 1507 bytes the batch packet alone (5th of March 2016)
				//
				// Suggestions:
				//  - Make this value configurable in order to easily adjust this value whenever necessary
				final int INTERNAL_BUFFER_SIZE = MAXIMUM_MTU_SIZE << 1;

				return new DatagramPacket( new byte[INTERNAL_BUFFER_SIZE], INTERNAL_BUFFER_SIZE );
			}
		} );
	}

	/**
	 * Initializes any sort of structures that are required internally.
	 */
	private void initializeStructures() {
		this.incomingDatagrams = new LinkedBlockingQueue<>( 512 );
	}

	/**
	 * Starts the thread that will continuously poll the UDP socket for incoming
	 * datagrams.
	 */
	private void startReceiveThread() {
		this.receiveThread = this.eventLoopFactory.newThread( new Runnable() {
			public void run() {
				ClientConnection.this.pollUdpSocket();
			}
		} );

		this.receiveThread.start();
	}

	/**
	 * Starts the thread that will continuously update all currently connected player's
	 * connections.
	 */
	private void startUpdateThread() {
		this.updateThread = this.eventLoopFactory.newThread( new Runnable() {
			@Override
			public void run() {
				ClientConnection.this.update();
			}
		} );

		this.updateThread.start();
	}

	// ============================================ DATAGRAM HANDLING ============================================ //

	/**
	 * Handles the specified datagram by decomposing all encapsulated packets.
	 *
	 * @param datagram The datagram to handle
	 */
	private void handleDatagram( DatagramPacket datagram ) {
		// Test for unconnected packet IDs:
		byte packetId = datagram.getData()[0];

		// Handle unconnected pings immediately:
		if ( packetId == UNCONNECTED_PONG ) {
			this.handleUnconnectedPong( datagram );
			this.datagramPool.putBack( datagram );
			return;
		}

		// Push datagram to update queue:
		try {
			this.incomingDatagrams.put( datagram );
		} catch ( InterruptedException e ) {
			this.logger.error( "Failed to handle incoming datagram", e );
		}
	}


	private void handleUnconnectedPong( DatagramPacket datagram ) {
		PacketBuffer buffer = new PacketBuffer( datagram.getData(), datagram.getOffset() );
		buffer.skip( 1 );                   // Packet ID

		long   pingTime   = buffer.readLong();
		long   serverGuid = buffer.readLong();
		String motd       = null;
		if ( buffer.getRemaining() > 0 ) {
			int    motdLength = buffer.readUShort();
			byte[] motdBytes  = new byte[motdLength];
			buffer.readBytes( motdBytes );

			motd = new String( motdBytes, StandardCharsets.US_ASCII );
		}

		// Fill out socket event:
		if ( this.eventHandler != null ) {
			SocketEvent.PingPongInfo info = new SocketEvent.PingPongInfo( datagram.getSocketAddress(), pingTime, System.currentTimeMillis(), serverGuid, motd );
			SocketEvent event = new SocketEvent( SocketEvent.Type.UNCONNECTED_PONG, info );
			this.eventHandler.onSocketEvent( this, event );
		}
	}

	// ============================================ PACKET HANDLING ============================================ //

	private void handleConnectedDatagram( DatagramPacket datagram ) {
		byte packetId = datagram.getData()[0];

		switch ( packetId ) {
			case OPEN_CONNECTION_REPLY_1:
				this.handlePreConnectionReply1( datagram );
				break;
			case OPEN_CONNECTION_REPLY_2:
				this.handlePreConnectionReply2( datagram );
				break;
			default:
				this.handleEncapsulatedDatagram( datagram );
				break;
		}
	}

	private void handlePreConnectionReply1( DatagramPacket datagram ) {
		PacketBuffer buffer = new PacketBuffer( datagram.getData(), datagram.getOffset() );
		buffer.skip( 1 );                                       // Packet ID
		buffer.readOfflineMessageDataId();                      // Offline Message Data ID
		this.remoteGuid = buffer.readLong();                    // Server GUID
		boolean securityEnabled = buffer.readBoolean();         // Security Enabled
		this.mtuSize = buffer.readUShort();                     // MTU Size

		if ( securityEnabled ) {
			// We don't support security:
			this.state = ConnectionState.UNCONNECTED;
			return;
		}

		this.sendPreConnectionRequest2( datagram.getSocketAddress() );
	}

	private void handlePreConnectionReply2( DatagramPacket datagram ) {

	}

	private void handleEncapsulatedDatagram( DatagramPacket datagram ) {
		
	}

	// ============================================ PACKET SENDERS ============================================ //

	private void sendUnconnectedPing( SocketAddress recipient ) {
		PacketBuffer buffer = new PacketBuffer( 9 );
		buffer.writeByte( UNCONNECTED_PING );
		buffer.writeLong( System.currentTimeMillis() );

		try {
			this.udpSocket.send( new DatagramPacket( buffer.getBuffer(), 0, 9, recipient ) );
		} catch ( IOException e ) {
			// ._.
		}
	}

	private void sendPreConnectionRequest1( SocketAddress recipient ) {
		this.state = ConnectionState.INITIALIZING;

		PacketBuffer buffer = new PacketBuffer( MAXIMUM_MTU_SIZE );
		buffer.writeByte( OPEN_CONNECTION_REQUEST_1 );
		buffer.writeOfflineMessageDataId();
		buffer.writeByte( RAKNET_PROTOCOL_VERSION );

		// Simulate filling with zeroes, in order to "test out" maximum MTU size:
		buffer.skip( buffer.getRemaining() );

		try {
			this.udpSocket.send( new DatagramPacket( buffer.getBuffer(), 0, buffer.getPosition(), recipient ) );
		} catch ( IOException e ) {
			// ._.
		}
	}

	private void sendPreConnectionRequest2( SocketAddress recipient ) {
		PacketBuffer buffer = new PacketBuffer( 34 );
		buffer.writeByte( OPEN_CONNECTION_REQUEST_2 );          // Packet ID
		buffer.writeOfflineMessageDataId();                     // Offline Message Data ID
		buffer.writeAddress( recipient );                       // Client Bind Address
		buffer.writeUShort( this.mtuSize );                     // MTU size

	}

}
