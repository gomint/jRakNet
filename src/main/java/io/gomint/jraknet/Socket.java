package io.gomint.jraknet;

import java.util.concurrent.ThreadFactory;

/**
 * @author BlackyPaw
 * @version 1.0
 */
public interface Socket extends AutoCloseable {

	/**
	 * Sets the event loop factory to be used for internal threads.
	 * <p>
	 * Must be set before the socket is bound otherwise the call will result in an
	 * IllegalStateException.
	 *
	 * @param factory The factory to be used to create internal threads
	 */
	void setEventLoopFactory( ThreadFactory factory );

	/**
	 * Sets the event handler that will be notified of any interesting events
	 * occurring on this socket.
	 *
	 * @param handler The handler to be notified of any events on this socket
	 */
	void setEventHandler( SocketEventHandler handler );

}
