package com.astradb.core.segment;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 段文件 {@link FileChannel} 空闲池（LRU）：复用文件句柄，减少查询路径反复打开/关闭。
 * <p>
 * 并发模型：channel 处于"活跃"（被 SegmentReader 持有）时不在池中，不会被淘汰关闭；
 * 释放（refCount 归零）后回池；池满时淘汰最久未用的空闲 channel 并关闭。
 * 同路径并发读各持有独立 channel（不共享 position，均使用 positional read），线程安全。
 */
public final class SegmentChannelCache implements AutoCloseable {

    private final int maxChannels;
    /** path -> 空闲 channel 队列（队尾为最近使用）。 */
    private final Map<Path, Deque<FileChannel>> idle = new HashMap<>();

    public SegmentChannelCache(int maxChannels) {
        if (maxChannels <= 0) {
            throw new IllegalArgumentException("maxChannels 必须 > 0");
        }
        this.maxChannels = maxChannels;
    }

    /** 取一个可读 channel：池命中直接复用，否则打开新句柄。调用方用完后必须 {@link #release}。 */
    public synchronized FileChannel acquire(Path path) throws IOException {
        Deque<FileChannel> q = idle.get(path);
        if (q != null && !q.isEmpty()) {
            return q.pollLast();
        }
        return FileChannel.open(path, StandardOpenOption.READ);
    }

    /** 归还空闲 channel：池未满则缓存，否则关闭。 */
    public synchronized void release(Path path, FileChannel ch) {
        try {
            Deque<FileChannel> q = idle.computeIfAbsent(path, p -> new ArrayDeque<>());
            if (totalIdle() >= maxChannels) {
                evictOne();
            }
            q.addLast(ch);
        } catch (RuntimeException e) {
            tryClose(ch);
        }
    }

    /** 删除数据文件前调用：丢弃该路径的空闲句柄（活跃句柄随 reader 生命周期自然释放）。 */
    public synchronized void evict(Path path) {
        Deque<FileChannel> q = idle.remove(path);
        if (q != null) {
            for (FileChannel ch : q) {
                tryClose(ch);
            }
        }
    }

    public synchronized int idleCount() {
        return totalIdle();
    }

    private int totalIdle() {
        int n = 0;
        for (Deque<FileChannel> q : idle.values()) {
            n += q.size();
        }
        return n;
    }

    private void evictOne() {
        Path victimPath = null;
        FileChannel victim = null;
        for (Map.Entry<Path, Deque<FileChannel>> e : idle.entrySet()) {
            FileChannel ch = e.getValue().pollFirst(); // 最久未用
            if (ch != null) {
                victim = ch;
                if (e.getValue().isEmpty()) {
                    victimPath = e.getKey();
                }
                break;
            }
        }
        if (victimPath != null) {
            idle.remove(victimPath);
        }
        if (victim != null) {
            tryClose(victim);
        }
    }

    private static void tryClose(FileChannel ch) {
        try {
            ch.close();
        } catch (IOException ignored) {
            // 关闭失败（已关闭等）忽略
        }
    }

    @Override
    public synchronized void close() {
        for (Deque<FileChannel> q : idle.values()) {
            for (FileChannel ch : q) {
                tryClose(ch);
            }
        }
        idle.clear();
    }
}
