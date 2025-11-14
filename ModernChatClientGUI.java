import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.*;
import java.net.Socket;
import java.text.DecimalFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch; // Import CountDownLatch
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private DataInputStream din;
    private DataOutputStream dout;
    private String userName;
    private static final int CHUNK_SIZE = 8192; // 8KB
    private final String DOWNLOAD_DIR = System.getProperty("user.home") + File.separator + "Downloads" + File.separator + "ChatApp" + File.separator;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ExecutorService frameProcessor = Executors.newSingleThreadExecutor();

    // Map to track active file downloads (key: sender_filename, value: FileTransferState)
    private final Map<String, FileTransferState> activeFileDownloads = new ConcurrentHashMap<>();
    // Map to track active file uploads for progress updates on sender side (key: unique file path, value: FileTransferState)
    private final Map<String, FileTransferState> activeFileUploads = new ConcurrentHashMap<>();

    // Inner class to hold state for each active file transfer
    private static class FileTransferState {
        FileOutputStream fos; // Null for uploads
        File outFile; // Local temp file for download, or original file for upload tracking
        long totalSize;
        long receivedSize; // For download, or sent size for upload
        JPanel bubblePanelRef; // Reference to the entire outer bubble JPanel
        String originalFilename;
        String sender; // Sender of the file (for download), or current user (for upload)
        CountDownLatch uiReadyLatch; // Latch to signal when UI bubble is ready for updates

        public FileTransferState(FileOutputStream fos, File outFile, long totalSize, JPanel bubblePanelRef, String originalFilename, String sender) {
            this.fos = fos;
            this.outFile = outFile;
            this.totalSize = totalSize;
            this.receivedSize = 0;
            this.bubblePanelRef = bubblePanelRef;
            this.originalFilename = originalFilename;
            this.sender = sender;
            this.uiReadyLatch = new CountDownLatch(1); // Initialize latch to 1, meaning 1 event needs to happen
        }

        public void close() throws IOException {
            if (fos != null) {
                fos.flush(); // Ensure all buffered data is written
                fos.close();
                System.out.println("Client: FileOutputStream for " + outFile.getName() + " flushed and closed.");
            }
        }
    }


    public ModernChatClientGUI() {
        initializeUserName();

        setTitle("Modern Chat Client - " + userName);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(480, 680);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(0, 0));

        // Top bar
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBorder(new EmptyBorder(10, 10, 10, 10));
        JLabel titleLabel = new JLabel("Chat Client");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        themeToggleButton = new JButton("☀️");
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

        JPanel containerPanel = new JPanel(new BorderLayout());
        containerPanel.setBackground(lightBg);
        containerPanel.add(chatPanel, BorderLayout.NORTH);

        scrollPane = new JScrollPane(containerPanel);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);

        JScrollBar verticalScrollBar = scrollPane.getVerticalScrollBar();
        verticalScrollBar.setUnitIncrement(16);
        verticalScrollBar.setPreferredSize(new Dimension(8, 0));
        verticalScrollBar.setUI(new ModernScrollBarUI());
        verticalScrollBar.setBackground(new Color(245, 245, 245));

        add(scrollPane, BorderLayout.CENTER);

        // Input panel
        JPanel inputPanel = new JPanel(new BorderLayout(10, 10));
        inputPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel inputWithAttachmentPanel = new JPanel(new BorderLayout(5, 0));

        JButton attachButton = new JButton("📎");
        attachButton.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 18));
        attachButton.setFocusPainted(false);
        attachButton.setBorderPainted(false);
        attachButton.setContentAreaFilled(false);
        attachButton.setPreferredSize(new Dimension(40, 40));
        attachButton.addActionListener(e -> showFileChooser());

        inputField = new JTextArea(2, 20);
        inputField.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        inputField.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        inputField.setLineWrap(true);
        inputField.setWrapStyleWord(true);

        JScrollPane inputScrollPane = new JScrollPane(inputField);
        inputScrollPane.setBorder(BorderFactory.createEmptyBorder());
        inputScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

        inputField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    if (e.isShiftDown()) {
                        inputField.append("\n");
                    } else {
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
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                sendQuitMessage();
                closeEverything();
            }
        });


        // Ensure download dir exists and is writable
        File downloadDir = new File(DOWNLOAD_DIR);
        if (!downloadDir.exists()) {
            if (downloadDir.mkdirs()) {
                System.out.println("Client: Created download directory: " + DOWNLOAD_DIR);
            } else {
                System.err.println("Client: Failed to create download directory: " + DOWNLOAD_DIR);
                JOptionPane.showMessageDialog(this, "Failed to create download directory. File downloads may fail.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        } else if (!downloadDir.isDirectory()) {
            System.err.println("Client: Download path exists but is not a directory: " + DOWNLOAD_DIR);
            JOptionPane.showMessageDialog(this, "Download path exists but is not a directory. File downloads may fail.", "Error", JOptionPane.ERROR_MESSAGE);
        } else if (!downloadDir.canWrite()) {
            System.err.println("Client: Download directory is not writable: " + DOWNLOAD_DIR);
            JOptionPane.showMessageDialog(this, "Download directory is not writable. File downloads may fail.", "Error", JOptionPane.ERROR_MESSAGE);
        }

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
            din = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            dout = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            // Send HELLO frame
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream tmp = new DataOutputStream(baos);
            tmp.writeUTF("HELLO");
            tmp.writeUTF(userName);
            tmp.flush();
            byte[] helloFrame = baos.toByteArray();

            synchronized (dout) {
                dout.writeInt(helloFrame.length); // Prefix with length
                dout.write(helloFrame);
                dout.flush();
            }
            System.out.println("Client: Sent HELLO frame with username: " + userName);

            // Start reader loop
            startReaderLoop();

        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Unable to connect to server: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
            System.exit(1); // Exit if cannot connect
        }
    }

    private void startReaderLoop() {
        executor.submit(() -> {
            try {
                while (!socket.isClosed()) {
                    int framedLen;
                    try {
                        framedLen = din.readInt(); // Read the length of the incoming frame
                        System.out.println("Client: Reader - Read framed message length: " + framedLen);
                    } catch (EOFException eof) {
                        System.out.println("Client: Reader - Server disconnected (EOF).");
                        break;
                    }

                    byte[] framed = new byte[framedLen];
                    din.readFully(framed); // Read the actual framed message bytes

                    // Process the framed bytes in separate thread to avoid blocking reader
                    frameProcessor.submit(() -> {
                        try (DataInputStream frameIn = new DataInputStream(new ByteArrayInputStream(framed))) {
                            String ftype = frameIn.readUTF();
                            System.out.println("Client: Frame Processor - Received frame type: " + ftype);

                            switch (ftype) {
                                case "MSG": {
                                    String sender = frameIn.readUTF();
                                    String text = frameIn.readUTF();
                                    SwingUtilities.invokeLater(() -> addMessageBubble(sender + ": " + text, false));
                                    System.out.println("Client: Frame Processor - Displayed MSG from " + sender);
                                    break;
                                }
                                case "JOIN": {
                                    String who = frameIn.readUTF();
                                    SwingUtilities.invokeLater(() -> addSystemBubble(who + " joined the chat"));
                                    System.out.println("Client: Frame Processor - Displayed JOIN message for " + who);
                                    break;
                                }
                                case "LEFT": {
                                    String who = frameIn.readUTF();
                                    SwingUtilities.invokeLater(() -> addSystemBubble(who + " left the chat"));
                                    System.out.println("Client: Frame Processor - Displayed LEFT message for " + who);
                                    break;
                                }
                                case "FILE": {
                                    String sender = frameIn.readUTF();
                                    String filename = frameIn.readUTF();
                                    long filesize = frameIn.readLong();
                                    String downloadKey = sender + "_" + filename; // Unique key for this download

                                    File outFile = new File(DOWNLOAD_DIR + System.currentTimeMillis() + "_" + filename);
                                    System.out.println("Client: Frame Processor - Starting download for " + downloadKey + ", size: " + filesize + ", saving to: " + outFile.getAbsolutePath());

                                    FileTransferState newState = null; // Declare here for broader scope
                                    try {
                                        // 1. Create FileOutputStream and FileTransferState immediately (NOT on EDT)
                                        FileOutputStream fos = new FileOutputStream(outFile);
                                        newState = new FileTransferState(fos, outFile, filesize, null, filename, sender); // bubblePanelRef is null initially
                                        activeFileDownloads.put(downloadKey, newState); // Add to map immediately
                                        System.out.println("Client: Frame Processor - Initialized download state in map for " + downloadKey + " (FOS ready, Latch ready).");

                                        // Capture newState in a final variable for use in lambda
                                        final FileTransferState finalNewState = newState;

                                        // 2. Schedule UI creation on EDT
                                        SwingUtilities.invokeLater(() -> {
                                            JPanel bubblePanel = createWhatsAppFileBubble(sender, filename, filesize, false, outFile);
                                            chatPanel.add(bubblePanel);
                                            chatPanel.add(Box.createVerticalStrut(6));
                                            chatPanel.revalidate();
                                            scrollToBottom();
                                            System.out.println("Client: UI Thread - Created UI for download for " + downloadKey + " at " + outFile.getAbsolutePath());

                                            // 3. Update the FileTransferState in the map with the actual UI bubble reference
                                            // We use finalNewState because it's guaranteed to be the correct object created just moments ago
                                            if (finalNewState != null) { // Defensive check, should not be null
                                                finalNewState.bubblePanelRef = bubblePanel; // <--- Set the UI reference
                                                System.out.println("Client: UI Thread - Updated state with bubblePanelRef for " + downloadKey);
                                            } else {
                                                System.err.println("Client: UI Thread - CRITICAL ERROR: finalNewState is null during UI creation for " + downloadKey + ". This should never happen if logic is correct.");
                                            }
                                            // 4. Signal that UI is ready
                                            finalNewState.uiReadyLatch.countDown(); // Release the worker thread
                                            System.out.println("Client: UI Thread - Latch countDown() for " + downloadKey);
                                        });

                                        // 5. Block the current worker thread until UI is ready
                                        try {
                                            // Wait for the UI creation on the EDT to complete and set bubblePanelRef
                                            if (newState != null) { // Defensive check
                                                newState.uiReadyLatch.await();
                                                System.out.println("Client: Frame Processor - Latch AWAIT released for " + downloadKey + ". UI is ready.");
                                            }
                                        } catch (InterruptedException ie) {
                                            Thread.currentThread().interrupt();
                                            System.err.println("Client: Frame Processor - Interrupted while waiting for UI readiness for " + downloadKey + ". Aborting download.");
                                            // Clean up if interrupted before UI is ready
                                            if (newState != null && newState.fos != null) {
                                                try { newState.close(); } catch (IOException ignored) {}
                                            }
                                            activeFileDownloads.remove(downloadKey);
                                            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "File download for " + filename + " interrupted due to UI initialization failure.", "Error", JOptionPane.ERROR_MESSAGE));
                                            break; // Exit this file processing
                                        }

                                    } catch (IOException e) { // Catch IOException for FOS creation
                                        System.err.println("Client: Frame Processor - Error preparing file for download: " + outFile.getAbsolutePath() + ": " + e.getMessage());
                                        e.printStackTrace();
                                        SwingUtilities.invokeLater(() -> {
                                            JOptionPane.showMessageDialog(this, "Failed to prepare file for download: " + filename + "\n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                                        });
                                        activeFileDownloads.remove(downloadKey); // Clean up state if FOS failed
                                        if (newState != null) newState.uiReadyLatch.countDown(); // Ensure latch is counted down even on error to prevent blocking
                                        break; // Exit this file processing as FOS failed
                                    }
                                    break; // Break after FILE frame is handled
                                }
                                case "FCHUNK": {
                                    String chunkSender = frameIn.readUTF();
                                    String chunkFilename = frameIn.readUTF();
                                    String downloadKey = chunkSender + "_" + chunkFilename;
                                    System.out.println("Client: Frame Processor - Received FCHUNK for " + downloadKey);

                                    int chunkLen = frameIn.readInt();
                                    byte[] chunk = new byte[chunkLen];
                                    frameIn.readFully(chunk);
                                    System.out.println("Client: Frame Processor - FCHUNK data read, length: " + chunkLen);

                                    FileTransferState state = activeFileDownloads.get(downloadKey);

                                    if (state != null) {
                                        // Ensure the UI is ready before attempting to update it, though the latch should cover this.
                                        if (state.uiReadyLatch.getCount() > 0) {
                                            System.out.println("Client: Frame Processor - Waiting for UI to be ready for FCHUNK for " + downloadKey);
                                            try { state.uiReadyLatch.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                                        }

                                        if (state.fos != null) {
                                            try {
                                                state.fos.write(chunk);
                                                state.fos.flush(); // Explicitly flush after each write for robustness
                                                state.receivedSize += chunkLen;
                                                final int prog = (int) ((state.receivedSize * 100) / state.totalSize);

                                                if (state.bubblePanelRef != null) { // Should be non-null now due to latch
                                                    SwingUtilities.invokeLater(() -> {
                                                        JPanel bubbleInner = (JPanel) state.bubblePanelRef.getComponent(0);
                                                        JProgressBar pb = (JProgressBar) bubbleInner.getClientProperty("progress");
                                                        if (pb != null) pb.setValue(prog);
                                                        System.out.println("Client: UI Thread - Updated progress for " + downloadKey + ": " + prog + "% (Received: " + state.receivedSize + "/" + state.totalSize + ")");
                                                    });
                                                } else {
                                                    System.err.println("Client: Frame Processor - FCHUNK for " + downloadKey + " received, but bubblePanelRef is STILL null. UI update skipped.");
                                                }
                                            } catch (IOException e) {
                                                System.err.println("Client: Frame Processor - Error writing chunk to file " + state.outFile.getAbsolutePath() + ": " + e.getMessage());
                                                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error writing file " + state.originalFilename + ": " + e.getMessage(), "File Error", JOptionPane.ERROR_MESSAGE));
                                                try { state.close(); } catch (IOException ignored) {}
                                                activeFileDownloads.remove(downloadKey);
                                            }
                                        } else {
                                            System.err.println("Client: Frame Processor - FileOutputStream is null for " + downloadKey + ". Cannot write chunk.");
                                            activeFileDownloads.remove(downloadKey);
                                        }
                                    } else {
                                        System.err.println("Client: Frame Processor - FCHUNK for " + downloadKey + " - state is NULL! (Chunk data of " + chunkLen + " bytes was consumed). Active downloads: " + activeFileDownloads.keySet());
                                    }
                                    break;
                                }
                                case "FILEEND": {
                                    String endSender = frameIn.readUTF();
                                    String endFilename = frameIn.readUTF();
                                    String downloadKey = endSender + "_" + endFilename;
                                    System.out.println("Client: Frame Processor - Received FILEEND for " + downloadKey);

                                    FileTransferState state = activeFileDownloads.remove(downloadKey);

                                    if (state != null) {
                                        // Ensure the UI is ready before attempting to update it.
                                        if (state.uiReadyLatch.getCount() > 0) {
                                            System.out.println("Client: Frame Processor - Waiting for UI to be ready for FILEEND for " + downloadKey);
                                            try { state.uiReadyLatch.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                                        }

                                        try {
                                            state.close();
                                            System.out.println("Client: Frame Processor - File download complete: " + state.outFile.getAbsolutePath() + ". Final size: " + state.outFile.length() + " bytes.");
                                            if (state.bubblePanelRef != null) { // Should be non-null now due to latch
                                                SwingUtilities.invokeLater(() -> {
                                                    JPanel bubbleInner = (JPanel) state.bubblePanelRef.getComponent(0);
                                                    JProgressBar pb = (JProgressBar) bubbleInner.getClientProperty("progress");
                                                    JButton openBtn = (JButton) bubbleInner.getClientProperty("openBtn");
                                                    if (pb != null) pb.setValue(100);
                                                    if (openBtn != null) openBtn.setEnabled(true);
                                                    System.out.println("Client: UI Thread - UI finalized for " + downloadKey);
                                                });
                                            } else {
                                                System.err.println("Client: Frame Processor - FILEEND for " + downloadKey + " received, but bubblePanelRef is STILL null. UI finalization skipped.");
                                            }
                                        } catch (IOException e) {
                                            System.err.println("Client: Frame Processor - Error closing file output stream for " + state.outFile.getAbsolutePath() + ": " + e.getMessage());
                                            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error finalizing file " + state.originalFilename + ": " + e.getMessage(), "File Error", JOptionPane.ERROR_MESSAGE));
                                        }
                                    } else {
                                        System.err.println("Client: Frame Processor - FILEEND for " + downloadKey + " - state is NULL! Active downloads: " + activeFileDownloads.keySet());
                                    }
                                    break;
                                }
                                default:
                                    System.err.println("Client: Frame Processor - Unknown frame type received on client: " + ftype);
                            }
                        } catch (IOException ex) {
                            System.err.println("Client: Frame Processor - Error processing received frame: " + ex.getMessage());
                            ex.printStackTrace();
                        }
                    });
                }
            } catch (IOException ex) {
                if (!socket.isClosed()) {
                    System.err.println("Client: Reader - Reader loop ended due to I/O error: " + ex.getMessage());
                }
            } finally {
                closeEverything();
            }
        });
    }

    private void sendMessage() {
        String message = inputField.getText().trim();
        if (!message.isEmpty()) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream tmp = new DataOutputStream(baos);
                tmp.writeUTF("MSG");
                tmp.writeUTF(userName);
                tmp.writeUTF(message);
                tmp.flush();
                byte[] frame = baos.toByteArray();

                synchronized (dout) { // Synchronize on dout for all writes
                    dout.writeInt(frame.length); // Prefix with length
                    dout.write(frame);
                    dout.flush();
                }

                addMessageBubble(userName + ": " + message, true);
                inputField.setText("");
                System.out.println("Client: Sent MSG: " + message);
            } catch (IOException e) {
                e.printStackTrace();
                addMessageBubble("Failed to send message: " + e.getMessage(), false);
                System.err.println("Client: Failed to send MSG: " + e.getMessage());
            }
        }
    }

    private void sendQuitMessage() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream tmp = new DataOutputStream(baos);
            tmp.writeUTF("QUIT");
            tmp.flush();
            byte[] frame = baos.toByteArray();

            synchronized (dout) {
                if (socket != null && !socket.isClosed()) { // Only send if socket is active
                    dout.writeInt(frame.length);
                    dout.write(frame);
                    dout.flush();
                    System.out.println("Client: Sent QUIT message.");
                }
            }
        } catch (IOException e) {
            System.err.println("Client: Failed to send QUIT message: " + e.getMessage());
        }
    }


    private void showFileChooser() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            sendFile(file);
        }
    }

    private void sendFile(File file) {
        executor.submit(() -> {
            if (socket == null || socket.isClosed() || !socket.isConnected()) {
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(this, "Connection is not active. Cannot send file.", "Error", JOptionPane.ERROR_MESSAGE)
                );
                return;
            }
            if (!file.exists() || !file.isFile()) {
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(this, "Selected file does not exist or is not a regular file.", "Error", JOptionPane.ERROR_MESSAGE)
                );
                return;
            }


            // Create UI bubble first, get reference to it
            final JPanel senderBubbleOuterPanel = createWhatsAppFileBubble("You", file.getName(), file.length(), true, file);
            SwingUtilities.invokeLater(() -> {
                chatPanel.add(senderBubbleOuterPanel);
                chatPanel.add(Box.createVerticalStrut(6));
                chatPanel.revalidate();
                scrollToBottom();
            });

            activeFileUploads.put(file.getAbsolutePath(), new FileTransferState(null, file, file.length(), senderBubbleOuterPanel, file.getName(), userName));
            System.out.println("Client: Sender - Initialized upload state for " + file.getName());

            try (FileInputStream fis = new FileInputStream(file)) {
                // ---- 1️⃣ Build and send FILE header ----
                ByteArrayOutputStream headerBaos = new ByteArrayOutputStream();
                try (DataOutputStream headerOut = new DataOutputStream(headerBaos)) {
                    headerOut.writeUTF("FILE");
                    headerOut.writeUTF(userName);
                    headerOut.writeUTF(file.getName());
                    headerOut.writeLong(file.length());
                    headerOut.flush();
                }

                byte[] headerFrame = headerBaos.toByteArray();
                synchronized (dout) {
                    dout.writeInt(headerFrame.length);
                    dout.write(headerFrame);
                    dout.flush();
                }
                System.out.println("Client: Sender - Sent FILE header for " + file.getName());


                // ---- 2️⃣ Stream file in chunks ----
                byte[] buffer = new byte[CHUNK_SIZE];
                long totalRead = 0;
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    if (socket.isClosed()) {
                        System.out.println("Client: Sender - Socket closed during file send, aborting.");
                        break;
                    }

                    ByteArrayOutputStream chunkBaos = new ByteArrayOutputStream();
                    try (DataOutputStream chunkOut = new DataOutputStream(chunkBaos)) {
                        chunkOut.writeUTF("FCHUNK");
                        chunkOut.writeUTF(userName); // Include sender
                        chunkOut.writeUTF(file.getName()); // Include filename
                        chunkOut.writeInt(read);
                        chunkOut.write(buffer, 0, read);
                        chunkOut.flush();
                    }

                    byte[] chunkFrame = chunkBaos.toByteArray();
                    synchronized (dout) {
                        dout.writeInt(chunkFrame.length);
                        dout.write(chunkFrame);
                        dout.flush();
                    }

                    totalRead += read;
                    final int progress = (int) ((totalRead * 100) / file.length());
                    SwingUtilities.invokeLater(() ->
                        updateFileProgressForSender(file, progress)
                    );
                    System.out.println("Client: Sender - Sent FCHUNK for " + file.getName() + ", " + read + " bytes. Progress: " + progress + "%");
                }
                System.out.println("Client: Sender - Finished sending file chunks for " + file.getName());


                // ---- 3️⃣ Send FILEEND frame ----
                ByteArrayOutputStream endBaos = new ByteArrayOutputStream();
                try (DataOutputStream endOut = new DataOutputStream(endBaos)) {
                    endOut.writeUTF("FILEEND");
                    endOut.writeUTF(userName);
                    endOut.writeUTF(file.getName());
                    endOut.flush();
                }

                byte[] endFrame = endBaos.toByteArray();
                synchronized (dout) {
                    dout.writeInt(endFrame.length);
                    dout.write(endFrame);
                    dout.flush();
                }
                System.out.println("Client: Sender - Sent FILEEND for " + file.getName());


                // ---- 4️⃣ Mark as completed ----
                SwingUtilities.invokeLater(() ->
                    finalizeFileSend(file, file.getName())
                );

            } catch (IOException e) {
                System.err.println("Client: Sender - Failed to send file " + file.getName() + ": " + e.getMessage());
                e.printStackTrace();
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(this, "Failed to send file " + file.getName() + ": " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE)
                );
                activeFileUploads.remove(file.getAbsolutePath());
            }
        });
    }


    // UI helpers

    private void addMessageBubble(String message, boolean isSent) {
        BubblePanel bubble = new BubblePanel(message, isSent ? (darkTheme ? sentBlueDark : sentBlue) : (darkTheme ? darkBubble : lightBubble), isSent);
        chatPanel.add(bubble);
        chatPanel.add(Box.createVerticalStrut(6));
        chatPanel.revalidate();
        SwingUtilities.invokeLater(this::scrollToBottom);
    }

    private void addSystemBubble(String message) {
        JLabel lbl = new JLabel(message);
        lbl.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        lbl.setForeground(Color.GRAY);
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.add(lbl);
        chatPanel.add(p);
        chatPanel.add(Box.createVerticalStrut(6));
        chatPanel.revalidate();
        SwingUtilities.invokeLater(this::scrollToBottom);
    }

    private void scrollToBottom() {
        JScrollBar vertical = scrollPane.getVerticalScrollBar();
        vertical.setValue(vertical.getMaximum());
    }

    // Creates the file receiving/sending bubble UI and returns the outer JPanel
    private JPanel createWhatsAppFileBubble(String sender, String filename, long filesize, boolean isSent, File fileRef) {
        JPanel outer = new JPanel();
        outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));
        outer.setOpaque(false);
        outer.setAlignmentX(isSent ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);

        JPanel bubble = new JPanel(new BorderLayout(8, 8)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(isSent ? (darkTheme ? sentBlueDark : sentBlue) : (darkTheme ? darkBubble : lightBubble));
                int arc = 16;
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                super.paintComponent(g);
            }
        };
        bubble.setOpaque(false);
        bubble.setBorder(new EmptyBorder(8, 8, 8, 8));
        bubble.setMaximumSize(new Dimension(360, 200));
        bubble.setAlignmentX(Component.LEFT_ALIGNMENT); // Keep bubble itself aligned left within its parent outer panel

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        JLabel icon = new JLabel("📄");
        icon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 26));
        left.add(icon);

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        JLabel nameLabel = new JLabel(filename);
        nameLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        nameLabel.setForeground(isSent ? Color.WHITE : Color.BLACK);
        JLabel sizeLabel = new JLabel(readableFileSize(filesize));
        sizeLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        sizeLabel.setForeground(isSent ? new Color(200, 200, 200) : Color.DARK_GRAY); // Lighter gray for dark theme sent bubbles
        center.add(nameLabel);
        center.add(sizeLabel);

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setValue(0);
        progressBar.setPreferredSize(new Dimension(120, 16));
        progressBar.setStringPainted(true);
        progressBar.setForeground(isSent ? Color.WHITE : new Color(0, 120, 215));
        progressBar.setBackground(new Color(230, 230, 230, 50));
        JButton openBtn = new JButton("Open");
        openBtn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        openBtn.setEnabled(false); // Enabled only after completion
        openBtn.setBorderPainted(false);
        openBtn.setContentAreaFilled(false);
        openBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        openBtn.setForeground(isSent ? Color.WHITE : Color.BLUE); // Set button color based on theme
        openBtn.addActionListener(e -> {
            try {
                // Ensure fileRef points to the final saved file path
                if (fileRef != null && fileRef.exists()) {
                    Desktop.getDesktop().open(fileRef);
                } else {
                    JOptionPane.showMessageDialog(this, "File not found: " + (fileRef != null ? fileRef.getAbsolutePath() : "null"), "Error", JOptionPane.ERROR_MESSAGE);
                }
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Cannot open file: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        right.add(progressBar);
        right.add(Box.createVerticalStrut(6));
        right.add(openBtn);

        bubble.add(left, BorderLayout.WEST);
        bubble.add(center, BorderLayout.CENTER);
        bubble.add(right, BorderLayout.EAST);

        // small label for sender below bubble
        JLabel fromLabel = new JLabel(sender);
        fromLabel.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        fromLabel.setForeground(Color.GRAY);

        // Store components directly on the 'bubble' JPanel using Client Properties for easy retrieval
        bubble.putClientProperty("progress", progressBar);
        bubble.putClientProperty("openBtn", openBtn);
        bubble.putClientProperty("fileRef", fileRef); // This fileRef is the temporary local file for receiving, or original for sending
                                                       // It's crucial for the openBtn handler.

        outer.add(bubble);
        outer.add(fromLabel);
        return outer;
    }

    // Update progress for sender's file bubble
    private void updateFileProgressForSender(File fileRef, int percent) {
        FileTransferState state = activeFileUploads.get(fileRef.getAbsolutePath());
        if (state != null && state.bubblePanelRef != null) {
            JPanel bubbleInner = (JPanel) state.bubblePanelRef.getComponent(0); // The inner JPanel that holds the properties
            JProgressBar pb = (JProgressBar) bubbleInner.getClientProperty("progress");
            if (pb != null) pb.setValue(percent);
            System.out.println("Client: Sender's UI for " + state.originalFilename + " updated to " + percent + "%");
        }
    }

    // Finalize UI for sent file
    private void finalizeFileSend(File tmpLocal, String originalName) {
        FileTransferState state = activeFileUploads.remove(tmpLocal.getAbsolutePath()); // Remove from map
        if (state != null && state.bubblePanelRef != null) {
            JPanel bubbleInner = (JPanel) state.bubblePanelRef.getComponent(0);
            JProgressBar pb = (JProgressBar) bubbleInner.getClientProperty("progress");
            JButton openBtn = (JButton) bubbleInner.getClientProperty("openBtn");
            if (pb != null) pb.setValue(100);
            if (openBtn != null) openBtn.setEnabled(true);
            System.out.println("Client: Sender's UI for " + originalName + " finalized.");
        }
    }

    private static String readableFileSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    private void applyTheme() {
        Color bg = darkTheme ? darkBg : lightBg;
        Color inputBg = darkTheme ? new Color(50, 54, 62) : Color.WHITE;
        Color textColor = darkTheme ? Color.WHITE : Color.BLACK;
        Color scrollThumbColor = darkTheme ? new Color(100, 100, 100) : new Color(200, 200, 200);
        Color scrollTrackColor = darkTheme ? new Color(60, 63, 69) : new Color(245, 245, 245);

        chatPanel.setBackground(bg);
        getContentPane().setBackground(bg);

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

        Container containerPanel = chatPanel.getParent();
        if (containerPanel != null) {
            containerPanel.setBackground(bg);
        }

        inputField.setBackground(inputBg);
        inputField.setForeground(textColor);
        inputField.setCaretColor(textColor);

        sendButton.setBackground(darkTheme ? sentBlueDark : sentBlue);
        sendButton.setForeground(Color.WHITE);
    }

    private void toggleTheme() {
        darkTheme = !darkTheme;
        themeToggleButton.setText(darkTheme ? "🌙" : "☀️");
        themeToggleButton.setForeground(darkTheme ? Color.WHITE : Color.BLACK);
        applyTheme();
    }

    private void closeEverything() {
        frameProcessor.shutdownNow();
        executor.shutdownNow(); // Stop all tasks
        // Close any ongoing file streams
        for (FileTransferState state : activeFileDownloads.values()) {
            try {
                state.close();
            } catch (IOException ignored) {}
        }
        activeFileDownloads.clear();
        activeFileUploads.clear(); // Clear any upload states

        try {
            if (din != null) din.close();
        } catch (IOException ignored) {}
        try {
            if (dout != null) dout.close();
        } catch (IOException ignored) {}
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
        System.out.println("Client: All resources closed.");
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(ModernChatClientGUI::new);
    }

    // Custom ScrollBarUI for modern look
    static class ModernScrollBarUI extends BasicScrollBarUI {
        private final int THUMB_SIZE = 8;
        protected Color thumbColor = new Color(200,200,200);
        protected Color trackColor = new Color(245,245,245);

        @Override
        protected void configureScrollBarColors() {
            this.thumbColor = new Color(200, 200, 200);
            this.trackColor = new Color(245, 245, 245);
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

    // Minimal BubblePanel for text messages
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
                messageLabel = new JLabel("<html><body style='width: 300px;'>" + text + "</body></html>");
                messageLabel.setFont(new Font("Segoe UI", Font.PLAIN, 14));
                messageLabel.setForeground(isSent ? Color.WHITE : Color.BLACK);
                messageLabel.setBorder(new EmptyBorder(10, 15, 10, 15));
                add(messageLabel, BorderLayout.CENTER);
            } else {
                messageLabel = null;
            }

            setMaximumSize(new Dimension(360, Integer.MAX_VALUE));
            setBorder(new EmptyBorder(5, isSent ? 50 : 5, 5, isSent ? 5 : 50));
            setAlignmentX(isSent ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);
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
}