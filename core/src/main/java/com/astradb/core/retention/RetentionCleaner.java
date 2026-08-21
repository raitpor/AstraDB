package com.astradb.core.retention;

import com.astradb.core.manifest.Manifest;
import com.astradb.core.meta.TableMeta;
import com.astradb.core.segment.SegmentReader;
import com.astradb.core.util.FsUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 保留期清理：整文件删除段结束时间超出保留天数的 .seg，同步更新 manifest。
 */
public final class RetentionCleaner {

    private static final Logger LOG = Logger.getLogger(RetentionCleaner.class.getName());

    private RetentionCleaner() {
    }

    /** @return 删除的段文件数 */
    public static int clean(TableMeta table, Manifest manifest, long now,
                            com.astradb.core.segment.SegmentChannelCache channels,
                            Consumer<Set<Long>> onDeletedTs) throws IOException {
        long cutoff = now - table.retentionDays() * 86_400_000L;
        List<Manifest.SegmentInfo> doomed = new ArrayList<>();
        for (Manifest.SegmentInfo s : manifest.segments()) {
            if (s.endTime() < cutoff) {
                doomed.add(s);
            }
        }
        for (Manifest.SegmentInfo s : doomed) {
            Path p = table.dir().resolve(s.path());
            // SF-1：删除前枚举段内时间戳，供删除后清理幂等记录（防同 ts 同内容重放被静默跳过）
            Set<Long> tsSet = new HashSet<>();
            try (SegmentReader r = SegmentReader.open(p, null)) {
                for (int i = 0; i < r.chunkCount(); i++) {
                    tsSet.add(r.timestampAt(i));
                }
            } catch (IOException e) {
                // 段已损坏（正常路径启动时已被隔离）：继续删除文件，跳过幂等清理并告警
                LOG.warning("保留期清理读取段时间戳失败（跳过幂等清理）: " + s.path() + " (" + e + ")");
            }
            if (channels != null) {
                channels.evict(p);
            }
            Files.deleteIfExists(p);
            FsUtil.fsyncDir(p.getParent()); // SF-7：delete 目录项落盘（防断电后段复活）
            manifest.remove(s.path());
            if (onDeletedTs != null) {
                onDeletedTs.accept(tsSet);
            }
        }
        if (!doomed.isEmpty()) {
            manifest.save();
        }
        return doomed.size();
    }
}
