package dev.partiesandchill.core.redis;

import org.junit.jupiter.api.Assumptions;
import redis.clients.jedis.RedisClient;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Starts one throwaway {@code redis-server} per test JVM; tests are skipped when it isn't installed. */
final class LocalRedis {

    private static RedisClient client;
    private static int port;

    private LocalRedis() {
    }

    /** @return a client connected to the test server, flushed */
    static synchronized RedisClient client() {
        if (client == null) client = start();
        client.flushAll();
        return client;
    }

    /** @return the test server's URI, for code that opens its own client; call {@link #client()} first */
    static String uri() {
        return "redis://127.0.0.1:" + port + "/0";
    }

    private static RedisClient start() {
        String binary = Stream.of(System.getenv().getOrDefault("PATH", "").split(File.pathSeparator))
                .map(dir -> Path.of(dir, "redis-server")).filter(Files::isExecutable)
                .map(Path::toString).findFirst().orElse(null);
        Assumptions.assumeTrue(binary != null, "redis-server not installed; skipping Redis tests");
        try {
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            Process process = new ProcessBuilder(binary, "--port", Integer.toString(port), "--bind", "127.0.0.1",
                    "--save", "", "--appendonly", "no").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
            RedisClient redis = RedisClient.create("127.0.0.1", port);
            for (int attempt = 0; ; attempt++) {
                try {
                    redis.ping();
                    return redis;
                } catch (RuntimeException notYet) {
                    if (attempt > 50) throw notYet;
                    Thread.sleep(100);
                }
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("could not start redis-server", e);
        }
    }
}
