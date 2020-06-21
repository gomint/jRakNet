package io.gomint.jraknet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

/**
 * @author BlackyPaw
 * @version 1.0
 */
class SplitPacketAssembler {

    private final EncapsulatedPacket[] parts;
    private int found;

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

        for ( EncapsulatedPacket part : this.parts ) {
            cursor += part.getPacketLength();
        }

        ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(cursor);
        for ( EncapsulatedPacket part : this.parts ) {
            buf.writeBytes(part.getPacketData());
            part.release(); // We wrote all content into another buffer, release the parted one
        }

        EncapsulatedPacket packet = new EncapsulatedPacket( this.parts[0] );
        packet.setPacketData( buf );
        buf.release(); // The encapsulated packet took over ownership of this buffer, we get out of here
        packet.setSplitPacketCount( 0L );
        packet.setSplitPacketId( 0 );
        packet.setSplitPacketIndex( 0L );
        return packet;
    }

}
