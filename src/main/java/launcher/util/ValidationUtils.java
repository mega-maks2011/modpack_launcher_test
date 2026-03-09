package launcher.util;

/**
 * Utility class for validating user input.
 */
public class ValidationUtils {
    /**
     * Validates a Minecraft username: 3-16 characters, alphanumeric and underscore only.
     */
    public static boolean isValidUsername(String name) {
        return name != null && name.matches("^[a-zA-Z0-9_]{3,16}$");
    }
}