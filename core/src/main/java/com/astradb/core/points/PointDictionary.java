package com.astradb.core.points;

import com.astradb.core.util.ByteBuf;
import com.astradb.core.util.ByteReader;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32C;

/**
 * 表级点字典：key → pointId（从 1 开始，分配顺序即落盘顺序）。
 *
 * points.dict 格式（追加式，崩溃安全）：
 *   Header: "APTD"(4B) version(2B)
 *   Entry:  [keyLen varint][key utf8][pointId varint][crc32c(4B)]
 * 加载时逐条 CRC 校验，尾部半写条目被安全忽略（截断语义）。
 */
public final class PointDictionary {

    public static final byte[] MAGIC = {'A', 'P', 'T', 'D'};
    public static final int VERSION = 1;
    public static final int ENTRY_HEADER_MIN = 4;

    private final Path file;
    private final Map<String, Integer> keyToId = new HashMap<>();
    private final List<String> idToKey = new ArrayList<>();   // 索引 = pointId - 1
    private final List<String> pending = new ArrayList<>();   // 未落盘的新 key（顺序 = id 顺序）
    private int nextId = 1;

    private PointDictionary(Path file) {
        this.file = file;
    }

    /** 加载；文件不存在返回空字典。 */
    public static PointDictionary load(Path file) throws IOException {
        PointDictionary d = new PointDictionary(file);
        if (!Files.exists(file)) {
            return d;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long len = raf.length();
            if (len < 6) {
                return d; // 空/无效文件视为空字典
            }
            byte[] header = new byte[6];
            raf.readFully(header);
            for (int i = 0; i < 4; i++) {
                if (header[i] != MAGIC[i]) {
                    throw new IOException("points.dict 文件头损坏: " + file);
                }
            }
            long pos = 6;
            while (pos < len) {
                long remaining = len - pos;
                byte[] head = readAt(raf, pos, (int) Math.min(remaining, 512));
                ByteReader probe = new ByteReader(head);
                int keyLen;
                try {
                    keyLen = (int) probe.readUInt();
                } catch (RuntimeException e) {
                    break; // 半写 varint
                }
                int headerLen = probe.position();
                if ((long) headerLen + keyLen + 1 + 4 > remaining) {
                    break; // 至少 1B id + 4B crc，不足即半写
                }
                int readLen = (int) Math.min(remaining, (long) headerLen + keyLen + 10 + 4);
                byte[] body = readAt(raf, pos, readLen);
                ByteReader in = new ByteReader(body);
                int kl;
                try {
                    kl = (int) in.readUInt();
                } catch (RuntimeException e) {
                    break;
                }
                byte[] keyBytes = new byte[kl];
                for (int i = 0; i < kl; i++) {
                    keyBytes[i] = (byte) in.readByte();
                }
                int id;
                try {
                    id = (int) in.readUInt();
                } catch (RuntimeException e) {
                    break;
                }
                int consumed = in.position() + 4;
                if (consumed > body.length) {
                    break; // id varint 越界，半写条目
                }
                int storedCrc = ((body[consumed - 4] & 0xFF) << 24) | ((body[consumed - 3] & 0xFF) << 16)
                        | ((body[consumed - 2] & 0xFF) << 8) | (body[consumed - 1] & 0xFF);
                CRC32C crc = new CRC32C();
                crc.update(body, 0, consumed - 4);
                if ((int) crc.getValue() != storedCrc) {
                    break; // 半写/损坏条目 → 截断忽略
                }
                String key = new String(keyBytes, StandardCharsets.UTF_8);
                if (d.keyToId.put(key, id) != null) {
                    throw new IOException("points.dict 存在重复 key: " + key);
                }
                d.idToKey.add(key);
                if (id >= d.nextId) {
                    d.nextId = id + 1;
                }
                pos += consumed;
            }
        }
        return d;
    }

    private static byte[] readAt(RandomAccessFile raf, long off, int n) throws IOException {
        byte[] b = new byte[n];
        raf.seek(off);
        raf.readFully(b);
        return b;
    }

    public Path file() {
        return file;
    }

    /** key 的 pointId；不存在返回 -1。 */
    public int idOf(String key) {
        return keyToId.getOrDefault(key, -1);
    }

    /** pointId → key；未知 id 返回 null。 */
    public String keyOf(int id) {
        if (id < 1 || id > idToKey.size()) {
            return null;
        }
        return idToKey.get(id - 1);
    }

    public int size() {
        return keyToId.size();
    }

    /** 分配新 pointId（仅内存态，需 flush 落盘）。 */
    public int assign(String key) {
        Integer exist = keyToId.get(key);
        if (exist != null) {
            return exist;
        }
        int id = nextId++;
        keyToId.put(key, id);
        idToKey.add(key);
        pending.add(key);
        return id;
    }

    /** 把新分配的点追加落盘并 fsync。 */
    public void flush() throws IOException {
        if (pending.isEmpty()) {
            return;
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        boolean created = !Files.exists(file);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            if (created) {
                raf.setLength(0);
                raf.write(MAGIC);
                raf.writeShort(VERSION);
            } else {
                raf.seek(raf.length());
            }
            ByteBuf body = new ByteBuf(64);
            for (String key : pending) {
                byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                body = new ByteBuf(16 + keyBytes.length + 8);
                int id = keyToId.get(key);
                body.writeUInt(keyBytes.length);
                body.writeBytes(keyBytes);
                body.writeUInt(id);
                byte[] withCrc = new byte[body.length() + 4];
                System.arraycopy(body.toArray(), 0, withCrc, 0, body.length());
                CRC32C crc = new CRC32C();
                crc.update(withCrc, 0, body.length());
                int v = (int) crc.getValue();
                withCrc[body.length()] = (byte) (v >>> 24);
                withCrc[body.length() + 1] = (byte) (v >>> 16);
                withCrc[body.length() + 2] = (byte) (v >>> 8);
                withCrc[body.length() + 3] = (byte) v;
                raf.write(withCrc);
            }
            raf.getFD().sync();
        }
        pending.clear();
    }
}
