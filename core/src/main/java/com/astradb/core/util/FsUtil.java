package com.astradb.core.util;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Logger;

/**
 * 文件系统小工具：目录 fsync（review SF-7）。
 * <p>
 * rename/delete 只落文件内容不落目录项时，断电后目录项可能不持久（段删除可能复活、
 * rename 可能回退）。fsync 父目录可强制目录项落盘；平台不支持（如 Windows 打开目录失败）时
 * 降级 WARN（best-effort，不阻断主流程）。
 */
public final class FsUtil {

    private static final Logger LOG = Logger.getLogger(FsUtil.class.getName());

    private FsUtil() {
    }

    /** fsync 目录（落盘 rename/delete 目录项）；dir 为 null 或平台不支持时降级 WARN。 */
    public static void fsyncDir(Path dir) {
        if (dir == null) {
            return;
        }
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException e) {
            LOG.warning("目录 fsync 失败（降级，目录项可能未立即落盘）: " + dir + " (" + e + ")");
        }
    }
}
