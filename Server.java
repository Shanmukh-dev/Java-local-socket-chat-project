import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;


class Server{
    public static void main(String[] args) throws Exception {
        System.out.println("Server started. \nWaiting for connections");
        ServerSocket server = new ServerSocket(9999);
        Socket soc = server.accept();
        String message;
        if(soc.isConnected()){
            BufferedReader reciever = new BufferedReader(new InputStreamReader(soc.getInputStream()));
            PrintWriter sender = new PrintWriter(soc.getOutputStream(), true);
            System.out.println("Client Connected");
            message = reciever.readLine();
            System.out.println("Client message: " + message);
            sender.println(message);
            sender.flush();
        }

    }
}