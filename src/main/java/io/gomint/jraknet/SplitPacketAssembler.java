package io.gomint.jraknet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author BlackyPaw
 * @version 1.0
 */
class SplitPacketAssembler {

    private final static Logger logger = LoggerFactory.getLogger( SplitPacketAssembler.class );
    private final EncapsulatedPacket[] parts;
    private int found;

    public SplitPacketAssembler( EncapsulatedPacket packet ) {
        this.parts = new EncapsulatedPacket[(int) packet.getSplitPacketCount()];
        this.found = 0;

        logger.debug( "Wanting to assemble " + packet.getSplitPacketCount() + " packets back into original packet" );
    }

    public EncapsulatedPacket add( EncapsulatedPacket packet ) {
        if ( packet.getSplitPacketIndex() < this.parts.length && this.parts[(int) packet.getSplitPacketIndex()] == null ) {
            this.parts[(int) packet.getSplitPacketIndex()] = packet;
            this.found++;

            logger.debug( "Got split packet part #" + packet.getSplitPacketIndex() + ". Need " + ( this.parts.length - this.found ) + " more till packet is completed" );

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

        byte[] data = new byte[cursor];
        cursor = 0;
        for ( EncapsulatedPacket part : this.parts ) {
            System.arraycopy( part.getPacketData(), 0, data, cursor, part.getPacketLength() );
            cursor += part.getPacketLength();
        }

        EncapsulatedPacket packet = new EncapsulatedPacket( this.parts[0] );
        packet.setPacketData( data );
        packet.setSplitPacketCount( 0L );
        packet.setSplitPacketId( 0 );
        packet.setSplitPacketIndex( 0L );
        return packet;
    }

}
