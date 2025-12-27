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
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.develop.lancaster.core.discovery.DiscoveryService;
import org.develop.lancaster.core.network.Sender;
import org.develop.lancaster.core.transfer.TransferManager;

import java.io.File;
import java.net.Socket;
import java.util.List;

public class MainWindow extends Application {

    private final ObservableList<String> peerList = FXCollections.observableArrayList();
    private DiscoveryService discoveryService;
    private VBox progressPanel;
    private Sender currentSender;

    // --- STYLES ---
    private static final String HEADER_STYLE = "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #333;";
    private static final String CARD_STYLE = "-fx-background-color: white; -fx-border-color: #e0e0e0; -fx-border-radius: 8; -fx-background-radius: 8; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.1), 5, 0, 0, 2);";
    private static final String BUTTON_SEND_STYLE = "-fx-background-color: #007bff; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 5;";
    private static final String BUTTON_DOWN_STYLE = "-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 5;";

    @Override
    public void start(Stage primaryStage) {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #f4f6f9;");

        VBox leftPane = createPeerListPane();
        VBox rightPane = createDashboardPane(primaryStage);

        SplitPane splitPane = new SplitPane();
        splitPane.getItems().addAll(leftPane, rightPane);
        splitPane.setDividerPositions(0.35);
        splitPane.setStyle("-fx-background-color: transparent; -fx-padding: 0;");

        root.setCenter(splitPane);

        startDiscovery();

        Scene scene = new Scene(root, 950, 650);
        primaryStage.setTitle("LAN-Caster | Professional P2P Transfer");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private VBox createPeerListPane() {
        Label header = new Label("Network Peers");
        header.setStyle(HEADER_STYLE);

        ListView<String> listView = new ListView<>(peerList);
        listView.setPlaceholder(new Label("Scanning local network..."));
        listView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        listView.setStyle("-fx-font-size: 14px; -fx-border-color: #ccc; -fx-border-radius: 5;");

        VBox container = new VBox(15, header, listView);
        container.setPadding(new Insets(0, 15, 0, 0));
        VBox.setVgrow(listView, Priority.ALWAYS);
        return container;
    }

    private VBox createDashboardPane(Stage stage) {
        Label header = new Label("Transfer Activity");
        header.setStyle(HEADER_STYLE);

        progressPanel = new VBox(10);
        progressPanel.setPadding(new Insets(5));
        ScrollPane scrollPane = new ScrollPane(progressPanel);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        Button sendBtn = new Button("Send File");
        sendBtn.setStyle(BUTTON_SEND_STYLE);
        sendBtn.setPrefHeight(40);
        sendBtn.setMaxWidth(Double.MAX_VALUE);

        Button downloadBtn = new Button("Download from Peer");
        downloadBtn.setStyle(BUTTON_DOWN_STYLE);
        downloadBtn.setPrefHeight(40);
        downloadBtn.setMaxWidth(Double.MAX_VALUE);

        // --- SEND BUTTON LOGIC ---
        sendBtn.setOnAction(e -> {
            ListView<String> listView = getListView(stage);
            List<String> selectedPeers = listView.getSelectionModel().getSelectedItems();

            if (selectedPeers.isEmpty()) {
                showAlert(Alert.AlertType.WARNING, "No Peers Selected", "Please select at least one peer to send the file to.");
                return;
            }

            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Select File to Send");
            File file = fileChooser.showOpenDialog(stage);

            if (file != null) {
                if (currentSender != null) currentSender.stop();

                // Start Sender with Progress Callback
                currentSender = new Sender((Socket socket) -> {
                    String peerIp = socket.getInetAddress().getHostAddress();

                    // Create UI Card for this connection
                    final TransferUIComponents[] uiRef = new TransferUIComponents[1];
                    Platform.runLater(() -> {
                        uiRef[0] = addTransferCard(file.getName(), peerIp, "Sending", file.length());
                    });

                    // Return listener to update this specific card
                    return (current, total) -> updateProgress(uiRef[0], current, total);
                });

                new Thread(() -> currentSender.startServing(file)).start();
                showAlert(Alert.AlertType.INFORMATION, "Hosting Started", "Hosting '" + file.getName() + "'.\nWaiting for " + selectedPeers.size() + " peers to accept...");
            }
        });

        // --- DOWNLOAD BUTTON LOGIC ---
        downloadBtn.setOnAction(e -> {
            ListView<String> listView = getListView(stage);
            String selectedPeer = listView.getSelectionModel().getSelectedItem();

            if (selectedPeer == null) {
                showAlert(Alert.AlertType.WARNING, "No Peer Selected", "Please select ONE peer to download from.");
                return;
            }

            DirectoryChooser dirChooser = new DirectoryChooser();
            dirChooser.setTitle("Select Save Location");
            File saveDir = dirChooser.showDialog(stage);

            if (saveDir != null) {
                TransferUIComponents ui = addTransferCard("Requesting File...", selectedPeer, "Downloading", 100);

                TransferManager tm = new TransferManager();
                tm.downloadFile(selectedPeer, saveDir.getAbsolutePath(), (current, total) -> updateProgress(ui, current, total));
            }
        });

        HBox buttons = new HBox(15, sendBtn, downloadBtn);
        buttons.setAlignment(Pos.CENTER);
        HBox.setHgrow(sendBtn, Priority.ALWAYS);
        HBox.setHgrow(downloadBtn, Priority.ALWAYS);

        VBox container = new VBox(15, header, scrollPane, buttons);
        container.setPadding(new Insets(0, 0, 0, 15));
        return container;
    }

    // --- PROGRESS UPDATE LOGIC ---
    private void updateProgress(TransferUIComponents ui, long current, long total) {
        if (ui == null) return;

        double progress = (double) current / total;

        Platform.runLater(() -> {
            ui.progressBar.setProgress(progress);
            int percent = (int) (progress * 100);
            ui.percentLabel.setText(percent + "%");

            if (progress >= 1.0) {
                ui.statusLabel.setText("Completed Successfully");
                ui.statusLabel.setTextFill(Color.GREEN);
                ui.progressBar.setStyle("-fx-accent: #28a745;"); // Green Bar
            } else {
                ui.statusLabel.setText(ui.transferType + "...");
                ui.statusLabel.setTextFill(Color.web("#007bff")); // Blue Text
            }
        });
    }

    // --- UI COMPONENTS ---
    private static class TransferUIComponents {
        ProgressBar progressBar;
        Label percentLabel;
        Label statusLabel;
        String transferType;

        public TransferUIComponents(ProgressBar pb, Label pl, Label sl, String type) {
            this.progressBar = pb;
            this.percentLabel = pl;
            this.statusLabel = sl;
            this.transferType = type;
        }
    }

    private TransferUIComponents addTransferCard(String filename, String peerIp, String type, long totalBytes) {
        VBox card = new VBox(8);
        card.setStyle(CARD_STYLE);
        card.setPadding(new Insets(12));

        HBox topRow = new HBox();
        Label nameLbl = new Label(type + ": " + filename);
        nameLbl.setFont(Font.font("System", FontWeight.BOLD, 13));

        Label statusLbl = new Label("Waiting...");
        statusLbl.setFont(Font.font("System", FontWeight.NORMAL, 12));
        statusLbl.setTextFill(Color.DARKORANGE);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        topRow.getChildren().addAll(nameLbl, spacer, statusLbl);

        Label ipLbl = new Label("Connected to: " + peerIp);
        ipLbl.setTextFill(Color.GRAY);
        ipLbl.setFont(Font.font("System", 11));

        HBox bottomRow = new HBox(10);
        bottomRow.setAlignment(Pos.CENTER_LEFT);
        ProgressBar pb = new ProgressBar(0);
        pb.setMaxWidth(Double.MAX_VALUE);
        pb.setPrefHeight(15);
        HBox.setHgrow(pb, Priority.ALWAYS);
        Label percentLbl = new Label("0%");
        percentLbl.setMinWidth(40);
        percentLbl.setAlignment(Pos.CENTER_RIGHT);
        bottomRow.getChildren().addAll(pb, percentLbl);

        card.getChildren().addAll(topRow, ipLbl, bottomRow);
        progressPanel.getChildren().add(0, card);

        return new TransferUIComponents(pb, percentLbl, statusLbl, type);
    }

    private ListView<String> getListView(Stage stage) {
        return (ListView<String>) ((VBox) ((SplitPane) stage.getScene().getRoot().lookup(".split-pane")).getItems().get(0)).getChildren().get(1);
    }

    private void startDiscovery() {
        discoveryService = new DiscoveryService();
        discoveryService.setOnPeerFound(ipAddress -> {
            Platform.runLater(() -> {
                if (!peerList.contains(ipAddress)) peerList.add(ipAddress);
            });
        });
        Thread t = new Thread(discoveryService);
        t.setDaemon(true);
        t.start();
        Thread b = new Thread(() -> {
            while (true) { discoveryService.broadcastPresence(); try { Thread.sleep(3000); } catch (Exception e) {} }
        });
        b.setDaemon(true);
        b.start();
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
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