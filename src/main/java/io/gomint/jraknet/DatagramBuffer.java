package io.gomint.jraknet;

import java.net.SocketAddress;

/**
 * @author BlackyPaw
 * @version 1.0
 */
class DatagramBuffer {

	private static final byte FLAG_CACHE_TO_BUFFER = 0x01;

	private static final byte FLAG_SET_CACHED  = FLAG_CACHE_TO_BUFFER;
	private static final byte FLAG_SET_WRAPPER = 0x00;

	private byte[] buffer;
	private byte   flags;

	private SocketAddress address;
	private int length;

	public DatagramBuffer( int capacity ) {
		this.buffer = new byte[capacity];
		this.flags = FLAG_SET_CACHED;
	}

	public DatagramBuffer( SocketAddress address, byte[] data ) {
		this.buffer = data;
		this.flags = FLAG_SET_WRAPPER;
		this.length = this.buffer.length;
		this.address = address;
	}

	/**
	 * Gets the data array of the datagram buffer.
	 *
	 * @return The datagram buffer's data array
	 */
	public byte[] getData() {
		return this.buffer;
	}

	/**
	 * Checks whether or not this datagram buffer should be cached (i.e. pooled).
	 *
	 * @return Whether or not this datagram buffer should be cached
	 */
	public boolean cached() {
		return ( this.flags & FLAG_CACHE_TO_BUFFER ) != 0;
	}

	/**
	 * Gets the length of the data held by the buffer.
	 *
	 * @return The length of the data held by the buffer
	 */
	public int length() {
		return this.length;
	}

	/**
	 * Sets the length of the data supposed to be held by the buffer's internal data
	 * array.
	 *
	 * @param length The length of the data held by the buffer
	 */
	public void length( int length ) {
		this.length = length;
	}

	/**
	 * Gets the address the datagram came from / is sent to.
	 *
	 * @return The address the datagram came from / is sent to
	 */
	public SocketAddress address() {
		return this.address;
	}

	/**
	 * Sets the address the datagram is coming from / is being sent to.
	 *
	 * @param address The address the datagram is coming from / is being sent to
	 */
	public void address( SocketAddress address ) {
		this.address = address;
	}

}
