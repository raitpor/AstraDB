package com.astradb.server.service;

import com.astradb.core.ingest.SnapshotData;
import com.astradb.core.ingest.SnapshotIngestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异步导入任务：提交后立即返回 taskId，后台线程池执行导入（大文件不阻塞 HTTP）。
 * 任务输入为已解析的 {@link SnapshotData}（内存列缓冲，不依赖 multipart 临时文件）。
 */
@Service
public class ImportTaskService {

    private static final Logger log = LoggerFactory.getLogger(ImportTaskService.class);
    private static final int MAX_TASKS = 200;

    public enum Status { RUNNING, SUCCESS, FAILED }

    public record TaskState(String id, Status status, String table, Long timestamp,
                            long rowCount, long newPoints, String error,
                            long createdAt, long completedAt) {
    }

    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final ExecutorService pool = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            r -> {
                Thread t = new Thread(r, "import-task");
                t.setDaemon(true);
                return t;
            });

    private final AstraDbService dbService;

    public ImportTaskService(AstraDbService dbService) {
        this.dbService = dbService;
    }

    /** 提交异步导入任务，返回 taskId。 */
    public String submit(String table, Long timestamp, SnapshotData data) {
        String id = "import-" + seq.incrementAndGet();
        long now = System.currentTimeMillis();
        tasks.put(id, new TaskState(id, Status.RUNNING, table, timestamp, 0, 0, null, now, 0));
        pool.submit(() -> {
            try {
                SnapshotIngestor.IngestResult r = dbService.db().ingest(table, data, timestamp);
                tasks.put(id, new TaskState(id, Status.SUCCESS, table, timestamp,
                        r.rowCount(), r.newPoints(), null, now, System.currentTimeMillis()));
                log.info("异步导入完成: taskId={} table={} rows={}", id, table, r.rowCount());
            } catch (Throwable t) {
                log.error("异步导入失败: taskId={} table={}", id, table, t);
                tasks.put(id, new TaskState(id, Status.FAILED, table, timestamp,
                        0, 0, t.getMessage(), now, System.currentTimeMillis()));
            }
        });
        trimOldTasks();
        return id;
    }

    public TaskState status(String id) {
        return tasks.get(id);
    }

    /** 保留最近 MAX_TASKS 个任务，超出删除最旧的已完成任务。 */
    private void trimOldTasks() {
        if (tasks.size() <= MAX_TASKS) {
            return;
        }
        tasks.entrySet().removeIf(e -> e.getValue().status() != Status.RUNNING);
        int overflow = tasks.size() - MAX_TASKS;
        if (overflow > 0) {
            tasks.keySet().stream().sorted().limit(overflow).forEach(tasks::remove);
        }
    }
}
