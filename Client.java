import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.Socket;
import java.util.Scanner;

public class Client{

    private Socket socket;
    private BufferedReader receiver;
    private BufferedWriter sender;
    private String uname;

    public Client(Socket socket,BufferedReader receiver,BufferedWriter sender ){
        try{
            this.socket = socket;
            this.receiver = receiver;
            this.sender = sender;
        }catch(Exception e){
            closeAllStreams(socket, receiver, sender);
        }
    }

    public void closeAllStreams(Socket socket, BufferedReader reciever, BufferedWriter sender){
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
    public void sendMessage(){
        try {

            sender.write(uname);
            sender.newLine();
            sender.flush();
    
            Scanner scanner = new Scanner(System.in);
            while (socket.isConnected()) {
                String messageToSend = scanner.nextLine();
                sender.write(uname + ": " + messageToSend);
                sender.newLine();
                sender.flush();
            
            }
        }catch(IOException e){
            closeAllStreams(this.socket, this.sender, this.receiver);
        }

        
    }
}
























// import java.io.*;
// import java.net.Socket;
// import java.util.Scanner;


// public class Client{

//     public static void main(String[] args) throws Exception {
//         Socket soc = new Socket("localhost", 8000);
//         Scanner scanner = new Scanner(System.in);
//         String message;
//         if(soc.isConnected()){
//             PrintWriter sender = new PrintWriter(soc.getOutputStream(), true);
//             sender.println("TSSV");
//             sender.flush();
//             BufferedReader reciever = new BufferedReader(new InputStreamReader(soc.getInputStream()));
//             System.out.println("Connctions established. \nSend a message");
//             System.out.println(">>> ");
//             message = scanner.nextLine();
//             sender.print(message);
//             sender.flush(); 
//             System.out.println("Server message: " + reciever.readLine());
            
//         }
//     }
// }