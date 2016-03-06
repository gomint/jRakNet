package io.gomint.jraknet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

import static io.gomint.jraknet.RakNetConstraints.*;

/**
 * @author BlackyPaw
 * @version 1.0
 */
public class ClientSocket extends Socket {

	// Logging
	private final Logger logger;

	private ClientConnection connection;

	public ClientSocket() {
		this.logger = LoggerFactory.getLogger( ClientSocket.class );
		this.connection = null;
		this.generateGuid();
	}


	// ================================ PUBLIC API ================================ //

	/**
	 * Initializes this socket and binds its internal udp socket to a free port.
	 * If the socket is already initialized any invocation of this method will
	 * result in an IllegalStateException.
	 *
	 * @throws SocketException Thrown in case the socket could not be initialized
	 */
	public void initialize() throws SocketException {
		if ( this.isInitialized() ) {
			throw new IllegalStateException( "Cannot re-initialized ClientSocket" );
		}

		this.udpSocket = new DatagramSocket();
		this.afterInitialize();
	}

	/**
	 * Sends one unconnected ping packet to the specified target system. Note though that the
	 * ping is not guaranteed to ever reach the target system nor is the pong response ever
	 * guaranteed to reach this client socket. If this socket should ever receive a pong response
	 * from the target system it will generate an {@link io.gomint.jraknet.SocketEvent.Type#UNCONNECTED_PONG} event.
	 * The socket must have been initialized before this method is invoked otherwise an IllegalStateException
	 * will be thrown.
	 *
	 * @param hostname The hostname of the target system
	 * @param port The port of the target system
	 */
	public void ping( String hostname, int port ) {
		this.ping( new InetSocketAddress( hostname, port ) );
	}

	/**
	 * Sends one unconnected ping packet to the specified target system. Note though that the
	 * ping is not guaranteed to ever reach the target system nor is the pong response ever
	 * guaranteed to reach this client socket. If this socket should ever receive a pong response
	 * from the target system it will generate an {@link io.gomint.jraknet.SocketEvent.Type#UNCONNECTED_PONG} event.
	 * The socket must have been initialized before this method is invoked otherwise an IllegalStateException
	 * will be thrown.
	 *
	 * @param address The address of the target system
	 */
	public void ping( InetSocketAddress address ) {
		if ( !this.isInitialized() ) {
			throw new IllegalStateException( "Cannot ping before initialization" );
		}

		this.sendUnconnectedPing( address );
	}

	/**
	 * Attempts to connect to the specified target system. This operation will perform asynchronously and
	 * generate appropriate socket events once it fails or completes. The socket must have been initialized
	 * before this method is invoked otherwise an IllegalStateException will be thrown. If the socket
	 * is already trying to connect to a remote system another invocation of this method will also result in
	 * an IllegalStateException.
	 *
	 * @param hostname The hostname of the target system
	 * @param port The port of the target system
	 */
	public void connect( String hostname, int port ) {
		this.connect( new InetSocketAddress( hostname, port ) );
	}

	/**
	 * Attempts to connect to the specified target system. This operation will perform asynchronously and
	 * generate appropriate socket events once it fails or completes. The socket must have been initialized
	 * before this method is invoked otherwise an IllegalStateException will be thrown. If the socket
	 * is already trying to connect to a remote system another invocation of this method will also result in
	 * an IllegalStateException.
	 *
	 * @param address The address of the target system
	 */
	public void connect( InetSocketAddress address ) {
		if ( !this.isInitialized() ) {
			throw new IllegalStateException( "Cannot ping before initialization" );
		}

		if ( this.connection != null ) {
			throw new IllegalStateException( "Cannot connect whilst an open connection exists" );
		}

		// Connection will start to send pre-connection requests automatically:
		this.connection = new ClientConnection( this, address, ConnectionState.INITIALIZING );
	}

	/**
	 * Gets the client socket's connection to the remote peer it connected to if a previous
	 * connection attempt has succeeded.
	 *
	 * @return The socket's connection to its remote peer
	 */
	public Connection getConnection() {
		return this.connection;
	}

	// ================================ IMPLEMENTATION HOOKS ================================ //

	/**
	 * Gets a logger to be used for logging errors and warnings.
	 *
	 * @return The logger to be used for logging errors and warnings
	 */
	@Override
	public Logger getImplementationLogger() {
		return this.logger;
	}

	/**
	 * Invoked right after a datagram was received. This method may perform very rudimentary
	 * datagram handling if necessary.
	 *
	 * @param datagram The datagram that was just received
	 * @return Whether or not the datagram was handled by this method already and should be processed no further
	 */
	@Override
	protected boolean receiveDatagram( DatagramPacket datagram ) {
		// Check if this might be an unconnected pong:
		byte packetId = datagram.getData()[0];
		if ( packetId == UNCONNECTED_PONG ) {
			this.handleUnconnectedPong( datagram );
			return true;
		}

		return false;
	}

	/**
	 * Handles the given datagram. This will be invoked on the socket's update thread and should hand
	 * this datagram to the connection it belongs to in order to deserialize it appropriately.
	 *
	 * @param datagram The datagram to be handled
	 * @param time The current system time
	 */
	@Override
	protected void handleDatagram( DatagramPacket datagram, long time ) {
		if ( this.connection != null ) {
			this.connection.handleDatagram( datagram, time );
		}
	}

	/**
	 * Updates all connections this socket created.
	 *
	 * @param time The current system time
	 */
	@Override
	protected void updateConnections( long time ) {
		if ( this.connection != null ) {
			if ( this.connection.getLastReceivedPacketTime() + CONNECTION_TIMEOUT_MILLIS < time ) {
				this.connection.notifyTimeout();
				this.connection = null;
			} else {
				if ( !this.connection.update( time ) ) {
					this.connection = null;
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
		long time = System.currentTimeMillis();
		if ( this.connection != null ) {
			this.connection.disconnect( "Socket is closing" );
			this.connection.update( time );
		}
		this.connection = null;
	}

	// ================================ INTERNALS ================================ //

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
	 * Invoked by a client connection whenever it got disconnected by its remote end.
	 *
	 * @param connection The connection that got disconnected
	 */
	void propagateConnectionClosed( ClientConnection connection ) {
		this.connection = null;
		this.propagateEvent( new SocketEvent( SocketEvent.Type.CONNECTION_CLOSED, connection ) );
	}

	/**
	 * Invoked by a client connection whenever this side of the network is disconnecting from the remote peer.
	 *
	 * @param connection The connection that disconnected
	 */
	void propagateConnectionDisconnected( ClientConnection connection ) {
		this.connection = null;
		this.propagateEvent( new SocketEvent( SocketEvent.Type.CONNECTION_DISCONNECTED, connection ) );
	}

	/**
	 * Invoked by a client connection whenever it could successfully establish a connection to a remote end
	 *
	 * @param connection The connection that successfully established a connection
	 */
	void propagateConnectionRequestSucceded( ClientConnection connection ) {
		this.propagateEvent( new SocketEvent( SocketEvent.Type.CONNECTION_ATTEMPT_SUCCEEDED ) );
	}

	/**
	 * Invoked by a client connection whenever its connection attempt failed.
	 *
	 * @param reason The reason why the connection attempt failed
	 */
	void propagateConnectionAttemptFailed( String reason ) {
		this.connection = null;
		this.propagateEvent( new SocketEvent( SocketEvent.Type.CONNECTION_ATTEMPT_FAILED, reason ) );
	}

	/**
	 * Sends an unconnected ping packet to the specified recipient.
	 *
	 * @param recipient The address to send the ping to
	 */
	private void sendUnconnectedPing( InetSocketAddress recipient ) {
		PacketBuffer buffer = new PacketBuffer( 9 );
		buffer.writeByte( UNCONNECTED_PING );
		buffer.writeLong( System.currentTimeMillis() );

		try {
			this.send( recipient, buffer );
		} catch ( IOException e ) {
			this.logger.error( "Failed to send ping to recipient", e );
		}
	}

	/**
	 * Handles unconnected pong datagrams and generates the appropriate socket events.
	 *
	 * @param datagram The datagram containing the unconnected pong packet
	 */
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

		SocketEvent.PingPongInfo info = new SocketEvent.PingPongInfo( datagram.getSocketAddress(),
		                                                              pingTime,
		                                                              System.currentTimeMillis(),
		                                                              serverGuid,
		                                                              motd );
		this.propagateEvent( new SocketEvent( SocketEvent.Type.UNCONNECTED_PONG, info ) );
	}

}
