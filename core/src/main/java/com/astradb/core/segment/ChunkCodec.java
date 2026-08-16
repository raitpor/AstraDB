package com.astradb.core.segment;

import com.astradb.core.codec.CodecRegistry;
import com.astradb.core.codec.ColumnCodec;
import com.astradb.core.compress.Compressor;
import com.astradb.core.meta.Column;
import com.astradb.core.util.ByteBuf;
import com.astradb.core.util.ByteReader;

import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * Chunk 编解码（design.md 6.2）。
 *
 * 布局：
 *   [0]    timestamp(8B)
 *   [8]    rowCount(4B)
 *   [12]   schemaVersion(2B)
 *   [14]   columnCount(2B)
 *   [16]   列偏移表: n × [offset(4B) length(4B) codecId(1B)]   // offset 相对 chunk 起点
 *   列数据区: 每列 = [uncompressedLen varint][zstd payload]
 *   尾部:   CRC32C(4B)，覆盖 [0, len-4)
 */
public final class ChunkCodec {

    public static final int HEADER_FIXED = 16;
    public static final int COL_TABLE_ENTRY = 9;
    public static final int CRC_BYTES = 4;

    private ChunkCodec() {
    }

    public static byte[] encode(Chunk chunk, Compressor compressor) {
        int n = chunk.columnCount();
        int headerSize = HEADER_FIXED + COL_TABLE_ENTRY * n;

        // 先编码各列（列块 = 未压缩长度 + zstd 载荷）
        byte[][] colBlocks = new byte[n][];
        int dataSize = 0;
        for (int i = 0; i < n; i++) {
            Column col = chunk.column(i);
            ColumnCodec codec = CodecRegistry.of(CodecRegistry.idOf(col.type()));
            byte[] encoded = codec.encode(col);
            byte[] compressed = compressor.compress(encoded);
            ByteBuf block = new ByteBuf(16 + compressed.length);
            block.writeUInt(encoded.length);
            block.writeBytes(compressed);
            colBlocks[i] = block.toArray();
            dataSize += colBlocks[i].length;
        }

        ByteBuf out = new ByteBuf(headerSize + dataSize + CRC_BYTES);
        out.writeLong(chunk.timestamp());
        out.writeInt(chunk.rowCount());
        out.writeShort(chunk.schemaVersion());
        out.writeShort(n);
        int offset = headerSize;
        for (int i = 0; i < n; i++) {
            out.writeInt(offset);
            out.writeInt(colBlocks[i].length);
            out.writeByte(CodecRegistry.idOf(chunk.column(i).type()));
            offset += colBlocks[i].length;
        }
        for (byte[] b : colBlocks) {
            out.writeBytes(b);
        }

        byte[] body = out.toArray();
        CRC32C crc = new CRC32C();
        crc.update(body, 0, body.length);
        byte[] result = new byte[body.length + CRC_BYTES];
        System.arraycopy(body, 0, result, 0, body.length);
        int crcValue = (int) crc.getValue();
        result[body.length] = (byte) (crcValue >>> 24);
        result[body.length + 1] = (byte) (crcValue >>> 16);
        result[body.length + 2] = (byte) (crcValue >>> 8);
        result[body.length + 3] = (byte) crcValue;
        return result;
    }

    /** 解码整块。 */
    public static Chunk decode(byte[] data, Compressor compressor) {
        ByteReader in = new ByteReader(data);
        long timestamp = in.readLong();
        int rowCount = in.readInt();
        int schemaVersion = in.readShort();
        int n = in.readShort();
        int[] offsets = new int[n];
        int[] lengths = new int[n];
        byte[] codecIds = new byte[n];
        for (int i = 0; i < n; i++) {
            offsets[i] = in.readInt();
            lengths[i] = in.readInt();
            codecIds[i] = (byte) in.readByte();
        }
        List<Column> columns = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            columns.add(decodeColumnBlock(data, offsets[i], lengths[i], codecIds[i], compressor));
        }
        return new Chunk(timestamp, schemaVersion, columns);
    }

    /** 只解码指定列（单点查询按需取列）。 */
    public static Column decodeColumn(byte[] data, int columnIndex, Compressor compressor) {
        ByteReader in = new ByteReader(data);
        in.readLong();      // timestamp
        in.readInt();       // rowCount
        in.readShort();     // schemaVersion
        int n = in.readShort();
        if (columnIndex < 0 || columnIndex >= n) {
            throw new IllegalArgumentException("列索引越界: " + columnIndex);
        }
        int[] offsets = new int[n];
        int[] lengths = new int[n];
        byte[] codecIds = new byte[n];
        for (int i = 0; i < n; i++) {
            offsets[i] = in.readInt();
            lengths[i] = in.readInt();
            codecIds[i] = (byte) in.readByte();
        }
        return decodeColumnBlock(data, offsets[columnIndex], lengths[columnIndex],
                codecIds[columnIndex], compressor);
    }

    private static Column decodeColumnBlock(byte[] data, int offset, int length, byte codecId,
                                            Compressor compressor) {
        ByteReader in = new ByteReader(data, offset);
        int uncompressedSize = (int) in.readUInt();
        byte[] compressed = new byte[length - (in.position() - offset)];
        System.arraycopy(data, in.position(), compressed, 0, compressed.length);
        byte[] raw = compressor.decompress(compressed, uncompressedSize);
        return CodecRegistry.of(codecId).decode(raw);
    }

    /** 读取 chunk 时间戳（不解压）。 */
    public static long timestampOf(byte[] data) {
        return ((data[0] & 0xFFL) << 56) | ((data[1] & 0xFFL) << 48)
                | ((data[2] & 0xFFL) << 40) | ((data[3] & 0xFFL) << 32)
                | ((data[4] & 0xFFL) << 24) | ((data[5] & 0xFFL) << 16)
                | ((data[6] & 0xFFL) << 8) | (data[7] & 0xFFL);
    }

    /** 读取 chunk 行数。 */
    public static int rowCountOf(byte[] data) {
        return ((data[8] & 0xFF) << 24) | ((data[9] & 0xFF) << 16)
                | ((data[10] & 0xFF) << 8) | (data[11] & 0xFF);
    }

    /** 由列偏移表计算 chunk 总长（含 CRC），崩溃恢复定位用。仅需头 + 列偏移表。 */
    public static int chunkLength(byte[] data, int available) {
        if (available < HEADER_FIXED + COL_TABLE_ENTRY) {
            return -1;
        }
        int n = ((data[14] & 0xFF) << 8) | (data[15] & 0xFF);
        int tableSize = COL_TABLE_ENTRY * n;
        if (available < HEADER_FIXED + tableSize) {
            return -1;
        }
        int base = HEADER_FIXED + tableSize;
        long lastOffset = 0;
        long lastLength = 0;
        for (int i = 0; i < n; i++) {
            int p = HEADER_FIXED + i * COL_TABLE_ENTRY;
            lastOffset = ((data[p] & 0xFFL) << 24) | ((data[p + 1] & 0xFFL) << 16)
                    | ((data[p + 2] & 0xFFL) << 8) | (data[p + 3] & 0xFFL);
            lastLength = ((data[p + 4] & 0xFFL) << 24) | ((data[p + 5] & 0xFFL) << 16)
                    | ((data[p + 6] & 0xFFL) << 8) | (data[p + 7] & 0xFFL);
        }
        long end = lastOffset + lastLength + CRC_BYTES;
        if (end < base || end > Integer.MAX_VALUE) {
            return -1;
        }
        return (int) end;
    }

    /** 校验 chunk 尾部 CRC32C；返回 true 表示 chunk 数据完整。 */
    public static boolean checkCrc(byte[] data, int len) {
        if (len < HEADER_FIXED + CRC_BYTES || len > data.length) {
            return false;
        }
        CRC32C crc = new CRC32C();
        crc.update(data, 0, len - CRC_BYTES);
        int expected = (int) crc.getValue();
        int stored = ((data[len - 4] & 0xFF) << 24) | ((data[len - 3] & 0xFF) << 16)
                | ((data[len - 2] & 0xFF) << 8) | (data[len - 1] & 0xFF);
        return expected == stored;
    }
}
