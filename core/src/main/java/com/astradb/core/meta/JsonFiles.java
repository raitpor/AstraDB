package com.astradb.core.meta;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import com.astradb.core.util.FsUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * JSON 元数据文件读写：写走临时文件 + 原子 rename，进程崩溃不产生半写可见数据。
 */
public final class JsonFiles {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JsonFiles() {
    }

    /** 读取；文件不存在返回 null。 */
    public static <T> T read(Path file, Class<T> type) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        byte[] data = Files.readAllBytes(file);
        return MAPPER.readValue(data, type);
    }

    /** 原子写（临时文件 + rename）。 */
    public static void write(Path file, Object value) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        byte[] data = MAPPER.writeValueAsBytes(value);
        Files.write(tmp, data);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        FsUtil.fsyncDir(file.getParent()); // SF-7：rename 目录项落盘
    }

    public static String toJson(Object value) throws IOException {
        return new String(MAPPER.writeValueAsBytes(value), StandardCharsets.UTF_8);
    }
}
