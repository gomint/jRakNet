package io.gomint.jraknet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.gomint.jraknet.RakNetConstraints.*;

/**
 * @author BlackyPaw
 * @version 1.0
 */
public class ServerSocket extends Socket {

	// Logging
	private final Logger logger;

	// Address the socket was bound to if already bound:
	private InetSocketAddress bindAddress;

	// RakNet data:
	private final int                                  maxConnections;
	private       Map<SocketAddress, ServerConnection> connectionsByAddress;
	private       Map<Long, ServerConnection>          connectionsByGuid;
	private       Set<ServerConnection>                activeConnections;

	// MOTD-Workaround for Mojang MOTD:
	private String motd;

	/**
	 * Constructs a new server socket which will allow for maxConnections concurrently playing
	 * players at max.
	 *
	 * @param maxConnections The maximum connections allowed on this socket
	 */
	public ServerSocket( int maxConnections ) {
		this.logger = LoggerFactory.getLogger( ServerSocket.class );
		this.maxConnections = maxConnections;
		this.activeConnections = new HashSet<>();
		this.setMotd( "GoMint" );
		this.generateGuid();
	}

	/**
	 * Constructs a new server socket which will allow for maxConnections concurrently playing
	 * players at max. Also it will use the specified logger for all logging it performs.
	 *
	 * @param maxConnections The maximum connections allowed on this socket
	 */
	public ServerSocket( Logger logger, int maxConnections ) {
		this.logger = logger;
		this.maxConnections = maxConnections;
		this.activeConnections = new HashSet<>();
		this.setMotd( "GoMint" );
		this.generateGuid();
	}

	// ================================ PUBLIC API ================================ //

	/**
	 * Sets the MOTD to be sent inside ping packets.
	 *
	 * @param motd The motd to be sent inside ping packets
	 */
	public void setMotd( String motd ) {
		this.motd = motd;
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
	 * Binds the server socket to the specified port. This operation initializes this socket.
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
	 * Binds the server socket to the specified address. This operation initializes this socket.
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
		try {
			this.channel = DatagramChannel.open();
			this.channel.configureBlocking( false );
			this.udpSocket = this.channel.socket();
			this.udpSocket.bind( address );

			// Buffers ?!
			this.udpSocket.setReceiveBufferSize( 1024 * 1024 * 8 );
			this.udpSocket.setSendBufferSize( 1024 * 1024 * 8 );
		} catch ( IOException e ) {
			throw new SocketException( e.getMessage() );
		}

		this.bindAddress = address;

		this.connectionsByAddress = new ConcurrentHashMap<>( this.maxConnections );
		this.connectionsByGuid = new ConcurrentHashMap<>( this.maxConnections );

		// Initialize other subsystems; won't get here if bind fails as DatagramSocket's
		// constructor will throw SocketException:
		this.afterInitialize();
	}

	/**
	 * Gets the address the socket is bound to if it is already bound.
	 *
	 * @return The address the socket is bound to or null if it is not bound yet
	 */
	public SocketAddress getBindAddress() {
		return this.bindAddress;
	}

	/**
	 * Closes the socket and cleans up all internal resources. If any other method is invoked on this
	 * socket after a call to this method was made the behaviour of the socket is undefined.
	 */
	@Override
	public void close() {
		super.close();
		this.bindAddress = null;
	}

	// ================================ IMPLEMENTATION HOOKS ================================ //

	/**
	 * Gets a logger to be used for logging errors and warnings.
	 *
	 * @return The logger to be used for logging errors and warnings
	 */
	@Override
	protected Logger getImplementationLogger() {
		return this.logger;
	}

	/**
	 * Invoked right after a datagram was received. This method may perform very rudimentary
	 * datagram handling if necessary.
	 *
	 * @param datagram The datagram that was just received
	 *
	 * @return Whether or not the datagram was handled by this method already and should be processed no further
	 */
	@Override
	protected boolean receiveDatagram( DatagramPacket datagram ) {
		// Handle unconnected pings:
		byte packetId = datagram.getData()[0];
		if ( packetId == UNCONNECTED_PING ) {
			this.handleUnconnectedPing( datagram );
			return true;
		}
		return false;
	}

	/**
	 * Handles the given datagram. This will be invoked on the socket's update thread and should hand
	 * this datagram to the connection it belongs to in order to deserialize it appropriately.
	 *
	 * @param datagram The datagram to be handled
	 * @param time     The current system time
	 */
	@Override
	protected void handleDatagram( DatagramPacket datagram, long time ) {
		ServerConnection connection = this.getConnection( datagram );
		if ( connection != null ) {
			// #getConnection() may return null if there is currently no connection
			// associated with the socket address the datagram came from and the
			// datagram itself does not contain the first packet of the connection
			// attempt chain:
			connection.handleDatagram( datagram, time );
		}
	}

	/**
	 * Updates all connections this socket created.
	 *
	 * @param time The current system time
	 */
	@Override
	protected void updateConnections( long time ) {
		// Update all connections:
		Iterator<Map.Entry<SocketAddress, ServerConnection>> it = this.connectionsByAddress.entrySet().iterator();
		while ( it.hasNext() ) {
			ServerConnection connection = it.next().getValue();
			if ( connection.getLastReceivedPacketTime() + CONNECTION_TIMEOUT_MILLIS < time ) {
				connection.notifyTimeout();
				it.remove();

				if ( connection.hasGuid() ) {
					this.connectionsByGuid.remove( connection.getGuid() );
                    this.activeConnections.remove( connection );
				}
			} else {
				if ( !connection.update( time ) ) {
					it.remove();

					if ( connection.hasGuid() ) {
						this.connectionsByGuid.remove( connection.getGuid() );
						this.activeConnections.remove( connection );
					}
				}
			}
		}
	}

	/**
	 * Invoked after the receive thread was stopped but right before it terminates. May perform any necessary
	 * cleanup.
	 */
	@Override
	protected void cleanupReceiveThread() {

	}

	/**
	 * Invoked after the update thread was stopped but right before it terminates. May perform any necessary
	 * cleanup.
	 */
	@Override
	protected void cleanupUpdateThread() {
		// Disconnect all connections lingering around:
		long time = System.currentTimeMillis();
		for ( ServerConnection connection : this.connectionsByAddress.values() ) {
			connection.disconnect( "Server is closing" );
			connection.update( time );
		}
		this.connectionsByAddress = null;
		this.connectionsByGuid.clear();
		this.connectionsByGuid = null;
	}

	// ================================ INTERNALS ================================ //

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
		return ( this.activeConnections.size() < this.maxConnections );
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
		this.channel.send( ByteBuffer.wrap( buffer, offset, length ), recipient );
	}

	/**
	 * Notifies the server socket that the specified connection has received the final connection request
	 * before it may switch into the connected state thus resulting in a fully established connection.
	 * At this point the connection would need to be considered a current connection, thus if this method
	 * returns true the server socket will have prepared everything in order to consider this connection
	 * a fully valid current connection.
	 *
	 * @param connection The connection that received the final connection request
	 *
	 * @return Whether or not the connection request should be accepted.
	 */
	boolean notifyConnectionRequest( ServerConnection connection ) {
		if ( this.activeConnections.size() >= this.maxConnections ) {
			return false;
		}

		this.activeConnections.add( connection );
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
		this.logger.info( "Fully established new incoming connection: " + connection.getAddress() );
		this.propagateEvent( new SocketEvent( SocketEvent.Type.NEW_INCOMING_CONNECTION, connection ) );
	}

	/**
	 * Invoked by a server connection whenever it got disconnected by its remote end.
	 *
	 * @param connection The connection that got disconnected
	 */
	void propagateConnectionClosed( ServerConnection connection ) {
		// Got to handle this disconnect:
		this.removeActiveConnection( connection );

		this.propagateEvent( new SocketEvent( SocketEvent.Type.CONNECTION_CLOSED, connection ) );
	}

	/**
	 * Invoked by a server connection whenever this side of the network is disconnecting from the remote peer.
	 *
	 * @param connection The connection that disconnected
	 */
	void propagateConnectionDisconnected( ServerConnection connection ) {
		// Got to handle this disconnect:
		this.removeActiveConnection( connection );

		this.propagateEvent( new SocketEvent( SocketEvent.Type.CONNECTION_DISCONNECTED, connection ) );
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
		int    index  = 1;
		long sendPingTime = ( ( (long) buffer[index++] << 56 ) |
		                      ( (long) buffer[index++] << 48 ) |
		                      ( (long) buffer[index++] << 40 ) |
		                      ( (long) buffer[index++] << 32 ) |
		                      ( (long) buffer[index++] << 24 ) |
		                      ( (long) buffer[index++] << 16 ) |
		                      ( (long) buffer[index++] << 8 ) |
		                      ( (long) buffer[index] ) );

		// Let the SocketEventHandler decide what to send
		SocketEvent.PingPongInfo info = new SocketEvent.PingPongInfo( datagram.getSocketAddress(), sendPingTime, sendPingTime, -1, this.motd, this.activeConnections.size(), this.maxConnections );
		this.propagateEvent( new SocketEvent( SocketEvent.Type.UNCONNECTED_PING, info ) );

		byte[] motdBytes = String.format( MOTD_FORMAT, info.getMotd(), info.getOnlineUsers(), info.getMaxUsers() ).getBytes( StandardCharsets.UTF_8 );
		PacketBuffer packet = new PacketBuffer( 35 + motdBytes.length );
		packet.writeByte( UNCONNECTED_PONG );
		packet.writeLong( sendPingTime );
		packet.writeLong( this.getGuid() );
		packet.writeOfflineMessageDataId();
		packet.writeUShort( motdBytes.length );
		packet.writeBytes( motdBytes );
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
	private void removeActiveConnection( @SuppressWarnings( "unused" ) ServerConnection connection ) {
		this.activeConnections.remove( connection );
	}

	/**
	 * Gets or creates a connection given its socket address. Must only be invoked in update thread in order
	 * to ensure thread safety. May return null if there is no connection associated with the sender of the
	 * given datagram and the received datagram does not contain a connection attempt packet
	 *
	 * @param datagram The datagram buffer holding the actual datagram data. USed to access socket address of sender and packet ID
	 * @return The connection of the given address or null (see above)
	 */
	private ServerConnection getConnection( DatagramPacket datagram ) {
		ServerConnection connection = this.connectionsByAddress.get( datagram.getSocketAddress() );
		if ( connection == null ) {
			// Only construct a new server connection if this datagram contains
			// a valid OPEN_CONNECTION_REQUEST_1 packet as this might be a discarded
			// or invalid connection receive otherwise:
			byte packetId = datagram.getData()[0];
			if ( packetId == OPEN_CONNECTION_REQUEST_1 ) {
				connection = new ServerConnection( this, datagram.getSocketAddress(), ConnectionState.UNCONNECTED );
				this.connectionsByAddress.put( datagram.getSocketAddress(), connection );
			}
		}
		return connection;
	}

}
