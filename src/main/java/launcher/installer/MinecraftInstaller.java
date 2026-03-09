package launcher.installer;

import launcher.download.DownloadTask;
import launcher.game.LibraryUtils;
import launcher.util.UrlUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static launcher.LauncherApp.META_DIR;
import static launcher.download.HttpClientSingleton.getClient;

/**
 * Installs vanilla Minecraft: downloads version JSON, client jar, libraries, and assets.
 */
public class MinecraftInstaller {
    private static final Logger log = LoggerFactory.getLogger(MinecraftInstaller.class);
    private static final String VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    /**
     * Collects all tasks needed to install a specific vanilla Minecraft version.
     */
    public static void collectVanillaTasks(String mcVer, Path instanceDir, List<DownloadTask> tasks) throws Exception {
        Path versionsDir = META_DIR.resolve("versions");
        Path vanillaVersionDir = versionsDir.resolve(mcVer);
        Path vanillaJsonPath = vanillaVersionDir.resolve(mcVer + ".json");

        // If the version JSON already exists, we only need to check for missing libraries/assets.
        if (Files.exists(vanillaJsonPath)) {
            collectMissingLibrariesAndAssets(mcVer, instanceDir, tasks);
            return;
        }

        // Fetch version manifest to get the URL for this specific version
        JSONObject versionManifest = fetchJson(VERSION_MANIFEST_URL);
        JSONArray versions = versionManifest.getJSONArray("versions");
        JSONObject versionInfo = null;
        for (int i = 0; i < versions.length(); i++) {
            JSONObject v = versions.getJSONObject(i);
            if (v.getString("id").equals(mcVer)) {
                versionInfo = v;
                break;
            }
        }
        if (versionInfo == null) throw new IOException("Version " + mcVer + " not found.");

        String versionUrl = versionInfo.getString("url");
        JSONObject vanillaJson = fetchJson(versionUrl);

        // Save the version JSON
        Files.createDirectories(vanillaVersionDir);
        Files.writeString(vanillaJsonPath, vanillaJson.toString(4));

        // Download client jar
        JSONObject downloads = vanillaJson.getJSONObject("downloads");
        JSONObject client = downloads.getJSONObject("client");
        long clientSize = client.getLong("size");
        tasks.add(new DownloadTask(client.getString("url"), vanillaVersionDir.resolve(mcVer + ".jar"), clientSize, "client.jar"));

        // Collect and download libraries
        JSONArray libraries = vanillaJson.getJSONArray("libraries");
        List<LibraryFile> vanillaLibs = collectLibraries(libraries);
        for (LibraryFile lib : vanillaLibs) {
            tasks.add(new DownloadTask(lib.url, META_DIR.resolve("libraries").resolve(lib.path), lib.size, lib.path));
        }

        // Download asset index and assets
        JSONObject assetIndex = vanillaJson.getJSONObject("assetIndex");
        String assetIndexId = assetIndex.getString("id");
        Path assetsDir = META_DIR.resolve("assets");
        Path indexesDir = assetsDir.resolve("indexes");
        Path indexPath = indexesDir.resolve(assetIndexId + ".json");
        Files.createDirectories(indexesDir);

        if (!Files.exists(indexPath)) {
            log.info("Downloading asset index {}...", assetIndexId);
            String assetIndexUrl = assetIndex.getString("url");
            JSONObject assetIndexJson = fetchJson(assetIndexUrl);
            Files.writeString(indexPath, assetIndexJson.toString(4));
            addAssetTasks(assetIndexJson, instanceDir, tasks);
        } else {
            String indexContent = Files.readString(indexPath);
            JSONObject assetIndexJson = new JSONObject(indexContent);
            addAssetTasks(assetIndexJson, instanceDir, tasks);
        }
    }

    /**
     * Checks existing vanilla installation and adds missing libraries/assets to tasks.
     */
    private static void collectMissingLibrariesAndAssets(String mcVer, Path instanceDir, List<DownloadTask> tasks) throws IOException {
        Path vanillaJsonPath = META_DIR.resolve("versions").resolve(mcVer).resolve(mcVer + ".json");
        if (!Files.exists(vanillaJsonPath)) return;
        String content = Files.readString(vanillaJsonPath);
        JSONObject vanillaJson = new JSONObject(content);

        // Check libraries
        JSONArray libraries = vanillaJson.getJSONArray("libraries");
        List<LibraryFile> allLibs = collectLibraries(libraries);
        for (LibraryFile lib : allLibs) {
            Path libFile = META_DIR.resolve("libraries").resolve(lib.path);
            if (!Files.exists(libFile)) {
                tasks.add(new DownloadTask(lib.url, libFile, lib.size, lib.path));
            }
        }

        // Check asset index and assets
        JSONObject assetIndex = vanillaJson.getJSONObject("assetIndex");
        String assetIndexId = assetIndex.getString("id");
        Path indexPath = META_DIR.resolve("assets").resolve("indexes").resolve(assetIndexId + ".json");
        if (!Files.exists(indexPath)) {
            log.info("Downloading missing asset index {}...", assetIndexId);
            String assetIndexUrl = assetIndex.getString("url");
            JSONObject assetIndexJson = fetchJson(assetIndexUrl);
            Files.createDirectories(indexPath.getParent());
            Files.writeString(indexPath, assetIndexJson.toString(4));
            addAssetTasks(assetIndexJson, instanceDir, tasks);
        } else {
            String indexContent = Files.readString(indexPath);
            JSONObject assetIndexJson = new JSONObject(indexContent);
            addAssetTasks(assetIndexJson, instanceDir, tasks);
        }
    }

    /**
     * Adds download tasks for all assets listed in the asset index.
     */
    private static void addAssetTasks(JSONObject assetIndex, Path instanceDir, List<DownloadTask> tasks) {
        JSONObject objects = assetIndex.getJSONObject("objects");
        Path objectsDir = META_DIR.resolve("assets").resolve("objects");
        for (String key : objects.keySet()) {
            JSONObject obj = objects.getJSONObject(key);
            String hash = obj.getString("hash");
            long size = obj.getLong("size");
            String subDir = hash.substring(0, 2);
            Path objectFile = objectsDir.resolve(subDir).resolve(hash);
            if (!Files.exists(objectFile) || objectFile.toFile().length() != size) {
                String url = "https://resources.download.minecraft.net/" + subDir + "/" + hash;
                tasks.add(new DownloadTask(url, objectFile, size, "assets/" + subDir + "/" + hash));
            }
        }
    }

    private static JSONObject fetchJson(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(UrlUtils.addCacheBuster(url)))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(Duration.ofSeconds(30))
                .GET().build();
        try {
            HttpResponse<String> resp = getClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
            return new JSONObject(resp.body());
        } catch (Exception e) {
            throw new IOException("Failed to fetch JSON from " + url, e);
        }
    }

    private static List<LibraryFile> collectLibraries(JSONArray libsArray) {
        List<LibraryFile> result = new ArrayList<>();
        for (int i = 0; i < libsArray.length(); i++) {
            JSONObject lib = libsArray.getJSONObject(i);
            if (lib.has("rules") && !LibraryUtils.evaluateRules(lib.getJSONArray("rules"))) continue;

            JSONObject downloads = lib.optJSONObject("downloads");
            if (downloads != null) {
                JSONObject artifact = downloads.optJSONObject("artifact");
                if (artifact != null) {
                    addArtifact(artifact, result, false);
                }
                JSONObject classifiers = downloads.optJSONObject("classifiers");
                if (classifiers != null) {
                    String currentOs = LibraryUtils.getCurrentOsClassifier();
                    for (String key : classifiers.keySet()) {
                        if (key.equals(currentOs) || key.startsWith("natives-")) {
                            addArtifact(classifiers.getJSONObject(key), result, key.startsWith("natives-"));
                            break;
                        }
                    }
                }
            }
        }
        return result;
    }

    private static void addArtifact(JSONObject artifact, List<LibraryFile> list, boolean isNative) {
        String path = artifact.getString("path");
        String url = artifact.getString("url");
        long size = artifact.optLong("size", 0);
        list.add(new LibraryFile(path, url, size, isNative));
    }

    private static class LibraryFile {
        String path, url; long size; boolean isNative;
        LibraryFile(String path, String url, long size, boolean isNative) {
            this.path = path; this.url = url; this.size = size; this.isNative = isNative;
        }
    }
}