package io.gomint.jraknet;

import static io.gomint.jraknet.RakNetConstraints.MAXIMUM_MTU_SIZE;
import static io.gomint.jraknet.RakNetConstraints.NUM_ORDERING_CHANNELS;

/**
 * Internal class used for buffering encapsulated packet data. Usually not returned to end users.
 *
 * @author BlackyPaw
 * @version 1.0
 */
// Only deprecated in order to hide it from the JavaDoc
// @Deprecated
public class EncapsulatedPacket {

	private PacketReliability reliability           = null;
	private int               reliableMessageNumber = -1;
	private int               sequencingIndex       = -1;
	private int               orderingIndex         = -1;
	private byte              orderingChannel       = 0;
	private long              splitPacketCount      = 0;
	private int               splitPacketId         = 0;
	private long              splitPacketIndex      = 0;
	private byte[]            packetData            = null;

	private long weight;
	private long nextExecution;
	private int resendCount;

	public EncapsulatedPacket() {

	}

	public EncapsulatedPacket( EncapsulatedPacket other ) {
		this.reliability = other.reliability;
		this.reliableMessageNumber = other.reliableMessageNumber;
		this.sequencingIndex = other.sequencingIndex;
		this.orderingIndex = other.orderingIndex;
		this.orderingChannel = other.orderingChannel;
		this.splitPacketCount = other.splitPacketCount;
		this.splitPacketId = other.splitPacketId;
		this.splitPacketIndex = other.splitPacketIndex;
		this.packetData = other.packetData;
	}

	/**
	 * Attempts to read the encapsulated from a datagram's raw data buffer.
	 *
	 * @param buffer The data buffer to read from
	 *
	 * @return Whether or not the encapsulated packet was read successfully
	 */
	public boolean readFromBuffer( PacketBuffer buffer ) {
		if ( buffer.getRemaining() < 3 ) {
			return false;
		}

		// Decode the packet:
		byte flags = buffer.readByte();
		this.reliability = PacketReliability.getFromId( (byte) ( ( flags & 0xE0 ) >>> 5 ) );
		boolean isSplitPacket = ( flags & 0x10 ) != 0;
		int packetLength = ( buffer.readUShort() + 7 ) >> 3;
		this.reliableMessageNumber = -1;
		this.sequencingIndex = -1;
		this.orderingIndex = -1;
		this.orderingChannel = 0;
		this.splitPacketCount = 0;
		this.splitPacketId = 0;
		this.splitPacketIndex = 0;

		// For the reasoning why the second check is commented out, please see the implementation of
		// ServerSocket#createObjectPools()
		if ( reliability == null /* || packetLength >= MAXIMUM_MTU_SIZE */ ) {
			// Datagram is malformed --> Discard
			return false;
		}

		if ( reliability == PacketReliability.RELIABLE ||
		     reliability == PacketReliability.RELIABLE_SEQUENCED ||
		     reliability == PacketReliability.RELIABLE_ORDERED ) {
			reliableMessageNumber = buffer.readTriad();
		}

		if ( reliability == PacketReliability.UNRELIABLE_SEQUENCED || reliability == PacketReliability.RELIABLE_SEQUENCED ) {
			sequencingIndex = buffer.readTriad();
		}

		if ( reliability == PacketReliability.UNRELIABLE_SEQUENCED ||
		     reliability == PacketReliability.RELIABLE_SEQUENCED ||
		     reliability == PacketReliability.RELIABLE_ORDERED ||
		     reliability == PacketReliability.RELIABLE_ORDERED_WITH_ACK_RECEIPT ) {
			orderingIndex = buffer.readTriad();
			orderingChannel = buffer.readByte();
		}

		if ( isSplitPacket ) {
			splitPacketCount = buffer.readUInt();
			splitPacketId = buffer.readUShort();
			splitPacketIndex = buffer.readUInt();
		}

		// Sanity check for odd circumstances:
		if ( packetLength <= 0 || orderingChannel < 0 || orderingChannel >= NUM_ORDERING_CHANNELS || ( isSplitPacket && splitPacketIndex >= splitPacketCount ) ) {
			// Datagram is malformed --> Discard
			return false;
		}

		// This is a nasty hack to get around https://bugs.mojang.com/browse/MCPE-16450
		// TODO: Remove this when issue got resolved
		if ( buffer.getBuffer()[buffer.getPosition()] == (byte) 0xFE && buffer.getBuffer()[buffer.getPosition() + 1] == (byte) 0x06 ) {
			// This is a MCPE batched packet. Look ahead for the compressed size and check if it overflows Raknets bit length short
			int compressedLength = ( ( buffer.getBuffer()[buffer.getPosition() + 2] & 0xFF ) << 24 |
					( buffer.getBuffer()[buffer.getPosition() + 3] & 0xFF ) << 16 |
					( buffer.getBuffer()[buffer.getPosition() + 4] & 0xFF ) << 8 |
					( buffer.getBuffer()[buffer.getPosition() + 5] & 0xFF ) );
			if ( compressedLength > 8192 ) {
					packetLength <<= 16;
			}
		}

		this.packetData = new byte[packetLength];
		buffer.readBytes( packetData );



		return true;
	}

	/**
	 * Writes the encapsulated packet to the specified packet buffer.
	 *
	 * @param buffer The packet buffer to write the encapuslated packet to
	 */
	public void writeToBuffer( PacketBuffer buffer ) {
		byte flags = (byte) ( this.reliability.getId() << 5 );
		if ( this.isSplitPacket() ) {
			flags |= 0x10;
		}

		buffer.writeByte( flags );
		buffer.writeUShort( this.getPacketLength() << 3 );

		if ( reliability == PacketReliability.RELIABLE ||
		     reliability == PacketReliability.RELIABLE_SEQUENCED ||
		     reliability == PacketReliability.RELIABLE_ORDERED ) {
			buffer.writeTriad( this.reliableMessageNumber );
		}

		if ( reliability == PacketReliability.UNRELIABLE_SEQUENCED || reliability == PacketReliability.RELIABLE_SEQUENCED ) {
			buffer.writeTriad( this.sequencingIndex );
		}

		if ( reliability == PacketReliability.UNRELIABLE_SEQUENCED ||
		     reliability == PacketReliability.RELIABLE_SEQUENCED ||
		     reliability == PacketReliability.RELIABLE_ORDERED ||
		     reliability == PacketReliability.RELIABLE_ORDERED_WITH_ACK_RECEIPT ) {
			buffer.writeTriad( this.orderingIndex );
			buffer.writeByte( this.orderingChannel );
		}

		if ( this.isSplitPacket() ) {
			buffer.writeUInt( this.splitPacketCount );
			buffer.writeUShort( this.splitPacketId );
			buffer.writeUInt( this.splitPacketIndex );
		}

		buffer.writeBytes( this.packetData );
	}

	public int getHeaderLength() {
		int length = 3;

		if ( reliability == PacketReliability.RELIABLE ||
		     reliability == PacketReliability.RELIABLE_SEQUENCED ||
		     reliability == PacketReliability.RELIABLE_ORDERED ) {
			length += 3;
		}

		if ( reliability == PacketReliability.UNRELIABLE_SEQUENCED || reliability == PacketReliability.RELIABLE_SEQUENCED ) {
			length += 3;
		}

		if ( reliability == PacketReliability.UNRELIABLE_SEQUENCED ||
		     reliability == PacketReliability.RELIABLE_SEQUENCED ||
		     reliability == PacketReliability.RELIABLE_ORDERED ||
		     reliability == PacketReliability.RELIABLE_ORDERED_WITH_ACK_RECEIPT ) {
			length += 4;
		}

		if ( this.isSplitPacket() ) {
			length += 10;
		}

		return length;
	}

	public PacketReliability getReliability() {
		return reliability;
	}

	void setReliability( PacketReliability reliability ) {
		this.reliability = reliability;
	}

	public boolean isSplitPacket() {
		return this.splitPacketCount > 0;
	}

	public int getPacketLength() {
		return this.packetData.length;
	}

	public int getReliableMessageNumber() {
		return reliableMessageNumber;
	}

	void setReliableMessageNumber( int reliableMessageNumber ) {
		this.reliableMessageNumber = reliableMessageNumber;
	}

	public int getSequencingIndex() {
		return sequencingIndex;
	}

	void setSequencingIndex( int sequencingIndex ) {
		this.sequencingIndex = sequencingIndex;
	}

	public int getOrderingIndex() {
		return orderingIndex;
	}

	void setOrderingIndex( int orderingIndex ) {
		this.orderingIndex = orderingIndex;
	}

	public byte getOrderingChannel() {
		return orderingChannel;
	}

	void setOrderingChannel( byte orderingChannel ) {
		this.orderingChannel = orderingChannel;
	}

	public long getSplitPacketCount() {
		return splitPacketCount;
	}

	void setSplitPacketCount( long splitPacketCount ) {
		this.splitPacketCount = splitPacketCount;
	}

	public int getSplitPacketId() {
		return splitPacketId;
	}

	void setSplitPacketId( int splitPacketId ) {
		this.splitPacketId = splitPacketId;
	}

	public long getSplitPacketIndex() {
		return splitPacketIndex;
	}

	void setSplitPacketIndex( long splitPacketIndex ) {
		this.splitPacketIndex = splitPacketIndex;
	}

	public byte[] getPacketData() {
		return packetData;
	}

	public void setPacketData( byte[] packetData ) {
		this.packetData = packetData;
	}

	public long getWeight() {
		return this.weight;
	}

	public void setWeight( long weight ) {
		this.weight = weight;
	}

	public long getNextExecution() {
		return nextExecution;
	}

	public void setNextExecution( long nextExecution ) {
		this.nextExecution = nextExecution;
	}

	public void incrementSendCount() {
		this.resendCount++;
	}

}
