package io.gomint.jraknet;

import io.gomint.jraknet.datastructures.FreeListObjectPool;
import io.gomint.jraknet.datastructures.InstanceCreator;
import io.gomint.jraknet.datastructures.ObjectPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.gomint.jraknet.RakNetConstraints.*;

/**
 * @author BlackyPaw
 * @version 1.0
 */
public class ServerSocket implements Socket {

	private static final Random random = new Random();

	// Logging
	private final Logger logger = LoggerFactory.getLogger( ServerSocket.class );

	// Address the socket was bound to if already bound:
	private InetSocketAddress bindAddress;
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
	private int                                  maxConnections;
	private long                                 guid;
	private Map<SocketAddress, ServerConnection> connectionsByAddress;
	private Map<Long, ServerConnection>          connectionsByGuid;
	private int                                  currentConnections;
	private BlockingQueue<DatagramPacket>        incomingDatagrams;

	// MOTD-Workaround for Mojang MOTD:
	private String motd;
	private byte[] motdBytes;

	public ServerSocket( int maxConnections ) {
		this.maxConnections = maxConnections;
		this.currentConnections = 0;
		this.setMotd( "GoMint" );
		this.generateGuid();
	}

	/**
	 * Sets the MOTD to be sent inside ping packets.
	 *
	 * @param motd The motd to be sent inside ping packets
	 */
	public void setMotd( String motd ) {
		this.motd = motd;

		String formatted = String.format( MOTD_FORMAT, this.motd, 0, 10 );
		this.motdBytes = formatted.getBytes( StandardCharsets.US_ASCII );
	}

	/**
	 * Gets the MOTD that is sent inside ping packets.
	 *
	 * @return The MOTD sent inside ping packets
	 */
	public String getMotd() {
		return this.motd;
	}

	/**
	 * Generates the server's globally unique identifier.
	 */
	private void generateGuid() {
		// Maybe use more sophisticated method here sometime:
		this.guid = random.nextLong();
	}

	/**
	 * Sets the event loop factory to be used for internal threads.
	 * <p>
	 * Must be set before the socket is bound otherwise the call will result in an
	 * IllegalStateException.
	 *
	 * @param factory The factory to be used to create internal threads
	 */
	public void setEventLoopFactory( ThreadFactory factory ) {
		if ( this.isBound() ) {
			throw new IllegalStateException( "Cannot set event loop factory after socket is already bound" );
		}
		this.eventLoopFactory = factory;
	}

	/**
	 * Sets the event handler that will be notified of any interesting events
	 * occurring on this socket.
	 *
	 * @param handler The handler to be notified of any events on this socket
	 */
	public void setEventHandler( SocketEventHandler handler ) {
		this.eventHandler = handler;
	}

	/**
	 * Checks whether or not the server socket has already been bound
	 *
	 * @return Whether or not the socket has already been bound
	 */
	public boolean isBound() {
		return ( this.udpSocket != null );
	}

	/**
	 * Binds the server socket to the specified port.
	 *
	 * @param host The hostname to bind to (either an IP or a FQDN)
	 * @param port The port to bind to
	 *
	 * @throws SocketException Thrown if the socket cannot be bound
	 */
	public void bind( String host, int port ) throws SocketException {
		this.bind( new InetSocketAddress( host, port ) );
	}

	/**
	 * Binds the server socket to the specified address.
	 *
	 * @param address The address to bind the port to
	 *
	 * @throws SocketException Thrown if the socket cannot be bound
	 */
	public void bind( InetSocketAddress address ) throws SocketException {
		if ( this.bindAddress != null ) {
			throw new SocketException( "ServerSocket is already bound" );
		}

		// Automatically binds socket to address (no further #bind() call required)
		this.udpSocket = new DatagramSocket( address );
		this.bindAddress = address;

		// Initialize other subsystems; won't get here if bind fails as DatagramSocket's
		// constructor will throw SocketException:
		this.running.set( true );
		this.initializeEventLoopFactory();
		this.createObjectPools();
		this.initializeStructures();
		this.startReceiveThread();
		this.startUpdateThread();
	}

	/**
	 * Closes the server socket if it has been bound before and cleans up
	 * all underlying resources.
	 */
	public void close() {
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

		// Disconnect all connections lingering around:
		for ( ServerConnection connection : this.connectionsByAddress.values() ) {
			connection.disconnect( "Server is closing" );
		}
		this.connectionsByAddress = null;
		this.connectionsByGuid.clear();
		this.connectionsByGuid = null;

		// Delete internal data:
		this.currentConnections = 0;

		// Destroy object pools:
		this.datagramPool = null;

		// Close the UDP socket:
		this.udpSocket.close();
	}

	/**
	 * Gets the server's RakNet globally unique identifier.
	 *
	 * @return The server's globally unique identifier for RakNet
	 */
	long getGuid() {
		return this.guid;
	}

	Logger getLogger() {
		return this.logger;
	}

	/**
	 * Checks whether or not another connection with this address's system address or
	 * client GUID already exists.
	 *
	 * @param connection The connection to test
	 *
	 * @return Whether or not the connection already exists
	 */
	boolean testAddressAndGuid( ServerConnection connection ) {
		return !this.connectionsByGuid.containsKey( connection.getGuid() );

	}

	/**
	 * Checks whether or not another new incoming is allowed (max connections).
	 *
	 * @return Whether or not another new incoming connection should be allowed
	 */
	boolean allowIncomingConnection() {
		return ( this.currentConnections < this.maxConnections );
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

	/**
	 * Notifies the server socket that the specified connection has received the final connection request
	 * before it may switch into the connected state thus resulting in a fully established connection.
	 * At this point the connection would need to be considered a current connection, thus if this method
	 * returns true the server socket will have prepared everything in order to consider this connection
	 * a fully valid current connection.
	 *
	 * @param connection The connection that received the final connection request
	 * @return Whether or not the connection request should be accepted.
	 */
	boolean notifyConnectionRequest( ServerConnection connection ) {
		if ( this.currentConnections >= this.maxConnections ) {
			return false;
		}

		++this.currentConnections;
		return true;
	}

	/**
	 * Invoked by a server connection that was just fully established. This is the point at which connection
	 * attempts may be announced to the end user as the underlying connection does now support reliable messages
	 * and is fully initialized.
	 *
	 * @param connection The connection that is fully established now
	 */
	void propagateFullyConnectedConnection( ServerConnection connection ) {
		if ( this.eventHandler != null ) {
			this.logger.info( "Fully established new incoming connection: " + connection.getAddress() );
			SocketEvent event = new SocketEvent( SocketEvent.Type.NEW_INCOMING_CONNECTION, connection );
			this.eventHandler.onSocketEvent( this, event );
		}
	}

	/**
	 * Invoked by a server connection whenever it got disconnected by its remote end.
	 *
	 * @param connection The connection that got disconnected
	 */
	void propagateConnectionClosed( ServerConnection connection ) {
		if ( this.eventHandler != null ) {
			if ( connection.isConnected() ) {
				// Got to handle this disconnect:
				this.removeActiveConnection( connection );
			}

			SocketEvent event = new SocketEvent( SocketEvent.Type.CONNECTION_CLOSED, connection );
			this.eventHandler.onSocketEvent( this, event );
		}
	}

	/**
	 * Invoked by a server connection whenever this side of the network is disconnecting from the remote peer.
	 *
	 * @param connection The connection that disconnected
	 */
	void propagateConnectionDisconnected( ServerConnection connection ) {
		if ( this.eventHandler != null ) {
			if ( connection.isConnected() ) {
				// Got to handle this disconnect:
				this.removeActiveConnection( connection );
			}

			SocketEvent event = new SocketEvent( SocketEvent.Type.CONNECTION_DISCONNECTED, connection );
			this.eventHandler.onSocketEvent( this, event );
		}
	}

	/**
	 * Handles the specified datagram by decomposing all encapsulated packets.
	 *
	 * @param datagram The datagram to handle
	 */
	private void handleDatagram( DatagramPacket datagram ) {
		// Test for unconnected packet IDs:
		byte packetId = datagram.getData()[0];

		// Handle unconnected pings immediately:
		if ( packetId == UNCONNECTED_PING ) {
			this.handleUnconnectedPing( datagram );
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

	/**
	 * Handles and responds to an unconnected ping sent from another system.
	 *
	 * @param datagram The datagram containing the ping request
	 */
	private void handleUnconnectedPing( DatagramPacket datagram ) {
		// Indeed, rather ugly but avoids the relatively unnecessary cost of
		// constructing yet another packet buffer instance:
		byte[] buffer = datagram.getData();
		int    index  = datagram.getOffset() + 1;
		long sendPingTime = ( ( (long) buffer[index++] << 56 ) |
		                      ( (long) buffer[index++] << 48 ) |
		                      ( (long) buffer[index++] << 40 ) |
		                      ( (long) buffer[index++] << 32 ) |
		                      ( (long) buffer[index++] << 24 ) |
		                      ( (long) buffer[index++] << 16 ) |
		                      ( (long) buffer[index++] << 8 ) |
		                      ( (long) buffer[index] ) );

		PacketBuffer packet = new PacketBuffer( 35 + this.motdBytes.length );
		packet.writeByte( UNCONNECTED_PONG );
		packet.writeLong( sendPingTime );
		packet.writeLong( this.guid );
		packet.writeOfflineMessageDataId();
		packet.writeUShort( this.motdBytes.length );
		packet.writeBytes( this.motdBytes );
		try {
			this.send( datagram.getSocketAddress(), packet );
		} catch ( IOException ignored ) {
			// ._.
		}
	}

	/**
	 * Removes a currently active connection from the server socket and cleans up any resources allocated
	 * for it.
	 *
	 * @param connection The connection to remove
	 */
	private void removeActiveConnection( ServerConnection connection ) {
		--this.currentConnections;
	}

	/**
	 * Updates all connection that belong to this server socket.
	 */
	private void update() {
		while ( this.running.get() ) {
			long start = System.currentTimeMillis();

			// Handle all incoming datagrams:
			DatagramPacket datagram;
			while( !this.incomingDatagrams.isEmpty() ) {
				try {
					datagram = this.incomingDatagrams.take();
					this.getConnection( datagram.getSocketAddress() ).handleDatagram( datagram );
					this.datagramPool.putBack( datagram );
				} catch ( InterruptedException e ) {
					this.logger.error( "Failed to handle incoming datagram", e );
				}
			}

			// Update all connections:
			Iterator<Map.Entry<SocketAddress, ServerConnection>> it = this.connectionsByAddress.entrySet().iterator();
			while ( it.hasNext() ) {
				ServerConnection connection = it.next().getValue();
				if ( connection.getLastReceivedPacketTime() + CONNECTION_TIMEOUT_MILLIS < start ) {
					connection.notifyTimeout();
					it.remove();
					if ( connection.hasGuid() ) {
						this.connectionsByGuid.remove( connection.getGuid() );
					}

					if ( connection.isConnected() ) {
						--this.currentConnections;
					}
				} else {
					if ( !connection.update( start ) ) {
						it.remove();
						if ( connection.hasGuid() ) {
							this.connectionsByGuid.remove( connection.getGuid() );
						}
					}
				}
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
		this.connectionsByAddress = new ConcurrentHashMap<>( this.maxConnections );
		this.connectionsByGuid = new ConcurrentHashMap<>( this.maxConnections );
		this.incomingDatagrams = new LinkedBlockingQueue<>( 512 );
	}

	/**
	 * Starts the thread that will continuously poll the UDP socket for incoming
	 * datagrams.
	 */
	private void startReceiveThread() {
		this.receiveThread = this.eventLoopFactory.newThread( new Runnable() {
			public void run() {
				ServerSocket.this.pollUdpSocket();
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
				ServerSocket.this.update();
			}
		} );

		this.updateThread.start();
	}

	private ServerConnection getConnection( SocketAddress address ) {
		if ( !this.connectionsByAddress.containsKey( address ) ) {
			ServerConnection connection = new ServerConnection( this, address, ConnectionState.CONNECTING );
			this.connectionsByAddress.put( address, connection );
			return connection;
		}

		return this.connectionsByAddress.get( address );
	}

}
