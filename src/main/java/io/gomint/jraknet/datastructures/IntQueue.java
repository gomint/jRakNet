package io.gomint.jraknet.datastructures;

/**
 * Memory-Saving queue implementation for integers.
 *
 * @author BlackyPaw
 * @version 1.0
 */
public class IntQueue {

	private int[] buffer;
	private int   head;
	private int   tail;

	public IntQueue() {
		this( 16 );
	}

	public IntQueue( int capacity ) {
		assert ( ( capacity != 0 ) && ( ( capacity & ( ~capacity + 1 ) ) == capacity ) )
				: "Capacity must be power of two";
		this.buffer = new int[capacity];
		this.head = 0;
		this.tail = 0;
	}

	/**
	 * Tests whether or not the queue is empty.
	 *
	 * @return Whether or not the queue is empty
	 */
	public boolean isEmpty() {
		return ( this.head == this.tail );
	}

	/**
	 * Adds the given integer to the queue.
	 *
	 * @param val The integer to add to the queue
	 */
	public void add( int val ) {
		if ( ( ( this.head + 1 ) & ( this.buffer.length - 1 ) ) == this.tail ) {
			// List is full --> Reallocate
			this.reallocate( this.buffer.length << 1 );
		}

		this.buffer[this.head] = val;
		this.head = ( this.head + 1 ) & ( this.buffer.length - 1 );
	}

	/**
	 * Clears the queue entirely.
	 */
	public void clear() {
		this.head = this.tail = 0;
	}

	/**
	 * Internal reallocation of the underlying buffer.
	 *
	 * @param capacity The capacity to reallocate the underlying buffer to
	 */
	private void reallocate( int capacity ) {
		int[] b = new int[capacity];

		int size = 0;
		if ( this.tail < this.head ) {
			// Only needing one copy:
			size = ( this.head - this.tail );
			System.arraycopy( this.buffer, this.tail, b, 0, size );
		} else if ( this.head < this.tail ) {
			// Needs two copies:
			size = ( this.buffer.length - ( this.tail - this.head ) );
			System.arraycopy( this.buffer, this.tail, b, 0, ( this.buffer.length - this.tail ) );
			System.arraycopy( this.buffer, 0, b, ( this.buffer.length - this.tail ), this.head );
		}

		this.buffer = b;
		this.head = size;
		this.tail = 0;
	}

	/**
	 * Sets the integer at the specified index.
	 *
	 * @param index The index of the integer to set
	 * @param val   The value to set
	 */
	public void set( int index, int val ) {
		if ( index < 0 || index >= this.size() ) {
			return;
		}

		int idx = ( this.tail + index ) & ( this.buffer.length - 1 );
		this.buffer[idx] = val;
	}

	/**
	 * Gets the size of the queue
	 *
	 * @return The size of the queue
	 */
	public int size() {
		if ( this.head > this.tail ) {
			return ( this.head - this.tail );
		} else if ( this.head < this.tail ) {
			return ( this.buffer.length - ( this.tail - this.head ) );
		} else {
			return 0;
		}
	}

	/**
	 * Gets the integer value at the specified index. If the index is out of bounds 0 will be returned.
	 *
	 * @param index The index of the integer to get
	 *
	 * @return The value of the integer at the specified index
	 */
	public int get( int index ) {
		if ( index < 0 || index >= this.size() ) {
			return 0;
		}

		int idx = ( this.tail + index ) & ( this.buffer.length - 1 );
		return this.buffer[idx];
	}

	/**
	 * Peeks the next element if the queue is not empty. Otherwise 0 will be returned.
	 *
	 * @return The value of the next element to be polled
	 */
	public int peek() {
		if ( this.tail == this.head ) {
			// List is empty
			return 0;
		}

		return this.buffer[this.tail];
	}

	/**
	 * Polls the next element from the queue if it is not empty and removes it. If the queue is empty
	 * 0 will be returned.
	 *
	 * @return The value of the polled element
	 */
	public int poll() {
		if ( this.tail == this.head ) {
			// List is empty
			return 0;
		}

		int i = this.tail;
		this.tail = ( this.tail + 1 ) & ( this.buffer.length - 1 );
		return this.buffer[i];
	}

}
