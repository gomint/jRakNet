package io.gomint.jraknet.datastructures;

import java.util.Iterator;
import java.util.function.Consumer;

/**
 * This datastructure will assemble adjecent triad values into cohesive triad ranges as required for ACKs and
 * NAKs.
 *
 * @author BlackyPaw
 * @version 1.0
 */
public class TriadRangeList {

	/**
	 * Iterator implementation for the triad range list class.
	 */
	private static final class TriadRangeListIterator implements Iterator<TriadRange> {

		private final TriadRangeList list;
		private int i;

		public TriadRangeListIterator( TriadRangeList list ) {
			this.list = list;
			this.i = 0;
		}

		@Override
		public boolean hasNext() {
			return ( this.i < this.list.size );
		}

		@Override
		public TriadRange next() {
			return ( this.list.buffer[this.i++] );
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException( "remove() is not supported on TriadRangeList" );
		}

		@Override
		public void forEachRemaining( Consumer<? super TriadRange> action ) {
			while ( this.hasNext() ) {
				action.accept( this.next() );
			}
		}
	}

	private TriadRange[] buffer;
	private int          size;

	public TriadRangeList() {
		this( 8 );
	}
	public TriadRangeList( int capacity ) {
		this.buffer = new TriadRange[capacity];
	}

	/**
	 * Returns the allocation capacity the list's internal buffer provides.
	 *
	 * @return The underlying buffer's allocation size
	 */
	public int capacity() {
		return this.buffer.length;
	}

	/**
	 * Returns the current size of the list, i.e. the number of ranges stored in the list.
	 *
	 * @return The list's current size
	 */
	public int size() {
		return this.size;
	}

	/**
	 * Gets the array backing the triad range list. Do NOT modify any of its contents! Ever!
	 *
	 * @return The underlying array of the triad range list
	 */
	public TriadRange[] getBackingArray() {
		return this.buffer;
	}

	/**
	 * Gets the n-th triad range from the list or null if the index is out of bounds.
	 *
	 * @param n The index of the element to get (0-based)
	 * @return The element or null if not found
	 */
	public TriadRange get( int n ) {
		if ( n < 0 || n >= this.size ) {
			return null;
		}

		return this.buffer[n];
	}

	/**
	 * Tests whether or not the specified value is contained in any range inside
	 * this list.
	 *
	 * @param value The value to check for
	 * @return Whether or not the value is actually contained inside the list
	 */
	public boolean contains( int value ) {
		int m = this.binarySearch( value );

		if ( m >= this.size ) {
			return false;
		}

		TriadRange prev = this.buffer[m];
		return ( prev.getMin() <= value && prev.getMax() >= value );
	}

	/**
	 * Removes the first count elements and shifts the remaining ones to the fron.
	 *
	 * @param count The number of elements to shift left
	 */
	public void shiftLeft( int count ) {
		if ( count <= 0 || count > this.size ) {
			return;
		}

		System.arraycopy( this.buffer, count, this.buffer, 0, ( this.size - count ) );
		this.size -= count;
	}

	/**
	 * Inserts the specified value into the best matching range if any such already exists
	 * or adds a new range if the value does not fit into any other range.
	 *
	 * @param value The value to insert
	 */
	public void insert( int value ) {
		int m = this.binarySearch( value );

		if ( m >= this.size ) {
			// Insert as new range:
			this.insert( this.size, new TriadRange( value, value ) );
			return;
		}

		TriadRange e = this.buffer[m];
		if ( e.getMin() <= value && e.getMax() >= value ) {
			// Already contained
			return;
		} else if ( e.getMax() + 1 == value ) {
			e.setMax( e.getMax() + 1 );
		} else if ( e.getMin() - 1 == value ) {
			e.setMin( e.getMin() - 1 );
		} else {
			if ( value > e.getMax() ) {
				// Value is larger than maximum

				e = ( m + 1 < this.size ? this.buffer[m + 1] : null );
				if ( e == null ) {
					// Insert at end:
					this.insert( this.size, new TriadRange( value, value ) );
				} else {
					// Check if we may append it to next element:
					if ( e.getMin() - 1 == value ) {
						e.setMin( e.getMin() - 1 );
					} else {
						// Insert as new range:
						this.insert( m + 1, new TriadRange( value, value ) );
					}
				}
			} else {
				// Value is lower than minimum

				e = ( m - 1 >= 0 ? this.buffer[m - 1] : null );
				if ( e == null ) {
					// Insert at front:
					this.insert( 0, new TriadRange( value, value ) );
				} else {
					// Check if we may append it to previous element:
					if ( e.getMax() + 1 == value ) {
						e.setMax( e.getMax() + 1 );
					} else {
						// Insert as new range:
						this.insert( m, new TriadRange( value, value ) );
					}
				}
			}
		}
	}

	/**
	 * Returns an iterator for iterating the triad range list. As the triad range list itself
	 * does not support a remove() operation the returned iterator will not support remove()
	 * either.
	 *
	 * @return An iterator for iterating over all triad ranges in this list
	 */
	public Iterator<TriadRange> iterator() {
		return new TriadRangeListIterator( this );
	}

	/**
	 * Clears the list entirely.
	 */
	public void clear() {
		this.size = 0;
	}

	private int binarySearch( int value ) {
		if ( this.size == 0 ) {
			return 0;
		}

		int l = 0;
		int u = this.size;
		int m;
		while ( true ) {
			if ( l + 1 == u ) {
				// Search came to an end:
				return l;
			}

			m = ( l + u ) >> 1;
			TriadRange range = this.buffer[m];

			if ( range.getMin() < value ) {
				l = m;
			} else if ( range.getMin() > value ) {
				u = m;
			} else {
				return m;
			}
		}
	}

	private void insert( int index, TriadRange range ) {
		// Got to reallocate if we cannot hold another item:
		if ( this.size == this.buffer.length ) {
			this.reallocate( this.buffer.length << 1 );
		}

		if ( index == this.size ) {
			// Fast 'pushBack' operation:
			this.buffer[this.size++] = range;
		} else {
			// We will need to move elements around (.___.):
			for ( int i = this.size - 1; i >= index; --i ) {
				this.buffer[i + 1] = this.buffer[i];
			}

			// Finally insert element into buffer:
			this.buffer[index] = range;
			++this.size;
		}
	}

	private void reallocate( int capacity ) {
		TriadRange[] b = new TriadRange[capacity];
		System.arraycopy( this.buffer, 0, b, 0, this.size );
		this.buffer = b;
	}

}
