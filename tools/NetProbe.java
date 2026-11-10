import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Probes HTTPS reachability from the JDK (JSSE), which is independent of Windows schannel. */
public final class NetProbe {
    public static void main(String[] args) throws Exception {
        String[] urls = {
            "https://dl.google.com/android/repository/repository2-3.xml",
            "https://repo1.maven.org/maven2/",
            "https://services.gradle.org/distributions/"
        };
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        for (String url : urls) {
            long start = System.nanoTime();
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(40))
                        .header("User-Agent", "lazyrotate-toolchain")
                        .GET().build();
                HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
                double secs = (System.nanoTime() - start) / 1e9;
                double kbps = res.body().length / 1024.0 / Math.max(secs, 0.001);
                System.out.printf("OK   %s status=%d bytes=%d time=%.2fs speed=%.0f KB/s%n",
                        url, res.statusCode(), res.body().length, secs, kbps);
            } catch (Throwable t) {
                System.out.printf("FAIL %s -> %s: %s%n", url, t.getClass().getName(), t.getMessage());
            }
        }
    }
}
