package launcher.github;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Parses current_modpack.json from the repository root (main/master) to determine
 * the active branch and global enable flag.
 */
public class CurrentModpackInfo {
    private static final Logger log = LoggerFactory.getLogger(CurrentModpackInfo.class);
    private final boolean enabled;
    private final String branch;

    private CurrentModpackInfo(JSONObject json) {
        this.enabled = json.optBoolean("enable", true);
        this.branch = json.optString("branch", "main");
        log.info("Created CurrentModpackInfo: enabled={}, branch={}", this.enabled, this.branch);
    }

    /**
     * Tries to fetch current_modpack.json from main and master branches.
     */
    public static CurrentModpackInfo fetch(String repoBaseUrl) throws IOException {
        String[] possibleBranches = {"main", "master"};
        for (String branch : possibleBranches) {
            try {
                GitHubRepo repo = GitHubRepo.fromBaseAndBranch(repoBaseUrl, branch);
                String content = repo.downloadRawString("current_modpack.json");
                // Remove possible UTF-8 BOM
                if (content.length() > 0 && content.charAt(0) == '\uFEFF') {
                    content = content.substring(1);
                }
                JSONObject json = new JSONObject(content);
                log.info("Loaded current_modpack.json from {}: enabled={}, branch={}", branch,
                        json.optBoolean("enable"), json.optString("branch"));
                return new CurrentModpackInfo(json);
            } catch (IOException e) {
                log.warn("Failed to load from {}: {}", branch, e.getMessage());
            }
        }
        throw new IOException("Could not find current_modpack.json in main or master branch");
    }

    public boolean isEnabled() { return enabled; }
    public String getBranch() { return branch; }
}