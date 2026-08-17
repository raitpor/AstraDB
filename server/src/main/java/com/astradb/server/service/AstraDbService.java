package com.astradb.server.service;

import com.astradb.core.AstraDB;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 持有 AstraDB 引擎实例的 Spring 单例。构造时完成数据目录加载（启动初始化）。
 */
@Service
public class AstraDbService {

    private static final Logger log = LoggerFactory.getLogger(AstraDbService.class);

    private final AstraDB db;
    private final Path dataDir;
    private final long startedAtMillis = System.currentTimeMillis();

    public AstraDbService(@Value("${astradb.data-dir:./data}") String dataDir,
                          @Value("${astradb.compression-level:3}") int compressionLevel,
                          @Value("${astradb.timezone:}") String timezone,
                          @Value("${astradb.query.cache-mb:64}") long cacheMb) throws IOException {
        this.dataDir = Path.of(dataDir);
        java.time.ZoneId zone = parseZone(timezone);
        this.db = AstraDB.open(this.dataDir, compressionLevel, zone, cacheMb * 1024 * 1024);
        log.info("AstraDB 已加载：dataDir={}, timezone={}, queryCacheMB={}, 表={}",
                this.dataDir, zone, cacheMb, db.listTables());
    }

    public long startedAtMillis() {
        return startedAtMillis;
    }

    /** 解析时区配置；为空则用系统默认时区（保证与页面/数据时间戳一致）。 */
    private static java.time.ZoneId parseZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return java.time.ZoneId.systemDefault();
        }
        try {
            return java.time.ZoneId.of(timezone.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("非法时区配置 astradb.timezone=" + timezone
                    + "（示例：Asia/Shanghai、UTC、+08:00）", e);
        }
    }

    public AstraDB db() {
        return db;
    }

    public Path dataDir() {
        return dataDir;
    }

    @PreDestroy
    public void close() {
        db.close();
    }
}
