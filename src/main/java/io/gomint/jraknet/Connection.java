package io.gomint.jraknet;

import java.net.SocketAddress;

/**
 * @author BlackyPaw
 * @version 1.0
 */
public interface Connection {

	/**
	 * Gets the address of the conection's remote peer.
	 *
	 * @return The address of the connection's remote peer
	 */
	SocketAddress getAddress();

	/**
	 * Gets the connection's current state.
	 *
	 * @return The connection's current state
	 */
	ConnectionState getState();

	/**
	 * Tests whether or not the connection is currently connecting.
	 * This is essentially the same as comparing {@link #getState()} to {@link ConnectionState#CONNECTING}.
	 *
	 * @return Whether or not the connection is entirely connected
	 */
	boolean isConnecting();

	/**
	 * Tests whether or not the connection is entirely connected.
	 * This is essentially the same as comparing {@link #getState()} to {@link ConnectionState#CONNECTED}.
	 *
	 * @return Whether or not the connection is entirely connected
	 */
	boolean isConnected();

	/**
	 * Tests whether or not the connection is currently trying to disconnect from its remote peer.
	 *
	 * @return Whether or not the connection is currently trying to disconnect
	 */
	boolean isDisconnecting();

	/**
	 * Disconnects from this connection giving a reason for the disconnect that will
	 * be sent to the remote peer. As the disconnect operation might require some time
	 * and may not be completed by blocking a {@link io.gomint.jraknet.SocketEvent.Type#CONNECTION_DISCONNECTED} event
	 * will be sent out once the connection disconnected successfully. After initiating the disconnect
	 * no further packets will be sent or received.
	 *
	 * @param reason The reason of the disconnect
	 */
	void disconnect( String reason );

	/**
	 * Gets the disconnect message of the connection (might only be available once a respective event was sent via the
	 * socket).
	 *
	 * @return The connection's disconnect message
	 */
	String getDisconnectMessage();

	/**
	 * Gets the MTU size of the connection.
	 *
	 * @return The MTU size of the connection
	 */
	int getMtuSize();

	/**
	 * Gets the GUID of the connection's remote peer.
	 *
	 * @return The GUID of the connection's remote peer.
	 */
	long getGuid();

	/**
	 * Receives one or more data packets.
	 * <p>
	 * Each invocation of this method will return exactly zero or one data packets.
	 * As long as this method returns non-null byte arrays there might still be more
	 * packets kept by the connection that need to be read.
	 *
	 * @return One single data packet or null if no more packets are available.
	 */
	byte[] receive();

	/**
	 * Sends the specified data ensuring the packet reliability {@link PacketReliability#RELIABLE}.
	 *
	 * @param data The data to send
	 */
	void send( byte[] data );

	/**
	 * Sends the specified data ensuring the given packet reliability.
	 *
	 * @param reliability The reliability to ensure
	 * @param data The data to send
	 */
	void send( PacketReliability reliability, byte[] data );

	/**
	 * Sends the specified data ensuring the given packet reliability on the specified ordering channel
	 * (must be smaller than {@link RakNetConstraints#NUM_ORDERING_CHANNELS}.
	 *
	 * @param reliability The reliability to ensure
	 * @param orderingChannel The ordering channel to send the data on
	 * @param data The data to send
	 */
	void send( PacketReliability reliability, int orderingChannel, byte[] data );

	/**
	 * Sends the specified data ensuring the given packet reliability on the specified ordering channel
	 * (must be smaller than {@link RakNetConstraints#NUM_ORDERING_CHANNELS}. In case the data is interleaved
	 * a copy must be made internally so use this method with care!
	 *
	 * @param reliability The reliability to ensure
	 * @param orderingChannel The ordering channel to send the data on
	 * @param data The data to send
	 * @param offset The offset into the data array
	 * @param length The length of the data chunk to send
	 */
	void send( PacketReliability reliability, int orderingChannel, byte[] data, int offset, int length );

	/**
	 * Sends the specified data ensuring the packet reliability {@link PacketReliability#RELIABLE}. Makes a copy
	 * of the specified data internally before caching it for send.
	 *
	 * @param data The data to send
	 */
	void sendCopy( byte[] data );

	/**
	 * Sends the specified data ensuring the given packet reliability. Makes a copy
	 * of the specified data internally before caching it for send.
	 *
	 * @param reliability The reliability to ensure
	 * @param data The data to send
	 */
	void sendCopy( PacketReliability reliability, byte[] data );

	/**
	 * Sends the specified data ensuring the given packet reliability on the specified ordering channel
	 * (must be smaller than {@link RakNetConstraints#NUM_ORDERING_CHANNELS}. Makes a copy of the specified
	 * data internally before caching it for send.
	 *
	 * @param reliability The reliability to ensure
	 * @param orderingChannel The ordering channel to send the data on
	 * @param data The data to send
	 */
	void sendCopy( PacketReliability reliability, int orderingChannel, byte[] data );

	/**
	 * Sends the specified data ensuring the given packet reliability on the specified ordering channel
	 * (must be smaller than {@link RakNetConstraints#NUM_ORDERING_CHANNELS}. In case the data is interleaved
	 * a copy must be made internally so use this method with care! Makes a copy of the specified data internally
	 * before caching it for send.
	 *
	 * @param reliability The reliability to ensure
	 * @param orderingChannel The ordering channel to send the data on
	 * @param data The data to send
	 * @param offset The offset into the data array
	 * @param length The length of the data chunk to send
	 */
	void sendCopy( PacketReliability reliability, int orderingChannel, byte[] data, int offset, int length );

}
