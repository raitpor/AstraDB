package com.astradb.server.api;

import com.astradb.core.AstraDB;
import com.astradb.server.service.AstraDbService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 健康检查（B2）：版本、表数、数据目录可写状态、运行时长。
 * 鉴权开启时本端点放行（供负载均衡/监控探测）。
 */
@RestController
public class HealthController {

    private final AstraDbService service;

    public HealthController(AstraDbService service) {
        this.service = service;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Path dir = service.dataDir();
        return Map.of(
                "status", "UP",
                "version", AstraDB.VERSION,
                "tables", service.db().listTables().size(),
                "dataDir", dir.toString(),
                "dataDirWritable", Files.isWritable(dir),
                "uptimeMs", System.currentTimeMillis() - service.startedAtMillis());
    }
}
