package launcher.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/**
 * Base frame with common styling, modal behavior, and a title bar with close button.
 * All custom dialogs inherit from this.
 */
public abstract class BaseFrame extends JFrame {
    protected static final Color BG_COLOR = new Color(30, 30, 35);
    protected static final Color FG_COLOR = new Color(200, 200, 210);
    protected static final Color ACCENT_COLOR = new Color(0, 160, 255);
    protected static final Color BUTTON_BG = new Color(60, 60, 70);
    protected static final Color BUTTON_HOVER = new Color(80, 80, 90);
    protected static final Font TITLE_FONT = new Font("Segoe UI", Font.BOLD, 18);
    protected static final Font TEXT_FONT = new Font("Segoe UI", Font.PLAIN, 14);

    private final JFrame parent;
    private final boolean modal;
    private Point initialClick;

    public BaseFrame(JFrame parent, String title, boolean modal) {
        this.parent = parent;
        this.modal = modal;
        setTitle(title);
        setUndecorated(true);
        setBackground(BG_COLOR);
        ((JPanel) getContentPane()).setBorder(BorderFactory.createLineBorder(new Color(60, 60, 70), 2));
        setLayout(new BorderLayout());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        // Create title bar
        JPanel titleBar = new JPanel(new BorderLayout());
        titleBar.setBackground(BG_COLOR);
        titleBar.setBorder(new EmptyBorder(5, 10, 5, 5));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
        titleLabel.setForeground(FG_COLOR);
        titleBar.add(titleLabel, BorderLayout.WEST);

        // Close button
        JButton closeButton = new JButton("x");
        closeButton.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        closeButton.setForeground(FG_COLOR);
        closeButton.setBackground(BG_COLOR);
        closeButton.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        closeButton.setFocusPainted(false);
        closeButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        closeButton.addActionListener(e -> dispose());
        closeButton.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                closeButton.setForeground(Color.RED);
            }
            public void mouseExited(MouseEvent e) {
                closeButton.setForeground(FG_COLOR);
            }
        });
        titleBar.add(closeButton, BorderLayout.EAST);

        // Allow dragging the window
        titleBar.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                initialClick = e.getPoint();
            }
        });
        titleBar.addMouseMotionListener(new MouseAdapter() {
            public void mouseDragged(MouseEvent e) {
                Point current = e.getLocationOnScreen();
                setLocation(current.x - initialClick.x, current.y - initialClick.y);
            }
        });

        add(titleBar, BorderLayout.NORTH);
    }

    @Override
    public void setVisible(boolean visible) {
        if (modal && visible && parent != null) {
            parent.setEnabled(false);
        }
        super.setVisible(visible);
        if (visible) {
            // Apply rounded corners after the frame is visible (size known)
            EventQueue.invokeLater(() ->
                    setShape(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 20, 20))
            );
            toFront();
            requestFocus();
        } else {
            if (modal && parent != null) {
                parent.setEnabled(true);
                parent.toFront();
            }
        }
    }

    @Override
    public void dispose() {
        if (modal && parent != null) {
            parent.setEnabled(true);
            parent.toFront();
        }
        super.dispose();
    }

    /**
     * Creates a styled button with hover effect.
     */
    protected JButton createStyledButton(String text, Color bg, Color fg) {
        JButton button = new JButton(text);
        button.setFont(new Font("Segoe UI", Font.BOLD, 14));
        button.setBackground(bg);
        button.setForeground(fg);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(90, 90, 100), 1),
                BorderFactory.createEmptyBorder(8, 30, 8, 30)));
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));

        button.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                button.setBackground(bg.brighter());
            }
            public void mouseExited(MouseEvent e) {
                button.setBackground(bg);
            }
        });
        return button;
    }

    protected void centerOnScreen() {
        setLocationRelativeTo(parent);
    }
}