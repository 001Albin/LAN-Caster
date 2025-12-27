package org.develop.lancaster.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.develop.lancaster.core.discovery.DiscoveryService;
import org.develop.lancaster.core.network.Sender;
import org.develop.lancaster.core.transfer.ProgressListener;

import java.io.File;
import java.net.Socket;
import java.util.List;

public class MainWindow extends Application {

    private final ObservableList<String> peerList = FXCollections.observableArrayList();
    private DiscoveryService discoveryService;
    private VBox progressPanel;

    // Keep a reference to stop it later if needed
    private Sender currentSender;

    @Override
    public void start(Stage primaryStage) {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(15));

        VBox leftPane = createPeerListPane();
        VBox rightPane = createDashboardPane(primaryStage);

        SplitPane splitPane = new SplitPane();
        splitPane.getItems().addAll(leftPane, rightPane);
        splitPane.setDividerPositions(0.35);

        root.setCenter(splitPane);

        startDiscovery();

        Scene scene = new Scene(root, 900, 600);
        primaryStage.setTitle("LAN-Caster | High-Speed P2P Transfer");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private VBox createPeerListPane() {
        Label header = new Label("Available Peers");
        header.setFont(Font.font("Arial", FontWeight.BOLD, 16));

        ListView<String> listView = new ListView<>(peerList);
        listView.setPlaceholder(new Label("Scanning Local Network..."));

        // ENABLE MULTI-SELECTION
        listView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        VBox.setVgrow(listView, Priority.ALWAYS);
        return new VBox(10, header, listView);
    }

    private VBox createDashboardPane(Stage stage) {
        Label header = new Label("Active Transfers");
        header.setFont(Font.font("Arial", FontWeight.BOLD, 16));

        progressPanel = new VBox(10);
        progressPanel.setPadding(new Insets(10));
        ScrollPane scrollPane = new ScrollPane(progressPanel);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        Button sendBtn = new Button("Send File to Selected");
        sendBtn.setStyle("-fx-background-color: #0078D7; -fx-text-fill: white; -fx-font-weight: bold;");
        sendBtn.setMaxWidth(Double.MAX_VALUE);

        sendBtn.setOnAction(e -> {
            // Get selected peers (Visual only, as we host for anyone who connects)
            ListView<String> listView = (ListView<String>) ((VBox) ((SplitPane) stage.getScene().getRoot().lookup(".split-pane")).getItems().get(0)).getChildren().get(1);
            List<String> selectedPeers = listView.getSelectionModel().getSelectedItems();

            if (selectedPeers.isEmpty()) {
                showAlert("Select Peer", "Please select who you want to send to.");
                return;
            }

            FileChooser fileChooser = new FileChooser();
            File file = fileChooser.showOpenDialog(stage);

            if (file != null) {
                // START HOSTING
                startSendingLogic(file);
                showAlert("Hosting Started", "File is ready! Waiting for " + selectedPeers.size() + " peers to connect...");
            }
        });

        return new VBox(15, header, scrollPane, sendBtn);
    }

    // --- LOGIC: Start the Sender Server ---
    private void startSendingLogic(File file) {
        if (currentSender != null) {
            currentSender.stop(); // Stop previous host if exists
        }

        // Create Sender with a "Factory" for Progress Listeners
        // When a client connects (Socket s), this code runs:
        currentSender = new Sender((Socket socket) -> {

            String peerIp = socket.getInetAddress().getHostAddress();

            // 1. Create Visual UI Card on JavaFX Thread
            // We use a helper wrapper to pass the progress bar back
            final ProgressBar[] pbRef = new ProgressBar[1];
            final Label[] percentRef = new Label[1];

            Platform.runLater(() -> {
                TransferUIComponents ui = addTransferCard(file.getName(), peerIp, "Sending", file.length());
                pbRef[0] = ui.progressBar;
                percentRef[0] = ui.percentLabel;
            });

            // 2. Return the Listener that updates this specific card
            return (current, total) -> {
                double progress = (double) current / total;
                Platform.runLater(() -> {
                    if (pbRef[0] != null) {
                        pbRef[0].setProgress(progress);
                        percentRef[0].setText((int)(progress * 100) + "%");
                    }
                });
            };
        });

        // Run Server in Background
        new Thread(() -> currentSender.startServing(file)).start();
    }

    // Helper class to return UI references
    private static class TransferUIComponents {
        ProgressBar progressBar;
        Label percentLabel;
        public TransferUIComponents(ProgressBar pb, Label pl) { this.progressBar = pb; this.percentLabel = pl; }
    }

    private TransferUIComponents addTransferCard(String filename, String peerIp, String type, long totalBytes) {
        HBox card = new HBox(15);
        card.setStyle("-fx-border-color: #cccccc; -fx-border-radius: 5; -fx-padding: 10; -fx-background-color: #f9f9f9;");
        card.setAlignment(Pos.CENTER_LEFT);

        VBox infoBox = new VBox(5);
        Label nameLbl = new Label(type + ": " + filename);
        Label ipLbl = new Label("Peer: " + peerIp);
        ipLbl.setTextFill(Color.GRAY);
        infoBox.getChildren().addAll(nameLbl, ipLbl);

        ProgressBar pb = new ProgressBar(0);
        pb.setPrefWidth(200);
        Label percentLbl = new Label("0%");
        percentLbl.setMinWidth(40);

        card.getChildren().addAll(infoBox, pb, percentLbl);
        HBox.setHgrow(infoBox, Priority.ALWAYS);

        progressPanel.getChildren().add(0, card);
        return new TransferUIComponents(pb, percentLbl);
    }

    private void startDiscovery() {
        discoveryService = new DiscoveryService();
        discoveryService.setOnPeerFound(ipAddress -> {
            Platform.runLater(() -> {
                if (!peerList.contains(ipAddress)) peerList.add(ipAddress);
            });
        });
        new Thread(discoveryService).start();
        new Thread(() -> {
            while (true) {
                discoveryService.broadcastPresence();
                try { Thread.sleep(3000); } catch (Exception e) {}
            }
        }).start();
    }

    private void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setContentText(content);
        alert.show();
    }

    @Override
    public void stop() {
        if (currentSender != null) currentSender.stop();
        System.exit(0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}