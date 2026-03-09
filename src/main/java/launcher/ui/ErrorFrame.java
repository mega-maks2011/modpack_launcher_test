package launcher.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Error dialog with a custom icon.
 */
public class ErrorFrame extends BaseFrame {
    public ErrorFrame(JFrame parent, String title, String message) {
        super(parent, title, true);
        initUI(title, message);
    }

    private void initUI(String title, String message) {
        JPanel content = new JPanel(new GridBagLayout());
        content.setBackground(BG_COLOR);
        content.setBorder(new EmptyBorder(20, 20, 20, 20));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.gridx = 0;
        gbc.gridy = 0;

        Icon errorIcon = UIManager.getIcon("OptionPane.errorIcon");
        if (errorIcon != null) {
            JLabel iconLabel = new JLabel(errorIcon);
            content.add(iconLabel, gbc);
            gbc.gridx = 1;
        }

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(TITLE_FONT);
        titleLabel.setForeground(new Color(255, 80, 80));
        content.add(titleLabel, gbc);

        gbc.gridy = 1;
        JTextArea msgArea = new JTextArea(message);
        msgArea.setEditable(false);
        msgArea.setBackground(BG_COLOR);
        msgArea.setForeground(FG_COLOR);
        msgArea.setFont(TEXT_FONT);
        msgArea.setLineWrap(true);
        msgArea.setWrapStyleWord(true);
        msgArea.setBorder(null);
        content.add(msgArea, gbc);

        gbc.gridy = 2;
        gbc.gridwidth = 2;
        JButton okButton = createStyledButton("OK", BUTTON_BG, Color.WHITE);
        okButton.addActionListener(e -> dispose());
        content.add(okButton, gbc);

        add(content, BorderLayout.CENTER);
        pack();
        setSize(Math.min(500, getWidth()), getHeight());
        centerOnScreen();
    }
}