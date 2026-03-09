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
 * Installer for the Fabric mod loader.
 * Fetches the loader profile from Fabric meta and collects required libraries.
 */
public class FabricInstaller implements LoaderInstaller {
    private static final Logger log = LoggerFactory.getLogger(FabricInstaller.class);
    private static final String FABRIC_PROFILE_URL = "https://meta.fabricmc.net/v2/versions/loader/%s/%s/profile/json";

    @Override
    public void collectTasks(String mcVersion, String loaderVersion, Path instanceDir, List<DownloadTask> tasks) throws Exception {
        String profileUrl = String.format(FABRIC_PROFILE_URL, mcVersion, loaderVersion);
        JSONObject fabricProfile = fetchJson(profileUrl);
        String fabricVersionId = mcVersion + "-fabric" + loaderVersion;
        Path fabricVersionDir = META_DIR.resolve("versions").resolve(fabricVersionId);
        Files.createDirectories(fabricVersionDir);
        Path jsonPath = fabricVersionDir.resolve(fabricVersionId + ".json");
        Files.writeString(jsonPath, fabricProfile.toString(4));

        // Collect all libraries from the profile
        List<LibraryFile> fabricLibs = collectLibraries(fabricProfile.getJSONArray("libraries"));
        for (LibraryFile lib : fabricLibs) {
            tasks.add(new DownloadTask(lib.url, META_DIR.resolve("libraries").resolve(lib.path), lib.size, lib.path));
        }
    }

    private JSONObject fetchJson(String url) throws IOException {
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

    private List<LibraryFile> collectLibraries(JSONArray libsArray) {
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
            } else {
                // Handle libraries without downloads (e.g., with custom URL)
                String name = lib.optString("name");
                String url = lib.optString("url");
                if (!name.isEmpty() && !url.isEmpty()) {
                    String[] parts = name.split(":");
                    if (parts.length >= 3) {
                        String group = parts[0].replace('.', '/');
                        String artifactName = parts[1];
                        String version = parts[2];
                        String path = group + "/" + artifactName + "/" + version + "/" + artifactName + "-" + version + ".jar";
                        String baseUrl = url.endsWith("/") ? url : url + "/";
                        String fullUrl = baseUrl + path;
                        result.add(new LibraryFile(path, fullUrl, 0, false));
                    }
                }
            }
        }
        return result;
    }

    private void addArtifact(JSONObject artifact, List<LibraryFile> list, boolean isNative) {
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