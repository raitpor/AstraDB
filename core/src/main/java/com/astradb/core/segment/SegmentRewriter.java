package com.astradb.core.segment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 段重写：保留旧 chunk（可过滤删除）+ 合并新增 chunk，按时间戳升序写入临时段后原子替换。
 * <p>
 * 用于"向段内任意不存在时间戳插入"与"删除指定时间快照"（段内快照始终保持有序，
 * ChunkIndex 二分查找持续有效）。旧 chunk 以原始字节复制（保持压缩最优性，不重编码）。
 * 崩溃安全：临时文件以 .tmp 后缀命名（启动校验只扫 .seg），替换后 manifest 未保存时
 * 由启动两级校验自动精确重建。
 */
public final class SegmentRewriter {

    /** 待写入的新 chunk（已编码字节）。 */
    public record NewChunk(byte[] chunkBytes, long ts, int rowCount) {
    }

    /** 重写结果：段内剩余 chunk 统计；isEmpty=true 表示段已空（调用方应删除段文件）。 */
    public record RewriteResult(int chunkCount, long rows, long endTime, boolean isEmpty) {
    }

    private SegmentRewriter() {
    }

    /**
     * 重写段文件：保留除 {@code deleteTs} 外的全部旧 chunk，并按时间升序合并 {@code inserts}。
     * 成功后原子替换原文件（调用方负责句柄池 evict 与 manifest 更新）。
     */
    public static RewriteResult rewrite(Path segPath, long dayStart, int schemaVersion, int columnCount,
                                        Set<Long> deleteTs, List<NewChunk> inserts) throws IOException {
        List<NewChunk> sorted = new ArrayList<>(inserts);
        sorted.sort((a, b) -> Long.compare(a.ts(), b.ts()));

        Path tmp = segPath.resolveSibling(segPath.getFileName() + ".tmp");
        int chunkCount = 0;
        long rows = 0;
        long endTime = Long.MIN_VALUE;

        try (SegmentReader r = SegmentReader.open(segPath, null);
             SegmentWriter w = SegmentWriter.create(tmp, dayStart, schemaVersion, columnCount)) {
            int n = r.chunkCount();
            int i = 0;
            int j = 0;
            while (i < n || j < sorted.size()) {
                long nextOld = i < n ? r.timestampAt(i) : Long.MAX_VALUE;
                long nextNew = j < sorted.size() ? sorted.get(j).ts() : Long.MAX_VALUE;
                if (nextNew < nextOld || (j < sorted.size() && i >= n)) {
                    NewChunk c = sorted.get(j);
                    w.append(c.chunkBytes(), c.ts(), c.rowCount());
                    chunkCount++;
                    rows += c.rowCount();
                    endTime = c.ts();
                    j++;
                } else if (nextOld <= nextNew) {
                    // 旧 chunk：deleteTs 命中则跳过（删除）；否则原样复制（重复时间戳由调用方前置校验）
                    if (!deleteTs.contains(nextOld)) {
                        w.append(r.readChunk(i), nextOld, r.entry(i).rowCount());
                        chunkCount++;
                        rows += r.entry(i).rowCount();
                        endTime = nextOld;
                    }
                    i++;
                }
            }
        }

        if (chunkCount == 0) {
            Files.deleteIfExists(tmp);
            return new RewriteResult(0, 0, Long.MIN_VALUE, true);
        }
        Files.move(tmp, segPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return new RewriteResult(chunkCount, rows, endTime, false);
    }
}
