package io.gomint.jraknet.datastructures;

/**
 * @author BlackyPaw
 * @version 1.0
 */
public interface InstanceCreator<T> {

	/**
	 * Creates a new instance for the specified pool.
	 *
	 * @param pool The pool the created instance will belong to
	 *
	 * @return The created instance (must not be null)
	 */
	T createInstance( ObjectPool<T> pool );
	
}
