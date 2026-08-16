package com.astradb.server.task;

import com.astradb.server.service.AstraDbService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 保留期清理：每日执行，删除各表超期段文件。
 */
@Component
public class RetentionTask {

    private static final Logger log = LoggerFactory.getLogger(RetentionTask.class);

    private final AstraDbService service;

    public RetentionTask(AstraDbService service) {
        this.service = service;
    }

    /** 每天 03:30 清理。 */
    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanAllTables() {
        for (String table : service.db().listTables()) {
            try {
                int deleted = service.db().cleanRetention(table);
                if (deleted > 0) {
                    log.info("保留期清理：表 {} 删除 {} 个段", table, deleted);
                }
            } catch (Exception e) {
                log.error("保留期清理失败：表 " + table, e);
            }
        }
    }
}
