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
	private static final int INTERNAL_BUFFER_SIZE = MAXIMUM_MTU_SIZE << 1;

	protected DatagramSocket udpSocket;

	// Threads used for modeling network "events"
	private ThreadFactory eventLoopFactory;
	private Thread        receiveThread;
	private Thread        updateThread;

	// Lifecycle
	private AtomicBoolean running = new AtomicBoolean( false );
	private SocketEventHandler eventHandler;

	// Object-Pooling for vast instance created objects:
	private ObjectPool<DatagramBuffer> bufferPool;

	// RakNet data:
	private long                          guid;
	private BlockingQueue<DatagramBuffer> incomingDatagrams;

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
		this.bufferPool = null;

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
	 *
	 * @return Whether or not the datagram was handled by this method already and should be processed no further
	 */
	protected boolean receiveDatagram( DatagramBuffer datagram ) {
		return false;
	}

	/**
	 * Handles the given datagram. This will be invoked on the socket's update thread and should hand
	 * this datagram to the connection it belongs to in order to deserialize it appropriately.
	 *
	 * @param datagram The datagram to be handled
	 * @param time     The current system time
	 */
	protected abstract void handleDatagram( DatagramBuffer datagram, long time );

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
		this.bufferPool = new FreeListObjectPool<>( new InstanceCreator<DatagramBuffer>() {
			public DatagramBuffer createInstance( ObjectPool<DatagramBuffer> pool ) {
				return new DatagramBuffer( INTERNAL_BUFFER_SIZE );
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
				Thread.currentThread().setName( Thread.currentThread().getName() + " [jRaknet " + Socket.this.getClass().getSimpleName() + " Receive]" );
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
				Thread.currentThread().setName( Thread.currentThread().getName() + " [jRaknet " + Socket.this.getClass().getSimpleName() + " Update]" );
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
			// ---------------------------------------------------------------------------
			// Allocate a 2^16 bytes long buffer
			// ---------------------------------------------------------------------------
			// During testing we encountered situations in which MCPE exceeded
			// the MTU size by far by unpredictable amounts of data. Even occasions
			// with more than 7x the actual MTU size haven been seen. Thus there was
			// the need to be able to potentially get ALL possible datagrams no
			// matter if they exceed the MTU thus do not fit into our pre-allocated
			// buffers and thus have their remaining data discarded. This is where
			// this buffer comes into play.
			//
			// The UDP header possesses a 16-bit field encoding the length of the
			// datagram. Therefore the longest possible size of any datagram is
			// 2^16 bytes and thus all datagrams will be able to fit into this buffer
			// without losing any data at all.
			// Unfortunately though no one would appreciate it if we would pre-allocate
			// multiple 64kB buffers for smaller datagrams which is why I came up with
			// the following idea:
			// The data received from the socket is first copied into this recvbuf so
			// that we will be able to get ALL data. Afterwards we take check whether
			// or not the datagram's actual length would fit into one of the buffers
			// we preallocated (~2kB) and if so the data is copied into one of these
			// buffers. Otherwise we manually allocate a new array just large enough
			// to hold our datagram and pass it through the system deleting it once
			// it was pushed to the end-user.
			// ---------------------------------------------------------------------------
			byte[]         recvbuf = new byte[65536];
			DatagramPacket datagram;
			try {
				datagram = new DatagramPacket( recvbuf, recvbuf.length );
				this.udpSocket.receive( datagram );

				if ( datagram.getLength() == 0 ) {
					continue;
				}

				DatagramBuffer buffer;
				if ( datagram.getLength() <= INTERNAL_BUFFER_SIZE ) {
					buffer = this.bufferPool.allocate();

					// Copy data into buffers:
					System.arraycopy( datagram.getData(), datagram.getOffset(), buffer.getData(), 0, datagram.getLength() );
					buffer.length( datagram.getLength() );
					buffer.address( datagram.getSocketAddress() );
				} else {
					// Copy data into new array:
					byte[] data = new byte[datagram.getLength()];
					System.arraycopy( datagram.getData(), datagram.getOffset(), data, 0, datagram.getLength() );

					buffer = new DatagramBuffer( datagram.getSocketAddress(), data );
				}

				if ( !this.receiveDatagram( buffer ) ) {
					// Push datagram to update queue:
					try {
						this.incomingDatagrams.put( buffer );
					} catch ( InterruptedException e ) {
						this.getImplementationLogger().error( "Failed to handle incoming datagram", e );
					}
				} else {
					if ( buffer.cached() ) {
						this.bufferPool.putBack( buffer );
					}
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
			DatagramBuffer datagram;
			while ( !this.incomingDatagrams.isEmpty() ) {
				try {
					datagram = this.incomingDatagrams.take();
					this.handleDatagram( datagram, start );
					if ( datagram.cached() ) {
						this.bufferPool.putBack( datagram );
					}
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
