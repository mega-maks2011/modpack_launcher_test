package launcher.download;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Singleton for a shared HTTP client.
 */
public class HttpClientSingleton {
    private static final HttpClient instance = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public static HttpClient getClient() { return instance; }
}