package io.gomint.jraknet;

import io.gomint.jraknet.datastructures.BinaryOrderingHeap;
import io.gomint.jraknet.datastructures.BitQueue;
import io.gomint.jraknet.datastructures.DatagramContentNode;
import io.gomint.jraknet.datastructures.FixedSizeRRBuffer;
import io.gomint.jraknet.datastructures.FreeListObjectPool;
import io.gomint.jraknet.datastructures.InstanceCreator;
import io.gomint.jraknet.datastructures.IntQueue;
import io.gomint.jraknet.datastructures.ObjectPool;
import io.gomint.jraknet.datastructures.OrderingHeap;
import io.gomint.jraknet.datastructures.TriadRange;
import io.gomint.jraknet.datastructures.TriadRangeList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
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
	private long lastSentPacket;

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
		this.lastSentPacket = this.lastReceivedPacket;
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
		this.udpSocket.setBroadcast( true );

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
		this.sendPreConnectionRequest1( address );
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
		if ( !this.isInitialized() ) {
			throw new IllegalStateException( "Cannot ping remote system without initializing connection first" );
		}

		try {
			this.sendUnconnectedPing( InetAddress.getByName( host ), port );
		} catch ( UnknownHostException e ) {
			e.printStackTrace();
		}
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

	/**
	 * Sends an unconnected ping to the specified remote system. One must never assume that the ping packet will
	 * reach its destination as it is transferred unreliably. If an unconnected pong packet is ever going to be
	 * received an {@link io.gomint.jraknet.SocketEvent.Type#UNCONNECTED_PONG} event will be filled out and handed
	 * to the socket's event handler.
	 *
	 * @param address The address of the target system
	 * @param port The port of the target system
	 */
	public void pingUnconnected( InetAddress address, int port ) {
		if ( !this.isInitialized() ) {
			throw new IllegalStateException( "Cannot ping remote system without initializing connection first" );
		}

		this.sendUnconnectedPing( address, port );
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
		return this.remoteAddress;
	}

	@Override
	public ConnectionState getState() {
		return this.state;
	}

	@Override
	public boolean isConnecting() {
		return ( this.getState() == ConnectionState.CONNECTING );
	}

	@Override
	public boolean isConnected() {
		return ( this.getState() == ConnectionState.CONNECTED );
	}

	@Override
	public boolean isDisconnecting() {
		return ( this.getState() == ConnectionState.DISCONNECTING );
	}

	@Override
	public byte[] receive() {
		synchronized ( this.receiveBuffer ) {
			if ( this.receiveBuffer.isEmpty() ) {
				return null;
			}
			return this.receiveBuffer.poll().getPacketData();
		}
	}

	@Override
	public void disconnect( String reason ) {
		if ( this.isConnected() ) {
			this.disconnectMessage = reason;
			this.sendDisconnectionNotification();
			this.state = ConnectionState.DISCONNECTING;
		}
	}

	@Override
	public String getDisconnectMessage() {
		return this.disconnectMessage;
	}

	/**
	 * Returns the connection's MTU size.
	 *
	 * @return The connection's MTU size.
	 */
	@Override
	public int getMtuSize() {
		return this.mtuSize;
	}

	/**
	 * Gets the remote peer's RakNet globally unique identifier.
	 *
	 * @return The remote peer's GUID
	 */
	@Override
	public long getGuid() {
		return this.remoteGuid;
	}

	@Override
	public void send( byte[] data ) {
		this.send( PacketReliability.RELIABLE, 0, data, 0, data.length );
	}

	@Override
	public void send( PacketReliability reliability, byte[] data ) {
		this.send( reliability, 0, data, 0, data.length );
	}

	@Override
	public void send( PacketReliability reliability, int orderingChannel, byte[] data ) {
		this.send( reliability, orderingChannel, data, 0, data.length );
	}

	@Override
	public void send( PacketReliability reliability, int orderingChannel, byte[] data, int offset, int length ) {
		if ( !this.state.isReliable() || reliability == null || orderingChannel < 0 || orderingChannel >= NUM_ORDERING_CHANNELS || data == null || this.state == ConnectionState.DISCONNECTING ) {
			return;
		}

		EncapsulatedPacket packet = new EncapsulatedPacket();

		// Got to copy packet data if it is not aligned correctly:
		if ( offset != 0 || length != data.length ) {
			packet.setPacketData( Arrays.copyOfRange( data, offset, offset + length ) );
		} else {
			packet.setPacketData( data );
		}

		// Test if this packet must be split up:
		int maxSize = this.mtuSize - DATA_HEADER_BYTE_LENGTH - MAX_MESSAGE_HEADER_BYTE_LENGTH;
		if ( packet.getPacketLength() > maxSize ) {
			// Yes, it does, so adjust reliability if necessary:
			switch ( reliability ) {
				case UNRELIABLE:
					reliability = PacketReliability.RELIABLE;
					break;
				case UNRELIABLE_SEQUENCED:
					reliability = PacketReliability.RELIABLE_SEQUENCED;
					break;
				case UNRELIABLE_WITH_ACK_RECEIPT:
					reliability = PacketReliability.UNRELIABLE_WITH_ACK_RECEIPT;
					break;
			}
		}

		// Give the packet all meta-information and counters it requires:
		if ( reliability == PacketReliability.UNRELIABLE_SEQUENCED || reliability == PacketReliability.RELIABLE_SEQUENCED ) {
			packet.setOrderingChannel( (byte) orderingChannel );
			packet.setOrderingIndex( this.orderedWriteIndex[orderingChannel] );
			packet.setSequencingIndex( this.highestSequencedWriteIndex[orderingChannel]++ );
		} else if ( reliability == PacketReliability.RELIABLE_ORDERED || reliability == PacketReliability.RELIABLE_ORDERED_WITH_ACK_RECEIPT ) {
			packet.setOrderingChannel( (byte) orderingChannel );
			packet.setOrderingIndex( this.orderedWriteIndex[orderingChannel]++ );
			this.highestSequencedWriteIndex[orderingChannel] = 0;
		}

		packet.setReliability( reliability );
		if ( packet.getPacketLength() > maxSize ) {
			// Split up this packet:
			this.splitPacket( packet );
		} else {
			// Add it to the send buffer immediately:
			synchronized ( this.sendBuffer ) {
				this.sendBuffer.add( packet );
			}
		}
	}

	@Override
	public void sendCopy( byte[] data ) {
		this.send( Arrays.copyOf( data, data.length ) );
	}

	@Override
	public void sendCopy( PacketReliability reliability, byte[] data ) {
		this.send( reliability, Arrays.copyOf( data, data.length ) );
	}

	@Override
	public void sendCopy( PacketReliability reliability, int orderingChannel, byte[] data ) {
		this.send( reliability, orderingChannel, Arrays.copyOf( data, data.length ) );
	}

	@Override
	public void sendCopy( PacketReliability reliability, int orderingChannel, byte[] data, int offset, int length ) {
		this.send( reliability, orderingChannel, Arrays.copyOfRange( data, offset, offset + length ) );
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
				if ( this.eventHandler != null ) {
					SocketEvent event = new SocketEvent( SocketEvent.Type.CONNECTION_ATTEMPT_FAILED, "Connection attempt timed out" );
					this.eventHandler.onSocketEvent( this, event );
				}
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
			if ( this.state.isReliable() && this.lastReceivedPacket + CONNECTION_TIMEOUT_MILLIS < start ) {
				this.disconnectMessage = "Connection timed out";
				this.state = ConnectionState.UNCONNECTED;
				if ( this.eventHandler != null ) {
					SocketEvent event = new SocketEvent( SocketEvent.Type.CONNECTION_CLOSED, this );
					this.eventHandler.onSocketEvent( this, event );
				}
			} else {
				// Send connected ping if necessary:
				if ( this.isConnected() && this.lastSentPacket + 2000L < start ) {
					this.sendConnectedPing( start );
				}
				this.updateConnection( start );
			}

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


	private boolean updateConnection( long time ) {
		if ( !this.state.isReliable() ) {
			return true;
		}

		this.sendACKs();
		this.sendNAKs();

		int currentDatagramSize = 0;
		int maxDatagramSize     = this.mtuSize - DATA_HEADER_BYTE_LENGTH;

		// Resend everything scheduled for resend:
		while ( !this.resendQueue.isEmpty() ) {
			EncapsulatedPacket packet = this.resendQueue.peek();
			if ( packet.getNextInteraction() <= time ) {

				// Delete packets marked for removal:
				if ( packet.getNextInteraction() == 0L ) {
					this.resendQueue.poll();
					continue;
				}

				// Push current datagram to send queue if adding this packet would exceed the MTU:
				int length = packet.getHeaderLength() + packet.getPacketLength();
				if ( currentDatagramSize + length > maxDatagramSize ) {
					// Push datagram:
					this.sendListIndices.add( this.sendList.size() );
					currentDatagramSize = 0;
				}

				this.resendQueue.poll();
				packet.setNextInteraction( time + this.resendTimeout );

				this.sendList.add( packet );
				packet.incrementSendCount();
				currentDatagramSize += length;

				// Insert back into resend queue:
				this.resendQueue.add( packet );
			} else {
				break;
			}
		}

		// Attempt to send new packets:
		synchronized ( this.sendBuffer ) {
			while ( !this.sendBuffer.isEmpty() && this.resendBuffer.get( this.nextReliableMessageNumber ) == null ) {
				EncapsulatedPacket packet = this.sendBuffer.poll();

				// Push current datagram to send queue if adding this packet would exceed the MTU:
				int length = packet.getHeaderLength() + packet.getPacketLength();
				if ( currentDatagramSize + length > maxDatagramSize ) {
					// Push datagram:
					this.sendListIndices.add( this.sendList.size() );
					currentDatagramSize = 0;
				}

				PacketReliability reliability = packet.getReliability();
				if ( reliability == PacketReliability.RELIABLE ||
				     reliability == PacketReliability.RELIABLE_SEQUENCED ||
				     reliability == PacketReliability.RELIABLE_ORDERED ) {
					packet.setReliableMessageNumber( this.nextReliableMessageNumber );

					// Insert into resend queue:
					packet.setNextInteraction( time + this.resendTimeout );
					this.resendQueue.add( packet );

					// Add to FixedSize round-robin resend buffer:
					this.resendBuffer.set( this.nextReliableMessageNumber, packet );

					++this.nextReliableMessageNumber;
				}

				this.sendList.add( packet );
				packet.incrementSendCount();
				currentDatagramSize += length;
			}
		}

		// Push the final datagram if any is to be pushed:
		if ( currentDatagramSize > 0 ) {
			this.sendListIndices.add( this.sendList.size() );
		}

		// Now finally build datagrams and send them out after all this surrounding handling:
		if ( !this.sendListIndices.isEmpty() ) {
			PacketBuffer        buffer = new PacketBuffer( this.mtuSize );
			DatagramContentNode dcn    = null;

			for ( int i = 0; i < this.sendListIndices.size(); ++i ) {
				int min, max;

				if ( i == 0 ) {
					min = 0;
					max = this.sendListIndices.get( i );
				} else {
					min = this.sendListIndices.get( i - 1 );
					max = this.sendListIndices.get( i );
				}

				// Write datagram header:
				byte flags = (byte) ( 0x80 | ( i > 0 ? 0x8 : 0x0 ) );     // IsValid | (isContinuousSend)
				buffer.writeByte( flags );
				buffer.writeTriad( this.nextDatagramSequenceNumber );

				for ( int j = min; j < max; ++j ) {
					EncapsulatedPacket packet = this.sendList.get( j );

					// Add this packet to the datagram content buffer if reliable:
					if ( packet.getReliability() != PacketReliability.UNRELIABLE && packet.getReliability() != PacketReliability.UNRELIABLE_SEQUENCED ) {
						if ( dcn == null ) {
							dcn = new DatagramContentNode( packet.getReliableMessageNumber() );
							this.datagramContentBuffer.set( this.nextDatagramSequenceNumber, dcn );
						} else {
							dcn.setNext( new DatagramContentNode( packet.getReliableMessageNumber() ) );
							dcn = dcn.getNext();
						}
					}

					packet.writeToBuffer( buffer );
				}

				if ( dcn == null ) {
					// TODO: Clear dcn here
					this.datagramContentBuffer.set( this.nextDatagramSequenceNumber, null );
				}

				// Finally send this packet buffer to its destination:
				try {
					this.send( this.remoteAddress, buffer );
				} catch ( IOException e ) {
					this.logger.error( "Failed to send datagram to destination", e );
				}

				this.lastSentPacket = time;
				++this.nextDatagramSequenceNumber;

				if ( i + 1 < this.sendListIndices.size() ) {
					buffer = new PacketBuffer( this.mtuSize );
				}
			}

			this.sendList.clear();
			this.sendListIndices.clear();
		}

		if ( this.state == ConnectionState.DISCONNECTING ) {
			// Check if we can perform a clean disconnect now:
			if ( this.resendQueue.isEmpty() && this.sendBuffer.isEmpty() ) {
				this.state = ConnectionState.UNCONNECTED;
				if ( this.eventHandler != null ) {
					SocketEvent event = new SocketEvent( SocketEvent.Type.CONNECTION_DISCONNECTED, this );
					this.eventHandler.onSocketEvent( this, event );
				}
				return false;
			}

			// Fake not timing out in order to fully send all packets still in queue:
			this.lastReceivedPacket = time;
		}

		return true;
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
					continue;
				}

				this.handleDatagram( datagram );
			} catch ( IOException e ) {
				e.printStackTrace();
			} finally {
				this.datagramPool.putBack( datagram );
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

	/**
	 * Initializes all internal structures that are quite memory-consuming.
	 */
	private void initializeConnectionStructures() {
		this.expectedReliableMessageNumber = 0;
		this.nextDatagramSequenceNumber = 0;
		this.expectedDatagramSequenceNumber = 0;
		this.nextReliableMessageNumber = 0;
		this.nextSplitPacketID = 0;
		this.nextDatagramSequenceNumber = 0;
		this.reliableMessageQueue = new BitQueue( 512 );
		this.orderedReadIndex = new int[NUM_ORDERING_CHANNELS];
		this.orderedWriteIndex = new int[NUM_ORDERING_CHANNELS];
		this.highestSequencedReadIndex = new int[NUM_ORDERING_CHANNELS];
		this.highestSequencedWriteIndex = new int[NUM_ORDERING_CHANNELS];
		this.orderingHeaps = new BinaryOrderingHeap[NUM_ORDERING_CHANNELS];
		this.heapWeightOffsets = new long[NUM_ORDERING_CHANNELS];
		for ( int i = 0; i < NUM_ORDERING_CHANNELS; ++i ) {
			this.orderingHeaps[i] = new BinaryOrderingHeap();
		}

		this.splitPacketChannels = new HashMap<>();
		this.receiveBuffer = new LinkedList<>();
		this.sendBuffer = new LinkedList<>();
		this.outgoingACKs = new TriadRangeList( 128 );
		this.outgoingNAKs = new TriadRangeList( 128 );
		this.sendList = new ArrayList<>( 32 );
		this.sendListIndices = new IntQueue();
		this.resendBuffer = new FixedSizeRRBuffer<>( 256 );
		this.resendQueue = new LinkedList<>();
		this.datagramContentBuffer = new FixedSizeRRBuffer<>( 256 );
	}

	/**
	 * Splits the given packet up into smaller packets and fills out all required header information.
	 * All smaller packets will be added to the send buffer immediately.
	 *
	 * @param packet The packet to be split into pieces
	 */
	private void splitPacket( EncapsulatedPacket packet ) {
		int bytesPerDatagram = this.mtuSize - DATA_HEADER_BYTE_LENGTH - MAX_MESSAGE_HEADER_BYTE_LENGTH;
		int splitPacketCount = ( ( packet.getPacketLength() - 1 ) / bytesPerDatagram ) + 1;
		int splitPacketID    = this.nextSplitPacketID++;

		// Simulate overflow to zero:
		if ( splitPacketID == ( 1 << 16 ) ) {
			splitPacketID = 0;
		}

		final int length = packet.getPacketLength();
		int       cursor = 0, count;
		for ( int splitPacketIndex = 0; splitPacketIndex < splitPacketCount; ++splitPacketIndex ) {
			count = length - cursor;
			if ( count > bytesPerDatagram ) {
				count = bytesPerDatagram;
			}

			EncapsulatedPacket copy = new EncapsulatedPacket( packet );
			copy.setPacketData( Arrays.copyOfRange( packet.getPacketData(), cursor, cursor + count ) );
			copy.setSplitPacketId( splitPacketID );
			copy.setSplitPacketIndex( splitPacketIndex );
			copy.setSplitPacketCount( splitPacketCount );
			this.sendBuffer.add( copy );

			cursor += count;
		}
	}

	/**
	 * Sends the given data to the specified recipient immediately, i.e. without caching nor any form of
	 * transmission control (reliability, resending, etc.)
	 *
	 * @param recipient The recipient of the data
	 * @param buffer    The buffer to transmit
	 *
	 * @throws IOException Thrown if the transmission fails
	 */
	void send( SocketAddress recipient, PacketBuffer buffer ) throws IOException {
		this.send( recipient, buffer.getBuffer(), buffer.getBufferOffset(), buffer.getPosition() - buffer.getBufferOffset() );
	}

	/**
	 * Sends the given data to the specified recipient immediately, i.e. without caching nor any form of
	 * transmission control (reliability, resending, etc.)
	 *
	 * @param recipient The recipient of the data
	 * @param buffer    The buffer holding the data to send
	 * @param offset    The offset into the buffer
	 * @param length    The length of the data chunk to send
	 *
	 * @throws IOException Thrown if the transmission fails
	 */
	void send( SocketAddress recipient, byte[] buffer, int offset, int length ) throws IOException {
		this.udpSocket.send( new DatagramPacket( buffer, offset, length, recipient ) );
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
			case ALREADY_CONNECTED:
				this.handleAlreadyConnected( datagram );
				break;
			case NO_FREE_INCOMING_CONNECTIONS:
				this.handleNoFreeIncomingConnections( datagram );
				break;
			case OPEN_CONNECTION_REPLY_2:
				this.handlePreConnectionReply2( datagram );
				break;
			case CONNECTION_REQUEST_FAILED:
				this.handleConnectionRequestFailed( datagram );
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
			if ( this.eventHandler != null ) {
				SocketEvent event = new SocketEvent( SocketEvent.Type.CONNECTION_ATTEMPT_FAILED, "Security is not supported" );
				this.eventHandler.onSocketEvent( this, event );
			}
			return;
		}

		this.sendPreConnectionRequest2( datagram.getSocketAddress() );
	}

	private void handlePreConnectionReply2( DatagramPacket datagram ) {
		if ( this.state != ConnectionState.INITIALIZING ) {
			return;
		}

		PacketBuffer buffer = new PacketBuffer( datagram.getData(), datagram.getOffset() );
		buffer.skip( 1 );                                       // Packet ID
		buffer.readOfflineMessageDataId();                      // Offline Message Data ID
		this.remoteGuid = buffer.readLong();                    // Server GUID
		this.mtuSize = buffer.readUShort();                     // MTU Size
		boolean securityEnabled = buffer.readBoolean();         // Security Enabled

		/* if ( securityEnabled ) {
			// We don't support security:
			this.state = ConnectionState.UNCONNECTED;
			if ( this.eventHandler != null ) {
				SocketEvent event = new SocketEvent( SocketEvent.Type.CONNECTION_ATTEMPT_FAILED, "Security is not supported" );
				this.eventHandler.onSocketEvent( this, event );
			}
			return;
		} */

		this.initializeConnectionStructures();
		this.remoteAddress = datagram.getSocketAddress();
		this.state = ConnectionState.RELIABLE;

		this.sendConnectionRequest( datagram.getSocketAddress() );
	}

	private void handleAlreadyConnected( DatagramPacket datagram ) {
		this.state = ConnectionState.UNCONNECTED;

		if ( this.eventHandler != null ) {
			SocketEvent event = new SocketEvent( SocketEvent.Type.CONNECTION_ATTEMPT_FAILED, "System is already connected" );
			this.eventHandler.onSocketEvent( this, event );
		}
	}

	private void handleNoFreeIncomingConnections( DatagramPacket datagram ) {
		this.state = ConnectionState.UNCONNECTED;

		if ( this.eventHandler != null ) {
			SocketEvent event = new SocketEvent( SocketEvent.Type.CONNECTION_ATTEMPT_FAILED, "Remote peer has no free incoming connections" );
			this.eventHandler.onSocketEvent( this, event );
		}
	}

	private void handleConnectionRequestFailed( DatagramPacket datagram ) {
		this.state = ConnectionState.UNCONNECTED;

		if ( this.eventHandler != null ) {
			SocketEvent event = new SocketEvent( SocketEvent.Type.CONNECTION_ATTEMPT_FAILED, "Remote peer sent CONNECTION_REQUEST_FAILED" );
			this.eventHandler.onSocketEvent( this, event );
		}
	}

	private void handleEncapsulatedDatagram( DatagramPacket datagram ) {
		if ( !this.state.isReliable() ) {
			// This connection is not reliable --> internal structures might not have been initialized
			return;
		}

		// Deserialize datagram header:
		PacketBuffer buffer  = new PacketBuffer( datagram.getData(), datagram.getOffset() );
		byte         flags   = buffer.readByte();
		boolean      isValid = ( flags & 0x80 ) != 0;
		if ( !isValid ) {
			// Not an encapsulated packet --> Discard
			this.logger.debug( "Discarding invalid packet" );
			return;
		}

		boolean isACK = ( flags & 0x40 ) != 0;
		if ( isACK ) {
			// This datagram only contains ACKs --> Handle separately
			this.handleACKs( buffer );
			return;
		}

		// Only handling ACKs if disconnecting --> makes room in resend buffer which might block clearing
		// the send buffer in order to disconnect cleanly:
		if ( this.state == ConnectionState.DISCONNECTING ) {
			// Do not receive any further data from this connection
			return;
		}

		boolean isNAK = ( flags & 0x20 ) != 0;
		if ( isNAK ) {
			// This datagram only contains NAKs --> Handle separately
			this.handleNAKs( buffer );
			return;
		}

		int datagramSequenceNumber = buffer.readTriad();
		int skippedMessageCount    = 0;
		if ( datagramSequenceNumber == this.expectedDatagramSequenceNumber ) {
			this.expectedDatagramSequenceNumber++;
		} else if ( datagramSequenceNumber > this.expectedDatagramSequenceNumber ) {
			this.expectedDatagramSequenceNumber = datagramSequenceNumber + 1;
			skippedMessageCount = ( datagramSequenceNumber - this.expectedDatagramSequenceNumber );
		}

		// NAK all datagrams missing in between:
		for ( int i = skippedMessageCount; i > 0; --i ) {
			this.outgoingNAKs.insert( datagramSequenceNumber - i );
		}

		// ACK this datagram:
		this.outgoingACKs.insert( datagramSequenceNumber );

		EncapsulatedPacket packet = new EncapsulatedPacket();
		while ( buffer.getPosition() - buffer.getBufferOffset() < datagram.getLength() && packet.readFromBuffer( buffer ) ) {
			if ( packet.isSplitPacket() ) {
				packet = this.rebuildSplitPacket( packet );
				if ( packet == null ) {
					packet = new EncapsulatedPacket();
					continue;
				}
			}

			PacketReliability reliability     = packet.getReliability();
			int               orderingIndex   = packet.getOrderingIndex();
			byte              orderingChannel = packet.getOrderingChannel();

			// Take not of this packet in order to force resend of possibly lost
			// reliable messages:
			if ( reliability == PacketReliability.RELIABLE ||
			     reliability == PacketReliability.RELIABLE_SEQUENCED ||
			     reliability == PacketReliability.RELIABLE_ORDERED ) {
				int holes = ( packet.getReliableMessageNumber() - this.expectedReliableMessageNumber );

				if ( holes > 0 ) {
					if ( holes < this.reliableMessageQueue.size() ) {
						if ( this.reliableMessageQueue.get( holes ) ) {
							this.reliableMessageQueue.set( holes, false );
						} else {
							// Packet was already received (Duplicate) --> Discard
							continue;
						}
					} else {
						// Got to fill up the queue with true s indicating missing packets in between:
						int count = ( holes - this.reliableMessageQueue.size() );
						for ( int i = 0; i < count; ++i ) {
							this.reliableMessageQueue.add( true );
						}

						// We did receive this packet though!
						this.reliableMessageQueue.add( false );
					}
				} else if ( holes == 0 ) {
					++this.expectedReliableMessageNumber;
					if ( !this.reliableMessageQueue.isEmpty() ) {
						this.reliableMessageQueue.poll();
					}
				} else {
					// Packet was already received (Duplicate) --> Discard
					continue;
				}

				// Maybe we finally received a packet that was blocking the rest of the queue before
				// Check if this is the case and if so adjust the message index and queue appropriately:
				while ( !this.reliableMessageQueue.isEmpty() && !this.reliableMessageQueue.peek() ) {
					this.reliableMessageQueue.poll();
					++this.expectedReliableMessageNumber;
				}
			}

			// Now handle decoded packet according to reliability:
			if ( reliability == PacketReliability.RELIABLE_SEQUENCED ||
			     reliability == PacketReliability.UNRELIABLE_SEQUENCED ||
			     reliability == PacketReliability.RELIABLE_ORDERED ) {
				// Is sequenced or ordered

				if ( orderingIndex == this.orderedReadIndex[orderingChannel] ) {
					// Has latest ordering index

					if ( reliability == PacketReliability.RELIABLE_SEQUENCED || reliability == PacketReliability.UNRELIABLE_SEQUENCED ) {
						// Is sequenced

						int sequencingIndex = packet.getSequencingIndex();
						if ( sequencingIndex >= this.highestSequencedReadIndex[orderingChannel] ) {
							// Is newer than any previous sequenced packets:

							this.highestSequencedReadIndex[orderingChannel] = sequencingIndex + 1;
							// Pass on to user
							this.pushReceivedPacket( packet );
						} // else {
						// Is coming out of band

						// Simply parse next packet:
						// continue;
						// }
					} else {
						// Is ordered

						// Pass on to user
						this.pushReceivedPacket( packet );
						this.orderedReadIndex[orderingChannel]++;
						this.highestSequencedReadIndex[orderingChannel] = 0;

						// IMPORTANT: Consider implementing Fast Binary Heap by Peter Sanders here!
						// Return all packets that have been sorted into the heap after this packet's ordering index:
						OrderingHeap heap = this.orderingHeaps[orderingChannel];
						while ( !heap.isEmpty() && heap.peek().getOrderingIndex() == this.orderedReadIndex[orderingChannel] ) {
							packet = heap.poll();
							// Pass on to user
							this.pushReceivedPacket( packet );

							if ( packet.getReliability() == PacketReliability.RELIABLE_ORDERED ) {
								this.orderedReadIndex[orderingChannel]++;
							} else {
								this.highestSequencedReadIndex[orderingChannel] = packet.getSequencingIndex();
							}
						}
					}
				} else if ( orderingIndex > this.orderedReadIndex[orderingChannel] ) {
					// Has higher ordering index than expected

					OrderingHeap heap = this.orderingHeaps[orderingChannel];
					// --> Buffer this packet until prior packets arrive:
					if ( heap.isEmpty() ) {
						this.heapWeightOffsets[orderingChannel] = this.orderedReadIndex[orderingChannel];
					}

					// This allows for 2^19 (=524.288) sequenced packets in between each ordered packet
					// OR 2^63 sequenced packets without any ordered packets in between:
					long weight = ( orderingIndex - this.heapWeightOffsets[orderingChannel] ) << 19;
					weight += ( packet.getReliability() == PacketReliability.RELIABLE_ORDERED ? ( 1 << 19 ) - 1 : packet.getSequencingIndex() );

					heap.insert( weight, packet );
				} // else {
				// Has lower ordering index than expected

				// --> This packet comes out of bands; discard it
				// }
			} else {
				// Pass on to user
				this.pushReceivedPacket( packet );
			}
		}
	}

	private void pushReceivedPacket( EncapsulatedPacket packet ) {
		if ( packet.getPacketLength() <= 0 ) {
			return;
		}

		// Handle special internal packets:
		byte packetId = packet.getPacketData()[0];

		switch ( packetId ) {
			case CONNECTED_PONG:
				this.handleConnectedPong( packet );
				break;
			case DISCONNECTION_NOTIFICATION:
				this.handleDisconnectionNotification( packet );
				break;
			case CONNECTION_REQUEST_ACCEPTED:
				this.handleConnectionRequestAccepted( packet );
				break;
			default:
				if ( packetId > USER_PACKET_ENUM ) {
					synchronized ( this.receiveBuffer ) {
						this.receiveBuffer.add( packet );
					}
				}
				break;
		}
	}

	private void handleConnectedPong( EncapsulatedPacket packet ) {
		// If there should ever be an interest of calculating connection latency
		// this would be the go-to place
	}

	private void handleDisconnectionNotification( EncapsulatedPacket packet ) {
		this.state = ConnectionState.UNCONNECTED;
		this.disconnectMessage = "Connection was forcibly closed by remote peer";
		if ( this.eventHandler != null ) {
			SocketEvent event = new SocketEvent( SocketEvent.Type.CONNECTION_CLOSED, this );
			this.eventHandler.onSocketEvent( this, event );
		}
	}

	private void handleConnectionRequestAccepted( EncapsulatedPacket packet ) {
		this.state = ConnectionState.CONNECTED;
		if ( this.eventHandler != null ) {
			SocketEvent event = new SocketEvent( SocketEvent.Type.CONNECTION_ATTEMPT_SUCCEEDED );
			this.eventHandler.onSocketEvent( this, event );
		}
	}

	private EncapsulatedPacket rebuildSplitPacket( EncapsulatedPacket packet ) {
		if ( !packet.isSplitPacket() ) {
			return null;
		}

		SplitPacketAssembler assembler = this.splitPacketChannels.get( packet.getSplitPacketId() );
		if ( assembler == null ) {
			assembler = new SplitPacketAssembler( packet );
			this.splitPacketChannels.put( packet.getSplitPacketId(), assembler );
		}

		packet = assembler.add( packet );
		return packet;
	}

	// ============================================ ACKS / NAKS ============================================ //

	private void handleACKs( PacketBuffer buffer ) {
		TriadRange[] ranges = buffer.readTriadRangeList();
		if ( ranges == null ) {
			return;
		}

		for ( int i = 0; i < ranges.length; ++i ) {
			for ( int j = ranges[i].getMin(); j <= ranges[i].getMax(); ++j ) {
				// Remove all packets contained in the ACKed datagram from the resend buffer:
				DatagramContentNode node = this.datagramContentBuffer.get( j );
				while ( node != null ) {
					EncapsulatedPacket packet = this.resendBuffer.get( node.getReliableMessageNumber() );
					if ( packet != null ) {
						// Enforce deletion on next interaction:
						packet.setNextInteraction( 0L );
						this.resendBuffer.set( node.getReliableMessageNumber(), null );
					}
					node = node.getNext();
				}
			}
		}
	}

	private void handleNAKs( PacketBuffer buffer ) {
		TriadRange[] ranges = buffer.readTriadRangeList();
		if ( ranges == null ) {
			return;
		}

		for ( int i = 0; i < ranges.length; ++i ) {
			for ( int j = ranges[i].getMin(); j <= ranges[i].getMax(); ++j ) {
				// Enforce immediate resend:
				DatagramContentNode node = this.datagramContentBuffer.get( j );
				while ( node != null ) {
					EncapsulatedPacket packet = this.resendBuffer.get( node.getReliableMessageNumber() );
					if ( packet != null ) {
						// Enforce instant resend on next interaction:
						packet.setNextInteraction( 1L );
					}
					node = node.getNext();
				}
			}
		}
	}

	private void sendACKs() {
		if ( this.outgoingACKs.size() > 0 ) {
			int          maxSize = this.mtuSize - DATA_HEADER_BYTE_LENGTH;
			PacketBuffer buffer  = new PacketBuffer( this.mtuSize );

			// IsValid | IsACK
			byte flags = (byte) 0x80 | (byte) 0x40;
			buffer.writeByte( flags );

			// Serialize ACKs into buffer and remove them afterwards:
			int count = buffer.writeTriadRangeList( this.outgoingACKs.getBackingArray(), 0, this.outgoingACKs.size(), maxSize );
			this.outgoingACKs.shiftLeft( count );

			// Send this data directly:
			try {
				this.send( this.remoteAddress, buffer );
			} catch ( IOException ignored ) {
				// ._.
			}
		}
	}

	private void sendNAKs() {
		if ( this.outgoingNAKs.size() > 0 ) {
			int          maxSize = this.mtuSize - DATA_HEADER_BYTE_LENGTH;
			PacketBuffer buffer  = new PacketBuffer( this.mtuSize );

			// IsValid | IsNAK
			byte flags = (byte) 0x80 | (byte) 0x20;
			buffer.writeByte( flags );

			// Serialize ACKs into buffer and remove them afterwards:
			int count = buffer.writeTriadRangeList( this.outgoingNAKs.getBackingArray(), 0, this.outgoingNAKs.size(), maxSize );
			this.outgoingNAKs.shiftLeft( count );

			// Send this data directly:
			try {
				this.send( this.remoteAddress, buffer );
			} catch ( IOException ignored ) {
				// ._.
			}
		}
	}

	// ============================================ PACKET SENDERS ============================================ //

	private void sendUnconnectedPing( SocketAddress recipient ) {
		PacketBuffer buffer = new PacketBuffer( 9 );
		buffer.writeByte( UNCONNECTED_PING );
		buffer.writeLong( System.currentTimeMillis() );

		try {
			this.send( recipient, buffer );
		} catch ( IOException e ) {
			// ._.
		}
	}

	private void sendUnconnectedPing( InetAddress recipient, int port ) {
		PacketBuffer buffer = new PacketBuffer( 9 );
		buffer.writeByte( UNCONNECTED_PING );
		buffer.writeLong( System.currentTimeMillis() );

		try {
			this.udpSocket.send( new DatagramPacket( buffer.getBuffer(), 0, 9, recipient, port ) );
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
			this.send( recipient, buffer );
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
		buffer.writeLong( this.guid );                          // Client GUID

		try {
			this.send( recipient, buffer );
		} catch ( IOException e ) {
			// ._.
		}
	}

	private void sendConnectionRequest( SocketAddress recipient ) {
		PacketBuffer buffer = new PacketBuffer( 18 );
		buffer.writeByte( CONNECTION_REQUEST );                 // Packet ID
		buffer.writeLong( this.guid );                          // Client GUID
		buffer.writeLong( System.currentTimeMillis() );         // Ping Time
		buffer.writeBoolean( false );                           // Security Enabled

		/*                  PASSWORD HANDLING
		String password = ...;
		buffer.writeBytes( password.getBytes( StandardCharsets.US_ASCII ) );
		*/

		this.send( PacketReliability.RELIABLE_ORDERED, 0, buffer.getBuffer(), buffer.getBufferOffset(), buffer.getPosition() - buffer.getBufferOffset() );
	}

	private void sendConnectedPing( long pingTime ) {
		PacketBuffer buffer = new PacketBuffer( 9 );
		buffer.writeByte( CONNECTED_PING );
		buffer.writeLong( pingTime );

		this.send( PacketReliability.RELIABLE, 0, buffer.getBuffer(), buffer.getBufferOffset(), buffer.getPosition() - buffer.getBufferOffset() );
	}

	private void sendDisconnectionNotification() {
		byte[] data = new byte[1];
		data[0] = DISCONNECTION_NOTIFICATION;
		this.send( PacketReliability.RELIABLE_ORDERED, 0, data );
	}

}
