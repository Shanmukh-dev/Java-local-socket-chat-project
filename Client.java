import java.io.*;
import java.net.Socket;
import java.util.Scanner;

public class Client{

    private Socket socket;
    private BufferedReader receiver;
    private BufferedWriter sender;
    private String uname;

    public Client(Socket socket, String uname){
        try{
            this.socket = socket;
            this.receiver = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.sender = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            this.uname = uname;
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
            closeAllStreams(socket, receiver, sender);
        }

        
    }
    public void listenForMessage(){
        new Thread(new Runnable(){
            @Override
            public void run(){
                String message;
                while(socket.isConnected()){
                    try{
                        message = receiver.readLine();
                        System.out.println(message);

                    }catch(IOException e){
                        closeAllStreams(socket, receiver, sender);
                    }
                }
            }
        }).start();
    }

    public static void main(String[] args) throws IOException {
        Scanner scanner = new Scanner(System.in);
        System.out.println("uname: ");
        String uname = scanner.nextLine();
        Socket socket = new Socket("localhost", 8000);
        Client client = new Client(socket, uname);
        client.listenForMessage();
        client.sendMessage();


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