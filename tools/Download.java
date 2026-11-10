import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Tiny HTTPS downloader that relies on the JDK's own JSSE stack.
 * Windows schannel is unusable in this restricted network environment, but Java's TLS works.
 *
 * usage: java Download &lt;url&gt; &lt;outFile&gt; [minBytes]
 */
public final class Download {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: java Download <url> <outFile> [minBytes]");
            System.exit(2);
        }
        URI uri = URI.create(args[0]);
        Path out = Path.of(args[1]);
        long minBytes = args.length > 2 ? Long.parseLong(args[2]) : 1L;
        if (Files.exists(out) && Files.size(out) >= minBytes) {
            System.out.println("SKIP " + out + " (" + Files.size(out) + " bytes already present)");
            return;
        }
        Files.createDirectories(out.getParent());
        Path tmp = out.resolveSibling(out.getFileName() + ".part");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(30))
                .header("User-Agent", "lazyrotate-toolchain")
                .GET().build();

        long start = System.nanoTime();
        HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() / 100 != 2) {
            System.err.println("HTTP " + res.statusCode() + " for " + uri);
            System.exit(3);
        }
        long total = res.headers().firstValueAsLong("content-length").orElse(-1L);
        long copied = 0L;
        long lastReport = 0L;
        try (InputStream in = res.body(); OutputStream os = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                copied += n;
                long now = System.nanoTime();
                if (now - lastReport > 5_000_000_000L) {
                    lastReport = now;
                    double secs = (now - start) / 1e9;
                    System.out.printf("  %s  %.1f/%.1f MB  %.0f KB/s%n", out.getFileName(),
                            copied / 1048576.0, total > 0 ? total / 1048576.0 : -1, copied / 1024.0 / secs);
                }
            }
        }
        if (copied < minBytes) {
            Files.deleteIfExists(tmp);
            System.err.println("TOO SMALL: got " + copied + " < " + minBytes);
            System.exit(4);
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        double secs = (System.nanoTime() - start) / 1e9;
        System.out.printf("DONE %s bytes=%d time=%.1fs avg=%.0f KB/s%n",
                out, copied, secs, copied / 1024.0 / Math.max(secs, 0.001));
    }
}
