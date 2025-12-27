package org.develop.lancaster.ui;

import javafx.application.Platform;
import javafx.stage.DirectoryChooser;
import org.develop.lancaster.core.discovery.DiscoveryService;
import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.develop.lancaster.core.network.Sender;

import java.io.File;

public class MainWindow extends Application {

    // The list that connects the backend to the frontend
    private final ObservableList<String> peerList = FXCollections.observableArrayList();
    private DiscoveryService discoveryService;

    @Override
    public void start(Stage primaryStage) {
        // ... (Layout setup remains the same) ...
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));

        // LEFT: Peer List
        ListView<String> listView = new ListView<>(peerList);
        listView.setPlaceholder(new Label("Scanning for peers..."));
        VBox leftPane = new VBox(10, new Label("Available Peers:"), listView);
        root.setLeft(leftPane);

        // RIGHT: Buttons
        Button sendBtn = new Button("Send File");
        Button downloadBtn = new Button("Download from Peer"); // <--- NEW BUTTON

        sendBtn.setDisable(true);
        downloadBtn.setDisable(true); // Disabled until peer selected

        Label statusLabel = new Label("Status: Ready");

        // Add both buttons to the layout
        VBox rightPane = new VBox(20, new Label("Actions"), sendBtn, downloadBtn, statusLabel);
        rightPane.setPadding(new Insets(0, 0, 0, 20));
        root.setCenter(rightPane);

        // --- SEND BUTTON LOGIC (Existing) ---
        sendBtn.setOnAction(e -> {
            String selectedPeer = listView.getSelectionModel().getSelectedItem();
            if (selectedPeer != null) {
                FileChooser fileChooser = new FileChooser();
                File file = fileChooser.showOpenDialog(primaryStage);
                if (file != null) {
                    statusLabel.setText("Hosting: " + file.getName());
                    new Thread(() -> {
                        org.develop.lancaster.core.network.Sender sender = new org.develop.lancaster.core.network.Sender();
                        sender.startServing(file);
                    }).start();
                }
            }
        });

        // --- DOWNLOAD BUTTON LOGIC (New!) ---
        downloadBtn.setOnAction(e -> {
            String selectedPeer = listView.getSelectionModel().getSelectedItem();
            if (selectedPeer != null) {
                // 1. Ask User: "Where do you want to save it?"
                DirectoryChooser dirChooser = new DirectoryChooser();
                dirChooser.setTitle("Select Save Location");
                File saveDir = dirChooser.showDialog(primaryStage);

                if (saveDir != null) {
                    statusLabel.setText("Downloading from " + selectedPeer + "...");

                    // 2. Run TransferManager in Background
                    new Thread(() -> {
                        org.develop.lancaster.core.transfer.TransferManager tm = new org.develop.lancaster.core.transfer.TransferManager();
                        // Assume Port 5000 for now
                        tm.downloadFile(selectedPeer, 5000, saveDir.getAbsolutePath());

                        // 3. Update UI when done
                        Platform.runLater(() -> statusLabel.setText("Download Complete!"));
                    }).start();
                }
            }
        });

        // Enable buttons only when peer is selected
        listView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    sendBtn.setDisable(newVal == null);
                    downloadBtn.setDisable(newVal == null);
                }
        );

        startDiscovery();

        Scene scene = new Scene(root, 600, 400);
        primaryStage.setTitle("LAN-Caster P2P");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private void startDiscovery() {
        // 1. Initialize Service
        discoveryService = new DiscoveryService();

        // 2. CONNECT BACKEND TO FRONTEND
        // When a peer is found -> Run this code
        discoveryService.setOnPeerFound(ipAddress -> {
            // Must use Platform.runLater because this comes from a background thread
            Platform.runLater(() -> {
                // Only add if not already in the list to avoid duplicates
                if (!peerList.contains(ipAddress)) {
                    peerList.add(ipAddress);
                    System.out.println("[UI] Added peer: " + ipAddress);
                }
            });
        });

        // 3. Start Listener Thread
        Thread listenerThread = new Thread(discoveryService);
        listenerThread.setDaemon(true); // Ensures thread dies when app closes
        listenerThread.start();

        // 4. Start Broadcaster Thread
        Thread broadcasterThread = new Thread(() -> {
            while (true) {
                discoveryService.broadcastPresence();
                try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
            }
        });
        broadcasterThread.setDaemon(true);
        broadcasterThread.start();
    }

    // Stop threads when closing window
    @Override
    public void stop() {
        System.out.println("Stopping App...");
        System.exit(0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
