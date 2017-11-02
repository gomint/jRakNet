package io.gomint.jraknet;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramPacket;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.gomint.jraknet.RakNetConstraints.MAXIMUM_MTU_SIZE;

/**
 * @author BlackyPaw
 * @author geNAZt
 * @version 2.0
 */
public abstract class Socket implements AutoCloseable {

    protected Bootstrap udpSocket;
    protected Channel channel;

    // Threads used for modeling network "events"
    private ThreadFactory eventLoopFactory;
    private Thread updateThread;

    // Lifecycle
    private AtomicBoolean running = new AtomicBoolean( false );
    private SocketEventHandler eventHandler;

    // RakNet data:
    private long guid;

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

        // Close the UDP socket:
        this.channel.close().syncUninterruptibly();
        this.channel.eventLoop().shutdownGracefully().syncUninterruptibly();
        this.channel = null;
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
     * @param sender  The channel which sent this datagram
     * @param datagram The datagram that was just received
     * @return Whether or not the datagram was handled by this method already and should be processed no further
     */
    protected boolean receiveDatagram( InetSocketAddress sender, PacketBuffer datagram ) {
        return false;
    }

    /**
     * Handles the given datagram. This will be invoked on the socket's update thread and should hand
     * this datagram to the connection it belongs to in order to deserialize it appropriately.
     *
     * @param sender  The channel which this datagram sent
     * @param datagram The datagram to be handled
     * @param time     The current system time
     */
    protected abstract void handleDatagram( InetSocketAddress sender, PacketBuffer datagram, long time );

    /**
     * Updates all connections this socket created.
     *
     * @param time The current system time
     */
    protected abstract void updateConnections( long time );

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
        this.guid = ThreadLocalRandom.current().nextLong();
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

    private void update() {
        long start;

        while ( this.running.get() ) {
            start = System.currentTimeMillis();

            // Update all connections:
            this.updateConnections( start );

            long time = System.currentTimeMillis() - start;
            if ( time < 10 ) {
                try {
                    Thread.sleep( 10 - time );
                } catch ( InterruptedException e ) {
                    e.printStackTrace();
                }
            }
        }

        this.cleanupUpdateThread();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Socket socket = (Socket) o;
        return guid == socket.guid &&
                Objects.equals(udpSocket, socket.udpSocket) &&
                Objects.equals(channel, socket.channel) &&
                Objects.equals(eventLoopFactory, socket.eventLoopFactory) &&
                Objects.equals(updateThread, socket.updateThread) &&
                Objects.equals(running, socket.running) &&
                Objects.equals(eventHandler, socket.eventHandler);
    }

    @Override
    public int hashCode() {
        return Objects.hash(udpSocket, channel, eventLoopFactory, updateThread, running, eventHandler, guid);
    }
}
