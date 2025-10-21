import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.*;
import java.net.Socket;
import java.util.Arrays;
import java.util.Base64;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;

public class ModernChatClientGUI extends JFrame {

    private final JPanel chatPanel;
    private final JScrollPane scrollPane;
    private final JButton themeToggleButton;
    private final JButton sendButton;
    private JTextArea inputField;
    private boolean darkTheme = false;

    private final Color lightBg = new Color(245, 245, 245);
    private final Color darkBg = new Color(40, 44, 52);
    private final Color lightBubble = new Color(230, 230, 230);
    private final Color darkBubble = new Color(70, 76, 85);
    private final Color sentBlue = new Color(0, 122, 255);
    private final Color sentBlueDark = new Color(10, 132, 255);

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private String userName;
    private static final int CHUNK_SIZE = 8192; // 8KB chunks for file transfer
    private final String DOWNLOAD_DIR = System.getProperty("user.home") + "/Downloads/ChatApp/";

    public ModernChatClientGUI() {
        initializeUserName();

        setTitle("Modern Chat Client - " + userName);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(450, 600);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(0, 0));

        // Top bar
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBorder(new EmptyBorder(10, 10, 10, 10));
        JLabel titleLabel = new JLabel("Chat Client");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        themeToggleButton = new JButton("☀️"); // Using actual emoji
        themeToggleButton.setFocusPainted(false);
        themeToggleButton.setBorderPainted(false);
        themeToggleButton.setContentAreaFilled(false);
        themeToggleButton.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 18));
        themeToggleButton.setOpaque(false);
        themeToggleButton.setForeground(Color.BLACK);
        themeToggleButton.addActionListener(e -> toggleTheme());
        topBar.add(titleLabel, BorderLayout.WEST);
        topBar.add(themeToggleButton, BorderLayout.EAST);
        add(topBar, BorderLayout.NORTH);

        // Chat panel
        chatPanel = new JPanel();
        chatPanel.setLayout(new BoxLayout(chatPanel, BoxLayout.Y_AXIS));
        chatPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        // Create a container panel to prevent stretching
        JPanel containerPanel = new JPanel(new BorderLayout());
        containerPanel.setBackground(lightBg);
        containerPanel.add(chatPanel, BorderLayout.NORTH);
        
        // Configure scroll pane with custom scrollbar
        scrollPane = new JScrollPane(containerPanel);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);
        
        // Custom scrollbar styling
        JScrollBar verticalScrollBar = scrollPane.getVerticalScrollBar();
        verticalScrollBar.setUnitIncrement(16);
        verticalScrollBar.setPreferredSize(new Dimension(8, 0));
        verticalScrollBar.setUI(new ModernScrollBarUI());
        verticalScrollBar.setBackground(new Color(245, 245, 245));
        
        add(scrollPane, BorderLayout.CENTER);

        // Input panel with file sharing
        JPanel inputPanel = new JPanel(new BorderLayout(10, 10));
        inputPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        // Create a panel for attachment button and input field
        JPanel inputWithAttachmentPanel = new JPanel(new BorderLayout(5, 0));
        
        // Create attachment button
        JButton attachButton = new JButton("📎");
        attachButton.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 18));
        attachButton.setFocusPainted(false);
        attachButton.setBorderPainted(false);
        attachButton.setContentAreaFilled(false);
        attachButton.setPreferredSize(new Dimension(40, 40));
        attachButton.addActionListener(e -> showFileChooser());
        
        // Create input field with scroll pane for multiple lines
        inputField = new JTextArea(2, 20);
        inputField.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        inputField.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        inputField.setLineWrap(true);
        inputField.setWrapStyleWord(true);
        
        // Wrap input field in scroll pane with custom scrollbar
        JScrollPane inputScrollPane = new JScrollPane(inputField);
        inputScrollPane.setBorder(BorderFactory.createEmptyBorder());
        inputScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        
        // Apply custom scrollbar to input area
        JScrollBar inputVerticalScrollBar = inputScrollPane.getVerticalScrollBar();
        inputVerticalScrollBar.setPreferredSize(new Dimension(8, 0));
        inputVerticalScrollBar.setUI(new ModernScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                this.thumbColor = darkTheme ? new Color(100, 100, 100) : new Color(200, 200, 200);
                this.trackColor = darkTheme ? new Color(60, 63, 69) : new Color(245, 245, 245);
            }
        });
        inputVerticalScrollBar.setBackground(darkTheme ? darkBg : lightBg);
        
        // Handle Enter and Shift+Enter keys
        inputField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    if (e.isShiftDown()) {
                        // Shift+Enter: add new line
                        inputField.append("\n");
                    } else {
                        // Enter only: send message
                        e.consume();
                        sendMessage();
                    }
                }
            }
        });

        sendButton = new JButton("Send");
        sendButton.setFont(new Font("Segoe UI", Font.BOLD, 14));
        sendButton.setFocusPainted(false);
        sendButton.setBackground(sentBlue);
        sendButton.setForeground(Color.WHITE);
        sendButton.addActionListener(e -> sendMessage());
        inputWithAttachmentPanel.add(attachButton, BorderLayout.WEST);
        inputWithAttachmentPanel.add(inputScrollPane, BorderLayout.CENTER);
        inputPanel.add(inputWithAttachmentPanel, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        add(inputPanel, BorderLayout.SOUTH);

        applyTheme();
        setVisible(true);

        connectToServer();
    }

    private void initializeUserName() {
        userName = JOptionPane.showInputDialog(this, "Enter your username:", "Username", JOptionPane.PLAIN_MESSAGE);
        if (userName == null || userName.trim().isEmpty()) {
            userName = "Anonymous";
        }
    }

    private void connectToServer() {
        try {
            socket = new Socket("localhost", 8000);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

            // Send username
            writer.write(userName);
            writer.newLine();
            writer.flush();

            new Thread(this::listenForMessages).start();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Unable to connect to server.", "Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    private void listenForMessages() {
        new Thread(() -> {
            try {
                String message;
                while ((message = reader.readLine()) != null) {
                    final String finalMessage = message;
                    SwingUtilities.invokeLater(() -> addMessageBubble(finalMessage, false));
                }
            } catch (IOException e) {
                System.err.println("Error while reading messages: " + e.getMessage());
            }
        }).start();
    }

    private void sendMessage() {
        String message = inputField.getText().trim();
        if (!message.isEmpty()) {
            try {
                writer.write(message);
                writer.newLine();
                writer.flush();
                addMessageBubble(userName + ": " + message, true);
                inputField.setText("");
            } catch (IOException e) {
                e.printStackTrace();
                addMessageBubble("Failed to send message.", false);
            }
        }
    }

    private void addMessageBubble(String message, boolean isSent) {
        BubblePanel bubble = new BubblePanel(message, isSent ? (darkTheme ? sentBlueDark : sentBlue) : (darkTheme ? darkBubble : lightBubble), isSent);
        chatPanel.add(bubble);
        chatPanel.revalidate();
        chatPanel.repaint();
    }

    private void toggleTheme() {
        darkTheme = !darkTheme;
        // Using actual emojis
        themeToggleButton.setText(darkTheme ? "🌙" : "☀️");
        themeToggleButton.setForeground(darkTheme ? Color.WHITE : Color.BLACK);
        applyTheme();
    }

    private void applyTheme() {
        // Set theme colors
        Color bg = darkTheme ? darkBg : lightBg;
        Color inputBg = darkTheme ? new Color(50, 54, 62) : Color.WHITE;
        Color textColor = darkTheme ? Color.WHITE : Color.BLACK;
        Color scrollThumbColor = darkTheme ? new Color(100, 100, 100) : new Color(200, 200, 200);
        Color scrollTrackColor = darkTheme ? new Color(60, 63, 69) : new Color(245, 245, 245);
        
        // Update main panel themes
        chatPanel.setBackground(bg);
        getContentPane().setBackground(bg);
        
        // Update top bar
        for (Component comp : getContentPane().getComponents()) {
            if (comp instanceof JPanel) {
                comp.setBackground(bg);
                for (Component subComp : ((JPanel) comp).getComponents()) {
                    if (subComp instanceof JLabel) {
                        subComp.setForeground(textColor);
                    } else if (subComp instanceof JButton && subComp == themeToggleButton) {
                        subComp.setForeground(textColor);
                    }
                }
            }
        }
        
        // Update chat area
        scrollPane.setBackground(bg);
        scrollPane.getViewport().setBackground(bg);
        scrollPane.getVerticalScrollBar().setBackground(bg);
        scrollPane.getVerticalScrollBar().setUI(new ModernScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                this.thumbColor = scrollThumbColor;
                this.trackColor = scrollTrackColor;
            }
        });
        
        // Update container panel
        Container containerPanel = chatPanel.getParent();
        if (containerPanel != null) {
            containerPanel.setBackground(bg);
        }
        
        // Update all message bubbles
        for (Component component : chatPanel.getComponents()) {
            if (component instanceof BubblePanel) {
                component.setBackground(bg);
                for (Component subComp : ((BubblePanel) component).getComponents()) {
                    if (subComp instanceof JLabel) {
                        JLabel label = (JLabel) subComp;
                        if (!((BubblePanel) component).isSent) {
                            label.setForeground(textColor);
                        }
                    }
                }
            }
        }
        
        // Update input area
        inputField.setBackground(inputBg);
        inputField.setForeground(textColor);
        inputField.setCaretColor(textColor);
        
        // Find and update input scroll pane and its container
        Container parent = inputField.getParent();
        while (parent != null) {
            parent.setBackground(bg);
            if (parent instanceof JScrollPane) {
                JScrollPane inputScrollPane = (JScrollPane) parent;
                inputScrollPane.setBackground(inputBg);
                inputScrollPane.getViewport().setBackground(inputBg);
                
                JScrollBar inputScrollBar = inputScrollPane.getVerticalScrollBar();
                inputScrollBar.setBackground(inputBg);
                inputScrollBar.setUI(new ModernScrollBarUI() {
                    @Override
                    protected void configureScrollBarColors() {
                        this.thumbColor = scrollThumbColor;
                        this.trackColor = darkTheme ? new Color(70, 73, 79) : new Color(240, 240, 240);
                    }
                });
                break;
            }
            parent = parent.getParent();
        }
        
        // Update send button
        sendButton.setBackground(darkTheme ? sentBlueDark : sentBlue);
        sendButton.setForeground(Color.WHITE);
    }

    // Fixed BubblePanel for dynamic width and proper wrapping
    static class BubblePanel extends JPanel {

        private final Color bgColor;
        private final boolean isSent;
        private final JLabel messageLabel;

        public BubblePanel(String text, Color bgColor, boolean isSent) {
            this.bgColor = bgColor;
            this.isSent = isSent;
            setLayout(new BorderLayout());
            setOpaque(false);

            if (!text.isEmpty()) {
                messageLabel = new JLabel("<html><body style='width: 250px;'>" + text + "</body></html>"); // max width
                messageLabel.setFont(new Font("Segoe UI", Font.PLAIN, 14));
                messageLabel.setForeground(isSent ? Color.WHITE : Color.BLACK);
                messageLabel.setBorder(new EmptyBorder(10, 15, 10, 15));
                add(messageLabel, BorderLayout.CENTER);
            } else {
                messageLabel = null;
            }

            setMaximumSize(new Dimension(300, Integer.MAX_VALUE)); // allow vertical growth
            setBorder(new EmptyBorder(5, isSent ? 50 : 5, 5, isSent ? 5 : 50)); // spacing for alignment
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(bgColor);
            int width = getWidth();
            int height = getHeight();
            int arc = 15;
            g2.fillRoundRect(0, 0, width, height, arc, arc);
            super.paintComponent(g);
        }
    }

    private void showFileChooser() {
        JFileChooser fileChooser = new JFileChooser();
        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            sendFile(selectedFile);
        }
    }

    private void sendFile(File file) {
        try {
            // Send file metadata
            writer.write("FILE:" + userName + ":" + file.getName() + ":" + file.length());
            writer.newLine();
            writer.flush();

            // Create progress dialog
            JDialog progressDialog = createProgressDialog("Sending file...");

            // Start file transfer in a separate thread
            new Thread(() -> {
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[CHUNK_SIZE];
                    long totalBytesRead = 0;
                    int bytesRead;

                    while ((bytesRead = fis.read(buffer)) != -1) {
                        String chunk = Base64.getEncoder().encodeToString(Arrays.copyOf(buffer, bytesRead));
                        writer.write(chunk);
                        writer.newLine();
                        writer.flush();

                        totalBytesRead += bytesRead;
                        final int progress = (int) ((totalBytesRead * 100) / file.length());
                        SwingUtilities.invokeLater(() -> progressDialog.setTitle("Sending file... " + progress + "%"));
                    }

                    // Send end marker
                    writer.write("END:FILE");
                    writer.newLine();
                    writer.flush();

                    SwingUtilities.invokeLater(() -> {
                        progressDialog.dispose();
                        addMessageBubble("You sent: " + file.getName(), true);
                    });

                } catch (IOException e) {
                    SwingUtilities.invokeLater(() -> {
                        progressDialog.dispose();
                        JOptionPane.showMessageDialog(this, "Failed to send file", "Error", JOptionPane.ERROR_MESSAGE);
                    });
                }
            }).start();

        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Failed to send file", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }



    private JDialog createProgressDialog(String title) {
        JDialog dialog = new JDialog(this, title, true);
        dialog.setLayout(new BorderLayout(10, 10));
        
        dialog.add(new JLabel(title), BorderLayout.NORTH);
        dialog.setSize(300, 100);
        dialog.setLocationRelativeTo(this);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        
        // Show dialog in a non-blocking way
        SwingUtilities.invokeLater(() -> dialog.setVisible(true));
        
        return dialog;
    }

    private void createFileBubble(String message, File file, boolean isSent) {
        SwingUtilities.invokeLater(() -> {
            // Create main panel for the bubble content
            JPanel contentPanel = new JPanel();
            contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
            contentPanel.setOpaque(false);
            
            // Add message label
            JLabel messageLabel = new JLabel("<html><body style='width: 250px'>" + message + "</body></html>");
            messageLabel.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            messageLabel.setForeground(isSent ? Color.WHITE : Color.BLACK);
            messageLabel.setBorder(new EmptyBorder(5, 5, 5, 5));
            contentPanel.add(messageLabel);
            
            if (file != null) {
                // Add file download panel
                JPanel filePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
                filePanel.setOpaque(false);
                
                // Add file icon
                JLabel fileIcon = new JLabel("📄");
                fileIcon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 24));
                fileIcon.setForeground(isSent ? Color.WHITE : Color.BLACK);
                filePanel.add(fileIcon);
                
                // Add download button
                JButton downloadButton = new JButton("Download");
                downloadButton.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                downloadButton.setForeground(isSent ? Color.WHITE : Color.BLUE);
                downloadButton.setBorderPainted(false);
                downloadButton.setContentAreaFilled(false);
                downloadButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
                downloadButton.addActionListener(e -> {
                    try {
                        Desktop.getDesktop().open(file);
                    } catch (IOException ex) {
                        JOptionPane.showMessageDialog(this, "Failed to open file", "Error", JOptionPane.ERROR_MESSAGE);
                    }
                });
                filePanel.add(downloadButton);
                
                contentPanel.add(Box.createVerticalStrut(5));
                contentPanel.add(filePanel);
            }
            
            // Create bubble
            BubblePanel bubble = new BubblePanel("", isSent ? (darkTheme ? sentBlueDark : sentBlue) 
                    : (darkTheme ? darkBubble : lightBubble), isSent);
            bubble.setLayout(new BorderLayout());
            bubble.add(contentPanel, BorderLayout.CENTER);
            bubble.setAlignmentX(isSent ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);
            
            // Add to chat panel
            chatPanel.add(Box.createVerticalStrut(5));
            chatPanel.add(bubble);
            chatPanel.revalidate();
            
            // Scroll to bottom
            JScrollBar vertical = scrollPane.getVerticalScrollBar();
            vertical.setValue(vertical.getMaximum());
        });
    }

    private void addFileReceivedMessage(String message, File file) {
        SwingUtilities.invokeLater(() -> {
            JPanel filePanel = new JPanel();
            filePanel.setLayout(new BoxLayout(filePanel, BoxLayout.Y_AXIS));
            filePanel.setOpaque(false);
            
            // Add the sender message first
            JLabel messageLabel = new JLabel(message);
            messageLabel.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            messageLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            filePanel.add(messageLabel);
            
            // Add some spacing
            filePanel.add(Box.createVerticalStrut(5));
            
            // Create the file button panel
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
            buttonPanel.setOpaque(false);
            buttonPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            
            // Add file icon
            JLabel fileIcon = new JLabel("📄");
            fileIcon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 24));
            buttonPanel.add(fileIcon);
            
            // Create download button
            JButton downloadButton = new JButton("Download");
            downloadButton.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            downloadButton.setForeground(Color.BLUE);
            downloadButton.setBorderPainted(false);
            downloadButton.setContentAreaFilled(false);
            downloadButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
            downloadButton.addActionListener(e -> {
                try {
                    Desktop.getDesktop().open(file);
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this, "Failed to open file", "Error", JOptionPane.ERROR_MESSAGE);
                }
            });
            buttonPanel.add(downloadButton);
            
            filePanel.add(buttonPanel);
            
            // Create and add the bubble
            BubblePanel bubble = new BubblePanel("", darkTheme ? darkBubble : lightBubble, false);
            bubble.setLayout(new BorderLayout());
            bubble.add(filePanel, BorderLayout.CENTER);
            bubble.setAlignmentX(Component.LEFT_ALIGNMENT);
            
            // Add bubble to chat panel
            chatPanel.add(Box.createVerticalStrut(5));
            chatPanel.add(bubble);
            chatPanel.revalidate();
            
            // Scroll to bottom
            SwingUtilities.invokeLater(() -> {
                JScrollBar vertical = scrollPane.getVerticalScrollBar();
                vertical.setValue(vertical.getMaximum());
            });
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(ModernChatClientGUI::new);
    }

    // Custom ScrollBarUI for modern look
    static class ModernScrollBarUI extends BasicScrollBarUI {
        private final int THUMB_SIZE = 8;

        @Override
        protected void configureScrollBarColors() {
            this.thumbColor = new Color(200, 200, 200);
            this.thumbDarkShadowColor = new Color(200, 200, 200);
            this.thumbHighlightColor = new Color(200, 200, 200);
            this.thumbLightShadowColor = new Color(200, 200, 200);
            this.trackColor = new Color(245, 245, 245);
            this.trackHighlightColor = new Color(245, 245, 245);
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return createZeroButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return createZeroButton();
        }

        private JButton createZeroButton() {
            JButton button = new JButton();
            button.setPreferredSize(new Dimension(0, 0));
            button.setMinimumSize(new Dimension(0, 0));
            button.setMaximumSize(new Dimension(0, 0));
            return button;
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
            if (thumbBounds.isEmpty() || !scrollbar.isEnabled()) {
                return;
            }

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            // Calculate thumb size
            int x = thumbBounds.x + (thumbBounds.width - THUMB_SIZE) / 2;
            int y = thumbBounds.y;
            int width = THUMB_SIZE;
            int height = thumbBounds.height;
            
            g2.setColor(thumbColor);
            g2.fillRoundRect(x, y, width, height, width, width);
            g2.dispose();
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
            g.setColor(trackColor);
            g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);
        }
    }
}
