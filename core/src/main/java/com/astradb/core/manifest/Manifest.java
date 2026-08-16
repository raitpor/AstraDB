package com.astradb.core.manifest;

import com.astradb.core.meta.JsonFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 分片清单（manifest.json）：段级信息，可随时由扫描 segments/ 重建。
 * 内存态按 startTime 升序维护。
 */
public final class Manifest {

    /** 段级信息。path 为相对表目录的路径，如 "segments/2026/2026-01-01.seg"。 */
    public record SegmentInfo(String path, long startTime, long endTime,
                              int chunkCount, long rows, int minKey, int maxKey, long sizeBytes) {
    }

    private record Store(String table, List<SegmentInfo> segments) {
    }

    private final Path file;
    private final String table;
    private final List<SegmentInfo> segments = new ArrayList<>();

    private Manifest(Path file, String table, List<SegmentInfo> initial) {
        this.file = file;
        this.table = table;
        if (initial != null) {
            this.segments.addAll(initial);
            this.segments.sort(Comparator.comparingLong(SegmentInfo::startTime));
        }
    }

    public static Manifest empty(Path file, String table) {
        return new Manifest(file, table, List.of());
    }

    public static Manifest load(Path file, String table) throws IOException {
        Store s = JsonFiles.read(file, Store.class);
        return new Manifest(file, table, s == null ? List.of() : s.segments());
    }

    public String table() {
        return table;
    }

    public void save() throws IOException {
        JsonFiles.write(file, new Store(table, segments));
    }

    /** 新增段或按 path 合并更新。 */
    public void addOrMerge(SegmentInfo info) {
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).path().equals(info.path())) {
                segments.set(i, info);
                return;
            }
        }
        segments.add(info);
        segments.sort(Comparator.comparingLong(SegmentInfo::startTime));
    }

    public void remove(String path) {
        segments.removeIf(s -> s.path().equals(path));
    }

    public List<SegmentInfo> segments() {
        return List.copyOf(segments);
    }

    /** startTime &lt;= ts 的最后一段；无则 null。 */
    public SegmentInfo lastAtOrBefore(long ts) {
        SegmentInfo ans = null;
        for (SegmentInfo s : segments) {
            if (s.startTime() <= ts) {
                ans = s;
            } else {
                break;
            }
        }
        return ans;
    }

    public long totalRows() {
        long sum = 0;
        for (SegmentInfo s : segments) {
            sum += s.rows();
        }
        return sum;
    }

    public long totalSizeBytes() {
        long sum = 0;
        for (SegmentInfo s : segments) {
            sum += s.sizeBytes();
        }
        return sum;
    }
}
