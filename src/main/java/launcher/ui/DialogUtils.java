package launcher.ui;

import javax.swing.*;
import java.awt.*;

/**
 * Utility methods for showing custom info/error dialogs.
 */
public class DialogUtils {
    public static void showInfo(Component parent, String title, String message) {
        JFrame frame = findJFrame(parent);
        new InfoFrame(frame, title, message).setVisible(true);
    }

    public static void showError(Component parent, String title, String message) {
        JFrame frame = findJFrame(parent);
        new ErrorFrame(frame, title, message).setVisible(true);
    }

    /**
     * Finds the nearest JFrame ancestor of a component.
     */
    private static JFrame findJFrame(Component parent) {
        if (parent == null) return null;
        Window window = SwingUtilities.getWindowAncestor(parent);
        while (window != null && !(window instanceof JFrame)) {
            window = window.getOwner();
        }
        return (JFrame) window;
    }
}