package com.astradb.core.segment;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.astradb.core.segment.SegmentFormat.ChunkIndexEntry;

/**
 * 段崩溃恢复：扫描 chunk 区，按 CRC32C 定位最后一个完整 chunk。
 * 未完成快照（半写 chunk / 缺失 Footer）被安全截断丢弃。
 */
public final class SegmentRecovery {

    public record RecoveryResult(List<ChunkIndexEntry> entries, long dataEnd) {
    }

    private SegmentRecovery() {
    }

    /** 只读扫描 chunk 区，返回全部完整 chunk 及其后偏移（不修改文件）。 */
    public static RecoveryResult scan(RandomAccessFile raf) throws IOException {
        SegmentFormat.FileHeader header = SegmentFormat.readFileHeader(raf);
        long fileLen = raf.length();
        long pos = SegmentFormat.FILE_HEADER_SIZE;
        List<ChunkIndexEntry> entries = new ArrayList<>();
        byte[] head = new byte[ChunkCodec.HEADER_FIXED];
        while (fileLen - pos >= ChunkCodec.HEADER_FIXED + ChunkCodec.COL_TABLE_ENTRY) {
            raf.seek(pos);
            raf.readFully(head);
            int n = ((head[14] & 0xFF) << 8) | (head[15] & 0xFF);
            int tableLen = ChunkCodec.COL_TABLE_ENTRY * n;
            if (fileLen - pos < ChunkCodec.HEADER_FIXED + tableLen + ChunkCodec.CRC_BYTES) {
                break;
            }
            byte[] table = new byte[tableLen];
            raf.readFully(table);
            byte[] hdr = new byte[ChunkCodec.HEADER_FIXED + tableLen];
            System.arraycopy(head, 0, hdr, 0, ChunkCodec.HEADER_FIXED);
            System.arraycopy(table, 0, hdr, ChunkCodec.HEADER_FIXED, tableLen);
            int len = ChunkCodec.chunkLength(hdr, hdr.length);
            if (len < 0 || pos + len > fileLen) {
                break;
            }
            byte[] chunk = new byte[len];
            raf.seek(pos);
            raf.readFully(chunk);
            if (!ChunkCodec.checkCrc(chunk, len)) {
                break;
            }
            int crc = ((chunk[len - 4] & 0xFF) << 24) | ((chunk[len - 3] & 0xFF) << 16)
                    | ((chunk[len - 2] & 0xFF) << 8) | (chunk[len - 1] & 0xFF);
            entries.add(new ChunkIndexEntry(pos, len, ChunkCodec.timestampOf(chunk),
                    ChunkCodec.rowCountOf(chunk), header.schemaVersion(), crc));
            pos += len;
        }
        return new RecoveryResult(List.copyOf(entries), pos);
    }

    /** 扫描并截断文件到最后一个完整 chunk 末尾（崩溃现场清理，供 writer 复用）。 */
    public static RecoveryResult recover(Path path) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw");
        try {
            RecoveryResult r = scan(raf);
            if (r.dataEnd() < raf.length()) {
                raf.setLength(r.dataEnd());
            }
            return r;
        } finally {
            raf.close();
        }
    }
}
