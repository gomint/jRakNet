package io.gomint.jraknet;

import io.gomint.jraknet.datastructures.BinaryOrderingHeap;
import io.gomint.jraknet.datastructures.BitQueue;
import io.gomint.jraknet.datastructures.DatagramContentNode;
import io.gomint.jraknet.datastructures.FixedSizeRRBuffer;
import io.gomint.jraknet.datastructures.IntQueue;
import io.gomint.jraknet.datastructures.OrderingHeap;
import io.gomint.jraknet.datastructures.TriadRange;
import io.gomint.jraknet.datastructures.TriadRangeList;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static io.gomint.jraknet.RakNetConstraints.*;

/**
 * @author BlackyPaw
 * @version 1.0
 */
public abstract class Connection {

	public static final    int                 DEFAULT_RESEND_TIMEOUT = 3000;
	protected static final InetSocketAddress[] LOCAL_IP_ADDRESSES     = new InetSocketAddress[] { new InetSocketAddress( "127.0.0.1", 0 ), new InetSocketAddress( "0.0.0.0", 0 ), new InetSocketAddress( "0.0.0.0", 0 ), new InetSocketAddress( "0.0.0.0", 0 ), new InetSocketAddress( "0.0.0.0", 0 ), new InetSocketAddress( "0.0.0.0", 0 ), new InetSocketAddress( "0.0.0.0", 0 ), new InetSocketAddress( "0.0.0.0", 0 ), new InetSocketAddress( "0.0.0.0", 0 ), new InetSocketAddress( "0.0.0.0", 0 ) };

	// Connection Metadata
	private final SocketAddress   address;
	private       ConnectionState state;

	private boolean hasGuid;
	private int     mtuSize;
	private long    guid;

	private long lastReceivedPacket;

	// Congestion Management
	private int      expectedReliableMessageNumber;
	private BitQueue reliableMessageQueue;
	private int      nextDatagramSequenceNumber;
	private int      expectedDatagramSequenceNumber;

	// Ordering Channels
	private int[]          orderedReadIndex;
	private int[]          orderedWriteIndex;
	private int[]          highestSequencedReadIndex;
	private int[]          highestSequencedWriteIndex;
	private OrderingHeap[] orderingHeaps;
	private long[]         heapWeightOffsets;

	// Split packets
	private Map<Integer, SplitPacketAssembler> splitPacketChannels;

	// Receiving
	private Queue<EncapsulatedPacket> receiveBuffer;

	// Sending
	private Queue<EncapsulatedPacket> sendBuffer;
	private int                       nextReliableMessageNumber;
	private int                       nextSplitPacketID;
	private TriadRangeList            outgoingACKs;
	private TriadRangeList            outgoingNAKs;
	private List<EncapsulatedPacket>  sendList;
	private IntQueue                  sendListIndices;

	// Resending
	private long                                   resendTimeout;
	private FixedSizeRRBuffer<EncapsulatedPacket>  resendBuffer;
	private Queue<EncapsulatedPacket>              resendQueue;
	private FixedSizeRRBuffer<DatagramContentNode> datagramContentBuffer;

	// Disconnect
	private String disconnectMessage;

	// ================================ CONSTRUCTORS ================================ //

	Connection( SocketAddress address, ConnectionState initialState ) {
		this.address = address;
		this.state = initialState;
		this.reset();
	}

	// ================================ PUBLIC API ================================ //

	/**
	 * Gets the address of the connection's remote peer.
	 *
	 * @return The address of the connection's remote peer
	 */
	public SocketAddress getAddress() {
		return this.address;
	}

	/**
	 * Gets the connection's current state.
	 *
	 * @return The connection's current state
	 */
	public ConnectionState getState() {
		return this.state;
	}

	/**
	 * Tests whether or not the connection is currently connecting. This test will only fail if
	 * the connection is either not pre-connecting or post-connecting.
	 *
	 * @return Whether or not the connection is entirely connected
	 */
	public boolean isConnecting() {
		ConnectionState state = this.getState();
		return !( state == ConnectionState.UNCONNECTED || state == ConnectionState.CONNECTED || state == ConnectionState.DISCONNECTING );
	}

	/**
	 * Tests whether or not the connection is entirely connected.
	 * This is essentially the same as comparing {@link #getState()} to {@link ConnectionState#CONNECTED}.
	 *
	 * @return Whether or not the connection is entirely connected
	 */
	public boolean isConnected() {
		return ( this.getState() == ConnectionState.CONNECTED );
	}

	/**
	 * Tests whether or not the connection is currently trying to disconnect from its remote peer.
	 *
	 * @return Whether or not the connection is currently trying to disconnect
	 */
	public boolean isDisconnecting() {
		return ( this.getState() == ConnectionState.DISCONNECTING );
	}

	/**
	 * Disconnects from this connection giving a reason for the disconnect that will
	 * be sent to the remote peer. As the disconnect operation might require some time
	 * and may not be completed by blocking a {@link io.gomint.jraknet.SocketEvent.Type#CONNECTION_DISCONNECTED} event
	 * will be sent out once the connection disconnected successfully. After initiating the disconnect
	 * no further packets will be sent or received.
	 *
	 * @param reason The reason of the disconnect
	 */
	public void disconnect( String reason ) {
		if ( this.isConnected() ) {
			this.disconnectMessage = reason;
			this.sendDisconnectionNotification();
			this.state = ConnectionState.DISCONNECTING;
		}
	}

	/**
	 * Gets the disconnect message of the connection (might only be available once a respective event was sent via the
	 * socket).
	 *
	 * @return The connection's disconnect message
	 */
	public String getDisconnectMessage() {
		return this.disconnectMessage;
	}

	/**
	 * Gets the MTU size of the connection.
	 *
	 * @return The MTU size of the connection
	 */
	public int getMtuSize() {
		return this.mtuSize;
	}

	/**
	 * Gets the GUID of the connection's remote peer.
	 *
	 * @return The GUID of the connection's remote peer.
	 */
	public long getGuid() {
		return this.guid;
	}

	/**
	 * Receives one or more data packets.
	 * <p>
	 * Each invocation of this method will return exactly zero or one data packets.
	 * As long as this method returns non-null byte arrays there might still be more
	 * packets kept by the connection that need to be read.
	 *
	 * @return One single data packet or null if no more packets are available.
	 */
	public byte[] receive() {
		synchronized ( this.receiveBuffer ) {
			if ( this.receiveBuffer.isEmpty() ) {
				return null;
			}
			return this.receiveBuffer.poll().getPacketData();
		}
	}

	/**
	 * Sends the specified data ensuring the packet reliability {@link PacketReliability#RELIABLE}.
	 *
	 * @param data The data to send
	 */
	public void send( byte[] data ) {
		this.send( PacketReliability.RELIABLE, 0, data );
	}

	/**
	 * Sends the specified data ensuring the given packet reliability.
	 *
	 * @param reliability The reliability to ensure
	 * @param data        The data to send
	 */
	public void send( PacketReliability reliability, byte[] data ) {
		this.send( reliability, 0, data );
	}

	/**
	 * Sends the specified data ensuring the given packet reliability on the specified ordering channel
	 * (must be smaller than {@link RakNetConstraints#NUM_ORDERING_CHANNELS}.
	 *
	 * @param reliability     The reliability to ensure
	 * @param orderingChannel The ordering channel to send the data on
	 * @param data            The data to send
	 */
	public void send( PacketReliability reliability, int orderingChannel, byte[] data ) {
		this.send( reliability, orderingChannel, data, 0, data.length );
	}

	/**
	 * Sends the specified data ensuring the given packet reliability on the specified ordering channel
	 * (must be smaller than {@link RakNetConstraints#NUM_ORDERING_CHANNELS}. In case the data is interleaved
	 * a copy must be made internally so use this method with care!
	 *
	 * @param reliability     The reliability to ensure
	 * @param orderingChannel The ordering channel to send the data on
	 * @param data            The data to send
	 * @param offset          The offset into the data array
	 * @param length          The length of the data chunk to send
	 */
	public void send( PacketReliability reliability, int orderingChannel, byte[] data, int offset, int length ) {
		if ( !this.state.isReliable() || reliability == null || orderingChannel < 0 || orderingChannel >= NUM_ORDERING_CHANNELS || data == null || this.state == ConnectionState.DISCONNECTING ) {
			return;
		}

		EncapsulatedPacket packet = new EncapsulatedPacket();

		// Got to copy packet data if it is not aligned correctly:
		if ( offset != 0 || length != data.length ) {
			packet.setPacketData( Arrays.copyOfRange( data, offset, offset + length ) );
		} else {
			packet.setPacketData( data );
		}

		// Test if this packet must be split up:
		int maxSize = this.mtuSize - DATA_HEADER_BYTE_LENGTH - MAX_MESSAGE_HEADER_BYTE_LENGTH;
		if ( packet.getPacketLength() > maxSize ) {
			// Yes, it does, so adjust reliability if necessary:
			switch ( reliability ) {
				case UNRELIABLE:
					reliability = PacketReliability.RELIABLE;
					break;
				case UNRELIABLE_SEQUENCED:
					reliability = PacketReliability.RELIABLE_SEQUENCED;
					break;
				case UNRELIABLE_WITH_ACK_RECEIPT:
					reliability = PacketReliability.UNRELIABLE_WITH_ACK_RECEIPT;
					break;
			}
		}

		// Give the packet all meta-information and counters it requires:
		if ( reliability == PacketReliability.UNRELIABLE_SEQUENCED || reliability == PacketReliability.RELIABLE_SEQUENCED ) {
			packet.setOrderingChannel( (byte) orderingChannel );
			packet.setOrderingIndex( this.orderedWriteIndex[orderingChannel] );
			packet.setSequencingIndex( this.highestSequencedWriteIndex[orderingChannel]++ );
		} else if ( reliability == PacketReliability.RELIABLE_ORDERED || reliability == PacketReliability.RELIABLE_ORDERED_WITH_ACK_RECEIPT ) {
			packet.setOrderingChannel( (byte) orderingChannel );
			packet.setOrderingIndex( this.orderedWriteIndex[orderingChannel]++ );
			this.highestSequencedWriteIndex[orderingChannel] = 0;
		}

		packet.setReliability( reliability );

		if ( packet.getPacketLength() > maxSize ) {
			// Split up this packet:
			this.splitPacket( packet );
		} else {
			// Add it to the send buffer immediately:
			synchronized ( this.sendBuffer ) {
				this.sendBuffer.add( packet );
			}
		}
	}

	/**
	 * Sends the specified data ensuring the packet reliability {@link PacketReliability#RELIABLE}. Makes a copy
	 * of the specified data internally before caching it for send.
	 *
	 * @param data The data to send
	 */
	public void sendCopy( byte[] data ) {
		this.send( Arrays.copyOf( data, data.length ) );
	}

	/**
	 * Sends the specified data ensuring the given packet reliability. Makes a copy
	 * of the specified data internally before caching it for send.
	 *
	 * @param reliability The reliability to ensure
	 * @param data        The data to send
	 */
	public void sendCopy( PacketReliability reliability, byte[] data ) {
		this.send( reliability, Arrays.copyOf( data, data.length ) );
	}

	/**
	 * Sends the specified data ensuring the given packet reliability on the specified ordering channel
	 * (must be smaller than {@link RakNetConstraints#NUM_ORDERING_CHANNELS}. Makes a copy of the specified
	 * data internally before caching it for send.
	 *
	 * @param reliability     The reliability to ensure
	 * @param orderingChannel The ordering channel to send the data on
	 * @param data            The data to send
	 */
	public void sendCopy( PacketReliability reliability, int orderingChannel, byte[] data ) {
		this.send( reliability, orderingChannel, Arrays.copyOf( data, data.length ) );
	}

	/**
	 * Sends the specified data ensuring the given packet reliability on the specified ordering channel
	 * (must be smaller than {@link RakNetConstraints#NUM_ORDERING_CHANNELS}. In case the data is interleaved
	 * a copy must be made internally so use this method with care! Makes a copy of the specified data internally
	 * before caching it for send.
	 *
	 * @param reliability     The reliability to ensure
	 * @param orderingChannel The ordering channel to send the data on
	 * @param data            The data to send
	 * @param offset          The offset into the data array
	 * @param length          The length of the data chunk to send
	 */
	public void sendCopy( PacketReliability reliability, int orderingChannel, byte[] data, int offset, int length ) {
		this.send( reliability, orderingChannel, Arrays.copyOfRange( data, offset, offset + length ) );
	}

	// ================================ IMPLEMENTATION HOOKS ================================ //

	/**
	 * Sends raw data through an implementation-specific datagram socket. The data will already be encoded
	 * properly and is only required to be sent directly.
	 *
	 * @param recipient The recipient of the data
	 * @param buffer    The buffer containing the data to be sent
	 *
	 * @throws IOException Thrown in case the data could not be sent for some reason
	 */
	protected abstract void sendRaw( SocketAddress recipient, PacketBuffer buffer ) throws IOException;

	/**
	 * Gets a logger to be used for logging errors and warnings.
	 *
	 * @return The logger to be used for logging errors and warnings
	 */
	protected abstract Logger getImplementationLogger();

	/**
	 * Invoked ahead of any internal updates during an update tick.
	 *
	 * @param time The current system time
	 */
	protected void preUpdate( long time ) {

	}

	/**
	 * Invoked after all internal updates have been made during an update tick.
	 * Might not be invoked at all if the internal updates require the connection
	 * to abort updating early.
	 *
	 * @param time The current system time
	 */
	protected void postUpdate( long time ) {

	}

	/**
	 * Implementation hook.
	 *
	 * @param datagram The datagram to be handled
	 * @param time     The current system time
	 *
	 * @return Whether or not the datagram was handled already and should be processed no further
	 */
	protected abstract boolean handleDatagram0( DatagramBuffer datagram, long time );

	/**
	 * Implementation hook.
	 *
	 * @param packet The packet to be handled
	 *
	 * @return Whether or not the packet was handled already and should be processed no further
	 */
	protected abstract boolean handlePacket0( EncapsulatedPacket packet );

	/**
	 * Invoked whenever the connection was closed for some reason. This event should be propagated to the
	 * socket the specific implementation was created by.
	 */
	protected abstract void propagateConnectionClosed();

	/**
	 * Invoked whenever the connection disconnected for some reason. This event should be propagated to the
	 * socket the specific implementation was created by.
	 */
	protected abstract void propagateConnectionDisconnected();

	/**
	 * Invoked whenever the connection switched to an entirely connected state. This event should be propagated to the
	 * socket the specific implementation was created by.
	 */
	protected abstract void propagateFullyConnected();


	// ================================ INTERNALS ================================ //

	/**
	 * Sets the connection's current state. To be used with utmost care as this may cause internal
	 * things to get messed up. Use it only for implementing connection establishment protocols or
	 * similar.
	 *
	 * @param state The connection's new state.
	 */
	protected final void setState( ConnectionState state ) {
		ConnectionState previousState = this.state;
		this.state = state;

		switch( this.state ) {
			case UNCONNECTED:
				if ( previousState != ConnectionState.UNCONNECTED ) {
					// Reset this connection entirely:
					this.reset();
				}
				break;
			case CONNECTED:
				this.propagateFullyConnected();
				break;
		}
	}


	protected final void setMtuSize( int mtuSize ) {
		this.mtuSize = mtuSize;
	}

	protected final void setGuid( long guid ) {
		this.hasGuid = true;
		this.guid = guid;
	}

	/**
	 * Initializes all internal structures that are quite memory-consuming.
	 */
	protected final void initializeStructures() {
		this.reliableMessageQueue = new BitQueue( 512 );
		this.orderedReadIndex = new int[NUM_ORDERING_CHANNELS];
		this.orderedWriteIndex = new int[NUM_ORDERING_CHANNELS];
		this.highestSequencedReadIndex = new int[NUM_ORDERING_CHANNELS];
		this.highestSequencedWriteIndex = new int[NUM_ORDERING_CHANNELS];
		this.orderingHeaps = new BinaryOrderingHeap[NUM_ORDERING_CHANNELS];
		this.heapWeightOffsets = new long[NUM_ORDERING_CHANNELS];
		for ( int i = 0; i < NUM_ORDERING_CHANNELS; ++i ) {
			this.orderingHeaps[i] = new BinaryOrderingHeap();
		}
		this.splitPacketChannels = new HashMap<>();
		this.receiveBuffer = new LinkedList<>();
		this.sendBuffer = new LinkedList<>();
		this.outgoingACKs = new TriadRangeList( 128 );
		this.outgoingNAKs = new TriadRangeList( 128 );
		this.sendList = new ArrayList<>( 32 );
		this.sendListIndices = new IntQueue();
		this.resendBuffer = new FixedSizeRRBuffer<>( 256 );
		this.resendQueue = new LinkedList<>();
		this.datagramContentBuffer = new FixedSizeRRBuffer<>( 256 );
	}

	/**
	 * Resets the entire connection state and deletes all memory consuming state structures.
	 */
	protected final void reset() {
		this.lastReceivedPacket = System.currentTimeMillis();
		this.hasGuid = false;
		this.mtuSize = 0;
		this.guid = 0L;
		this.expectedReliableMessageNumber = 0;
		this.nextDatagramSequenceNumber = 0;
		this.expectedDatagramSequenceNumber = 0;
		this.nextReliableMessageNumber = 0;
		this.nextSplitPacketID = 0;
		this.nextDatagramSequenceNumber = 0;
		this.resendTimeout = DEFAULT_RESEND_TIMEOUT;
		this.disconnectMessage = "Connection closed";
		this.reliableMessageQueue = null;
		this.orderedReadIndex = null;
		this.orderedWriteIndex = null;
		this.highestSequencedReadIndex = null;
		this.highestSequencedWriteIndex = null;
		this.orderingHeaps = null;
		this.heapWeightOffsets = null;
		this.splitPacketChannels = null;
		this.receiveBuffer = null;
		this.sendBuffer = null;
		this.outgoingACKs = null;
		this.outgoingNAKs = null;
		this.sendList = null;
		this.sendListIndices = null;
		this.resendBuffer = null;
		this.resendQueue = null;
		this.datagramContentBuffer = null;
	}

	/**
	 * Invoked by the socket's update thread.
	 *
	 * @param time The current system time
	 *
	 * @return Whether or not the connection is still active
	 */
	boolean update( long time ) {
		this.preUpdate( time );

		if ( this.state == ConnectionState.UNCONNECTED ) {
			return false;
		}

		if ( !this.state.isReliable() ) {
			return true;
		}

		this.sendACKs();
		this.sendNAKs();

		int currentDatagramSize = 0;
		int maxDatagramSize     = this.mtuSize - DATA_HEADER_BYTE_LENGTH;

		// Resend everything scheduled for resend:
		while ( !this.resendQueue.isEmpty() ) {
			EncapsulatedPacket packet = this.resendQueue.peek();
			if ( packet.getNextInteraction() <= time ) {

				// Delete packets marked for removal:
				if ( packet.getNextInteraction() == 0L ) {
					this.resendQueue.poll();
					continue;
				}

				// Push current datagram to send queue if adding this packet would exceed the MTU:
				int length = packet.getHeaderLength() + packet.getPacketLength();
				if ( currentDatagramSize + length > maxDatagramSize ) {
					// Push datagram:
					this.sendListIndices.add( this.sendList.size() );
					currentDatagramSize = 0;
				}

				this.resendQueue.poll();
				packet.setNextInteraction( time + this.resendTimeout );

				this.sendList.add( packet );
				packet.incrementSendCount();
				currentDatagramSize += length;

				// Insert back into resend queue:
				this.resendQueue.add( packet );
			} else {
				break;
			}
		}

		// Attempt to send new packets:
		synchronized ( this.sendBuffer ) {
			while ( !this.sendBuffer.isEmpty() && this.resendBuffer.get( this.nextReliableMessageNumber ) == null ) {
				EncapsulatedPacket packet = this.sendBuffer.poll();

				// Push current datagram to send queue if adding this packet would exceed the MTU:
				int length = packet.getHeaderLength() + packet.getPacketLength();
				if ( currentDatagramSize + length > maxDatagramSize ) {
					// Push datagram:
					this.sendListIndices.add( this.sendList.size() );
					currentDatagramSize = 0;
				}

				PacketReliability reliability = packet.getReliability();
				if ( reliability == PacketReliability.RELIABLE ||
				     reliability == PacketReliability.RELIABLE_SEQUENCED ||
				     reliability == PacketReliability.RELIABLE_ORDERED ) {
					packet.setReliableMessageNumber( this.nextReliableMessageNumber );

					// Insert into resend queue:
					packet.setNextInteraction( time + this.resendTimeout );
					this.resendQueue.add( packet );

					// Add to FixedSize round-robin resend buffer:
					this.resendBuffer.set( this.nextReliableMessageNumber, packet );

					++this.nextReliableMessageNumber;
				}

				this.sendList.add( packet );
				packet.incrementSendCount();
				currentDatagramSize += length;
			}
		}

		// Push the final datagram if any is to be pushed:
		if ( currentDatagramSize > 0 ) {
			this.sendListIndices.add( this.sendList.size() );
		}

		// Now finally build datagrams and send them out after all this surrounding handling:
		if ( !this.sendListIndices.isEmpty() ) {
			PacketBuffer        buffer = new PacketBuffer( this.mtuSize );
			DatagramContentNode dcn    = null;

			for ( int i = 0; i < this.sendListIndices.size(); ++i ) {
				int min, max;

				if ( i == 0 ) {
					min = 0;
					max = this.sendListIndices.get( i );
				} else {
					min = this.sendListIndices.get( i - 1 );
					max = this.sendListIndices.get( i );
				}

				// Write datagram header:
				byte flags = (byte) ( 0x80 | ( i > 0 ? 0x8 : 0x0 ) );     // IsValid | (isContinuousSend)
				buffer.writeByte( flags );
				buffer.writeTriad( this.nextDatagramSequenceNumber );

				for ( int j = min; j < max; ++j ) {
					EncapsulatedPacket packet = this.sendList.get( j );

					// Add this packet to the datagram content buffer if reliable:
					if ( packet.getReliability() != PacketReliability.UNRELIABLE && packet.getReliability() != PacketReliability.UNRELIABLE_SEQUENCED ) {
						if ( dcn == null ) {
							dcn = new DatagramContentNode( packet.getReliableMessageNumber() );
							this.datagramContentBuffer.set( this.nextDatagramSequenceNumber, dcn );
						} else {
							dcn.setNext( new DatagramContentNode( packet.getReliableMessageNumber() ) );
							dcn = dcn.getNext();
						}
					}

					packet.writeToBuffer( buffer );
				}

				if ( dcn == null ) {
					this.datagramContentBuffer.set( this.nextDatagramSequenceNumber, null );
				}

				// Finally send this packet buffer to its destination:
				try {
					this.sendRaw( this.address, buffer );
				} catch ( IOException e ) {
					this.getImplementationLogger().error( "Failed to send datagram to destination", e );
				}

				++this.nextDatagramSequenceNumber;
				if ( i + 1 < this.sendListIndices.size() ) {
					buffer = new PacketBuffer( this.mtuSize );
				}
			}

			this.sendList.clear();
			this.sendListIndices.clear();
		}

		this.postUpdate( time );

		if ( this.state == ConnectionState.DISCONNECTING ) {
			// Check if we can perform a clean disconnect now:
			if ( this.resendQueue.isEmpty() && this.sendBuffer.isEmpty() ) {
				this.state = ConnectionState.UNCONNECTED;
				this.propagateConnectionDisconnected();
				return false;
			}

			// Fake not timing out in order to fully send all packets still in queue:
			this.lastReceivedPacket = time;
		}

		return true;
	}

	/**
	 * Notifies the connection that it timed out
	 */
	void notifyTimeout() {
		this.disconnectMessage = "Connection timed out";
		this.state = ConnectionState.UNCONNECTED;
		this.propagateConnectionClosed();
	}

	/**
	 * Gets the timestamp at which the connection received the last datagram from its
	 * remote peer.
	 *
	 * @return The timestamp of the connection's last received packet
	 */
	long getLastReceivedPacketTime() {
		return this.lastReceivedPacket;
	}

	/**
	 * Checks whether or not the connection has already transmitted the remote peer's GUID.
	 *
	 * @return Whether or not the remote peer's GUID is already available
	 */
	boolean hasGuid() {
		return this.hasGuid;
	}

	/**
	 * Invoked by the receive thread whenever a datagram from this connection's remote peer
	 * was received.
	 *
	 * @param datagram The datagram that was received
	 */
	void handleDatagram( DatagramBuffer datagram, long time ) {
		this.lastReceivedPacket = time;

		if ( !this.handleDatagram0( datagram, time ) ) {
			this.handleConnectedDatagram( datagram );
		}
	}

	/**
	 * Splits the given packet up into smaller packets and fills out all required header information.
	 * All smaller packets will be added to the send buffer immediately.
	 *
	 * @param packet The packet to be split into pieces
	 */
	private void splitPacket( EncapsulatedPacket packet ) {
		int bytesPerDatagram = this.mtuSize - DATA_HEADER_BYTE_LENGTH - MAX_MESSAGE_HEADER_BYTE_LENGTH;
		int splitPacketCount = ( ( packet.getPacketLength() - 1 ) / bytesPerDatagram ) + 1;
		int splitPacketID    = this.nextSplitPacketID++;

		// Simulate overflow to zero:
		if ( splitPacketID == ( 1 << 16 ) ) {
			splitPacketID = 0;
		}

		final int length = packet.getPacketLength();
		int       cursor = 0, count;
		for ( int splitPacketIndex = 0; splitPacketIndex < splitPacketCount; ++splitPacketIndex ) {
			count = length - cursor;
			if ( count > bytesPerDatagram ) {
				count = bytesPerDatagram;
			}

			EncapsulatedPacket copy = new EncapsulatedPacket( packet );
			copy.setPacketData( Arrays.copyOfRange( packet.getPacketData(), cursor, cursor + count ) );
			copy.setSplitPacketId( splitPacketID );
			copy.setSplitPacketIndex( splitPacketIndex );
			copy.setSplitPacketCount( splitPacketCount );
			this.sendBuffer.add( copy );

			cursor += count;
		}
	}

	// ================================ ACKs AND NAKs ================================ //

	private void handleACKs( PacketBuffer buffer ) {
		TriadRange[] ranges = buffer.readTriadRangeList();
		if ( ranges == null ) {
			return;
		}

		for ( int i = 0; i < ranges.length; ++i ) {
			for ( int j = ranges[i].getMin(); j <= ranges[i].getMax(); ++j ) {
				// Remove all packets contained in the ACKed datagram from the resend buffer:
				DatagramContentNode node = this.datagramContentBuffer.get( j );
				while ( node != null ) {
					EncapsulatedPacket packet = this.resendBuffer.get( node.getReliableMessageNumber() );
					if ( packet != null ) {
						// Enforce deletion on next interaction:
						packet.setNextInteraction( 0L );
						this.resendBuffer.set( node.getReliableMessageNumber(), null );
					}
					node = node.getNext();
				}
			}
		}
	}

	private void handleNAKs( PacketBuffer buffer ) {
		TriadRange[] ranges = buffer.readTriadRangeList();
		if ( ranges == null ) {
			return;
		}

		for ( int i = 0; i < ranges.length; ++i ) {
			for ( int j = ranges[i].getMin(); j <= ranges[i].getMax(); ++j ) {
				// Enforce immediate resend:
				DatagramContentNode node = this.datagramContentBuffer.get( j );
				while ( node != null ) {
					EncapsulatedPacket packet = this.resendBuffer.get( node.getReliableMessageNumber() );
					if ( packet != null ) {
						// Enforce instant resend on next interaction:
						packet.setNextInteraction( 1L );
					}
					node = node.getNext();
				}
			}
		}
	}

	private void sendACKs() {
		if ( this.outgoingACKs.size() > 0 ) {
			int          maxSize = this.mtuSize - DATA_HEADER_BYTE_LENGTH;
			PacketBuffer buffer  = new PacketBuffer( this.mtuSize );

			// IsValid | IsACK
			byte flags = (byte) 0x80 | (byte) 0x40;
			buffer.writeByte( flags );

			// Serialize ACKs into buffer and remove them afterwards:
			int count = buffer.writeTriadRangeList( this.outgoingACKs.getBackingArray(), 0, this.outgoingACKs.size(), maxSize );
			this.outgoingACKs.shiftLeft( count );

			// Send this data directly:
			try {
				this.sendRaw( this.address, buffer );
			} catch ( IOException ignored ) {
				// ._.
			}
		}
	}

	private void sendNAKs() {
		if ( this.outgoingNAKs.size() > 0 ) {
			int          maxSize = this.mtuSize - DATA_HEADER_BYTE_LENGTH;
			PacketBuffer buffer  = new PacketBuffer( this.mtuSize );

			// IsValid | IsNAK
			byte flags = (byte) 0x80 | (byte) 0x20;
			buffer.writeByte( flags );

			// Serialize ACKs into buffer and remove them afterwards:
			int count = buffer.writeTriadRangeList( this.outgoingNAKs.getBackingArray(), 0, this.outgoingNAKs.size(), maxSize );
			this.outgoingNAKs.shiftLeft( count );

			// Send this data directly:
			try {
				this.sendRaw( this.address, buffer );
			} catch ( IOException ignored ) {
				// ._.
			}
		}
	}

	// ================================ PACKET HANDLERS ================================ //

	private void handleConnectedDatagram( DatagramBuffer datagram ) {
		if ( !this.state.isReliable() ) {
			// This connection is not reliable --> internal structures might not have been initialized
			return;
		}

		// Deserialize datagram header:
		PacketBuffer buffer  = new PacketBuffer( datagram.getData(), 0 );
		byte         flags   = buffer.readByte();
		boolean      isValid = ( flags & 0x80 ) != 0;
		if ( !isValid ) {
			// Not an encapsulated packet --> Discard
			this.getImplementationLogger().debug( "Discarding invalid packet" );
			return;
		}

		boolean isACK = ( flags & 0x40 ) != 0;
		if ( isACK ) {
			// This datagram only contains ACKs --> Handle separately
			this.handleACKs( buffer );
			return;
		}

		// Only handling ACKs if disconnecting --> makes room in resend buffer which might block clearing
		// the send buffer in order to disconnect cleanly:
		if ( this.state == ConnectionState.DISCONNECTING ) {
			// Do not receive any further data from this connection
			return;
		}

		boolean isNAK = ( flags & 0x20 ) != 0;
		if ( isNAK ) {
			// This datagram only contains NAKs --> Handle separately
			this.handleNAKs( buffer );
			return;
		}

		int datagramSequenceNumber = buffer.readTriad();
		int skippedMessageCount    = 0;
		if ( datagramSequenceNumber == this.expectedDatagramSequenceNumber ) {
			this.expectedDatagramSequenceNumber++;
		} else if ( datagramSequenceNumber > this.expectedDatagramSequenceNumber ) {
			this.expectedDatagramSequenceNumber = datagramSequenceNumber + 1;
			skippedMessageCount = ( datagramSequenceNumber - this.expectedDatagramSequenceNumber );
		}

		// NAK all datagrams missing in between:
		for ( int i = skippedMessageCount; i > 0; --i ) {
			this.outgoingNAKs.insert( datagramSequenceNumber - i );
		}

		// ACK this datagram:
		this.outgoingACKs.insert( datagramSequenceNumber );

		EncapsulatedPacket packet = new EncapsulatedPacket();
		while ( buffer.getPosition() - buffer.getBufferOffset() < datagram.length() && packet.readFromBuffer( buffer ) ) {
			if ( packet.isSplitPacket() ) {
				packet = this.rebuildSplitPacket( packet );
				if ( packet == null ) {
					packet = new EncapsulatedPacket();
					continue;
				}
			}

			PacketReliability reliability     = packet.getReliability();
			int               orderingIndex   = packet.getOrderingIndex();
			byte              orderingChannel = packet.getOrderingChannel();

			// Take not of this packet in order to force resend of possibly lost
			// reliable messages:
			if ( reliability == PacketReliability.RELIABLE ||
			     reliability == PacketReliability.RELIABLE_SEQUENCED ||
			     reliability == PacketReliability.RELIABLE_ORDERED ) {
				int holes = ( packet.getReliableMessageNumber() - this.expectedReliableMessageNumber );

				if ( holes > 0 ) {
					if ( holes < this.reliableMessageQueue.size() ) {
						if ( this.reliableMessageQueue.get( holes ) ) {
							this.reliableMessageQueue.set( holes, false );
						} else {
							// Packet was already received (Duplicate) --> Discard
							continue;
						}
					} else {
						// Got to fill up the queue with true s indicating missing packets in between:
						int count = ( holes - this.reliableMessageQueue.size() );
						for ( int i = 0; i < count; ++i ) {
							this.reliableMessageQueue.add( true );
						}

						// We did receive this packet though!
						this.reliableMessageQueue.add( false );
					}
				} else if ( holes == 0 ) {
					++this.expectedReliableMessageNumber;
					if ( !this.reliableMessageQueue.isEmpty() ) {
						this.reliableMessageQueue.poll();
					}
				} else {
					// Packet was already received (Duplicate) --> Discard
					continue;
				}

				// Maybe we finally received a packet that was blocking the rest of the queue before
				// Check if this is the case and if so adjust the message index and queue appropriately:
				while ( !this.reliableMessageQueue.isEmpty() && !this.reliableMessageQueue.peek() ) {
					this.reliableMessageQueue.poll();
					++this.expectedReliableMessageNumber;
				}
			}

			// Now handle decoded packet according to reliability:
			if ( reliability == PacketReliability.RELIABLE_SEQUENCED ||
			     reliability == PacketReliability.UNRELIABLE_SEQUENCED ||
			     reliability == PacketReliability.RELIABLE_ORDERED ) {
				// Is sequenced or ordered

				if ( orderingIndex == this.orderedReadIndex[orderingChannel] ) {
					// Has latest ordering index

					if ( reliability == PacketReliability.RELIABLE_SEQUENCED || reliability == PacketReliability.UNRELIABLE_SEQUENCED ) {
						// Is sequenced

						int sequencingIndex = packet.getSequencingIndex();
						if ( sequencingIndex >= this.highestSequencedReadIndex[orderingChannel] ) {
							// Is newer than any previous sequenced packets:

							this.highestSequencedReadIndex[orderingChannel] = sequencingIndex + 1;
							// Pass on to user
							this.pushReceivedPacket( packet );
						} // else {
						// Is coming out of band

						// Simply parse next packet:
						// continue;
						// }
					} else {
						// Is ordered

						// Pass on to user
						this.pushReceivedPacket( packet );
						this.orderedReadIndex[orderingChannel]++;
						this.highestSequencedReadIndex[orderingChannel] = 0;

						// IMPORTANT: Consider implementing Fast Binary Heap by Peter Sanders here!
						// Return all packets that have been sorted into the heap after this packet's ordering index:
						OrderingHeap heap = this.orderingHeaps[orderingChannel];
						while ( !heap.isEmpty() && heap.peek().getOrderingIndex() == this.orderedReadIndex[orderingChannel] ) {
							packet = heap.poll();
							// Pass on to user
							this.pushReceivedPacket( packet );

							if ( packet.getReliability() == PacketReliability.RELIABLE_ORDERED ) {
								this.orderedReadIndex[orderingChannel]++;
							} else {
								this.highestSequencedReadIndex[orderingChannel] = packet.getSequencingIndex();
							}
						}
					}
				} else if ( orderingIndex > this.orderedReadIndex[orderingChannel] ) {
					// Has higher ordering index than expected

					OrderingHeap heap = this.orderingHeaps[orderingChannel];
					// --> Buffer this packet until prior packets arrive:
					if ( heap.isEmpty() ) {
						this.heapWeightOffsets[orderingChannel] = this.orderedReadIndex[orderingChannel];
					}

					// This allows for 2^19 (=524.288) sequenced packets in between each ordered packet
					// OR 2^63 sequenced packets without any ordered packets in between:
					long weight = ( orderingIndex - this.heapWeightOffsets[orderingChannel] ) << 19;
					weight += ( packet.getReliability() == PacketReliability.RELIABLE_ORDERED ? ( 1 << 19 ) - 1 : packet.getSequencingIndex() );

					heap.insert( weight, packet );
				} // else {
				// Has lower ordering index than expected

				// --> This packet comes out of bands; discard it
				// }
			} else {
				// Pass on to user
				this.pushReceivedPacket( packet );
			}
		}
	}

	private void pushReceivedPacket( EncapsulatedPacket packet ) {
		if ( packet.getPacketLength() <= 0 ) {
			return;
		}

		byte packetId = packet.getPacketData()[0];
		switch ( packetId ) {
			case CONNECTED_PING:
				this.handleConnectedPing( packet );
				break;
			case CONNECTED_PONG:
				this.handleConnectedPong( packet );
				break;
			case DISCONNECTION_NOTIFICATION:
				this.handleDisconnectionNotification( packet );
				break;
			default:
				if ( !this.handlePacket0( packet ) ) {
					if ( packetId > USER_PACKET_ENUM ) {
						synchronized ( this.receiveBuffer ) {
							this.receiveBuffer.add( packet );
						}
					}
				}
				break;
		}
	}

	private EncapsulatedPacket rebuildSplitPacket( EncapsulatedPacket packet ) {
		if ( !packet.isSplitPacket() ) {
			return null;
		}

		SplitPacketAssembler assembler = this.splitPacketChannels.get( packet.getSplitPacketId() );
		if ( assembler == null ) {
			assembler = new SplitPacketAssembler( packet );
			this.splitPacketChannels.put( packet.getSplitPacketId(), assembler );
		}

		packet = assembler.add( packet );
		return packet;
	}

	private void handleConnectedPing( EncapsulatedPacket packet ) {
		PacketBuffer buffer = new PacketBuffer( packet.getPacketData(), 0 );
		buffer.skip( 1 );                                      // Packet ID
		long pingTime = buffer.readLong();                     // Ping Time

		// Respond with pong packet
		// If one would like to detect latencies this would be the go-to place:
		this.sendConnectedPong( pingTime );
	}

	private void handleConnectedPong( @SuppressWarnings( "unused" ) EncapsulatedPacket packet ) {
		// If there should ever be an interest of calculating connection latency
		// this would be the go-to place
	}

	private void handleDisconnectionNotification( @SuppressWarnings( "unused" ) EncapsulatedPacket packet ) {
		this.state = ConnectionState.UNCONNECTED;
		this.disconnectMessage = "Connection was forcibly closed by remote peer";
		this.propagateConnectionClosed();
	}

	// ================================ PACKET SENDERS ================================ //

	private void sendConnectedPong( long pingTime ) {
		PacketBuffer buffer = new PacketBuffer( 17 );
		buffer.writeByte( CONNECTED_PONG );
		buffer.writeLong( pingTime );
		buffer.writeLong( System.currentTimeMillis() );
		this.send( PacketReliability.UNRELIABLE, buffer.getBuffer() );
	}

	private void sendDisconnectionNotification() {
		byte[] data = new byte[1];
		data[0] = DISCONNECTION_NOTIFICATION;
		this.send( PacketReliability.RELIABLE_ORDERED, 0, data );
	}

}
