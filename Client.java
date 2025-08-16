import java.io.*;
import java.net.Socket;
import java.util.Scanner;


class Client{
    public static void main(String[] args) throws Exception {
        Socket soc = new Socket("localhost", 8000);
        Scanner scanner = new Scanner(System.in);
        String message;
        if(soc.isConnected()){
            PrintWriter sender = new PrintWriter(soc.getOutputStream(), true);
            sender.println("TSSV");
            sender.flush();
            BufferedReader reciever = new BufferedReader(new InputStreamReader(soc.getInputStream()));
            System.out.println("Connctions established. \nSend a message");
            System.out.println(">>> ");
            message = scanner.nextLine();
            sender.print(message);
            sender.flush();
            System.out.println("Server message: " + reciever.readLine());
            
        }
    }
}