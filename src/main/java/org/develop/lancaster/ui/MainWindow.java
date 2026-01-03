package org.develop.lancaster.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle; // Added for Undecorated style
import org.develop.lancaster.core.discovery.DiscoveryService;
import org.develop.lancaster.core.discovery.PeerInfo;
import org.develop.lancaster.core.network.Sender;
import org.develop.lancaster.core.transfer.TransferManager;
import org.develop.lancaster.core.util.Config;

import java.io.File;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MainWindow extends Application {

    private static final Logger logger = Logger.getLogger(MainWindow.class.getName());

    private final ObservableList<PeerInfo> peerList = FXCollections.observableArrayList();
    private DiscoveryService discoveryService;
    private VBox progressPanel;
    private Sender currentSender;
    private final Map<String, TransferUIComponents> activeTransfers = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> activeProgress = new ConcurrentHashMap<>();

    // Stats tracking
    private Label activeTransfersLabel;
    private Label speedLabel;
    private Label filesCompletedLabel;
    private AtomicLong totalFilesCompleted = new AtomicLong(0);
    private AtomicLong totalBytesTransferred = new AtomicLong(0);
    private long statsStartTime = System.currentTimeMillis();

    // Window Dragging Offsets
    private double xOffset = 0;
    private double yOffset = 0;

    // === MODERN COLOR PALETTE ===
    private static final String PRIMARY_COLOR = "#1f2937";
    private static final String SECONDARY_COLOR = "#3b82f6";
    private static final String SUCCESS_COLOR = "#10b981";
    private static final String WARNING_COLOR = "#f59e0b";
    private static final String LIGHT_BG = "#f9fafb";
    private static final String CARD_BG = "#ffffff";
    private static final String BORDER_COLOR = "#e5e7eb";

    // === MODERN STYLES ===
    private static final String HEADER_STYLE = "-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: " + PRIMARY_COLOR + ";";
    private static final String BODY_TEXT_STYLE = "-fx-font-size: 13px; -fx-text-fill: #6b7280;";

    private static final String CARD_STYLE = "-fx-background-color: " + CARD_BG + "; "
            + "-fx-border-color: " + BORDER_COLOR + "; "
            + "-fx-border-width: 1; "
            + "-fx-border-radius: 12; "
            + "-fx-background-radius: 12; "
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.08), 8, 0, 0, 2);";

    // Style for selected peer
    private static final String SELECTED_CARD_STYLE = "-fx-background-color: #eff6ff; " // Very light blue
            + "-fx-border-color: " + SECONDARY_COLOR + "; "
            + "-fx-border-width: 2; "
            + "-fx-border-radius: 12; "
            + "-fx-background-radius: 12;";

    private static final String BUTTON_PRIMARY_STYLE = "-fx-background-color: " + SECONDARY_COLOR + "; "
            + "-fx-text-fill: white; "
            + "-fx-font-weight: 600; "
            + "-fx-cursor: hand; "
            + "-fx-background-radius: 8; "
            + "-fx-padding: 10 20 10 20; "
            + "-fx-font-size: 13px;";

    private static final String BUTTON_SUCCESS_STYLE = "-fx-background-color: " + SUCCESS_COLOR + "; "
            + "-fx-text-fill: white; "
            + "-fx-font-weight: 600; "
            + "-fx-cursor: hand; "
            + "-fx-background-radius: 8; "
            + "-fx-padding: 10 20 10 20; "
            + "-fx-font-size: 13px;";

    private static final String TEXT_FIELD_STYLE = "-fx-font-size: 13px; "
            + "-fx-padding: 10; "
            + "-fx-border-color: " + BORDER_COLOR + "; "
            + "-fx-border-width: 1; "
            + "-fx-border-radius: 6; "
            + "-fx-background-radius: 6; "
            + "-fx-text-fill: #374151;";

    // Style for window control buttons
    private static final String WINDOW_BTN_STYLE = "-fx-background-color: transparent; -fx-text-fill: white; -fx-font-size: 14px; -fx-cursor: hand;";

    @Override
    public void start(Stage primaryStage) {
        // 1. REMOVE DEFAULT OS TITLE BAR
        primaryStage.initStyle(StageStyle.UNDECORATED);

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: " + LIGHT_BG + "; -fx-border-color: #374151; -fx-border-width: 1;"); // Add border since we lost window border

        // Pass primaryStage to createTopBar so we can control min/max/close
        HBox topBar = createTopBar(primaryStage);
        root.setTop(topBar);

        VBox leftPane = createPeerListPane();
        VBox rightPane = createDashboardPane(primaryStage);

        SplitPane splitPane = new SplitPane();
        splitPane.getItems().addAll(leftPane, rightPane);
        splitPane.setDividerPositions(0.3);
        splitPane.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        SplitPane.setResizableWithParent(leftPane, true);
        SplitPane.setResizableWithParent(rightPane, true);

        root.setCenter(splitPane);

        root.setOnMouseClicked(event -> {
            if (!isClickOnPeerList(event.getSceneX(), event.getSceneY(), primaryStage)) {
                ListView<PeerInfo> listView = getListView(primaryStage);
                listView.getSelectionModel().clearSelection();
            }
        });

        startDiscovery();

        Scene scene = new Scene(root, 1200, 800);
        primaryStage.setTitle("LAN-Caster");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(600);
        primaryStage.show();
    }

    private boolean isClickOnPeerList(double sceneX, double sceneY, Stage stage) {
        ListView<PeerInfo> listView = getListView(stage);
        if (listView == null) return false;
        javafx.geometry.Bounds bounds = listView.localToScene(listView.getBoundsInLocal());
        return bounds.contains(sceneX, sceneY);
    }

    // UPDATED: Added Stage parameter for controls and dragging
    private HBox createTopBar(Stage stage) {
        HBox topBar = new HBox();
        topBar.setStyle("-fx-background-color: " + PRIMARY_COLOR + "; -fx-padding: 15 20 15 20;");
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setSpacing(20);

        // Make window draggable
        topBar.setOnMousePressed(event -> {
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        });
        topBar.setOnMouseDragged(event -> {
            stage.setX(event.getScreenX() - xOffset);
            stage.setY(event.getScreenY() - yOffset);
        });

        Label title = new Label("⚡ LAN-Caster");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: white;");

        Label deviceLabel = new Label("Device Name:");
        deviceLabel.setStyle("-fx-text-fill: white; -fx-font-weight: 600;");

        TextField deviceNameField = new TextField();
        deviceNameField.setStyle(TEXT_FIELD_STYLE + " -fx-control-inner-background: #f3f4f6;");
        deviceNameField.setPromptText("e.g., John's Laptop");
        deviceNameField.setPrefWidth(200);
        String current = Config.getDeviceName();
        deviceNameField.setText(current == null ? "" : current);

        Button saveDeviceBtn = new Button("Save");
        saveDeviceBtn.setStyle(BUTTON_PRIMARY_STYLE);
        saveDeviceBtn.setPrefWidth(70);
        saveDeviceBtn.setOnAction(e -> {
            String newName = sanitizeName(deviceNameField.getText(), 32);
            if (newName != null && newName.length() == 0) newName = null;
            Config.setDeviceName(newName == null ? "" : newName);
            if (discoveryService != null) discoveryService.setLocalName(newName);
            showNotification("Device name updated to: " + (newName == null ? "(default)" : newName));
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // 2. CUSTOM WINDOW CONTROLS
        HBox windowControls = new HBox(10);
        windowControls.setAlignment(Pos.CENTER_RIGHT);

        Button minBtn = new Button("—");
        minBtn.setStyle(WINDOW_BTN_STYLE);
        minBtn.setOnAction(e -> stage.setIconified(true));

        Button maxBtn = new Button("☐");
        maxBtn.setStyle(WINDOW_BTN_STYLE);
        maxBtn.setOnAction(e -> stage.setMaximized(!stage.isMaximized()));

        Button closeBtn = new Button("✕");
        closeBtn.setStyle(WINDOW_BTN_STYLE + " -fx-text-fill: #ff6b6b;"); // Red tint for close
        closeBtn.setOnAction(e -> stop());

        windowControls.getChildren().addAll(minBtn, maxBtn, closeBtn);

        // Add everything to top bar
        topBar.getChildren().addAll(title, deviceLabel, deviceNameField, saveDeviceBtn, spacer, windowControls);
        return topBar;
    }

    private VBox createPeerListPane() {
        VBox container = new VBox();
        container.setStyle("-fx-background-color: " + LIGHT_BG + ";");
        container.setPadding(new Insets(20));
        container.setSpacing(15);

        Label header = new Label("🌐 Network Peers");
        header.setStyle(HEADER_STYLE);

        HBox searchBox = new HBox();
        searchBox.setStyle("-fx-background-color: transparent;");
        searchBox.setSpacing(10);
        searchBox.setAlignment(Pos.CENTER_LEFT);

        TextField searchField = new TextField();
        searchField.setStyle(TEXT_FIELD_STYLE);
        searchField.setPromptText("Search peers...");
        searchField.setPrefHeight(40);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        searchBox.getChildren().add(searchField);

        ListView<PeerInfo> listView = new ListView<>(peerList);
        listView.setPrefHeight(400);
        listView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        // Remove default blue selection color of listview, we handle it in cell
        listView.setStyle("-fx-font-size: 13px; -fx-background-color: transparent; -fx-control-inner-background: " + LIGHT_BG + "; -fx-selection-bar: transparent; -fx-selection-bar-non-focused: transparent;");
        listView.setFocusTraversable(false);

        listView.setCellFactory(lv -> new PeerListCell());

        VBox.setVgrow(listView, Priority.ALWAYS);

        Label statusLabel = new Label("🔍 Scanning for peers...");
        statusLabel.setStyle(BODY_TEXT_STYLE);

        container.getChildren().addAll(header, searchBox, listView, statusLabel);
        return container;
    }

    private class PeerListCell extends ListCell<PeerInfo> {
        private final VBox content = new VBox();

        public PeerListCell() {
            content.setSpacing(4);
            content.setPadding(new Insets(10));
            // Initial style
            content.setStyle(CARD_STYLE);
        }

        // 3. HANDLE SELECTION STATE VISUALLY
        @Override
        public void updateSelected(boolean selected) {
            super.updateSelected(selected);
            if (getItem() != null) {
                if (selected) {
                    // "Perfect" selected state: Highlighted border and light blue background
                    content.setStyle(SELECTED_CARD_STYLE);
                } else {
                    // Normal state
                    content.setStyle(CARD_STYLE);
                }
            }
        }

        @Override
        protected void updateItem(PeerInfo item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                setStyle("-fx-background-color: transparent;"); // Clear cell background
            } else {
                content.getChildren().clear();

                Label nameLbl = new Label("💻 " + item.toString());
                nameLbl.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: " + PRIMARY_COLOR + ";");

                Label ipLbl = new Label(item.getIp());
                ipLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #9ca3af;");

                HBox actionBox = new HBox(8);
                actionBox.setPadding(new Insets(8, 0, 0, 0));

                Button renameBtn = createSmallButton("✏️ Rename", e -> {
                    e.consume();
                    renameItem(item);
                });
                Button copyBtn = createSmallButton("📋 Copy IP", e -> {
                    e.consume();
                    copyIP(item.getIp());
                });

                actionBox.getChildren().addAll(renameBtn, copyBtn);
                content.getChildren().addAll(nameLbl, ipLbl, actionBox);

                // Ensure style is correct on refresh
                if (isSelected()) {
                    content.setStyle(SELECTED_CARD_STYLE);
                } else {
                    content.setStyle(CARD_STYLE);
                }

                setGraphic(content);
                setStyle("-fx-background-color: transparent; -fx-padding: 5 0 5 0;"); // Gap between items
            }
        }

        private void renameItem(PeerInfo item) {
            TextInputDialog dlg = new TextInputDialog(Optional.ofNullable(item.getName()).orElse(""));
            dlg.setTitle("Rename Peer");
            dlg.setHeaderText("Set a friendly name for " + item.getIp());
            dlg.setContentText("Device Name (max 32 chars):");
            Optional<String> res = dlg.showAndWait();
            res.ifPresent(n -> {
                String clean = sanitizeName(n, 32);
                Config.setPeerName(item.getIp(), clean);
                item.setName(clean);
                updateItem(item, false);
            });
        }

        private void copyIP(String ip) {
            Clipboard cb = Clipboard.getSystemClipboard();
            ClipboardContent cc = new ClipboardContent();
            cc.putString(ip);
            cb.setContent(cc);
            showNotification("IP copied to clipboard!");
        }
    }

    private Button createSmallButton(String text, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button btn = new Button(text);
        btn.setStyle("-fx-font-size: 11px; -fx-padding: 4 8 4 8; -fx-background-color: " + SECONDARY_COLOR + "; "
                + "-fx-text-fill: white; -fx-background-radius: 4; -fx-cursor: hand;");
        btn.setOnAction(action);
        return btn;
    }

    private VBox createDashboardPane(Stage stage) {
        VBox container = new VBox();
        container.setStyle("-fx-background-color: " + LIGHT_BG + ";");
        container.setPadding(new Insets(20));
        container.setSpacing(15);

        Label header = new Label("📤 Transfer Activity");
        header.setStyle(HEADER_STYLE);

        HBox statsBox = createStatsBox();

        progressPanel = new VBox(12);
        progressPanel.setPadding(new Insets(0));
        ScrollPane scrollPane = new ScrollPane(progressPanel);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-padding: 0;");
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        HBox actionButtonsBox = new HBox(15);
        actionButtonsBox.setAlignment(Pos.CENTER);
        actionButtonsBox.setSpacing(15);

        Button sendBtn = new Button("📤 Send File");
        sendBtn.setStyle(BUTTON_PRIMARY_STYLE);
        sendBtn.setPrefHeight(48);
        sendBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(sendBtn, Priority.ALWAYS);

        Button downloadBtn = new Button("📥 Download File");
        downloadBtn.setStyle(BUTTON_SUCCESS_STYLE);
        downloadBtn.setPrefHeight(48);
        downloadBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(downloadBtn, Priority.ALWAYS);

        sendBtn.setOnAction(e -> handleSendFile(stage));
        downloadBtn.setOnAction(e -> handleDownloadFile(stage));

        actionButtonsBox.getChildren().addAll(sendBtn, downloadBtn);

        container.getChildren().addAll(header, statsBox, scrollPane, actionButtonsBox);
        return container;
    }

    private void handleSendFile(Stage stage) {
        ListView<PeerInfo> listView = getListView(stage);
        if (listView == null) return;
        List<PeerInfo> selectedPeers = listView.getSelectionModel().getSelectedItems();

        if (selectedPeers.isEmpty()) {
            showAlert("No Peers Selected", "Please select at least one peer to send the file to.");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select File to Send");
        File file = fileChooser.showOpenDialog(stage);

        if (file != null) {
            if (currentSender != null) currentSender.stop();

            currentSender = new Sender((Socket socket) -> {
                String peerIp = socket.getInetAddress().getHostAddress();
                String key = peerIp + "|" + file.getName();

                TransferUIComponents ui = activeTransfers.computeIfAbsent(key, k -> {
                    FutureTask<TransferUIComponents> uiTask = new FutureTask<>(() ->
                            addTransferCard(file.getName(), peerIp, "Sending", file.length())
                    );
                    Platform.runLater(uiTask);
                    try {
                        TransferUIComponents result = uiTask.get();
                        activeProgress.putIfAbsent(key, new AtomicLong(0));
                        updateStatsUI();
                        return result;
                    } catch (Exception ex) {
                        logger.log(Level.WARNING, "Failed to create UI card", ex);
                        return null;
                    }
                });

                long totalFileBytes = file.length();
                AtomicLong globalCounter = activeProgress.computeIfAbsent(key, k -> new AtomicLong(0));

                return new org.develop.lancaster.core.transfer.ProgressListener() {
                    private final AtomicLong lastSeen = new AtomicLong(0);

                    @Override
                    public void onProgress(long current, long chunkTotal) {
                        long prev = lastSeen.getAndSet(current);
                        long delta = current - prev;
                        if (delta <= 0) return;
                        long aggregated = globalCounter.addAndGet(delta);
                        totalBytesTransferred.addAndGet(delta);

                        updateProgress(ui, aggregated, totalFileBytes);

                        if (aggregated >= totalFileBytes) {
                            activeTransfers.remove(key);
                            activeProgress.remove(key);
                            totalFilesCompleted.incrementAndGet();
                            updateStatsUI();
                        }
                    }
                };
            });

            new Thread(() -> currentSender.startServing(file)).start();
            showNotification("Hosting '" + file.getName() + "' for " + selectedPeers.size() + " peer(s)");
        }
    }

    private void handleDownloadFile(Stage stage) {
        ListView<PeerInfo> listView = getListView(stage);
        if (listView == null) return;
        PeerInfo selectedPeer = listView.getSelectionModel().getSelectedItem();

        if (selectedPeer == null) {
            showAlert("No Peer Selected", "Please select ONE peer to download from.");
            return;
        }

        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Select Save Location");
        File saveDir = dirChooser.showDialog(stage);

        if (saveDir != null) {
            String display = selectedPeer.toString();
            TransferUIComponents ui = addTransferCard("Requesting File...", display, "Downloading", 100);
            updateStatsUI();

            TransferManager tm = new TransferManager();
            tm.downloadFile(selectedPeer.getIp(), saveDir.getAbsolutePath(), (current, total) -> {
                totalBytesTransferred.addAndGet(current);
                updateProgress(ui, current, total);
                if (current >= total) {
                    totalFilesCompleted.incrementAndGet();
                    updateStatsUI();
                }
            });
        }
    }

    private HBox createStatsBox() {
        HBox statsBox = new HBox(15);
        statsBox.setStyle("-fx-background-color: transparent;");
        statsBox.setSpacing(15);

        activeTransfersLabel = new Label("0");
        speedLabel = new Label("0 MB/s");
        filesCompletedLabel = new Label("0");

        VBox stat1 = createStatCard("Active Transfers", activeTransfersLabel, "#3b82f6");
        VBox stat2 = createStatCard("Total Speed", speedLabel, "#10b981");
        VBox stat3 = createStatCard("Files Completed", filesCompletedLabel, "#f59e0b");

        statsBox.getChildren().addAll(stat1, stat2, stat3);
        return statsBox;
    }

    private VBox createStatCard(String title, Label valueLabel, String color) {
        VBox card = new VBox(8);
        card.setStyle(CARD_STYLE + " -fx-padding: 15;");
        card.setAlignment(Pos.CENTER);
        HBox.setHgrow(card, Priority.ALWAYS);

        Label titleLbl = new Label(title);
        titleLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #9ca3af; -fx-font-weight: 600;");

        valueLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");

        card.getChildren().addAll(titleLbl, valueLabel);
        return card;
    }

    private void updateStatsUI() {
        Platform.runLater(() -> {
            int activeCount = activeTransfers.size();
            activeTransfersLabel.setText(String.valueOf(activeCount));

            long elapsedSeconds = Math.max(1, (System.currentTimeMillis() - statsStartTime) / 1000);
            double mbps = (totalBytesTransferred.get() / (1024.0 * 1024.0)) / elapsedSeconds;
            speedLabel.setText(String.format("%.2f MB/s", mbps));

            filesCompletedLabel.setText(String.valueOf(totalFilesCompleted.get()));
        });
    }

    private void updateProgress(TransferUIComponents ui, long current, long total) {
        if (ui == null) return;

        double progress = total > 0 ? (double) current / total : 0;

        Platform.runLater(() -> {
            ui.progressBar.setProgress(progress);
            int percent = (int) (progress * 100);
            ui.percentLabel.setText(percent + "%");

            if (progress >= 0.99) {
                ui.statusLabel.setText("✓ Completed");
                ui.statusLabel.setTextFill(Color.web(SUCCESS_COLOR));
                ui.progressBar.setStyle("-fx-accent: " + SUCCESS_COLOR + ";");
                ui.card.setStyle(CARD_STYLE + " -fx-border-color: " + SUCCESS_COLOR + ";");
            } else {
                ui.statusLabel.setText("↻ " + ui.transferType + "...");
                ui.statusLabel.setTextFill(Color.web(SECONDARY_COLOR));
            }
        });
    }

    private static class TransferUIComponents {
        ProgressBar progressBar;
        Label percentLabel;
        Label statusLabel;
        VBox card;
        String transferType;

        public TransferUIComponents(ProgressBar pb, Label pl, Label sl, VBox c, String type) {
            this.progressBar = pb;
            this.percentLabel = pl;
            this.statusLabel = sl;
            this.card = c;
            this.transferType = type;
        }
    }

    private TransferUIComponents addTransferCard(String filename, String peerDisplay, String type, long totalBytes) {
        VBox card = new VBox(12);
        card.setStyle(CARD_STYLE);
        card.setPadding(new Insets(16));

        HBox headerRow = new HBox();
        headerRow.setSpacing(12);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        Label iconLbl = new Label(type.equals("Sending") ? "📤" : "📥");
        iconLbl.setStyle("-fx-font-size: 20px;");

        VBox infoBox = new VBox(2);
        Label nameLbl = new Label(filename);
        nameLbl.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: " + PRIMARY_COLOR + ";");
        nameLbl.setWrapText(true);

        Label peerLbl = new Label(peerDisplay);
        peerLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #9ca3af;");

        infoBox.getChildren().addAll(nameLbl, peerLbl);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label statusLbl = new Label("Waiting...");
        statusLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: " + WARNING_COLOR + ";");

        headerRow.getChildren().addAll(iconLbl, infoBox, spacer, statusLbl);

        HBox progressRow = new HBox(10);
        progressRow.setAlignment(Pos.CENTER_LEFT);

        ProgressBar pb = new ProgressBar(0);
        pb.setMinHeight(8);
        pb.setPrefHeight(8);
        pb.setStyle("-fx-accent: " + SECONDARY_COLOR + "; -fx-control-inner-background: " + BORDER_COLOR + ";");
        HBox.setHgrow(pb, Priority.ALWAYS);

        Label percentLbl = new Label("0%");
        percentLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: " + PRIMARY_COLOR + "; -fx-min-width: 40;");

        progressRow.getChildren().addAll(pb, percentLbl);

        card.getChildren().addAll(headerRow, progressRow);
        progressPanel.getChildren().add(0, card);

        return new TransferUIComponents(pb, percentLbl, statusLbl, card, type);
    }

    private ListView<PeerInfo> getListView(Stage stage) {
        // Safe lookup for the list view in the new structure
        if (stage.getScene() == null) return null;
        try {
            SplitPane split = (SplitPane) stage.getScene().getRoot().lookup(".split-pane");
            VBox leftPane = (VBox) split.getItems().get(0);
            for(javafx.scene.Node n : leftPane.getChildren()) {
                if (n instanceof ListView) {
                    return (ListView<PeerInfo>) n;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private void startDiscovery() {
        discoveryService = new DiscoveryService();
        discoveryService.setLocalName(Config.getDeviceName());
        discoveryService.setOnPeerFound(pi -> {
            Platform.runLater(() -> {
                String override = Config.getPeerName(pi.getIp());
                if (override != null) pi.setName(override);

                if (!peerList.contains(pi)) {
                    peerList.add(pi);
                } else {
                    int idx = peerList.indexOf(pi);
                    peerList.set(idx, pi);
                }
            });
        });
        Thread t = new Thread(discoveryService);
        t.setDaemon(true);
        t.start();
        Thread b = new Thread(() -> {
            while (true) {
                discoveryService.broadcastPresence();
                try {
                    Thread.sleep(3000);
                } catch (Exception e) {
                    logger.log(Level.FINE, "Discovery broadcaster sleep interrupted", e);
                }
            }
        });
        b.setDaemon(true);
        b.start();
    }

    private void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private void showNotification(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Info");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private String sanitizeName(String input, int maxLen) {
        if (input == null) return "";
        String s = input.replaceAll("[\r\n\t]", " ").trim();
        s = s.replaceAll("[|()]", "");
        if (s.length() > maxLen) s = s.substring(0, maxLen);
        return s;
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