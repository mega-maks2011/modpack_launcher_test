package launcher.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Utility for manipulating URLs, e.g., adding cache-busting parameters.
 */
public class UrlUtils {
    private static final Logger log = LoggerFactory.getLogger(UrlUtils.class);

    /**
     * Builds a unique cache-buster string using current time and random UUID.
     */
    private static String buildCacheBuster() {
        LocalTime now = LocalTime.now();
        String time = String.format("%02d.%02d.%02d", now.getSecond(), now.getMinute(), now.getHour());
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "&_=" + time + "-" + random;
    }

    /**
     * Adds a cache-buster parameter to the given URL.
     */
    public static String addCacheBuster(String url) {
        try {
            URI uri = new URI(url);
            String query = uri.getQuery();
            String newQuery = (query == null ? "" : query + "&") + buildCacheBuster().substring(1); // remove leading '&'
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), newQuery, uri.getFragment()).toString();
        } catch (URISyntaxException e) {
            log.warn("Failed to add cache buster to URL: {}", url, e);
            return url;
        }
    }
}