package io.gomint.jraknet;

import io.gomint.jraknet.datastructures.FreeListObjectPool;
import io.gomint.jraknet.datastructures.InstanceCreator;
import io.gomint.jraknet.datastructures.ObjectPool;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.gomint.jraknet.RakNetConstraints.MAXIMUM_MTU_SIZE;

/**
 * @author BlackyPaw
 * @version 1.0
 */
public abstract class Socket implements AutoCloseable {

	private static final Random random = new Random();

	protected DatagramSocket udpSocket;

	// Threads used for modeling network "events"
	private ThreadFactory eventLoopFactory;
	private Thread        receiveThread;
	private Thread        updateThread;

	// Lifecycle
	private AtomicBoolean running = new AtomicBoolean( false );
	private SocketEventHandler eventHandler;

	// Object-Pooling for vast instance created objects:
	private ObjectPool<DatagramPacket> datagramPool;

	// RakNet data:
	private long                                 guid;
	private BlockingQueue<DatagramPacket>        incomingDatagrams;

	// ================================ CONSTRUCTORS ================================ //

	Socket() {

	}

	// ================================ PUBLIC API ================================ //

	/**
	 * Checks whether or not the socket has already been initialized for further use. How a socket
	 * is initialized depends on its implementation and may be found in the respective documentation.
	 *
	 * @return Whether or not the socket has already been initialized
	 */
	public boolean isInitialized() {
		return ( this.udpSocket != null );
	}

	/**
	 * Sets the event loop factory to be used for internal threads.
	 * <p>
	 * Must be set before the socket is somehow initialized otherwise the call will result in an
	 * IllegalStateException.
	 *
	 * @param factory The factory to be used to create internal threads
	 */
	public void setEventLoopFactory( ThreadFactory factory ) {
		if ( this.udpSocket != null ) {
			throw new IllegalStateException( "Cannot set event loop factory if socket was already initialized" );
		}

		this.eventLoopFactory = factory;
	}

	/**
	 * Sets the event handler that will be notified of any interesting events
	 * occurring on this socket.
	 *
	 * @param handler The handler to be notified of any events on this socket
	 */
	public void setEventHandler( SocketEventHandler handler ) {
		this.eventHandler = handler;
	}

	/**
	 * Returns the socket's GUID.
	 * <p>
	 * Depending on the implementation this value might change repeatedly.
	 *
	 * @return The socket's GUID.
	 */
	public long getGuid() {
		return this.guid;
	}

	/**
	 * Closes the socket and cleans up all internal resources. If any other method is invoked on this
	 * socket after a call to this method was made the behaviour of the socket is undefined.
	 */
	@Override
	public void close() {
		// Stop all threads safely:
		this.running.set( false );
		try {
			this.updateThread.join();
		} catch ( InterruptedException ignored ) {
			// ._.
		} finally {
			this.updateThread = null;
		}

		try {
			this.receiveThread.join();
		} catch ( InterruptedException ignored ) {
			// ._.
		} finally {
			this.receiveThread = null;
		}

		// Destroy object pools:
		this.datagramPool = null;

		// Close the UDP socket:
		this.udpSocket.close();
		this.udpSocket = null;
	}

	// ================================ IMPLEMENTATION HOOKS ================================ //

	/**
	 * Gets a logger to be used for logging errors and warnings.
	 *
	 * @return The logger to be used for logging errors and warnings
	 */
	protected abstract Logger getImplementationLogger();

	/**
	 * Invoked right after a datagram was received. This method may perform very rudimentary
	 * datagram handling if necessary.
	 *
	 * @param datagram The datagram that was just received
	 * @return Whether or not the datagram was handled by this method already and should be processed no further
	 */
	protected boolean receiveDatagram( DatagramPacket datagram ) {
		return false;
	}

	/**
	 * Handles the given datagram. This will be invoked on the socket's update thread and should hand
	 * this datagram to the connection it belongs to in order to deserialize it appropriately.
	 *
	 * @param datagram The datagram to be handled
	 * @param time The current system time
	 */
	protected abstract void handleDatagram( DatagramPacket datagram, long time );

	/**
	 * Updates all connections this socket created.
	 *
	 * @param time The current system time
	 */
	protected abstract void updateConnections( long time );

	/**
	 * Invoked after the receive thread was stopped but right before it terminates. May perform any necessary
	 * cleanup.
	 */
	protected void cleanupReceiveThread() {

	}

	/**
	 * Invoked after the update thread was stopped but right before it terminates. May perform any necessary
	 * cleanup.
	 */
	protected void cleanupUpdateThread() {

	}

	// ================================ INTERNALS ================================ //

	/**
	 * Generates a new GUID for this socket. Must be invoked with care as incoming connections could
	 * potentially receive different GUIDs for the same server if this method is invoked if there
	 * are connections that have already been established.
	 */
	protected final void generateGuid() {
		this.guid = random.nextLong();
	}

	/**
	 * Must be invoked by the implementation right after the socket's internal datagram socket
	 * was initialized. This will initialize all internal structures and start up the socket's
	 * receive and update threads.
	 */
	protected final void afterInitialize() {
		// Initialize other subsystems; won't get here if bind fails as DatagramSocket's
		// constructor will throw SocketException:
		this.running.set( true );
		this.initializeEventLoopFactory();
		this.createObjectPools();
		this.initializeStructures();
		this.startReceiveThread();
		this.startUpdateThread();
	}

	/**
	 * Propagates the given event to the socket's event handler if any such is available.
	 *
	 * @param event The event to be propagated
	 */
	protected final void propagateEvent( SocketEvent event ) {
		if ( this.eventHandler != null ) {
			this.eventHandler.onSocketEvent( this, event );
		}
	}

	/**
	 * Initializes the socket's event loop factory if it does not yet have one set.
	 */
	private void initializeEventLoopFactory() {
		if ( this.eventLoopFactory != null ) {
			return;
		}

		// Construct default event loop factory:
		this.eventLoopFactory = new ThreadFactory() {
			private ThreadGroup group = new ThreadGroup( "jRakNet-ServerSocket" );
			private AtomicInteger id = new AtomicInteger( 0 );

			public Thread newThread( Runnable r ) {
				return new Thread( this.group, r, "EventLoop-" + Integer.toString( id.incrementAndGet() ) );
			}
		};
	}

	/**
	 * Creates all object pools used internally for reducing the number of created instances
	 * of certain objects.
	 */
	private void createObjectPools() {
		this.datagramPool = new FreeListObjectPool<>( new InstanceCreator<DatagramPacket>() {
			public DatagramPacket createInstance( ObjectPool<DatagramPacket> pool ) {
				// We allocate buffers able to hold twice the maximum MTU size for the reasons listed below:
				//
				// Somehow I noticed receiving datagrams that exceeded their respective connection's MTU by
				// far whenever they contained a Batch Packet. As this behaviour comes out of seemingly
				// nowhere yet we do need to receive these batch packets we must have room enough to actually
				// gather all this data even though it does not seem legit to allocate larger buffers for this
				// reason. But as the underlying DatagramSocket provides no way of grabbing the minimum required
				// buffer size for the datagram we are forced to play this dirty trick. If - at any point in the
				// future - this behaviour changes, please add this buffer size back to its original value:
				// MAXIMUM_MTU_SIZE.
				//
				// Examples of too large datagrams:
				//  - Datagram containing BatchPacket for LoginPacket: 1507 bytes the batch packet alone (5th of March 2016)
				//
				// Suggestions:
				//  - Make this value configurable in order to easily adjust this value whenever necessary
				final int INTERNAL_BUFFER_SIZE = MAXIMUM_MTU_SIZE << 1;

				return new DatagramPacket( new byte[INTERNAL_BUFFER_SIZE], INTERNAL_BUFFER_SIZE );
			}
		} );
	}

	/**
	 * Initializes any sort of structures that are required internally.
	 */
	private void initializeStructures() {
		this.incomingDatagrams = new LinkedBlockingQueue<>( 512 );
	}

	/**
	 * Starts the thread that will continuously poll the UDP socket for incoming
	 * datagrams.
	 */
	private void startReceiveThread() {
		this.receiveThread = this.eventLoopFactory.newThread( new Runnable() {
			public void run() {
				Socket.this.pollUdpSocket();
			}
		} );

		this.receiveThread.start();
	}

	/**
	 * Starts the thread that will continuously update all currently connected player's
	 * connections.
	 */
	private void startUpdateThread() {
		this.updateThread = this.eventLoopFactory.newThread( new Runnable() {
			@Override
			public void run() {
				Socket.this.update();
			}
		} );

		this.updateThread.start();
	}

	/**
	 * Polls the socket's internal datagram socket and pushes off any received datagrams
	 * to dedicated handlers that will decode the datagram into actual data packets.
	 */
	private void pollUdpSocket() {
		while ( this.running.get() ) {
			DatagramPacket datagram = this.datagramPool.allocate();
			try {
				this.udpSocket.receive( datagram );

				if ( datagram.getLength() == 0 ) {
					this.datagramPool.putBack( datagram );
					continue;
				}


				if ( !this.receiveDatagram( datagram ) ) {
					// Push datagram to update queue:
					try {
						this.incomingDatagrams.put( datagram );
					} catch ( InterruptedException e ) {
						this.getImplementationLogger().error( "Failed to handle incoming datagram", e );
					}
				} else {
					this.datagramPool.putBack( datagram );
				}
			} catch ( IOException e ) {
				e.printStackTrace();
			}
		}

		this.cleanupReceiveThread();
	}


	private void update() {
		long start;
		while ( this.running.get() ) {
			start = System.currentTimeMillis();

			// Handle all incoming datagrams:
			DatagramPacket datagram;
			while( !this.incomingDatagrams.isEmpty() ) {
				try {
					datagram = this.incomingDatagrams.take();
					this.handleDatagram( datagram, start );
					this.datagramPool.putBack( datagram );
				} catch ( InterruptedException e ) {
					this.getImplementationLogger().error( "Failed to handle incoming datagram", e );
				}
			}

			// Update all connections:
			this.updateConnections( start );

			long end = System.currentTimeMillis();

			if ( end - start < 10L ) { // Update 100 times per second if possible
				try {
					Thread.sleep( 10L - ( end - start ) );
				} catch ( InterruptedException ignored ) {
					// ._.
				}
			}
		}

		this.cleanupUpdateThread();
	}

}
