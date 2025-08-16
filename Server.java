import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;

// class Server{
//     public static void main(String[] args) throws Exception {
//         System.out.println("Server started. \nWaiting for connections");
//         ServerSocket server = new ServerSocket(9999);
//         Socket soc = server.accept();
//         String message;
//         if(soc.isConnected()){
//             BufferedReader reciever = new BufferedReader(new InputStreamReader(soc.getInputStream()));
//             PrintWriter sender = new PrintWriter(soc.getOutputStream(), true);
//             System.out.println("Client Connected");
//             message = reciever.readLine();
//             System.out.println("Client message: " + message);
//             sender.println(message);
//             sender.flush();
//         }

//     }
// }

class ClientHandler implements Runnable{
    public static ArrayList<ClientHandler> clientHandlers = new ArrayList<>();
    private Socket socket;
    private BufferedReader reciever;
    private BufferedWriter sender;
    private String uname;

    public ClientHandler(Socket socket){
        try {
            this.socket = socket;
            this.reciever = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.sender = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            this.uname = this.reciever.readLine();
            clientHandlers.add(this);
            broadCastMsg(uname + " joind the chat!");

        } catch (IOException e) {
            closeAllStreams(socket, reciever, sender);
        }
    }



    @Override
    public void run(){
        String message;
        while(socket.isConnected()){
            try {
                message = reciever.readLine();
                System.out.println(uname +": "+ message);
                broadCastMsg(uname +": "+ message);
                
            } catch (IOException e) {
                closeAllStreams(socket, reciever, sender);
                break;
            }
        }

    }

    public void broadCastMsg(String msg){
        try {
            for (ClientHandler client : clientHandlers) {
                if(!client.uname.equals(uname)){
                    client.sender.write(msg);
                    client.sender.newLine();
                    client.sender.flush();
                }
                
            }
        } catch (IOException e) {
            closeAllStreams(socket, reciever, sender);
        }
    }

    public void removeHandler(){
        clientHandlers.remove(this);
        broadCastMsg(uname + " Left the chat...");
    }

    public void closeAllStreams(Socket socket, BufferedReader reciever, BufferedWriter sender){
        removeHandler();
        try {
            if(reciever != null){
                reciever.close();
            }
            if(sender != null){
                sender.close();
            }
            if(socket != null){
                socket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

class Server{
    private ServerSocket serverSocket;

    public Server(ServerSocket socket){
        this.serverSocket = socket;
    }

    public void startServer(){
        try {
            while(!serverSocket.isClosed()){

                System.out.println("Waiting for clients...");
                
                Socket socket = serverSocket.accept();
                System.out.println("New client connected");
                ClientHandler clientHandler = new ClientHandler(socket);

                Thread thread = new Thread(clientHandler);
                thread.start();
            }
            
        } catch (IOException e) {

        }
    }

    public void closeServer(){
        try {
            
            if(this.serverSocket != null){
                serverSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws IOException {
        
        ServerSocket serverSocket = new ServerSocket(8000);
        Server server = new Server(serverSocket);
        server.startServer();

    }
}