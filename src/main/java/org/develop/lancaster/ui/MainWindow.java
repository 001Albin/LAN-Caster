package org.develop.lancaster.ui;

import javafx.application.Platform;
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

import java.io.File;

public class MainWindow extends Application {

    // The list that connects the backend to the frontend
    private final ObservableList<String> peerList = FXCollections.observableArrayList();
    private DiscoveryService discoveryService;

    @Override
    public void start(Stage primaryStage) {
        // 1. Setup Layout
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));

        // 2. Left Side: Peer List
        ListView<String> listView = new ListView<>(peerList);
        listView.setPlaceholder(new Label("Scanning for peers..."));

        VBox leftPane = new VBox(10, new Label("Available Peers:"), listView);
        root.setLeft(leftPane);

        // 3. Right Side: Actions
        Button sendBtn = new Button("Send File");
        sendBtn.setDisable(true); // Disabled until you pick a peer

        Label statusLabel = new Label("Status: Ready");

        VBox rightPane = new VBox(20, new Label("Actions"), sendBtn, statusLabel);
        rightPane.setPadding(new Insets(0, 0, 0, 20)); // Add some spacing
        root.setCenter(rightPane);

        // 4. Button Logic (File Chooser)
        sendBtn.setOnAction(e -> {
            String selectedPeer = listView.getSelectionModel().getSelectedItem();
            if (selectedPeer != null) {
                FileChooser fileChooser = new FileChooser();
                File file = fileChooser.showOpenDialog(primaryStage);

                if (file != null) {
                    statusLabel.setText("Sending: " + file.getName() + " to " + selectedPeer);
                    // TODO: Call TransferManager here!
                }
            }
        });

        // Enable button only when a peer is selected
        listView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> sendBtn.setDisable(newVal == null)
        );

        // 5. Start Backend Services
        startDiscovery();

        // 6. Show Window
        Scene scene = new Scene(root, 600, 400);
        primaryStage.setTitle("LAN-Caster (P2P File Share)");
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
