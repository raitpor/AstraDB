package com.astradb.core.retention;

import com.astradb.core.manifest.Manifest;
import com.astradb.core.meta.TableMeta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 保留期清理：整文件删除段结束时间超出保留天数的 .seg，同步更新 manifest。
 */
public final class RetentionCleaner {

    private RetentionCleaner() {
    }

    /** @return 删除的段文件数 */
    public static int clean(TableMeta table, Manifest manifest, long now,
                        com.astradb.core.segment.SegmentChannelCache channels) throws IOException {
        long cutoff = now - table.retentionDays() * 86_400_000L;
        List<Manifest.SegmentInfo> doomed = new ArrayList<>();
        for (Manifest.SegmentInfo s : manifest.segments()) {
            if (s.endTime() < cutoff) {
                doomed.add(s);
            }
        }
        for (Manifest.SegmentInfo s : doomed) {
            Path p = table.dir().resolve(s.path());
            if (channels != null) {
                channels.evict(p);
            }
            Files.deleteIfExists(p);
            manifest.remove(s.path());
        }
        if (!doomed.isEmpty()) {
            manifest.save();
        }
        return doomed.size();
    }
}
