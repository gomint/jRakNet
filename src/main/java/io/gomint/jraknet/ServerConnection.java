package io.gomint.jraknet;

import org.slf4j.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static io.gomint.jraknet.RakNetConstraints.*;

/**
 * @author BlackyPaw
 * @version 1.0
 */
class ServerConnection extends Connection {

	// References
	private final ServerSocket server;

	ServerConnection( ServerSocket server, SocketAddress address, ConnectionState initialState ) {
		super( address, initialState );
		this.server = server;
	}

	// ================================ CONNECTION ================================ //

	/**
	 * Sends raw data through an implementation-specific datagram socket. The data will already be encoded
	 * properly and is only required to be sent directly.
	 *
	 * @param recipient The recipient of the data
	 * @param buffer    The buffer containing the data to be sent
	 *
	 * @throws IOException Thrown in case the data could not be sent for some reason
	 */
	@Override
	protected void sendRaw( SocketAddress recipient, PacketBuffer buffer ) throws IOException {
		this.server.send( recipient, buffer );
	}

	/**
	 * Gets a logger to be used for logging errors and warnings.
	 *
	 * @return The logger to be used for logging errors and warnings
	 */
	@Override
	protected Logger getImplementationLogger() {
		return this.server.getImplementationLogger();
	}

	/**
	 * Implementation hook.
	 *
	 * @param datagram The datagram to be handled
	 * @param time     The current system time
	 *
	 * @return Whether or not the datagram was handled already and should be processed no further
	 */
	@Override
	protected boolean handleDatagram0( DatagramPacket datagram, long time ) {
		// Handle special internal packets:
		byte packetId = datagram.getData()[0];
		switch ( packetId ) {
			case OPEN_CONNECTION_REQUEST_1:
				this.handlePreConnectionRequest1( datagram );
				return true;
			case OPEN_CONNECTION_REQUEST_2:
				this.handlePreConnectionRequest2( datagram );
				return true;
		}
		return false;
	}

	/**
	 * Implementation hook.
	 *
	 * @param packet The packet to be handled
	 *
	 * @return Whether or not the packet was handled already and should be processed no further
	 */
	@Override
	protected boolean handlePacket0( EncapsulatedPacket packet ) {
		// Handle special internal packets:
		byte packetId = packet.getPacketData()[0];
		switch ( packetId ) {
			case CONNECTION_REQUEST:
				this.handleConnectionRequest( packet );
				return true;
			case NEW_INCOMING_CONNECTION:
				this.handleNewIncomingConnection( packet );
				return true;
		}
		return false;
	}

	/**
	 * Invoked whenever the connection was closed for some reason. This event should be propagated to the
	 * socket the specific implementation was created by.
	 */
	@Override
	protected void propagateConnectionClosed() {
		this.server.propagateConnectionClosed( this );
	}

	/**
	 * Invoked whenever the connection disconnected for some reason. This event should be propagated to the
	 * socket the specific implementation was created by.
	 */
	@Override
	protected void propagateConnectionDisconnected() {
		this.server.propagateConnectionDisconnected( this );
	}

	/**
	 * Invoked whenever the connection switched to an entirely connected state. This event should be propagated to the
	 * socket the specific implementation was created by.
	 */
	@Override
	protected void propagateFullyConnected() {
		this.server.propagateFullyConnectedConnection( this );
	}

	// ================================ PACKET HANDLERS ================================ //

	private void handlePreConnectionRequest1( DatagramPacket datagram ) {
		this.setState( ConnectionState.INITIALIZING );

		byte remoteProtocol = datagram.getData()[1 + RakNetConstraints.OFFLINE_MESSAGE_DATA_ID.length];

		// Check for correct protocol:
		if ( remoteProtocol != RAKNET_PROTOCOL_VERSION ) {
			this.sendIncompatibleProtocolVersion();
			this.setState( ConnectionState.UNCONNECTED );
			return;
		}

		this.sendConnectionReply1( datagram );
	}

	private void handlePreConnectionRequest2( DatagramPacket datagram ) {
		PacketBuffer buffer = new PacketBuffer( datagram.getData(), datagram.getOffset() );
		buffer.skip( 1 );                                                                       // Packet ID
		buffer.readOfflineMessageDataId();                                                      // Offline Message Data ID
		@SuppressWarnings( "unused" ) InetSocketAddress bindAddress = buffer.readAddress();     // Address the client bound to
		this.setMtuSize( buffer.readUShort() );                                                 // MTU
		this.setGuid( buffer.readLong() );                                                      // Client GUID

		if ( !this.server.testAddressAndGuid( this ) ) {
			this.sendAlreadyConnected();
			return;
		}

		if ( !this.server.allowIncomingConnection() ) {
			this.sendNoFreeIncomingConnections();
			return;
		}

		// Do this before sending the response:
		this.initializeStructures();

		this.sendConnectionReply2();
		this.setState( ConnectionState.RELIABLE );
	}

	private void handleConnectionRequest( EncapsulatedPacket packet ) {
		this.setState( ConnectionState.CONNECTING );

		PacketBuffer buffer = new PacketBuffer( packet.getPacketData(), 0 );
		buffer.skip( 1 );                       // Packet ID
		if ( this.getGuid() != buffer.readLong() ) { // Client GUID
			this.setState( ConnectionState.UNCONNECTED );
			this.sendConnectionRequestFailed();
			return;
		}
		long    time            = buffer.readLong();          // Current client time
		boolean securityEnabled = buffer.readBoolean(); // Whether or not a password is appended to the packet's usual data

		if ( securityEnabled ) {
			// We do not support security!
			this.sendConnectionRequestFailed();
			return;
		}

		/*              PASSWORD HANDLING (if this will ever be of interest)
		String password = null;
		if ( buffer.getRemaining() > 0 ) {
			byte[] ascii = new byte[buffer.getRemaining()];
			buffer.readBytes( ascii );
			password = new String( ascii, StandardCharsets.US_ASCII );
		}
		*/

		this.sendConnectionRequestAccepted( time );
	}

	private void handleNewIncomingConnection( @SuppressWarnings( "unused" ) EncapsulatedPacket packet ) {
		if ( !this.server.notifyConnectionRequest( this ) ) {
			// This connection is hereby cancelled immediately!
			this.setState( ConnectionState.UNCONNECTED );
			this.sendNoFreeIncomingConnections();
			return;
		}

		// Connection is now ready to rumble!
		this.setState( ConnectionState.CONNECTED );
	}

	// ================================ PACKET SENDERS ================================ //

	private void sendIncompatibleProtocolVersion() {
		PacketBuffer buffer = new PacketBuffer( 22 );
		buffer.writeByte( INCOMPATIBLE_PROTOCOL_VERSION );
		buffer.writeByte( RAKNET_PROTOCOL_VERSION );
		buffer.writeOfflineMessageDataId();
		buffer.writeLong( this.server.getGuid() );
		try {
			this.sendRaw( this.getAddress(), buffer );
		} catch ( IOException ignored ) {
			// No need to log these ones here, as they are still unconnected connections
			// ._.
		}
	}

	private void sendConnectionReply1( DatagramPacket request ) {
		// The request packet will be as large as possible in order to determine the MTU size:
		int mtuSize = request.getLength() + UDP_DATAGRAM_HEADER_SIZE;
		if ( mtuSize > MAXIMUM_MTU_SIZE ) {
			mtuSize = MAXIMUM_MTU_SIZE;
		}

		PacketBuffer buffer = new PacketBuffer( 24 );
		buffer.writeByte( OPEN_CONNECTION_REPLY_1 );
		buffer.writeOfflineMessageDataId();
		buffer.writeLong( this.server.getGuid() );
		buffer.writeByte( (byte) 0x00 ); // We are not using LIBCAT Security
		buffer.writeUShort( mtuSize );
		try {
			this.sendRaw( this.getAddress(), buffer );
		} catch ( IOException ignored ) {
			// ._.
		}
	}

	private void sendAlreadyConnected() {
		this.sendConnectionRequestAbort( ALREADY_CONNECTED );
	}

	private void sendNoFreeIncomingConnections() {
		this.sendConnectionRequestAbort( NO_FREE_INCOMING_CONNECTIONS );
	}

	private void sendConnectionRequestAbort( byte id ) {
		PacketBuffer buffer = new PacketBuffer( 21 );
		buffer.writeByte( id );
		buffer.writeOfflineMessageDataId();
		buffer.writeLong( this.server.getGuid() );
		try {
			this.sendRaw( this.getAddress(), buffer );
		} catch ( IOException ignored ) {
			// ._.
		}
	}

	private void sendConnectionReply2() {
		PacketBuffer buffer = new PacketBuffer( 31 );
		buffer.writeByte( OPEN_CONNECTION_REPLY_2 );    // Packet ID
		buffer.writeOfflineMessageDataId();             // Offline Message Data ID
		buffer.writeLong( this.server.getGuid() );      // Server GUID
		buffer.writeUShort( this.getMtuSize() );        // MTU
		buffer.writeBoolean( false );                   // Not using LIBCAT Security
		try {
			this.sendRaw( this.getAddress(), buffer );
		} catch ( IOException ignored ) {
			// ._.
		}
	}

	private void sendConnectionRequestAccepted( long timestamp ) {
		PacketBuffer buffer = new PacketBuffer( 98 );
		buffer.writeByte( CONNECTION_REQUEST_ACCEPTED );            // Packet ID
		buffer.writeAddress( this.getAddress() );                   // Remote system address
		//buffer.writeUInt( 0 );                                      // Remote system index (not applicable)
		for ( int i = 0; i < MAX_LOCAL_IPS; ++i ) {                 // Local IP Addresses
			buffer.writeAddress( LOCAL_IP_ADDRESSES[i] );
		}
		buffer.writeLong( timestamp );                              // Timestamp (used for latency detection)
		buffer.writeLong( System.currentTimeMillis() );             // Current Time (used for latency detection)

		// Send to client reliably!
		this.send( PacketReliability.RELIABLE, 0, buffer.getBuffer() );
	}

	private void sendConnectionRequestFailed() {
		// Simply send NO_FREE_INCOMING_CONNECTIONS
		this.sendNoFreeIncomingConnections();
	}

}
