package org.develop.lancaster.core.network;

import java.io.*;
import java.net.Socket;

public class TestClient {
    public static void main(String[] args) {
        try (Socket socket = new Socket("localhost", 5000);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream())) {

            // Test 1: Ask for Metadata
            System.out.println("--- Requesting Metadata ---");
            dos.writeUTF("METADATA");
            String name = dis.readUTF();
            long size = dis.readLong();
            System.out.println("Server has: " + name + " (" + size + " bytes)");

        } catch (IOException e) {
            e.printStackTrace();
        }

        // Test 2: Ask for a specific Chunk (e.g., first 10 bytes)
        try (Socket socket = new Socket("localhost", 5000);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream())) {

            System.out.println("--- Requesting Chunk (0-10) ---");
            dos.writeUTF("CHUNK|0|10");

            byte[] buffer = new byte[10];
            dis.readFully(buffer);
            System.out.println("Received: " + new String(buffer));

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
