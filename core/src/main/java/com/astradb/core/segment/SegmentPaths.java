package com.astradb.core.segment;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 段文件定位：按「配置时区」的天分片，segments/YYYY/YYYY-MM-DD.seg。
 * 分片时区与页面/数据时间戳保持一致（默认系统时区，可由 astradb.timezone 配置）。
 */
public final class SegmentPaths {

    private SegmentPaths() {
    }

    /** 指定时区的当天 0 点（毫秒）。 */
    public static long dayStart(long ts, ZoneId zone) {
        LocalDate date = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate();
        return date.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** UTC 当天 0 点（兼容/测试用）。 */
    public static long dayStartUtc(long ts) {
        return dayStart(ts, ZoneOffset.UTC);
    }

    public static Path pathFor(Path tableDir, long ts, ZoneId zone) {
        LocalDate date = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate();
        return tableDir.resolve("segments")
                .resolve(Integer.toString(date.getYear()))
                .resolve(date + ".seg");
    }

    /** 段相对表目录的路径（manifest 记录用）。 */
    public static String relative(Path segPath, Path tableDir) {
        return tableDir.relativize(segPath).toString().replace('\\', '/');
    }
}
