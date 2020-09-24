package io.gomint.jraknet;

import io.gomint.jraknet.datastructures.TriadRange;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * @author BlackyPaw
 * @version 1.0
 */
public class PacketBuffer {

    private static final BigInteger UNSIGNED_LONG_MAX_VALUE = new BigInteger("FFFFFFFFFFFFFFFF", 16);
    private static final short AF_INET6 = (short) (System.getProperty("os.name").equals("windows")
            ? 23 : 10);
    private static final int MAX_SIZE = 10 * 1024 * 1024;

    private ByteBuf buf;

    public PacketBuffer(int capacity) {
        this.buf = PooledByteBufAllocator.DEFAULT.directBuffer(capacity);
    }

    public PacketBuffer(ByteBuf buf) {
        this.buf = buf;
        this.buf.retain();
    }

    public int getReadPosition() {
        return this.buf.readerIndex();
    }

    public int getWritePosition() {
        return this.buf.writerIndex();
    }

    public int getRemaining() {
        return this.buf.readableBytes();
    }

    public short readShort() {
        return this.buf.readShort();
    }

    public short readLShort() {
        return this.buf.readShortLE();
    }

    public float readFloat() {
        return Float.intBitsToFloat(this.readInt());
    }

    public float readLFloat() {
        return Float.intBitsToFloat(this.readLInt());
    }

    public int readInt() {
        return this.buf.readInt();
    }

    public int readLInt() {
        return this.buf.readIntLE();
    }

    public double readDouble() {
        return Double.longBitsToDouble(this.readLong());
    }

    public long readLong() {
        return this.buf.readLong();
    }

    public long readLLong() {
        return this.buf.readLongLE();
    }

    public String readString() {
        int length = this.readUnsignedVarInt();
        byte[] data = new byte[length];
        this.buf.readBytes(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    public int readUShort() {
        return this.buf.readUnsignedShort();
    }

    public void readOfflineMessageDataId() {
        this.buf.skipBytes(RakNetConstraints.OFFLINE_MESSAGE_DATA_ID.length);
    }

    public InetSocketAddress readAddress() {
        byte ipVersion = this.readByte();
        if (ipVersion == 4) {
            long complement = ~this.readUInt();

            String hostname = String.format("%s.%s.%s.%s", ((complement >> 24) & 0xFF),
                    ((complement >> 16) & 0xFF), ((complement >> 8) & 0xFF),
                    (complement & 0xFF));
            int port = this.readUShort();

            return InetSocketAddress.createUnresolved(hostname, port);
        } else {
            // Reading sockaddr_in6 structure whose fields are _always_ in big-endian order!
            this.readUShort(); // Addressinfo
            int port = this.readUShort();
            this.readUInt(); // Flowinfo (see RFC 6437 - can safely leave it at 0)
            byte[] in6addr = new byte[16];
            this.buf.readBytes(in6addr);
            this.readUInt(); // Scope ID

            try {
                return new InetSocketAddress(Inet6Address.getByAddress(null, in6addr, 0), port);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Could not read sockaddr_in6", e);
            }
        }
    }

    public byte readByte() {
        return this.buf.readByte();
    }

    public long readUInt() {
        return this.buf.readUnsignedInt();
    }

    public UUID readUUID() {
        return new UUID(this.readLLong(), this.readLLong());
    }

    public int readUnsignedVarInt() {
        int value = 0;
        int i = 0;
        int b;

        while (((b = this.readByte()) & 0x80) != 0) {
            value |= (b & 0x7F) << i;
            i += 7;
            if (i > 35) {
                throw new RuntimeException("VarInt too big");
            }
        }

        return value | (b << i);
    }

    public int readSignedVarInt() {
        int val = readUnsignedVarInt();
        return decodeZigZag32(val);
    }

    public long readUnsignedVarLong() {
        long value = 0;
        int i = 0;
        long b;

        while (((b = this.readByte()) & 0x80) != 0) {
            value |= (b & 0x7F) << i;
            i += 7;
            if (i > 63) {
                throw new RuntimeException("VerLong too big");
            }
        }

        return value | (b << i);
    }

    public BigInteger readSignedVarLong() {
        BigInteger val = readVarNumber(10);
        return decodeZigZag64(val);
    }

    private BigInteger readVarNumber(int length) {
        BigInteger result = BigInteger.ZERO;
        int shiftOffset = 0;

        do {
            long b = (long) (this.readByte()) & 128L;
            if (b == 0L) {
                return result.or(BigInteger.valueOf(b << (shiftOffset * 7)));
            }

            result = result.or(BigInteger.valueOf((b & 127L) << shiftOffset * 7));
            shiftOffset++;
        } while (shiftOffset < length);

        throw new IllegalArgumentException("Var Number too big");
    }

    private int encodeZigZag32(int v) {
        // Note:  the right-shift must be arithmetic
        return (v << 1) ^ (v >> 31);
    }

    private int decodeZigZag32(int v) {
        return (v >> 1) ^ -(v & 1);
    }

    private BigInteger encodeZigZag64(long v) {
        BigInteger origin = BigInteger.valueOf(v);
        BigInteger left = origin.shiftLeft(1);
        BigInteger right = origin.shiftRight(63);
        return left.xor(right);
    }

    private BigInteger decodeZigZag64(BigInteger v) {
        BigInteger left = v.shiftRight(1);
        BigInteger right = v.and(BigInteger.ONE).negate();
        return left.xor(right);
    }


    public void writeUnsignedVarLong(long value) {
        while ((value & 0xFFFFFFFFFFFFFF80L) != 0L) {
            this.writeByte((byte) (((int) value & 0x7F) | 0x80));
            value >>>= 7;
        }

        this.writeByte((byte) ((int) value & 0x7F));
    }

    public void writeSignedVarLong(long value) {
        BigInteger signedLong = encodeZigZag64(value);
        this.writeVarBigInteger(signedLong);
    }

    public void writeUnsignedVarInt(int value) {
        while ((value & 0xFFFFFF80) != 0L) {
            this.writeByte((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }

        this.writeByte((byte) (value & 0x7F));
    }

    public void writeSignedVarInt(int value) {
        int signedValue = encodeZigZag32(value);
        writeUnsignedVarInt(signedValue);
    }

    private void writeVarBigInteger(BigInteger value) {
        if (value.compareTo(UNSIGNED_LONG_MAX_VALUE) > 0) {
            throw new IllegalArgumentException("The value is too big");
        }

        value = value.and(UNSIGNED_LONG_MAX_VALUE);
        BigInteger i = BigInteger.valueOf(-128);
        BigInteger x7f = BigInteger.valueOf(0x7f);
        BigInteger x80 = BigInteger.valueOf(0x80);

        while (!value.and(i).equals(BigInteger.ZERO)) {
            this.writeByte(value.and(x7f).or(x80).byteValue());
            value = value.shiftRight(7);
        }

        this.writeByte(value.byteValue());
    }

    public void skip(int length) {
        this.buf.skipBytes(length);
    }

    public TriadRange[] readTriadRangeList() {
        short length = this.readShort();
        boolean isPair;
        TriadRange[] ranges = new TriadRange[length];
        int min;
        int max;

        for (int i = 0; i < length; ++i) {
            isPair = !this.readBoolean();
            min = this.readTriad();
            if (isPair) {
                max = this.readTriad();
                if (min >= max) {
                    return null;
                }
            } else {
                max = min;
            }
            ranges[i] = new TriadRange(min, max);
        }
        return ranges;
    }

    public boolean readBoolean() {
        return (this.readByte() != 0x00);
    }

    public int readTriad() {
        return this.buf.readMediumLE();
    }

    public void writeBoolean(boolean v) {
        this.writeByte((v ? (byte) 0x01 : (byte) 0x00));
    }

    public void writeByte(byte v) {
        this.ensureCapacity(1);
        this.buf.writeByte(v);
    }

    private void ensureCapacity(int capacity) {
        int targetCapacity = this.buf.writerIndex() + capacity;
        if (targetCapacity <= this.buf.capacity()) {
            return;
        }

        int fastWritable = this.buf.maxFastWritableBytes();
        int newCapacity = fastWritable >= capacity ? this.buf.writerIndex() + fastWritable : this.buf.alloc().calculateNewCapacity(targetCapacity, MAX_SIZE);
        this.buf.capacity(newCapacity);
    }

    public void writeBytes(byte[] v, int from, int length) {
        this.ensureCapacity(v.length);
        this.buf.writeBytes(v, from, length);
    }

    public void writeBytes(byte[] v) {
        this.ensureCapacity(v.length);
        this.buf.writeBytes(v);
    }

    public void writeShort(short v) {
        this.ensureCapacity(2);
        this.buf.writeShort(v);
    }

    public void writeLShort(short v) {
        this.ensureCapacity(2);
        this.buf.writeShortLE(v);
    }

    public void writeUInt(long v) {
        this.writeInt((int) v);
    }

    public void writeFloat(float v) {
        this.writeInt(Float.floatToRawIntBits(v));
    }

    public void writeLFloat(float v) {
        this.writeLInt(Float.floatToRawIntBits(v));
    }

    public void writeInt(int v) {
        this.ensureCapacity(4);
        this.buf.writeInt(v);
    }

    public void writeLInt(int v) {
        this.ensureCapacity(4);
        this.buf.writeIntLE(v);
    }

    public void writeDouble(double v) {
        this.writeLong(Double.doubleToRawLongBits(v));
    }

    public void writeLong(long v) {
        this.ensureCapacity(8);
        this.buf.writeLong(v);
    }

    public void writeLLong(long v) {
        this.ensureCapacity(8);
        this.buf.writeLongLE(v);
    }

    public void writeString(String v) {
        byte[] ascii = v.getBytes(StandardCharsets.UTF_8);

        this.writeUnsignedVarInt(ascii.length);
        this.ensureCapacity(ascii.length);

        this.buf.writeBytes(ascii);
    }

    public void writeUShort(int v) {
        this.writeShort((short) v);
    }

    public void writeOfflineMessageDataId() {
        this.ensureCapacity(RakNetConstraints.OFFLINE_MESSAGE_DATA_ID.length);
        this.buf.writeBytes(RakNetConstraints.OFFLINE_MESSAGE_DATA_ID);
    }

    public void writeAddress(SocketAddress address) {
        if (!(address instanceof InetSocketAddress)) {
            throw new IllegalArgumentException(
                    "Unknown socket address family (only AF_INET and AF_INET6 supported)");
        }

        InetSocketAddress addr = (InetSocketAddress) address;
        if (addr.getAddress() instanceof Inet4Address) {
            this.ensureCapacity(7);
            this.writeByte((byte) 4);

            Inet4Address inet = (Inet4Address) addr.getAddress();
            byte[] bytes = inet.getAddress();
            int complement = (bytes[0] & 255) << 24 | (bytes[1] & 255) << 16 | (bytes[2] & 255) << 8
                    | (int) bytes[3] & 255;
            complement = ~complement;

            this.writeUInt(complement);
            this.writeUShort(addr.getPort());
        } else if (addr.getAddress() instanceof Inet6Address) {
            Inet6Address in6addr = (Inet6Address) addr.getAddress();

            this.ensureCapacity(25);
            this.writeByte((byte) 6);
            this.writeUShort(AF_INET6);
            this.writeUShort((short) addr.getPort());
            this.writeUInt(0L);
            this.writeBytes(in6addr.getAddress());
            this.writeUInt(0L);
        }
    }

    public void writeUUID(UUID uuid) {
        this.writeLLong(uuid.getMostSignificantBits());
        this.writeLLong(uuid.getLeastSignificantBits());
    }

    public void writeTriad(int v) {
        this.ensureCapacity(3);
        this.buf.writeMediumLE(v);
    }

    public int writeTriadRangeList(List<TriadRange> ranges, int offset, int length, int maxSize) {
        this.ensureCapacity(2);

        // Reserve two bytes for the length:
        this.buf.markWriterIndex();
        this.buf.writerIndex(this.buf.writerIndex() + 2);
        maxSize -= 2;

        short count = 0;
        for (int i = offset; i < offset + length; ++i) {
            if (ranges.get(i).getMin() == ranges.get(i).getMax()) {
                if (maxSize < 4) {
                    break;
                }

                this.writeBoolean(true);
                this.writeTriad(ranges.get(i).getMin());
                maxSize -= 4;
            } else {
                if (maxSize < 7) {
                    break;
                }

                this.writeBoolean(false);
                this.writeTriad(ranges.get(i).getMin());
                this.writeTriad(ranges.get(i).getMax());
                maxSize -= 7;
            }
            ++count;
        }

        int pos = this.buf.writerIndex();
        this.buf.resetWriterIndex();
        this.writeShort(count);
        this.buf.writerIndex(pos);
        return count;
    }

    /**
     * Set the pointer of this buffer to the specified position
     *
     * @param position The position at which the pointer should be
     */
    public void setReadPosition(int position) {
        this.buf.readerIndex(position);
    }

    public void setWritePosition(int position) {
        this.buf.writerIndex(position);
    }

    public ByteBuf readSlice(int length) {
        return this.buf.retainedSlice(this.buf.readerIndex(), length);
    }

    public void writeBytes(ByteBuf data) {
        this.ensureCapacity(data.readableBytes());
        this.buf.writeBytes(data);
    }

    public ByteBuf getBuffer() {
        return this.buf;
    }

    public void release() {
        this.buf.release();
    }

    public void readBytes(byte[] v) {
        this.buf.readBytes(v);
    }

    @Override
    public String toString() {
        StringBuilder data = new StringBuilder();
        ByteBuf buf = this.buf.asReadOnly().readerIndex(0);
        while(buf.readableBytes() > 0) {
            data.append("0x").append(Integer.toHexString(buf.readByte())).append(" ");
        }

        return "PacketBuffer[" + data + "]";
    }

}
