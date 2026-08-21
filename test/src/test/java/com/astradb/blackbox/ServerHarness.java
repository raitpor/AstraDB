package com.astradb.blackbox;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 黑盒测试服务端 harness：真实启动 server jar（随机端口 + 独立临时数据目录），
 * 提供 HTTP 请求工具、multipart 构造、server 进程启停与崩溃恢复（kill -9 重启）。
 * 仅通过外部接口（HTTP + 文件系统行为）验证，不触碰内部实现。
 */
public final class ServerHarness {

    public static final String BASE = "http://127.0.0.1";
    public static final String ADMIN_USER = "admin";
    public static final String ADMIN_PASS = "blackbox-pass-2026";

    private static Process server;
    private static Path dataDir;
    private static int port;
    private static boolean authEnabled;
    private static String jarPath;
    private static final StringBuilder serverLog = new StringBuilder();

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private ServerHarness() {
    }

    /** 定位 server jar（测试前需已构建：mvn -pl server -am package -Dmaven.test.skip=true）。 */
    public static synchronized void start(boolean auth) throws IOException, InterruptedException {
        if (server != null && server.isAlive()) {
            throw new IllegalStateException("server 已在运行，请先 stop/killAndRestart");
        }
        jarPath = findJar();
        dataDir = Files.createTempDirectory("astradb-blackbox-");
        port = freePort();
        authEnabled = auth;
        launch(auth);
        waitReady(30);
    }

    private static String findJar() throws IOException {
        // 从 user.dir 向上逐级查找 server/target 下的可执行 jar（兼容 mvn -f test/pom.xml 的 user.dir=test/）
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path jar = dir.resolve("server/target/astradb-server-0.1.0-SNAPSHOT.jar");
            if (Files.exists(jar)) {
                return jar.toString();
            }
            dir = dir.getParent();
        }
        throw new IOException("server jar 未构建: 未在 " + Path.of(System.getProperty("user.dir")).toAbsolutePath()
                + " 及其父目录找到 server/target/astradb-server-0.1.0-SNAPSHOT.jar"
                + "（请先执行 mvn -pl server -am package -Dmaven.test.skip=true）");
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void launch(boolean auth) throws IOException {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-jar");
        cmd.add(jarPath);
        cmd.add("--server.port=" + port);
        cmd.add("--astradb.data-dir=" + dataDir);
        cmd.add("--astradb.security.enabled=" + auth);
        if (auth) {
            cmd.add("--astradb.security.username=" + ADMIN_USER);
            cmd.add("--astradb.security.password=" + ADMIN_PASS);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        server = pb.start();
        // 后台收集 server 日志（测试报告可引用）
        Thread t = new Thread(() -> {
            try (var in = server.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    serverLog.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    if (serverLog.length() > 200_000) {
                        serverLog.delete(0, 100_000);
                    }
                }
            } catch (IOException ignored) {
                // 进程退出后流关闭
            }
        }, "server-log-collector");
        t.setDaemon(true);
        t.start();
    }

    /** 轮询 /api/health 直到 200（就绪）。 */
    private static void waitReady(int timeoutSec) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (server == null || !server.isAlive()) {
                throw new IllegalStateException("server 进程提前退出\n日志:\n" + serverLog);
            }
            try {
                HttpResponse<String> r = send("GET", "/api/health", null, null);
                if (r.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // 未就绪，重试
            }
            Thread.sleep(300);
        }
        throw new IllegalStateException("server 就绪超时（" + timeoutSec + "s）\n日志:\n" + serverLog);
    }

    /** 崩溃恢复：kill -9 当前进程，同一数据目录重启（不换端口语义，重新选端口亦可）。 */
    public static synchronized void killAndRestart() throws IOException, InterruptedException {
        if (server == null) {
            throw new IllegalStateException("server 未运行");
        }
        server.destroyForcibly();
        server.waitFor(10, TimeUnit.SECONDS);
        port = freePort();
        launch(authEnabled);
        waitReady(30);
    }

    public static synchronized void stop() {
        if (server != null) {
            server.destroyForcibly();
            try {
                server.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            server = null;
        }
        if (dataDir != null) {
            deleteRecursively(dataDir);
            dataDir = null;
        }
    }

    public static String baseUrl() {
        return BASE + ":" + port;
    }

    public static String authHeader() {
        return "Basic " + Base64.getEncoder().encodeToString(
                (ADMIN_USER + ":" + ADMIN_PASS).getBytes(StandardCharsets.UTF_8));
    }

    public static String serverLog() {
        return serverLog.toString();
    }

    // ---- HTTP 原语 ----

    public static HttpResponse<String> send(String method, String path, String jsonBody, String auth)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl() + path));
        b.timeout(Duration.ofSeconds(60));
        if (jsonBody != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        if (auth != null) {
            b.header("Authorization", auth);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** multipart/form-data 上传（importSnapshot：table/timestamp + file）。 */
    public static HttpResponse<String> multipart(String path, String table, String timestamp,
                                                 byte[] csv, String auth)
            throws IOException, InterruptedException {
        String boundary = "----blackbox" + Long.toHexString(System.nanoTime());
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        writeField(body, boundary, "table", table);
        if (timestamp != null) {
            writeField(body, boundary, "timestamp", timestamp);
        }
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"snap.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(csv);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (auth != null) {
            b.header("Authorization", auth);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** multipart/form-data 多文件上传（importSnapshots：table + file[] + timestamps[]，一一对应）。 */
    public static HttpResponse<String> multipartFiles(String path, String table,
                                                      java.util.List<Long> timestamps,
                                                      java.util.List<byte[]> files, String auth)
            throws IOException, InterruptedException {
        String boundary = "----blackbox" + Long.toHexString(System.nanoTime());
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        writeField(body, boundary, "table", table);
        for (int i = 0; i < files.size(); i++) {
            body.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"snap" + i + ".csv\"\r\n"
                    + "Content-Type: text/csv\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(files.get(i));
            body.write(("\r\n").getBytes(StandardCharsets.UTF_8));
        }
        for (Long ts : timestamps) {
            writeField(body, boundary, "timestamps", String.valueOf(ts));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (auth != null) {
            b.header("Authorization", auth);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void writeField(java.io.ByteArrayOutputStream out, String boundary,
                                   String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
