package launcher.github;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Locale;

/**
 * Parses modpack.json from a GitHub repository branch.
 */
public class ModpackInfo {
    private static final Logger log = LoggerFactory.getLogger(ModpackInfo.class);
    private final boolean enabled;
    private final boolean technicalWork;
    private final String loader;
    private final String loaderVersion;
    private final String mcVersion;

    public ModpackInfo(JSONObject json) {
        this.enabled = json.optBoolean("enabled", true);
        this.technicalWork = json.optBoolean("technical work", false);
        this.loader = json.optString("loader", "vanilla").toLowerCase(Locale.ROOT);
        this.loaderVersion = json.optString("loader_version", "");
        this.mcVersion = json.optString("MC_version", "1.21.11");
        log.info("Modpack info: enabled={}, techWork={}, loader={}, loaderVer={}, mc={}",
                enabled, technicalWork, loader, loaderVersion, mcVersion);
    }

    public static ModpackInfo fetch(GitHubRepo repo) throws IOException {
        String raw = repo.downloadRawString("modpack.json");
        JSONObject json = new JSONObject(raw);
        return new ModpackInfo(json);
    }

    public boolean isEnabled() { return enabled; }
    public boolean isTechnicalWork() { return technicalWork; }
    public String getLoader() { return loader; }
    public String getLoaderVersion() { return loaderVersion; }
    public String getMcVersion() { return mcVersion; }
    public String getVersionId() {
        if ("vanilla".equals(loader)) {
            return mcVersion;
        } else {
            return mcVersion + "-" + loader + loaderVersion;
        }
    }
}