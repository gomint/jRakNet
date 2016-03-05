package io.gomint.jraknet.datastructures;

/**
 * @author BlackyPaw
 * @version 1.0
 */
public interface ObjectPool<T> {

	/**
	 * Allocates one instance for further use.
	 *
	 * @return The allocated object instance
	 */
	T allocate();

	/**
	 * Puts back an object for further use into the pool.
	 *
	 * @param reference The instance to put back
	 */
	void putBack( T reference );

}
