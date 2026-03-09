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
 * Installer for the Quilt mod loader.
 * Fetches the loader profile from Quilt meta and collects required libraries.
 */
public class QuiltInstaller implements LoaderInstaller {
    private static final Logger log = LoggerFactory.getLogger(QuiltInstaller.class);
    private static final String QUILT_PROFILE_URL = "https://meta.quiltmc.org/v3/versions/loader/%s/%s/profile/json";

    @Override
    public void collectTasks(String mcVersion, String loaderVersion, Path instanceDir, List<DownloadTask> tasks) throws Exception {
        String profileUrl = String.format(QUILT_PROFILE_URL, mcVersion, loaderVersion);
        JSONObject quiltProfile = fetchJson(profileUrl);
        String quiltVersionId = mcVersion + "-quilt" + loaderVersion;
        Path quiltVersionDir = META_DIR.resolve("versions").resolve(quiltVersionId);
        Files.createDirectories(quiltVersionDir);
        Path jsonPath = quiltVersionDir.resolve(quiltVersionId + ".json");
        Files.writeString(jsonPath, quiltProfile.toString(4));

        // Collect all libraries from the profile
        List<LibraryFile> quiltLibs = collectLibraries(quiltProfile.getJSONArray("libraries"));
        for (LibraryFile lib : quiltLibs) {
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
            // Skip if rules prevent this library on current OS
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
                        // Add native libraries for the current OS
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