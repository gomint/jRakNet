package io.gomint.jraknet.datastructures;

import java.util.LinkedList;

/**
 * @author BlackyPaw
 * @version 1.0
 */
public class FreeListObjectPool<T> implements ObjectPool<T> {

	private final LinkedList<T>      backingList;
	private       InstanceCreator<T> creator;

	/**
	 * Constructs a new free list object pool with a default capacity.
	 *
	 * @param creator The creator to be used for constructing object instances
	 */
	public FreeListObjectPool( InstanceCreator<T> creator ) {
		this( creator, 8 );
	}

	/**
	 * Constructs a new free list object pool which will possess the specified
	 * initial capacity.
	 *
	 * @param creator         The creator to be used for constructing object instances
	 * @param initialCapacity The pool's initial capacity
	 */
	public FreeListObjectPool( InstanceCreator<T> creator, int initialCapacity ) {
		this.creator = creator;
		this.backingList = new LinkedList<T>();
		for ( int i = 0; i < initialCapacity; ++i ) {
			this.backingList.add( this.createInstance() );
		}
	}

	/**
	 * Creates a new instance and immediately returns it without inserting it into the
	 * backingList first.
	 *
	 * @return The created instance
	 */
	private T createInstance() {
		return this.creator.createInstance( this );
	}

	/**
	 * Allocates one instance for further use.
	 *
	 * @return The allocated object instance
	 */
	public T allocate() {
		synchronized ( this.backingList ) {
			if ( this.backingList.isEmpty() ) {
				return this.createInstance();
			}
			return this.backingList.poll();
		}
	}

	/**
	 * Puts back an object for further use into the pool.
	 *
	 * @param reference The instance to put back
	 */
	public void putBack( T reference ) {
		synchronized ( this.backingList ) {
			this.backingList.add( reference );
		}
	}
}
