package io.gomint.jraknet;

import org.slf4j.Logger;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;

import static io.gomint.jraknet.RakNetConstraints.*;

/**
 * @author BlackyPaw
 * @version 1.0
 */
class ServerConnection extends Connection {

	// References
	private final ServerSocket server;

	// Lost connection
	private long lastConnectionLostCheck;

	ServerConnection( ServerSocket server, InetSocketAddress address, ConnectionState initialState ) {
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
	protected void sendRaw( InetSocketAddress recipient, PacketBuffer buffer ) throws IOException {
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
	protected boolean handleDatagram0( InetSocketAddress sender, PacketBuffer datagram, long time ) {
		// Handle special internal packets:
		byte packetId = datagram.getBuffer()[0];
		switch ( packetId ) {
			case OPEN_CONNECTION_REQUEST_1:
				this.handlePreConnectionRequest1( sender, datagram );
				return true;
			case OPEN_CONNECTION_REQUEST_2:
				this.handlePreConnectionRequest2( sender, datagram );
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

	@Override
	boolean update( long time ) {
		// Timeout
		if ( this.getLastReceivedPacketTime() + CONNECTION_TIMEOUT_MILLIS < time ) {
			this.notifyTimeout();
			return false;
		}

		return super.update( time );
	}

	@Override
	void notifyRemoval() {
		if ( hasGuid() ) {
			this.server.removeConnection( this );
		}

		super.notifyRemoval();
	}

	// ================================ PACKET HANDLERS ================================ //

	private void handlePreConnectionRequest1( InetSocketAddress sender, PacketBuffer datagram ) {
		if ( this.getState() != ConnectionState.UNCONNECTED ) {
			// Connection is not in a valid state to handle this packet:
			return;
		}

		this.setState( ConnectionState.INITIALIZING );
		datagram.skip( 1 );
		datagram.readOfflineMessageDataId();

		byte remoteProtocol = datagram.readByte();

		// Check for correct protocol:
		if ( ( !server.mojangModificationEnabled && remoteProtocol != RAKNET_PROTOCOL_VERSION  ) ||
				( server.mojangModificationEnabled && remoteProtocol != RAKNET_PROTOCOL_VERSION_MOJANG ) ) {
			this.sendIncompatibleProtocolVersion();
			this.setState( ConnectionState.UNCONNECTED );
			return;
		}

		this.sendConnectionReply1( sender, datagram.getRemaining() + 18 );
	}

	private void handlePreConnectionRequest2( InetSocketAddress sender, PacketBuffer datagram ) {
		if ( this.getState() != ConnectionState.INITIALIZING ) {
			// Connection is not in a valid state to handle this packet:
			return;
		}

		datagram.skip( 1 );                                                                       // Packet ID
		datagram.readOfflineMessageDataId();                                                      // Offline Message Data ID
		@SuppressWarnings( "unused" ) InetSocketAddress bindAddress = datagram.readAddress();     // Address the client bound to
		this.setMtuSize( datagram.readUShort() );                                                 // MTU
		this.setGuid( datagram.readLong() );                                                      // Client GUID

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
		if ( this.getState() != ConnectionState.CONNECTING ) {
			// Connection is not in a valid state to handle this packet:
			return;
		}

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
		PacketBuffer buffer = new PacketBuffer( 26 );
		buffer.writeByte( INCOMPATIBLE_PROTOCOL_VERSION );
		buffer.writeByte( server.mojangModificationEnabled ? RAKNET_PROTOCOL_VERSION_MOJANG : RAKNET_PROTOCOL_VERSION );
		buffer.writeOfflineMessageDataId();
		buffer.writeLong( this.server.getGuid() );
		try {
			this.sendRaw( this.getAddress(), buffer );
		} catch ( IOException ignored ) {
			// No need to log these ones here, as they are still unconnected connections
			// ._.
		}
	}

	private void sendConnectionReply1( InetSocketAddress sender, int length ) {
		// The request packet will be as large as possible in order to determine the MTU size:
		int mtuSize = length;
		if ( mtuSize > MAXIMUM_MTU_SIZE ) {
			mtuSize = MAXIMUM_MTU_SIZE;
		}

		PacketBuffer buffer = new PacketBuffer( 28 );
		buffer.writeByte( OPEN_CONNECTION_REPLY_1 );
		buffer.writeOfflineMessageDataId();
		buffer.writeLong( this.server.getGuid() );
		buffer.writeByte( (byte) 0x00 ); // We are not using LIBCAT Security
		buffer.writeUShort( mtuSize );

		try {
			this.sendRaw( sender, buffer );
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
		buffer.writeAddress( this.getAddress() );
		buffer.writeUShort( this.getMtuSize() );        // MTU
		buffer.writeBoolean( false );                   // Not using LIBCAT Security
		try {
			this.sendRaw( this.getAddress(), buffer );
		} catch ( IOException ignored ) {
			// ._.
		}
	}

	private void sendConnectionRequestAccepted( long timestamp ) {
		PacketBuffer buffer;
		boolean ipv6;
		if ( this.getAddress().getAddress() instanceof Inet6Address ) {
			buffer = new PacketBuffer( 294 );
			ipv6 = true;
		} else if ( this.getAddress().getAddress() instanceof Inet4Address ) {
			buffer = new PacketBuffer( 94 );
			ipv6 = false;
		} else {
			// WTF is this IP version?
			return;
		}

		buffer.writeByte( CONNECTION_REQUEST_ACCEPTED );            // Packet ID
		buffer.writeAddress( this.getAddress() );                   // Remote system address
		buffer.writeShort( (short) 0 );                             // Remote system index (not applicable)
		for ( int i = 0; i < MAX_LOCAL_IPS; ++i ) {                 // Local IP Addresses
			if ( ipv6 ) {
				buffer.writeAddress( LOCAL_IP_ADDRESSES_V6[i] );
			} else {
				buffer.writeAddress( LOCAL_IP_ADDRESSES[i] );
			}
		}

		buffer.writeLong( timestamp );                              // Timestamp (used for latency detection)
		buffer.writeLong( System.currentTimeMillis() );             // Current Time (used for latency detection)

		// Send to client reliably!
		this.send( PacketReliability.RELIABLE, 0, buffer.getBuffer(),0, buffer.getPosition() );
	}

	private void sendConnectionRequestFailed() {
		// Simply send NO_FREE_INCOMING_CONNECTIONS
		this.sendNoFreeIncomingConnections();
	}

	@Override
	protected void preUpdate( long time ) {
		super.preUpdate( time );

		// When we did not get a packet in the last 2 seconds send detect lost connection
		if ( this.isConnected() && this.getLastReceivedPacketTime() + 2000L < time && this.lastConnectionLostCheck + 2000L < time ) {
			this.sendDetectLostConnection();
			this.lastConnectionLostCheck = time;
		}
	}

}
