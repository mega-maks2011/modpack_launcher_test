package launcher.game;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Utility methods for evaluating library rules and OS detection.
 */
public class LibraryUtils {
    /**
     * Evaluates Mojang's library rules to determine if a library should be included.
     */
    public static boolean evaluateRules(JSONArray rules) {
        boolean allow = false;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            String action = rule.getString("action");
            boolean ok = true;
            if (rule.has("os")) {
                JSONObject os = rule.getJSONObject("os");
                if (os.has("name")) {
                    String osName = os.getString("name").toLowerCase();
                    String currentOs = System.getProperty("os.name").toLowerCase();
                    if (osName.equals("windows") && !currentOs.contains("windows")) ok = false;
                    else if (osName.equals("linux") && !currentOs.contains("linux")) ok = false;
                    else if (osName.equals("osx") && !(currentOs.contains("mac") || currentOs.contains("darwin"))) ok = false;
                }
            }
            if (ok) allow = action.equals("allow");
        }
        return allow;
    }

    /**
     * Returns the classifier string for native libraries on the current OS.
     */
    public static String getCurrentOsClassifier() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("windows")) return "natives-windows";
        if (osName.contains("linux")) return "natives-linux";
        if (osName.contains("mac")) return "natives-osx";
        return "natives-unknown";
    }
}