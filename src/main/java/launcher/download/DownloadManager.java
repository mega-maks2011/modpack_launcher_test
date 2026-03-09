package launcher.download;

import launcher.ui.DownloadDialog;
import launcher.util.UrlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages concurrent downloads with a fixed thread pool.
 * Reports progress to a DownloadDialog.
 */
public class DownloadManager {
    private static final Logger log = LoggerFactory.getLogger(DownloadManager.class);
    private final ExecutorService executor;
    private final DownloadDialog dialog;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final Object lock = new Object();

    public DownloadManager(DownloadDialog dialog, int threads) {
        this.dialog = dialog;
        this.executor = Executors.newFixedThreadPool(threads);
    }

    public DownloadDialog getDialog() { return dialog; }

    /**
     * Submits a download task. Returns immediately.
     */
    public void submit(String url, Path target, long size, String fileLabel) {
        if (cancelled.get()) return;
        synchronized (lock) {
            activeTasks.incrementAndGet();
        }
        executor.submit(() -> {
            try {
                if (cancelled.get()) return;
                downloadWithRetry(url, target, size, fileLabel);
            } catch (Exception e) {
                log.error("Download error {}: {}", url, e.getMessage());
            } finally {
                synchronized (lock) {
                    if (activeTasks.decrementAndGet() == 0) {
                        lock.notifyAll();
                    }
                }
            }
        });
    }

    /**
     * Attempts to download a file with up to 3 retries.
     */
    private void downloadWithRetry(String url, Path target, long size, String fileLabel) throws IOException {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (cancelled.get()) return;
                dialog.fileStarted(fileLabel);

                String finalUrl = UrlUtils.addCacheBuster(url);

                HttpRequest req = HttpRequest.newBuilder(URI.create(finalUrl))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .timeout(Duration.ofSeconds(30))
                        .GET().build();
                HttpResponse<byte[]> resp = HttpClientSingleton.getClient().send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (cancelled.get()) return;
                if (resp.statusCode() != 200) {
                    throw new IOException("HTTP " + resp.statusCode());
                }
                byte[] data = resp.body();

                Files.createDirectories(target.getParent());
                Files.write(target, data);
                dialog.addCompletedBytes(size);
                dialog.fileCompleted(fileLabel);
                log.info("Downloaded {} ({} bytes)", fileLabel, data.length);
                return;
            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    throw new IOException("Failed after " + maxAttempts + " attempts", e);
                }
                log.warn("Attempt {} failed for {}, retry in 1s", attempt, url);
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                if (cancelled.get()) return;
            }
        }
    }

    /**
     * Blocks until all submitted tasks are completed or cancelled.
     */
    public void awaitTermination() throws InterruptedException {
        synchronized (lock) {
            while (activeTasks.get() > 0 && !cancelled.get()) {
                lock.wait();
            }
        }
        if (cancelled.get()) {
            throw new InterruptedException("Download cancelled");
        }
    }

    public void cancel() {
        cancelled.set(true);
        executor.shutdownNow();
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    public boolean isCancelled() { return cancelled.get(); }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}