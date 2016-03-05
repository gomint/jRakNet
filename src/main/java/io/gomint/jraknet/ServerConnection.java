package io.gomint.jraknet;

import io.gomint.jraknet.datastructures.BinaryOrderingHeap;
import io.gomint.jraknet.datastructures.BitQueue;
import io.gomint.jraknet.datastructures.DatagramContentNode;
import io.gomint.jraknet.datastructures.FixedSizeRRBuffer;
import io.gomint.jraknet.datastructures.IntQueue;
import io.gomint.jraknet.datastructures.OrderingHeap;
import io.gomint.jraknet.datastructures.TriadRange;
import io.gomint.jraknet.datastructures.TriadRangeList;

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
class ServerConnection implements Connection {

	public static final int                 DEFAULT_RESEND_TIMEOUT = 3000;
	public static final InetSocketAddress[] LOCAL_IP_ADDRESSES     = new InetSocketAddress[] { new InetSocketAddress( "127.0.0.1", 0 ), new InetSocketAddress( "0.0.0.0", 0 ), new InetSocketAddress( "0.0.0.0", 0 ), new InetSocketAddress( "0.0.0.0", 0 ), new InetSocketAddress( "0.0.0.0", 0 ), new InetSocketAddress( "0.0.0.0", 0 ), new InetSocketAddress( "0.0.0.0", 0 ), new InetSocketAddress( "0.0.0.0", 0 ), new InetSocketAddress( "0.0.0.0", 0 ), new InetSocketAddress( "0.0.0.0", 0 ) };

	// References
	private final ServerSocket server;

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
	private String disconnectMessage = "Connection closed";

	ServerConnection( ServerSocket server, SocketAddress address ) {
		this( server, address, ConnectionState.UNCONNECTED );
	}

	ServerConnection( ServerSocket server, SocketAddress address, ConnectionState initialState ) {
		this.server = server;
		this.address = address;
		this.state = initialState;
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
	}

	// ================================ CONNECTION ================================ //

	@Override
	public SocketAddress getAddress() {
		return this.address;
	}

	@Override
	public ConnectionState getState() {
		return this.state;
	}

	@Override
	public boolean isConnecting() {
		return ( this.getState() == ConnectionState.CONNECTING );
	}

	@Override
	public boolean isConnected() {
		return ( this.getState() == ConnectionState.CONNECTED );
	}

	@Override
	public boolean isDisconnecting() {
		return ( this.getState() == ConnectionState.DISCONNECTING );
	}

	@Override
	public byte[] receive() {
		synchronized ( this.receiveBuffer ) {
			return this.receiveBuffer.poll().getPacketData();
		}
	}

	@Override
	public void disconnect( String reason ) {
		if ( this.isConnected() ) {
			this.disconnectMessage = reason;
			this.sendDisconnectionNotification();
			this.state = ConnectionState.DISCONNECTING;
		}
	}

	@Override
	public String getDisconnectMessage() {
		return this.disconnectMessage;
	}

	/**
	 * Returns the connection's MTU size.
	 *
	 * @return The connection's MTU size.
	 */
	@Override
	public int getMtuSize() {
		return this.mtuSize;
	}

	/**
	 * Gets the remote peer's RakNet globally unique identifier.
	 *
	 * @return The remote peer's GUID
	 */
	@Override
	public long getGuid() {
		return this.guid;
	}

	@Override
	public void send( byte[] data ) {
		this.send( PacketReliability.RELIABLE, 0, data, 0, data.length );
	}

	@Override
	public void send( PacketReliability reliability, byte[] data ) {
		this.send( reliability, 0, data, 0, data.length );
	}

	@Override
	public void send( PacketReliability reliability, int orderingChannel, byte[] data ) {
		this.send( reliability, orderingChannel, data, 0, data.length );
	}

	@Override
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

	@Override
	public void sendCopy( byte[] data ) {
		this.send( Arrays.copyOf( data, data.length ) );
	}

	@Override
	public void sendCopy( PacketReliability reliability, byte[] data ) {
		this.send( reliability, Arrays.copyOf( data, data.length ) );
	}

	@Override
	public void sendCopy( PacketReliability reliability, int orderingChannel, byte[] data ) {
		this.send( reliability, orderingChannel, Arrays.copyOf( data, data.length ) );
	}

	@Override
	public void sendCopy( PacketReliability reliability, int orderingChannel, byte[] data, int offset, int length ) {
		this.send( reliability, orderingChannel, Arrays.copyOfRange( data, offset, offset + length ) );
	}

	// ================================ INTERNALS ================================ //

	/**
	 * Invoked by the server socket's update thread.
	 *
	 * @param time The current system time
	 *
	 * @return Whether or not the connection is still active
	 */
	boolean update( long time ) {
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
					// TODO: Clear dcn here
					this.datagramContentBuffer.set( this.nextDatagramSequenceNumber, null );
				}

				// Finally send this packet buffer to its destination:
				try {
					System.out.println( "Sending packets" );
					this.server.send( this.address, buffer );
				} catch ( IOException e ) {
					this.server.getLogger().error( "Failed to send datagram to destination", e );
				}

				++this.nextDatagramSequenceNumber;
			}

			this.sendList.clear();
			this.sendListIndices.clear();
		}

		if ( this.state == ConnectionState.DISCONNECTING ) {
			// Check if we can perform a clean disconnect now:
			if ( this.resendQueue.isEmpty() && this.sendBuffer.isEmpty() ) {
				this.state = ConnectionState.UNCONNECTED;
				this.server.propagateConnectionDisconnected( this );
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
		this.server.propagateConnectionClosed( this );
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
	void handleDatagram( DatagramPacket datagram ) {
		this.lastReceivedPacket = System.currentTimeMillis(); // Replace time consuming task somehow

		byte packetID = datagram.getData()[0];

		switch ( packetID ) {
			case OPEN_CONNECTION_REQUEST_1:
				this.handlePreConnectionRequest1( datagram );
				break;
			case OPEN_CONNECTION_REQUEST_2:
				this.handlePreConnectionRequest2( datagram );
				break;
			default:
				this.handleConnectedDatagram( datagram );
				break;
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
			copy.setPacketData( Arrays.copyOfRange( packet.getPacketData(), cursor, count ) );
			copy.setSplitPacketId( splitPacketID );
			copy.setSplitPacketIndex( splitPacketIndex );
			copy.setSplitPacketCount( splitPacketCount );
			this.sendBuffer.add( copy );

			cursor += count;
		}
	}

	/**
	 * Initializes all internal structures that are quite memory-consuming.
	 */
	private void initializeStructures() {
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
				System.out.println( "Received ACK for datagram " + j );
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
				this.server.send( this.address, buffer );
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
				this.server.send( this.address, buffer );
			} catch ( IOException ignored ) {
				// ._.
			}
		}
	}

	// ================================ PACKET HANDLERS ================================ //

	private void handleConnectedDatagram( DatagramPacket datagram ) {
		if ( !this.state.isReliable() ) {
			// This connection is not reliable --> internal structures might not have been initialized
			return;
		}

		// Deserialize datagram header:
		PacketBuffer buffer  = new PacketBuffer( datagram.getData(), datagram.getOffset() );
		byte         flags   = buffer.readByte();
		boolean      isValid = ( flags & 0x80 ) != 0;
		if ( !isValid ) {
			// Not an encapsulated packet --> Discard
			this.server.getLogger().debug( "Discarding invalid packet" );
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
		while ( buffer.getPosition() - buffer.getBufferOffset() < datagram.getLength() && packet.readFromBuffer( buffer ) ) {
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

		// Handle special internal packets:
		byte packetId = packet.getPacketData()[0];
		switch ( packetId ) {
			case CONNECTED_PING:
				System.out.println( "Received connected ping" );
				this.handleConnectedPing( packet );
				break;
			case CONNECTION_REQUEST:
				System.out.println( "Received connection request" );
				this.handleConnectionRequest( packet );
				break;
			case DISCONNECTION_NOTIFICATION:
				System.out.println( "Received disconnection notification" );
				this.handleDisconnectionNotification( packet );
				break;
			case NEW_INCOMING_CONNECTION:
				System.out.println( "Received new incoming connection" );
				this.handleNewIncomingConnection( packet );
				break;
			default:
				System.out.println( "Received data packet (0x" + Integer.toHexString( ( (int) packetId ) & 0xFF ) + ")" );
				if ( packetId > USER_PACKET_ENUM ) {
					synchronized ( this.receiveBuffer ) {
						this.receiveBuffer.add( packet );
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

	private void handlePreConnectionRequest1( DatagramPacket datagram ) {
		this.state = ConnectionState.INITIALIZING;

		byte remoteProtocol = datagram.getData()[1 + RakNetConstraints.OFFLINE_MESSAGE_DATA_ID.length];

		// Check for correct protocol:
		if ( remoteProtocol != RAKNET_PROTOCOL_VERSION ) {
			this.sendIncompatibleProtocolVersion();
			this.state = ConnectionState.UNCONNECTED;
			return;
		}

		this.sendConnectionReply1( datagram );
	}

	private void handlePreConnectionRequest2( DatagramPacket datagram ) {
		PacketBuffer buffer = new PacketBuffer( datagram.getData(), datagram.getOffset() );
		buffer.skip( 1 );                                      // Packet ID
		buffer.readOfflineMessageDataId();                     // Offline Message Data ID
		InetSocketAddress bindAddress = buffer.readAddress();  // Address the client bound to
		this.mtuSize = buffer.readUShort();                    // MTU
		this.guid = buffer.readLong();                         // Client GUID
		this.hasGuid = true;

		if ( !this.server.testAddressAndGuid( this ) ) {
			this.sendAlreadyConnected();
			return;
		}

		if ( !this.server.allowIncomingConnection() ) {
			this.sendNoFreeIncomingConnections();
			return;
		}

		// Do this before sending the response:
		this.initializeStructures();

		this.sendConnectionReply2();
		this.state = ConnectionState.RELIABLE;
	}

	private void handleConnectedPing( EncapsulatedPacket packet ) {
		PacketBuffer buffer = new PacketBuffer( packet.getPacketData(), 0 );
		buffer.skip( 1 );                                      // Packet ID
		long pingTime = buffer.readLong();                     // Ping Time

		// Respond with pong packet
		// If one would like to detect latencies this would be the go-to place:
		this.sendConnectedPong( pingTime );
	}

	private void handleConnectionRequest( EncapsulatedPacket packet ) {
		this.state = ConnectionState.CONNECTING;

		PacketBuffer buffer = new PacketBuffer( packet.getPacketData(), 0 );
		buffer.skip( 1 );                       // Packet ID
		if ( this.guid != buffer.readLong() ) { // Client GUID
			this.state = ConnectionState.UNCONNECTED;
			this.sendConnectionRequestFailed();
			return;
		}
		long    time            = buffer.readLong();          // Current client time
		boolean securityEnabled = buffer.readBoolean(); // Whether or not a password is appended to the packet's usual data

		if ( securityEnabled ) {
			// We do not support security!
			this.sendConnectionRequestFailed();
			return;
		}

		/*              PASSWORD HANDLING (if this will ever be of interest)
		String password = null;
		if ( buffer.getRemaining() > 0 ) {
			byte[] ascii = new byte[buffer.getRemaining()];
			buffer.readBytes( ascii );
			password = new String( ascii, StandardCharsets.US_ASCII );
		}
		*/

		this.sendConnectionRequestAccepted( time );
	}

	private void handleNewIncomingConnection( EncapsulatedPacket packet ) {
		if ( !this.server.notifyConnectionRequest( this ) ) {
			// This connection is hereby cancelled immediately!
			this.state = ConnectionState.UNCONNECTED;
			this.sendNoFreeIncomingConnections();
			return;
		}

		// Connection is now ready to rumble!
		this.state = ConnectionState.CONNECTED;

		// Propage this connection to the outside:
		this.server.propagateFullyConnectedConnection( this );
	}

	private void handleDisconnectionNotification( EncapsulatedPacket packet ) {
		this.state = ConnectionState.UNCONNECTED;
		this.server.propagateConnectionClosed( this );
	}

	// ================================ PACKET SENDERS ================================ //

	private void sendIncompatibleProtocolVersion() {
		PacketBuffer buffer = new PacketBuffer( 22 );
		buffer.writeByte( INCOMPATIBLE_PROTOCOL_VERSION );
		buffer.writeByte( RAKNET_PROTOCOL_VERSION );
		buffer.writeOfflineMessageDataId();
		buffer.writeLong( this.server.getGuid() );
		try {
			this.server.send( this.address, buffer );
		} catch ( IOException ignored ) {
			// No need to log these ones here, as they are still unconnected connections
			// ._.
		}
	}

	private void sendConnectionReply1( DatagramPacket request ) {
		// The request packet will be as large as possible in order to determine the MTU size:
		int mtuSize = request.getLength() + UDP_DATAGRAM_HEADER_SIZE;
		if ( mtuSize > MAXIMUM_MTU_SIZE ) {
			mtuSize = MAXIMUM_MTU_SIZE;
		}

		PacketBuffer buffer = new PacketBuffer( 24 );
		buffer.writeByte( OPEN_CONNECTION_REPLY_1 );
		buffer.writeOfflineMessageDataId();
		buffer.writeLong( this.server.getGuid() );
		buffer.writeByte( (byte) 0x00 ); // We are not using LIBCAT Security
		buffer.writeUShort( mtuSize );
		try {
			this.server.send( this.address, buffer );
		} catch ( IOException ignored ) {
			// ._.
		}
	}

	private void sendAlreadyConnected() {
		PacketBuffer buffer = new PacketBuffer( 21 );
		buffer.writeByte( ALREADY_CONNECTED );
		buffer.writeOfflineMessageDataId();
		buffer.writeLong( this.server.getGuid() );
		try {
			this.server.send( this.address, buffer );
		} catch ( IOException ignored ) {
			// ._.
		}
	}

	private void sendNoFreeIncomingConnections() {
		PacketBuffer buffer = new PacketBuffer( 21 );
		buffer.writeByte( NO_FREE_INCOMING_CONNECTIONS );
		buffer.writeOfflineMessageDataId();
		buffer.writeLong( this.server.getGuid() );
		try {
			this.server.send( this.address, buffer );
		} catch ( IOException ignored ) {
			// ._.
		}
	}

	private void sendConnectionReply2() {
		PacketBuffer buffer = new PacketBuffer( 31 );
		buffer.writeByte( OPEN_CONNECTION_REPLY_2 );    // Packet ID
		buffer.writeOfflineMessageDataId();             // Offline Message Data ID
		buffer.writeLong( this.server.getGuid() );      // Server GUID
		buffer.writeUShort( this.mtuSize );             // MTU
		buffer.writeBoolean( false );                   // Not using LIBCAT Security
		try {
			this.server.send( this.address, buffer );
		} catch ( IOException ignored ) {
			// ._.
		}
	}

	private void sendConnectedPong( long pingTime ) {
		PacketBuffer buffer = new PacketBuffer( 17 );
		buffer.writeByte( CONNECTED_PONG );
		buffer.writeLong( pingTime );
		buffer.writeLong( System.currentTimeMillis() );
		this.send( PacketReliability.UNRELIABLE, buffer.getBuffer() );
	}

	private void sendConnectionRequestAccepted( long timestamp ) {
		PacketBuffer buffer = new PacketBuffer( 98 );
		buffer.writeByte( CONNECTION_REQUEST_ACCEPTED );            // Packet ID
		buffer.writeAddress( this.address );                        // Remote system address
		//buffer.writeUInt( 0 );                                      // Remote system index (not applicable)
		for ( int i = 0; i < MAX_LOCAL_IPS; ++i ) {                 // Local IP Addresses
			buffer.writeAddress( LOCAL_IP_ADDRESSES[i] );
		}
		buffer.writeLong( timestamp );                              // Timestamp (used for latency detection)
		buffer.writeLong( System.currentTimeMillis() );             // Current Time (used for latency detection)

		// Send to client reliably!
		this.send( PacketReliability.RELIABLE, 0, buffer.getBuffer() );
	}

	private void sendConnectionRequestFailed() {
		// Simply send NO_FREE_INCOMING_CONNECTIONS
		this.sendNoFreeIncomingConnections();
	}

	private void sendDisconnectionNotification() {
		byte[] data = new byte[1];
		data[0] = DISCONNECTION_NOTIFICATION;
		this.send( PacketReliability.RELIABLE_ORDERED, 0, data );
	}

}
