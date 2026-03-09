package launcher.github;

import launcher.download.HttpClientSingleton;
import launcher.util.UrlUtils;
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
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static launcher.LauncherApp.GITHUB_TOKEN;
import static launcher.LauncherApp.LAUNCHER_DIR;

/**
 * Represents a GitHub repository and branch.
 * Provides methods to fetch file trees and download raw files.
 */
public class GitHubRepo {
    private static final Logger log = LoggerFactory.getLogger(GitHubRepo.class);
    private static final Path CACHE_DIR = LAUNCHER_DIR.resolve(".cache");
    private static final Duration CACHE_TTL = Duration.ofSeconds(10);
    public final String user;
    public final String repo;
    public final String branch;
    private Map<String, String> shaMap;
    private Map<String, Long> sizeMap;

    public GitHubRepo(String user, String repo, String branch) {
        this.user = user;
        this.repo = repo;
        this.branch = branch;
    }

    /**
     * Parses a GitHub base URL (e.g., https://github.com/user/repo) and combines with branch.
     */
    public static GitHubRepo fromBaseAndBranch(String baseUrl, String branch) throws IOException {
        Pattern pattern = Pattern.compile("github\\.com/([^/]+)/([^/]+)");
        Matcher m = pattern.matcher(baseUrl);
        if (!m.find()) throw new IOException("Invalid base GitHub URL: " + baseUrl);
        String user = m.group(1);
        String repo = m.group(2);
        return new GitHubRepo(user, repo, branch);
    }

    /**
     * Fetches the git tree for the branch (recursive) using GitHub API.
     * Caches the result for CACHE_TTL.
     */
    private void fetchTree() throws IOException {
        if (shaMap != null) return;
        Path cacheFile = CACHE_DIR.resolve(user + "_" + repo + "_" + branch + ".json");
        if (Files.exists(cacheFile)) {
            try {
                Instant modTime = Files.getLastModifiedTime(cacheFile).toInstant();
                if (modTime.plus(CACHE_TTL).isAfter(Instant.now())) {
                    String json = Files.readString(cacheFile);
                    parseTree(new JSONObject(json));
                    log.info("Loaded file tree from cache");
                    return;
                } else {
                    log.debug("Cache expired, re-fetching tree for {}/{}", repo, branch);
                }
            } catch (Exception e) {
                log.warn("Cache read error, reloading", e);
            }
        }
        String apiUrl = "https://api.github.com/repos/" + user + "/" + repo + "/git/trees/" + branch + "?recursive=1";
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(apiUrl))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(Duration.ofSeconds(30));
        if (GITHUB_TOKEN != null && !GITHUB_TOKEN.isEmpty()) {
            requestBuilder.header("Authorization", "token " + GITHUB_TOKEN);
        }
        HttpRequest req = requestBuilder.GET().build();
        try {
            HttpResponse<String> resp = HttpClientSingleton.getClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 403) {
                String errorMsg = "Access forbidden (HTTP 403). ";
                if (GITHUB_TOKEN == null || GITHUB_TOKEN.isEmpty()) {
                    errorMsg += "API rate limit exceeded. Add a valid GitHub token.";
                } else {
                    errorMsg += "Token may be invalid or repository private.";
                }
                throw new IOException(errorMsg);
            }
            if (resp.statusCode() != 200) {
                throw new IOException("GitHub API returned " + resp.statusCode() + ": " + resp.body());
            }
            JSONObject treeJson = new JSONObject(resp.body());
            Files.createDirectories(CACHE_DIR);
            Files.writeString(cacheFile, treeJson.toString(4));
            parseTree(treeJson);
        } catch (Exception e) {
            throw new IOException("Failed to load file tree: " + e.getMessage(), e);
        }
    }

    private void parseTree(JSONObject treeJson) {
        shaMap = new HashMap<>();
        sizeMap = new HashMap<>();
        JSONArray tree = treeJson.getJSONArray("tree");
        for (int i = 0; i < tree.length(); i++) {
            JSONObject entry = tree.getJSONObject(i);
            if ("blob".equals(entry.getString("type"))) {
                String path = entry.getString("path");
                String sha = entry.getString("sha");
                long size = entry.optLong("size", 0);
                shaMap.put(path, sha);
                sizeMap.put(path, size);
            }
        }
    }

    public Map<String, String> getShaMap() throws IOException {
        fetchTree();
        return shaMap;
    }

    public long getFileSize(String path) throws IOException {
        fetchTree();
        return sizeMap.getOrDefault(path, 0L);
    }

    public String getSha(String path) throws IOException {
        fetchTree();
        return shaMap.get(path);
    }

    /**
     * Downloads a raw file from the repository as byte array.
     */
    public byte[] downloadRawBytes(String path) throws IOException {
        String[] parts = path.split("/");
        StringBuilder encodedPath = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) encodedPath.append("/");
            encodedPath.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        String rawUrl = "https://raw.githubusercontent.com/" + user + "/" + repo + "/" + branch + "/" + encodedPath.toString();
        String finalUrl = UrlUtils.addCacheBuster(rawUrl);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(finalUrl))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(Duration.ofSeconds(30));
        if (GITHUB_TOKEN != null && !GITHUB_TOKEN.isEmpty()) {
            requestBuilder.header("Authorization", "token " + GITHUB_TOKEN);
        }
        HttpRequest req = requestBuilder.GET().build();
        try {
            HttpResponse<byte[]> resp = HttpClientSingleton.getClient().send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new IOException("HTTP " + resp.statusCode() + " while downloading " + path);
            }
            return resp.body();
        } catch (Exception e) {
            throw new IOException("Failed to download " + path, e);
        }
    }

    public String downloadRawString(String path) throws IOException {
        return new String(downloadRawBytes(path), StandardCharsets.UTF_8);
    }
}