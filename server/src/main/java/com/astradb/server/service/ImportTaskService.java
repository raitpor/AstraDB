package com.astradb.server.service;

import com.astradb.core.ingest.SnapshotData;
import com.astradb.core.ingest.SnapshotIngestor;
import com.astradb.core.meta.Schema;
import com.astradb.server.ingest.CsvParser;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异步导入任务：提交后立即返回 taskId，后台线程池执行 CSV 解析 + 导入（大文件不阻塞 HTTP）。
 * 任务输入为 CSV 字节（请求线程仅读取字节，解析/校验/落盘全部在后台线程）。
 */
@Service
public class ImportTaskService {

    private static final Logger log = LoggerFactory.getLogger(ImportTaskService.class);
    /** 任务记录保留上限（仅限制已完成任务数量，RUNNING 永不裁剪）。 */
    private static final int MAX_TASKS = 200;
    /** 后台队列上限（SS-6：有界队列，防止大文件任务连续提交导致内存无限堆积）。 */
    private static final int MAX_QUEUE = 100;

    public enum Status { RUNNING, SUCCESS, FAILED }

    public record TaskState(String id, Status status, String table, Long timestamp,
                            long rowCount, long newPoints, String error,
                            long createdAt, long completedAt) {
    }

    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUE),
            r -> {
                Thread t = new Thread(r, "import-task");
                t.setDaemon(true);
                return t;
            });

    private final AstraDbService dbService;

    public ImportTaskService(AstraDbService dbService) {
        this.dbService = dbService;
    }

    /** 提交异步导入任务（CSV 字节，后台解析 + 导入），返回 taskId；队列满抛 400 语义异常。 */
    public String submit(String table, Long timestamp, byte[] csvBytes) {
        String id = "import-" + seq.incrementAndGet();
        long now = System.currentTimeMillis();
        tasks.put(id, new TaskState(id, Status.RUNNING, table, timestamp, 0, 0, null, now, 0));
        try {
            pool.submit(() -> runTask(id, table, timestamp, csvBytes, now));
        } catch (RejectedExecutionException e) {
            tasks.remove(id);
            // SS-6：队列满 → 客户端语义错误（400），提示稍后重试
            throw new IllegalArgumentException("异步导入队列已满（最多 " + MAX_QUEUE + " 个待处理任务），请稍后重试");
        }
        trimOldTasks();
        return id;
    }

    /** 后台执行：解析 CSV → 导入（SS-7：解析与落盘均不占 HTTP 请求线程）。 */
    private void runTask(String id, String table, Long timestamp, byte[] csvBytes, long now) {
        try {
            Schema schema = dbService.db().tableInfo(table).schema();
            SnapshotData data;
            try (InputStream in = new ByteArrayInputStream(csvBytes)) {
                data = CsvParser.parse(in, schema, true);
            }
            SnapshotIngestor.IngestResult r = dbService.db().ingest(table, data, timestamp);
            tasks.put(id, new TaskState(id, Status.SUCCESS, table, timestamp,
                    r.rowCount(), r.newPoints(), null, now, System.currentTimeMillis()));
            log.info("异步导入完成: taskId={} table={} rows={}", id, table, r.rowCount());
        } catch (Throwable t) {
            log.error("异步导入失败: taskId={} table={}", id, table, t);
            tasks.put(id, new TaskState(id, Status.FAILED, table, timestamp,
                    0, 0, t.getMessage(), now, System.currentTimeMillis()));
        }
    }

    public TaskState status(String id) {
        return tasks.get(id);
    }

    /** 保留最近 MAX_TASKS 个任务；SS-6：只清理已结束任务，RUNNING 永不裁剪。 */
    private void trimOldTasks() {
        if (tasks.size() <= MAX_TASKS) {
            return;
        }
        tasks.entrySet().removeIf(e -> e.getValue().status() != Status.RUNNING);
        // RUNNING 数量受线程数 + 队列上限约束（≤ 线程数 + MAX_QUEUE），不会超过 MAX_TASKS；
        // 若极端情况仍超限，保留全部 RUNNING（不裁剪，避免状态查询返回"任务不存在"）
    }

    /** SS-6：应用关闭时优雅停池（拒绝新任务、等待在途任务结束）。 */
    @PreDestroy
    public void shutdown() {
        pool.shutdown();
    }
}
