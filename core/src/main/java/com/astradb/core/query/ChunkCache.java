package com.astradb.core.query;

import com.astradb.core.segment.ChunkCodec;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LRU 列解压缓存：缓存 zstd 解压后的列原始字节（RawColumn），
 * 同一快照的多次分页/查询复用解压结果，避免反复解压。
 * 按字节数上限淘汰最久未用项；maxBytes &lt;= 0 表示禁用。
 * 线程安全（synchronized）。
 */
public final class ChunkCache {

    private record Key(String table, String segRel, int chunkIdx, int columnIdx) {
    }

    private final long maxBytes;
    private final Map<Key, ChunkCodec.RawColumn> map;
    private long currentBytes;

    public ChunkCache(long maxBytes) {
        this.maxBytes = maxBytes;
        this.map = new LinkedHashMap<>(64, 0.75f, true); // accessOrder=true：迭代从头为最久未用
    }

    public long maxBytes() {
        return maxBytes;
    }

    public synchronized ChunkCodec.RawColumn get(String table, String segRel, int chunkIdx, int columnIdx) {
        return map.get(new Key(table, segRel, chunkIdx, columnIdx));
    }

    public synchronized void put(String table, String segRel, int chunkIdx, int columnIdx,
                                 ChunkCodec.RawColumn raw) {
        if (maxBytes <= 0) {
            return;
        }
        Key k = new Key(table, segRel, chunkIdx, columnIdx);
        ChunkCodec.RawColumn old = map.put(k, raw);
        if (old != null) {
            currentBytes -= old.raw().length;
        }
        currentBytes += raw.raw().length;
        evict();
    }

    private void evict() {
        if (currentBytes <= maxBytes) {
            return;
        }
        Iterator<Map.Entry<Key, ChunkCodec.RawColumn>> it = map.entrySet().iterator();
        while (currentBytes > maxBytes && it.hasNext()) {
            currentBytes -= it.next().getValue().raw().length;
            it.remove();
        }
    }

    public synchronized void clear() {
        map.clear();
        currentBytes = 0;
    }

    /** 当前缓存字节数（测试/监控用）。 */
    public synchronized long currentBytes() {
        return currentBytes;
    }

    /** 当前条目数（测试/监控用）。 */
    public synchronized int size() {
        return map.size();
    }
}
