package io.gomint.jraknet;

/**
 * @author BlackyPaw
 * @version 1.0
 */
public class SocketEvent {

	public enum Type {

		/**
		 * Notifies that a new incoming connection was established and is ready for further use. Events of this
		 * type are guaranteed to possess a connection parameter.
		 */
		NEW_INCOMING_CONNECTION,

		/**
		 * Notifies that a connection was closed by the remote host. Events of this type are guaranteed to possess a
		 * connection parameter. For connections that closed but were disconnected by this side of the network see
		 * the {@link #CONNECTION_DISCONNECTED} event.
		 */
		CONNECTION_CLOSED,

		/**
		 * Notifies that a connection was disconnected by this side of the network. Events of this type are guaranteed
		 * to possess a connection parameter. For connections closed by the remote peer see the {@link #CONNECTION_CLOSED}
		 * event.
		 */
		CONNECTION_DISCONNECTED;

	}

	private final Type type;

	private Connection connection;

	SocketEvent( Type type ) {
		this.type = type;
	}

	SocketEvent( Type type, Connection connection ) {
		this.type = type;
		this.connection = connection;
	}

	/**
	 * Gets the type of the socket event.
	 *
	 * @return The type of the socket event
	 */
	public Type getType() {
		return this.type;
	}

	/**
	 * Returns the connection of the event if applicable or null otherwise.
	 *
	 * @return The connection of the event
	 */
	public Connection getConnection() {
		return this.connection;
	}

}
