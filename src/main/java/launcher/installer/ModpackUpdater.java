package launcher.installer;

import launcher.download.DownloadTask;
import launcher.download.HttpClientSingleton;
import launcher.github.GitHubRepo;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles updating modpack files and downloading large files listed in 25MB_dl.json.
 */
public class ModpackUpdater {
    private static final Logger log = LoggerFactory.getLogger(ModpackUpdater.class);

    private static class BigFile {
        String path;
        String url;
        BigFile(JSONObject obj) {
            this.path = obj.getString("path");
            this.url = obj.getString("url");
        }
    }

    /**
     * Collects tasks for updating modpack files (configs, mods, etc.) based on git SHA.
     * Skips files that should be kept locally (e.g., configs, options.txt) depending on loader.
     */
    public static void collectUpdateTasks(GitHubRepo repo, String loader, Path instanceDir, List<DownloadTask> tasks) throws IOException {
        Map<String, String> remoteShas = repo.getShaMap();
        Map<String, Long> remoteSizes = new HashMap<>();
        for (String path : remoteShas.keySet()) {
            remoteSizes.put(path, repo.getFileSize(path));
        }

        // Load local manifest (SHA of previously downloaded files)
        Path manifestFile = instanceDir.resolve(".manifest.json");
        Map<String, String> localShas = new HashMap<>();
        if (Files.exists(manifestFile)) {
            String content = Files.readString(manifestFile);
            JSONObject localJson = new JSONObject(content);
            for (String key : localJson.keySet()) {
                localShas.put(key, localJson.getString(key));
            }
        }

        // Determine which files to sync
        List<String> pathsToSync = new ArrayList<>();
        if ("vanilla".equals(loader)) {
            // For vanilla, only sync resource packs and options.txt
            for (String path : remoteShas.keySet()) {
                if (path.startsWith("resourcepacks/") || path.equals("options.txt")) {
                    pathsToSync.add(path);
                }
            }
        } else {
            // For modded, sync everything except modpack.json and 25MB_dl.json
            for (String path : remoteShas.keySet()) {
                if (path.equals("modpack.json") || path.equals("25MB_dl.json")) {
                    continue;
                }
                pathsToSync.add(path);
            }
        }

        for (String path : pathsToSync) {
            String remoteSha = remoteShas.get(path);
            String localSha = localShas.get(path);
            Path localFile = instanceDir.resolve(path);
            boolean needDownload = false;
            if (!Files.exists(localFile)) {
                needDownload = true;
            } else if (!remoteSha.equals(localSha)) {
                needDownload = true;
            }
            // Preserve user-modified configs/options
            if (loader.equals("vanilla")) {
                if (path.equals("options.txt") && Files.exists(localFile)) {
                    needDownload = false;
                }
            } else {
                if ((path.startsWith("config/") || path.startsWith("config\\") || path.equals("options.txt")) && Files.exists(localFile)) {
                    needDownload = false;
                }
            }
            if (needDownload) {
                long size = remoteSizes.getOrDefault(path, 0L);
                // Encode path for URL
                String[] parts = path.split("/");
                StringBuilder encodedPath = new StringBuilder();
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) encodedPath.append("/");
                    encodedPath.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
                }
                String fileUrl = "https://raw.githubusercontent.com/" + repo.user + "/" + repo.repo + "/" + repo.branch + "/" + encodedPath.toString();
                tasks.add(new DownloadTask(fileUrl, instanceDir.resolve(path), size, path));
            }
        }
    }

    /**
     * Collects tasks for downloading large files defined in 25MB_dl.json.
     * Compares with local manifest to avoid redownloading.
     */
    public static void collectBigFilesTasks(GitHubRepo repo, Path instanceDir, List<DownloadTask> tasks) throws IOException {
        Path bigFilesManifest = instanceDir.resolve(".bigfiles_manifest.json");
        Map<String, BigFile> localBigFiles = new HashMap<>();
        if (Files.exists(bigFilesManifest)) {
            String content = Files.readString(bigFilesManifest);
            JSONObject localJson = new JSONObject(content);
            for (String key : localJson.keySet()) {
                JSONObject obj = localJson.getJSONObject(key);
                localBigFiles.put(key, new BigFile(obj));
            }
        }

        List<BigFile> remoteBigFiles = new ArrayList<>();
        try {
            String bigListJson = repo.downloadRawString("25MB_dl.json");
            JSONArray arr = new JSONArray(bigListJson);
            for (int i = 0; i < arr.length(); i++) {
                remoteBigFiles.add(new BigFile(arr.getJSONObject(i)));
            }
        } catch (IOException e) {
            log.info("No 25MB_dl.json found or error loading: {}", e.getMessage());
            return;
        }

        for (BigFile remote : remoteBigFiles) {
            String urlPath = URI.create(remote.url).getPath();
            String fileName = Paths.get(urlPath).getFileName().toString();
            String localKey = remote.path + "/" + fileName;

            BigFile local = localBigFiles.get(localKey);
            Path localFile = instanceDir.resolve(localKey);
            boolean needDownload = false;
            if (local == null) {
                needDownload = true;
            } else if (!remote.url.equals(local.url)) {
                needDownload = true;
            } else if (!Files.exists(localFile)) {
                needDownload = true;
            }

            long size = 0;
            if (needDownload) {
                // Try to get file size via HEAD request
                try {
                    HttpRequest headReq = HttpRequest.newBuilder(URI.create(remote.url))
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .build();
                    HttpResponse<Void> headResp = HttpClientSingleton.getClient().send(headReq, HttpResponse.BodyHandlers.discarding());
                    String contentLength = headResp.headers().firstValue("Content-Length").orElse(null);
                    if (contentLength != null) {
                        size = Long.parseLong(contentLength);
                    }
                } catch (Exception e) {
                    log.warn("Could not determine size for {}: {}", remote.url, e.getMessage());
                }
                tasks.add(new DownloadTask(remote.url, localFile, size, localKey));
            }
        }
    }
}