package io.gomint.jraknet;

/**
 * @author BlackyPaw
 * @version 1.0
 */
class SplitPacketAssembler {

	private final EncapsulatedPacket[] parts;
	private       int                  found;

	public SplitPacketAssembler( EncapsulatedPacket packet ) {
		this.parts = new EncapsulatedPacket[(int) packet.getSplitPacketCount()];
		this.found = 0;
	}

	public EncapsulatedPacket add( EncapsulatedPacket packet ) {
		if ( packet.getSplitPacketIndex() < this.parts.length && this.parts[(int) packet.getSplitPacketIndex()] == null ) {
			this.parts[(int) packet.getSplitPacketIndex()] = packet;
			this.found++;
			if ( this.found == this.parts.length ) {
				return this.rebuild();
			}
		}
		return null;
	}

	private EncapsulatedPacket rebuild() {
		int cursor = 0;
		for ( int i = 0; i < this.parts.length; ++i ) {
			cursor += this.parts[i].getPacketLength();
		}

		byte[] data = new byte[cursor];
		cursor = 0;
		for ( int i = 0; i < this.parts.length; ++i ) {
			System.arraycopy( this.parts[i].getPacketData(), 0, data, cursor, this.parts[i].getPacketLength() );
			cursor += this.parts[i].getPacketLength();
		}

		EncapsulatedPacket packet = new EncapsulatedPacket( this.parts[0] );
		packet.setPacketData( data );
		packet.setSplitPacketCount( 0L );
		packet.setSplitPacketId( 0 );
		packet.setSplitPacketIndex( 0L );
		return packet;
	}

}
