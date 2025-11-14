import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService; // For thread pool
import java.util.concurrent.Executors; // For thread pool

public class Server {
    private ServerSocket serverSocket;
    private final List<ClientHandler> handlers = new ArrayList<>();
    private final ExecutorService clientPool = Executors.newCachedThreadPool(); // Use a thread pool for client handlers

    public Server(int port) throws IOException {
        serverSocket = new ServerSocket(port);
        System.out.println("Server started on port " + port);
    }

    public void start() {
        try {
            while (!serverSocket.isClosed()) {
                System.out.println("Server: Waiting for clients...");
                Socket clientSocket = serverSocket.accept();
                System.out.println("Server: New client connected: " + clientSocket.getRemoteSocketAddress());
                ClientHandler handler = new ClientHandler(clientSocket, this);
                synchronized (handlers) {
                    handlers.add(handler);
                }
                clientPool.submit(handler); // Submit to thread pool
            }
        } catch (IOException e) {
            System.err.println("Server: Server stopped accepting connections: " + e.getMessage());
        } finally {
            shutdown();
        }
    }

    // Forward framed message to all clients except the origin
    public void forwardToAll(byte[] framedMessage, ClientHandler origin) {
        synchronized (handlers) {
            Iterator<ClientHandler> it = handlers.iterator();
            while (it.hasNext()) {
                ClientHandler h = it.next();
                if (h == origin) continue;
                try {
                    h.sendRaw(framedMessage);
                } catch (IOException e) {
                    System.err.println("Server: Failed to send to client " + (h.getUserName() != null ? h.getUserName() : "Unknown") + ", removing: " + e.getMessage());
                    h.safeClose();  // safer close
                    it.remove();
                }
            }
        }
    }

    public void removeHandler(ClientHandler h) {
        synchronized (handlers) {
            boolean removed = handlers.remove(h);
            if(removed) {
                System.out.println("Server: Removed client handler for: " + h.getUserName());
                try {
                    // Only broadcast "LEFT" if the handler was successfully removed and had a username.
                    if (h.getUserName() != null) {
                        byte[] framed = ClientHandler.buildUserLeftFrame(h.getUserName());
                        forwardToAll(framed, null);
                    }
                } catch (IOException ignored) {} // Ignore if building frame fails during shutdown or critical error
            }
        }
    }

    public void shutdown() {
        clientPool.shutdownNow(); // Attempt to stop all client handlers
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            System.err.println("Server: Error closing server socket: " + e.getMessage());
        }
        System.out.println("Server: Server shut down.");
    }

    public static void main(String[] args) throws IOException {
        Server server = new Server(8000);
        server.start();
    }
}

class ClientHandler implements Runnable {
    private final Socket socket;
    private final Server server;
    private DataInputStream din; // Input stream from this client
    private DataOutputStream dout; // Output stream to this client
    private String userName;

    private static final int BUFFER_SIZE = 8192;
    private volatile boolean running = true;

    public ClientHandler(Socket socket, Server server) {
        this.socket = socket;
        this.server = server;
        try {
            this.din = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.dout = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            // First frame expected: a "HELLO" with username, using the new framed message reading
            byte[] helloFrame = readFramedMessage();
            try (DataInputStream frameIn = new DataInputStream(new ByteArrayInputStream(helloFrame))) {
                String type = frameIn.readUTF();
                if (!"HELLO".equals(type)) throw new IOException("Expected HELLO, got " + type);
                this.userName = frameIn.readUTF();
            }
            System.out.println("Server: Client " + userName + " from " + socket.getRemoteSocketAddress() + " joined and sent HELLO.");

            byte[] joinFrame = buildUserJoinFrame(userName);
            server.forwardToAll(joinFrame, this);
        } catch (IOException e) {
            System.err.println("Server: Error setting up client handler for " + socket.getRemoteSocketAddress() + ": " + e.getMessage());
            safeClose(); // Close if setup fails
        }
    }

    public String getUserName() {
        return userName;
    }

    // Helper method to read a length-prefixed framed message from the client
    private byte[] readFramedMessage() throws IOException {
        int length = din.readInt(); // Read the length of the incoming frame
        if (length < 0) { // Check for invalid length
            throw new IOException("Invalid frame length received: " + length);
        }
        byte[] framedMessageBytes = new byte[length];
        din.readFully(framedMessageBytes); // Read the actual framed message bytes
        return framedMessageBytes;
    }

    @Override
    public void run() {
        try {
            while (running && !socket.isClosed()) {
                byte[] incomingFrame = readFramedMessage(); // Always read a framed message
                System.out.println("Server: Handler for " + userName + " read incoming frame length: " + incomingFrame.length);

                try (DataInputStream frameIn = new DataInputStream(new ByteArrayInputStream(incomingFrame))) {
                    String type = frameIn.readUTF(); // Read the type from the extracted frame
                    System.out.println("Server: Handler for " + userName + " received client frame type: " + type);

                    if ("MSG".equals(type)) {
                        String sender = frameIn.readUTF();
                        String text = frameIn.readUTF();

                        // Reconstruct the inner frame to forward to other clients
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        DataOutputStream tmpOut = new DataOutputStream(baos);
                        tmpOut.writeUTF("MSG");
                        tmpOut.writeUTF(sender);
                        tmpOut.writeUTF(text);
                        tmpOut.flush();
                        server.forwardToAll(baos.toByteArray(), this);
                        System.out.println("Server: Forwarded MSG from " + userName);

                    } else if ("FILE".equals(type)) {
                        handleFileTransfer(frameIn); // Pass the DataInputStream of the current header frame

                    } else if ("QUIT".equals(type)) {
                        System.out.println("Server: Client " + userName + " sent QUIT command.");
                        safeClose(); // Client explicitly requested to quit
                        break;

                    } else {
                        System.err.println("Server: Unknown frame type from " + userName + ": " + type);
                    }
                } // frameIn is closed here
            }
        } catch (EOFException eof) {
            System.out.println("Server: Client " + userName + " disconnected gracefully.");
        } catch (IOException e) {
            if (running) { // Only print error if not initiated by safeClose
                System.err.println("Server: I/O error for " + userName + ": " + e.getMessage());
            }
        } finally {
            safeClose();
        }
    }

    // Handles the FILE header and then sequentially reads FCHUNKs and FILEEND from the client
    private void handleFileTransfer(DataInputStream headerFrameIn) throws IOException {
        String sender = headerFrameIn.readUTF();
        String filename = headerFrameIn.readUTF();
        long filesize = headerFrameIn.readLong();

        System.out.println("Server: " + userName + " initiating file transfer: " + filename + " (" + filesize + " bytes)");

        // Reconstruct the original FILE header frame content to forward
        ByteArrayOutputStream headerBaos = new ByteArrayOutputStream();
        DataOutputStream tmpHeaderOut = new DataOutputStream(headerBaos);
        tmpHeaderOut.writeUTF("FILE");
        tmpHeaderOut.writeUTF(sender);
        tmpHeaderOut.writeUTF(filename);
        tmpHeaderOut.writeLong(filesize);
        tmpHeaderOut.flush();
        server.forwardToAll(headerBaos.toByteArray(), this); // Forward the *inner* header frame
        System.out.println("Server: Forwarded FILE header for " + filename + " from " + userName + " to all others.");


        // File chunks: now read subsequent FCHUNK frames sent by the client
        long remaining = filesize;
        while (remaining > 0 && running && !socket.isClosed()) {
            byte[] chunkFullFrame = readFramedMessage(); // Read the length-prefixed FCHUNK frame from client
            try (DataInputStream chunkFrameIn = new DataInputStream(new ByteArrayInputStream(chunkFullFrame))) {
                String chunkType = chunkFrameIn.readUTF();
                if (!"FCHUNK".equals(chunkType)) {
                    System.err.println("Server: Protocol violation from " + userName + ": Expected FCHUNK, but received " + chunkType + " during file transfer of " + filename + ". Aborting transfer.");
                    break;
                }
                // Read the sender and filename from chunk frame (added for client-side identification)
                String chunkSender = chunkFrameIn.readUTF();
                String chunkFilename = chunkFrameIn.readUTF();
                int chunkLen = chunkFrameIn.readInt();
                byte[] chunkData = new byte[chunkLen];
                chunkFrameIn.readFully(chunkData); // Actual file data chunk

                // Now, reconstruct the *inner* FCHUNK frame to forward to other clients
                ByteArrayOutputStream chunkBaos = new ByteArrayOutputStream();
                DataOutputStream tmpChunkOut = new DataOutputStream(chunkBaos);
                tmpChunkOut.writeUTF("FCHUNK");
                tmpChunkOut.writeUTF(chunkSender); // Include sender
                tmpChunkOut.writeUTF(chunkFilename); // Include filename
                tmpChunkOut.writeInt(chunkData.length);
                tmpChunkOut.write(chunkData);
                tmpChunkOut.flush();
                server.forwardToAll(chunkBaos.toByteArray(), this);

                remaining -= chunkData.length;
                System.out.println("Server: Forwarding FCHUNK for " + filename + " (from " + chunkSender + "), size " + chunkData.length + ", remaining " + remaining + ".");
            }
        }

        // After chunks, expect FILEEND frame from the client
        if (running && !socket.isClosed()) {
            byte[] fileEndFrame = readFramedMessage(); // Read the length-prefixed FILEEND frame from client
            try (DataInputStream fileEndFrameIn = new DataInputStream(new ByteArrayInputStream(fileEndFrame))) {
                String endType = fileEndFrameIn.readUTF();
                if (!"FILEEND".equals(endType)) {
                     System.err.println("Server: Protocol violation from " + userName + ": Expected FILEEND, but received " + endType + " after file chunks for " + filename + ". Aborting finalization.");
                } else {
                    // Read sender and filename from FILEEND frame
                    String endSender = fileEndFrameIn.readUTF();
                    String endFilename = fileEndFrameIn.readUTF();

                    // Reconstruct the *inner* FILEEND frame to forward
                    ByteArrayOutputStream endBaos = new ByteArrayOutputStream();
                    DataOutputStream tmpEndOut = new DataOutputStream(endBaos);
                    tmpEndOut.writeUTF("FILEEND");
                    tmpEndOut.writeUTF(endSender); // Include sender
                    tmpEndOut.writeUTF(endFilename); // Include filename
                    tmpEndOut.flush();
                    server.forwardToAll(endBaos.toByteArray(), this);
                    System.out.println("Server: Forwarded FILEEND for " + filename + " from " + userName + " to all others.");
                }
            }
        } else {
            System.out.println("Server: File transfer for " + filename + " interrupted due to client disconnection or server shutdown.");
        }
    }

    public void sendRaw(byte[] framedMessage) throws IOException {
        synchronized (dout) {
            if (socket.isClosed() || !running) {
                return;
            }
            dout.writeInt(framedMessage.length);
            dout.write(framedMessage);
            dout.flush();
        }
    }

    public static byte[] buildUserJoinFrame(String username) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream tmp = new DataOutputStream(baos);
        tmp.writeUTF("JOIN");
        tmp.writeUTF(username);
        tmp.flush();
        return baos.toByteArray();
    }

    public static byte[] buildUserLeftFrame(String username) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream tmp = new DataOutputStream(baos);
        tmp.writeUTF("LEFT");
        tmp.writeUTF(username);
        tmp.flush();
        return baos.toByteArray();
    }

    // safeClose prevents multiple concurrent close attempts
    public void safeClose() {
        if (!running) return;
        running = false;
        try {
            if (din != null) din.close();
        } catch (IOException ignored) {}
        try {
            if (dout != null) dout.close();
        } catch (IOException ignored) {}
        try {
            if (!socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
        server.removeHandler(this);
    }
}