/**This class acts as the "Listener." It waits for a connection and prints the message it receives.**/

package org.develop.lancaster.core.network;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

public class Sender {
    public static void main(String[] args) {
        System.out.println("Sender is running...");

        String filePath = "test_file.txt";

        // 1. Initialize ServerSocket on Port 5000
        try(ServerSocket serverSocket = new ServerSocket(5000)){
            System.out.println("[Sender] Ready to send '" + filePath + "'. Waiting for Receiver...");

            try(Socket socket = serverSocket.accept();
                FileInputStream fis = new FileInputStream(filePath);
                OutputStream os = socket.getOutputStream();){

                System.out.println("Client connected, file transfer starting...");

                // --- THE BINARY STREAM LOGIC (Module 2) ---
                byte[] buffer = new byte[1024];
                int bytesRead;

                //Read from file -> write to socket
                while((bytesRead = fis.read(buffer))!=-1){
                    os.write(buffer, 0, bytesRead);
                }

                System.out.println("[Sender] File sent successfully!");
            }

//            // 2. Block until a client connects (The Program pauses here!)
//            Socket clientSocket = serverSocket.accept();
//            System.out.println("Connection established with " + clientSocket.getInetAddress());
//
//            // 3. Create Input Stream to read data
//            DataInputStream dis = new DataInputStream(clientSocket.getInputStream());
//
//            // 4. Read the simple string message
//            String message = dis.readUTF();
//            System.out.println("Received message: " + message);

        } catch (IOException e) {
           e.printStackTrace();
        }
    }
}
