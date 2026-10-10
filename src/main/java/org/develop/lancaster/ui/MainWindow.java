package org.develop.lancaster.ui;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.develop.lancaster.core.discovery.DiscoveryService;
import org.develop.lancaster.core.discovery.PeerInfo;
import org.develop.lancaster.core.network.Protocol;
import org.develop.lancaster.core.transfer.TransferEngine;
import org.develop.lancaster.core.transfer.TransferSession;
import org.develop.lancaster.core.util.Config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MainWindow extends Application implements TransferCard.Actions {

    private static final Logger logger = Logger.getLogger(MainWindow.class.getName());

    private Stage stage;
    private TransferEngine engine;
    private DiscoveryService discovery;

    // FX-thread only state
    private final ObservableList<PeerInfo> peerList = FXCollections.observableArrayList();
    private final Map<String, PeerInfo> peersByKey = new LinkedHashMap<>();
    private final Set<String> selectedKeys = new LinkedHashSet<>();
    private final Map<String, TransferCard> cards = new HashMap<>();

    private ListView<PeerInfo> peerListView;
    private VBox transfersBox;
    private Label emptyTransfersLabel;
    private Button sendBtn;
    private Label peerCountLabel;
    private Label discoveryStatusLabel;
    private Label portLabel;
    private Label activeLabel;
    private Label speedLabel;
    private Label completedLabel;
    private Label toastLabel;
    private PauseTransition toastHider;
    private int completedCount = 0;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;

        engine = new TransferEngine(Config.getInstanceId(), Config.getDeviceName());
        engine.setSessionCreatedListener(s -> Platform.runLater(() -> onSessionCreated(s)));
        engine.setPairingHandler(req -> {
            CompletableFuture<Boolean> answer = new CompletableFuture<>();
            Platform.runLater(() -> showPairingRequest(req, answer));
            return answer;
        });
        engine.setUnpairedOfferListener(msg -> Platform.runLater(() -> toast(msg)));
        String startupError = null;
        try {
            engine.start();
        } catch (IOException e) {
            startupError = "Could not open a listening port: " + e.getMessage();
            logger.log(Level.SEVERE, startupError, e);
        }

        discovery = new DiscoveryService(Config.getInstanceId(), Config.getDeviceName(), engine::getPort);
        discovery.setOnPeerFound(pi -> Platform.runLater(() -> onPeerSeen(pi)));
        discovery.start();

        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setTop(createHeader());

        SplitPane split = new SplitPane(createPeerPane(), createTransferPane());
        split.setDividerPositions(0.32);
        root.setCenter(split);

        toastLabel = new Label();
        toastLabel.getStyleClass().add("toast");
        toastLabel.setVisible(false);
        toastLabel.setMouseTransparent(true);
        StackPane.setAlignment(toastLabel, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toastLabel, new Insets(0, 0, 24, 0));

        StackPane stack = new StackPane(root, toastLabel);
        Scene scene = new Scene(stack, 1180, 760);
        URL css = getClass().getResource("/org/develop/lancaster/modern.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

        // Drag & drop files anywhere in the window to send them.
        stack.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        stack.setOnDragDropped(e -> {
            if (e.getDragboard().hasFiles()) {
                sendFiles(e.getDragboard().getFiles());
                e.setDropCompleted(true);
            }
            e.consume();
        });

        primaryStage.setTitle("LAN-Caster");
        try (InputStream icon = getClass().getResourceAsStream("/org/develop/lancaster/logo.png")) {
            if (icon != null) primaryStage.getIcons().add(new Image(icon));
        } catch (IOException ignored) {}
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(600);
        primaryStage.setOnCloseRequest(e -> stop());
        primaryStage.show();

        Timeline ticker = new Timeline(new KeyFrame(Duration.millis(300), e -> tick()));
        ticker.setCycleCount(Animation.INDEFINITE);
        ticker.play();

        if (startupError != null) {
            showError("Network error", startupError + "\n\nOther devices will not be able to send you files.");
        }
    }

    // ------------------------------------------------------------------ header

    private HBox createHeader() {
        HBox bar = new HBox(16);
        bar.getStyleClass().add("header-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("LAN-Caster");
        title.getStyleClass().add("app-title");

        Label nameLbl = new Label("This device:");
        nameLbl.getStyleClass().add("header-label");

        TextField nameField = new TextField(Config.getDeviceName());
        nameField.getStyleClass().add("header-field");
        nameField.setPromptText("Device name");
        nameField.setPrefWidth(200);
        Button saveName = new Button("Save");
        saveName.getStyleClass().addAll("btn", "btn-secondary");
        saveName.setOnAction(e -> {
            String n = sanitizeName(nameField.getText(), 32);
            Config.setDeviceName(n);
            String effective = Config.getDeviceName();
            nameField.setText(effective);
            engine.setDeviceName(effective);
            discovery.setLocalName(effective);
            toast("Device name saved: " + effective);
        });
        nameField.setOnAction(e -> saveName.fire());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        portLabel = new Label();
        portLabel.getStyleClass().add("header-meta");
        portLabel.setText(engine.getPort() > 0 ? "Listening on port " + engine.getPort() : "Not listening");
        Tooltip.install(portLabel, new Tooltip("Other devices connect to this TCP port. Allow it in your firewall if transfers fail."));

        Button settings = new Button("Settings");
        settings.getStyleClass().addAll("btn", "btn-header");
        settings.setOnAction(e -> showSettings());

        bar.getChildren().addAll(title, nameLbl, nameField, saveName, spacer, portLabel, settings);
        return bar;
    }

    // ------------------------------------------------------------------ peers

    private VBox createPeerPane() {
        VBox pane = new VBox(12);
        pane.getStyleClass().add("side-pane");
        pane.setPadding(new Insets(18));

        Label header = new Label("Devices");
        header.getStyleClass().add("pane-title");
        peerCountLabel = new Label("Searching…");
        peerCountLabel.getStyleClass().add("pane-subtitle");
        VBox titles = new VBox(2, header, peerCountLabel);

        peerListView = new ListView<>(peerList);
        peerListView.getStyleClass().add("peer-list");
        peerListView.setCellFactory(lv -> new PeerCell());
        peerListView.setFocusTraversable(false);
        peerListView.setPlaceholder(placeholder("No devices found yet.\nOpen LAN-Caster on another computer on the same Wi-Fi, or add it by IP below.\nDevices must be paired once before files can be exchanged."));
        VBox.setVgrow(peerListView, Priority.ALWAYS);

        HBox selectRow = new HBox(8);
        Button selectAll = linkButton("Select all paired", e -> {
            for (PeerInfo p : peerList) if (p.isOnline() && p.isCompatible() && Config.isPaired(p.getId())) selectedKeys.add(p.key());
            peerListView.refresh();
            updateSendButton();
        });
        Button clearSel = linkButton("Clear", e -> {
            selectedKeys.clear();
            peerListView.refresh();
            updateSendButton();
        });
        selectRow.getChildren().addAll(selectAll, clearSel);

        TextField ipField = new TextField();
        ipField.setPromptText("Add by IP, e.g. 192.168.1.20 or 192.168.1.20:5000");
        HBox.setHgrow(ipField, Priority.ALWAYS);
        Button addBtn = new Button("Add");
        addBtn.getStyleClass().addAll("btn", "btn-secondary");
        addBtn.setOnAction(e -> addPeerByAddress(ipField.getText(), ipField));
        ipField.setOnAction(e -> addBtn.fire());
        HBox addRow = new HBox(8, ipField, addBtn);
        addRow.setAlignment(Pos.CENTER_LEFT);

        discoveryStatusLabel = new Label("");
        discoveryStatusLabel.getStyleClass().add("hint-text");
        discoveryStatusLabel.setWrapText(true);

        pane.getChildren().addAll(titles, peerListView, selectRow, addRow, discoveryStatusLabel);
        return pane;
    }

    private final class PeerCell extends ListCell<PeerInfo> {
        private final CheckBox check = new CheckBox();
        private final Label name = new Label();
        private final Label meta = new Label();
        private final Label dot = new Label("●");
        private final Label pairChip = new Label("Pair");
        private final Tooltip dotTip = new Tooltip();
        private final HBox box;

        PeerCell() {
            Tooltip.install(dot, dotTip);
            check.setMouseTransparent(true);
            check.setFocusTraversable(false);
            pairChip.getStyleClass().addAll("chip", "chip-warn");
            pairChip.setMouseTransparent(true);
            name.getStyleClass().add("peer-name");
            meta.getStyleClass().add("peer-meta");
            VBox text = new VBox(2, name, meta);
            HBox.setHgrow(text, Priority.ALWAYS);
            box = new HBox(12, check, pairChip, text, dot);
            box.setAlignment(Pos.CENTER_LEFT);
            box.getStyleClass().add("peer-cell");

            setOnMouseClicked(e -> {
                PeerInfo p = getItem();
                if (p == null || e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
                if (!p.isCompatible()) { toast(p.displayName() + " runs an older LAN-Caster. Update it first."); return; }
                if (!Config.isPaired(p.getId())) { startPairing(p); return; }
                if (selectedKeys.contains(p.key())) selectedKeys.remove(p.key()); else selectedKeys.add(p.key());
                updateItem(p, false);
                updateSendButton();
            });

            ContextMenu menu = new ContextMenu();
            MenuItem pair = new MenuItem("Pair…");
            pair.setOnAction(e -> {
                PeerInfo p = getItem();
                if (p == null) return;
                if (Config.isPaired(p.getId())) {
                    engine.unpair(p.getId());
                    selectedKeys.remove(p.key());
                    peerListView.refresh();
                    updateSendButton();
                    toast("Unpaired " + p.displayName());
                } else {
                    startPairing(p);
                }
            });
            menu.setOnShowing(e -> {
                PeerInfo p = getItem();
                pair.setText(p != null && Config.isPaired(p.getId()) ? "Unpair" : "Pair…");
            });
            MenuItem rename = new MenuItem("Rename…");
            rename.setOnAction(e -> { if (getItem() != null) renamePeer(getItem()); });
            MenuItem copyIp = new MenuItem("Copy IP address");
            copyIp.setOnAction(e -> {
                if (getItem() == null) return;
                ClipboardContent cc = new ClipboardContent();
                cc.putString(getItem().getIp());
                Clipboard.getSystemClipboard().setContent(cc);
                toast("Copied " + getItem().getIp());
            });
            MenuItem remove = new MenuItem("Remove from list");
            remove.setOnAction(e -> { if (getItem() != null) removePeer(getItem()); });
            menu.getItems().addAll(pair, rename, copyIp, remove);
            setContextMenu(menu);
        }

        @Override
        protected void updateItem(PeerInfo p, boolean empty) {
            super.updateItem(p, empty);
            if (empty || p == null) {
                setGraphic(null);
                return;
            }
            boolean paired = Config.isPaired(p.getId());
            boolean selected = paired && selectedKeys.contains(p.key());
            check.setSelected(selected);
            check.setDisable(!p.isCompatible());
            check.setVisible(paired);
            check.setManaged(paired);
            pairChip.setVisible(!paired);
            pairChip.setManaged(!paired);
            name.setText(p.displayName());
            StringBuilder m = new StringBuilder(p.getIp());
            if (p.getPort() != Protocol.DEFAULT_PORT) m.append(':').append(p.getPort());
            if (!p.getOs().isEmpty()) m.append("  ·  ").append(p.getOs());
            m.append(paired ? "  ·  paired" : "  ·  not paired, click to pair");
            if (!p.isCompatible()) m.append("  ·  needs update");
            else if (p.isManual()) m.append("  ·  added by IP");
            else if (!p.isOnline()) m.append("  ·  last seen ").append(Fmt.ago(System.currentTimeMillis() - p.getLastSeen()));
            meta.setText(m.toString());
            dot.getStyleClass().setAll(p.isOnline() ? "dot-online" : "dot-offline");
            dotTip.setText(p.isOnline() ? "Online" : "Not responding");
            box.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("checked"), selected);
            box.setOpacity(p.isOnline() ? 1.0 : 0.55);
            setGraphic(box);
        }
    }

    private void onPeerSeen(PeerInfo fresh) {
        String custom = Config.getPeerName(fresh.getIp());
        PeerInfo existing = peersByKey.get(fresh.key());
        if (existing == null) {
            if (custom != null) fresh.setName(custom);
            peersByKey.put(fresh.key(), fresh);
            peerList.add(fresh);
            if (fresh.isCompatible()) toast("Found " + fresh.displayName());
        } else {
            boolean wasOnline = existing.isOnline();
            existing.updateFrom(fresh, custom != null);
            if (custom != null) existing.setName(custom);
            if (!wasOnline) peerListView.refresh();
        }
        updatePeerCount();
    }

    private void addPeerByAddress(String text, TextField field) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return;
        String ip = t;
        int port = Protocol.DEFAULT_PORT;
        int colon = t.lastIndexOf(':');
        if (colon > 0 && t.indexOf(':') == colon) {
            ip = t.substring(0, colon);
            try { port = Integer.parseInt(t.substring(colon + 1)); } catch (NumberFormatException e) { toast("Invalid port"); return; }
        }
        final String fIp = ip;
        final int fPort = port;
        toast("Looking for LAN-Caster at " + fIp + ":" + fPort + "…");
        Thread t2 = new Thread(() -> {
            try {
                PeerInfo p = engine.ping(fIp, fPort);
                Platform.runLater(() -> {
                    PeerInfo existing = peersByKey.get(p.key());
                    if (existing == null) {
                        String custom = Config.getPeerName(p.getIp());
                        if (custom != null) p.setName(custom);
                        peersByKey.put(p.key(), p);
                        peerList.add(p);
                    } else {
                        existing.updateFrom(p, Config.getPeerName(p.getIp()) != null);
                    }
                    selectedKeys.add(p.key());
                    field.clear();
                    peerListView.refresh();
                    updatePeerCount();
                    updateSendButton();
                    toast("Connected to " + p.displayName());
                });
            } catch (IOException e) {
                Platform.runLater(() -> toast("No LAN-Caster found at " + fIp + ":" + fPort + " (" + e.getMessage() + ")"));
            }
        }, "ping-peer");
        t2.setDaemon(true);
        t2.start();
    }

    // ------------------------------------------------------------------ pairing

    private void startPairing(PeerInfo p) {
        if (p.getId() == null) {
            toast("Cannot pair: unknown device id. Remove the device and add it again by IP.");
            return;
        }
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Pair with " + p.displayName());
        dlg.initOwner(stage);
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);

        Label intro = new Label("A pairing request was sent to " + p.displayName() + " (" + p.getIp() + ").\n"
                + "On that device, check that the same code is shown and press Pair.");
        intro.setWrapText(true);
        Label code = new Label("······");
        code.getStyleClass().add("pair-code");
        Label waiting = new Label("Waiting for " + p.displayName() + " to confirm…");
        waiting.getStyleClass().add("hint-text");
        VBox content = new VBox(14, intro, code, waiting);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(10));
        content.setPrefWidth(380);
        dlg.getDialogPane().setContent(content);
        dlg.show();

        engine.requestPairing(p, c -> Platform.runLater(() -> code.setText(c)))
                .thenAccept(r -> Platform.runLater(() -> {
                    dlg.close();
                    if (r.ok) {
                        if (r.peerName != null && Config.getPeerName(p.getIp()) == null) p.setName(r.peerName);
                        selectedKeys.add(p.key());
                        toast("Paired with " + p.displayName() + ". You can now send files to each other.");
                    } else {
                        toast("Pairing failed: " + r.message);
                    }
                    peerListView.refresh();
                    updateSendButton();
                }));
    }

    private void showPairingRequest(TransferEngine.PairingRequest req, CompletableFuture<Boolean> answer) {
        if (stage.isIconified()) stage.setIconified(false);
        stage.toFront();
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Pairing request");
        alert.setHeaderText(req.peerName + " wants to pair with this device");
        ButtonType pairBtn = new ButtonType("Pair", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType rejectBtn = new ButtonType("Reject", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(rejectBtn, pairBtn);
        Label text = new Label("Device: " + req.peerName + " (" + req.peerIp + ")\n\n"
                + "Only press Pair if " + req.peerName + " is showing this code:");
        text.setWrapText(true);
        Label code = new Label(req.code);
        code.getStyleClass().add("pair-code");
        VBox box = new VBox(12, text, code);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPrefWidth(380);
        alert.getDialogPane().setContent(box);
        alert.initOwner(stage);
        Optional<ButtonType> r = alert.showAndWait();
        boolean ok = r.isPresent() && r.get() == pairBtn;
        answer.complete(ok);
        if (ok) {
            toast("Paired with " + req.peerName);
            peerListView.refresh();
        }
    }

    private void renamePeer(PeerInfo p) {
        TextInputDialog dlg = new TextInputDialog(Optional.ofNullable(p.getName()).orElse(""));
        dlg.setTitle("Rename device");
        dlg.setHeaderText("Friendly name for " + p.getIp());
        dlg.setContentText("Name:");
        dlg.initOwner(stage);
        dlg.showAndWait().ifPresent(n -> {
            String clean = sanitizeName(n, 32);
            Config.setPeerName(p.getIp(), clean);
            p.setName(clean);
            peerListView.refresh();
        });
    }

    private void removePeer(PeerInfo p) {
        peersByKey.remove(p.key());
        peerList.remove(p);
        selectedKeys.remove(p.key());
        updatePeerCount();
        updateSendButton();
    }

    private void updatePeerCount() {
        long online = peerList.stream().filter(PeerInfo::isOnline).count();
        if (peerList.isEmpty()) peerCountLabel.setText("Searching your network…");
        else peerCountLabel.setText(online + " online  ·  " + selectedKeys.size() + " selected");
    }

    private void updateSendButton() {
        int n = selectedKeys.size();
        sendBtn.setDisable(n == 0);
        sendBtn.setText(n == 0 ? "Select a device to send files"
                : "Send files to " + n + (n == 1 ? " device" : " devices"));
        updatePeerCount();
    }

    private List<PeerInfo> selectedPeers() {
        List<PeerInfo> out = new ArrayList<>();
        for (String k : selectedKeys) {
            PeerInfo p = peersByKey.get(k);
            if (p != null) out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------ transfers

    private VBox createTransferPane() {
        VBox pane = new VBox(14);
        pane.getStyleClass().add("main-pane");
        pane.setPadding(new Insets(18));

        Label header = new Label("Transfers");
        header.getStyleClass().add("pane-title");

        activeLabel = new Label("0");
        speedLabel = new Label("0 B/s");
        completedLabel = new Label("0");
        HBox stats = new HBox(12,
                statCard("Active", activeLabel, "stat-blue"),
                statCard("Current speed", speedLabel, "stat-green"),
                statCard("Completed", completedLabel, "stat-amber"));

        sendBtn = new Button();
        sendBtn.getStyleClass().addAll("btn", "btn-primary", "btn-big");
        sendBtn.setMaxWidth(Double.MAX_VALUE);
        sendBtn.setOnAction(e -> chooseAndSend());
        updateSendButton();

        Label dropHint = new Label("Tip: you can also drag files from your file manager and drop them on this window.");
        dropHint.getStyleClass().add("hint-text");

        transfersBox = new VBox(10);
        emptyTransfersLabel = placeholder("No transfers yet.\nFiles other devices send you will show up here with an Accept button.");
        transfersBox.getChildren().add(emptyTransfersLabel);

        ScrollPane scroll = new ScrollPane(transfersBox);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("transfer-scroll");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        HBox listHeader = new HBox();
        listHeader.setAlignment(Pos.CENTER_LEFT);
        Label listTitle = new Label("Activity");
        listTitle.getStyleClass().add("section-title");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button clear = linkButton("Clear finished", e -> {
            for (TransferSession s : engine.getSessions()) if (!s.isActive()) dismiss(s);
        });
        listHeader.getChildren().addAll(listTitle, sp, clear);

        pane.getChildren().addAll(header, stats, sendBtn, dropHint, listHeader, scroll);
        return pane;
    }

    private VBox statCard(String title, Label value, String colorClass) {
        VBox card = new VBox(4);
        card.getStyleClass().addAll("card", "stat-card");
        card.setAlignment(Pos.CENTER);
        HBox.setHgrow(card, Priority.ALWAYS);
        Label t = new Label(title);
        t.getStyleClass().add("stat-title");
        value.getStyleClass().addAll("stat-value", colorClass);
        card.getChildren().addAll(t, value);
        return card;
    }

    private void chooseAndSend() {
        if (selectedKeys.isEmpty()) {
            toast("Select at least one device first");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose files to send");
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files != null && !files.isEmpty()) sendFiles(files);
    }

    private void sendFiles(List<File> files) {
        List<PeerInfo> peers = selectedPeers();
        if (peers.isEmpty()) {
            toast("Select a device in the list first, then drop or choose files");
            return;
        }
        List<File> regular = new ArrayList<>();
        int folders = 0;
        for (File f : files) {
            if (f.isDirectory()) folders++;
            else if (f.isFile()) regular.add(f);
        }
        if (folders > 0) toast("Folders are not supported yet, zip them first (" + folders + " skipped)");
        if (regular.isEmpty()) return;
        engine.sendFiles(regular, peers);
        toast("Offering " + regular.size() + (regular.size() == 1 ? " file to " : " files to ")
                + peers.size() + (peers.size() == 1 ? " device" : " devices"));
    }

    private void onSessionCreated(TransferSession s) {
        TransferCard card = new TransferCard(s, this);
        cards.put(s.getId(), card);
        transfersBox.getChildren().remove(emptyTransfersLabel);
        transfersBox.getChildren().add(0, card);
        s.addListener(sess -> Platform.runLater(() -> onSessionChanged(sess)));
        if (s.getState() == TransferSession.State.INCOMING_OFFER) {
            toast(s.getPeerName() + " wants to send you " + s.getFileName() + " (" + Fmt.bytes(s.getFileSize()) + ")");
            stage.toFront();
            if (stage.isIconified()) stage.setIconified(false);
        }
        updateStats();
    }

    private void onSessionChanged(TransferSession s) {
        TransferCard card = cards.get(s.getId());
        if (card != null) card.refresh();
        if (s.getState() == TransferSession.State.COMPLETED) {
            completedCount++;
            toast((s.getDirection() == TransferSession.Direction.SEND ? "Sent " : "Received ") + s.getFileName());
        } else if (s.getState() == TransferSession.State.FAILED) {
            toast(s.getFileName() + ": " + s.getMessage());
        }
        updateStats();
    }

    private void tick() {
        // peers: expire silent ones, refresh online indicators
        boolean changed = false;
        for (Iterator<PeerInfo> it = peerList.iterator(); it.hasNext(); ) {
            PeerInfo p = it.next();
            if (p.isExpired()) {
                it.remove();
                peersByKey.remove(p.key());
                if (selectedKeys.remove(p.key())) updateSendButton();
                changed = true;
            }
        }
        if (changed || !peerList.isEmpty()) peerListView.refresh();
        updatePeerCount();
        String err = discovery.getLastError();
        discoveryStatusLabel.setText(err == null ? "" : "Discovery problem: " + err);

        // transfers
        for (TransferCard c : cards.values()) {
            if (c.getSession().isActive()) c.refresh();
        }
        updateStats();
        if (engine.getPort() > 0) portLabel.setText("Listening on port " + engine.getPort());
    }

    private void updateStats() {
        int active = 0;
        double bps = 0;
        for (TransferSession s : engine.getSessions()) {
            if (s.isActive()) active++;
            bps += s.bytesPerSecond();
        }
        activeLabel.setText(String.valueOf(active));
        speedLabel.setText(Fmt.speed(bps));
        completedLabel.setText(String.valueOf(completedCount));
    }

    // ------------------------------------------------------------------ card actions

    @Override
    public void accept(TransferSession s, boolean chooseFolder) {
        Path dir = Config.getDownloadDir();
        if (chooseFolder) {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Save " + s.getFileName() + " to…");
            dc.setInitialDirectory(dir.toFile());
            File chosen = dc.showDialog(stage);
            if (chosen == null) return;
            dir = chosen.toPath();
        }
        s.setMessage("Saving to " + dir);
        s.accept(dir);
    }

    @Override
    public void decline(TransferSession s) { s.decline(); }

    @Override
    public void cancel(TransferSession s) { s.requestCancel(); }

    @Override
    public void retry(TransferSession s) {
        PeerInfo peer = peersByKey.get(s.getPeerKey());
        if (peer == null) {
            peer = PeerInfo.manual(s.getPeerIp(), s.getPeerPort() > 0 ? s.getPeerPort() : Protocol.DEFAULT_PORT);
            peer.setName(s.getPeerName());
        }
        Path local = s.getLocalFile();
        if (local == null || !local.toFile().isFile()) {
            toast("The original file is no longer available");
            return;
        }
        dismiss(s);
        engine.sendFile(local.toFile(), peer);
    }

    @Override
    public void openFolder(Path p) {
        if (p == null) return;
        try {
            getHostServices().showDocument(p.toUri().toString());
        } catch (RuntimeException e) {
            toast("Saved in " + p);
        }
    }

    @Override
    public void dismiss(TransferSession s) {
        engine.removeSession(s);
        TransferCard c = cards.remove(s.getId());
        if (c != null) transfersBox.getChildren().remove(c);
        if (cards.isEmpty() && !transfersBox.getChildren().contains(emptyTransfersLabel)) {
            transfersBox.getChildren().add(emptyTransfersLabel);
        }
        updateStats();
    }

    // ------------------------------------------------------------------ settings

    private void showSettings() {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Settings");
        dlg.initOwner(stage);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField dirField = new TextField(Config.getDownloadDir().toString());
        dirField.setPrefWidth(320);
        Button browse = new Button("Browse…");
        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Download folder");
            File cur = new File(dirField.getText());
            if (cur.isDirectory()) dc.setInitialDirectory(cur);
            File chosen = dc.showDialog(stage);
            if (chosen != null) dirField.setText(chosen.getAbsolutePath());
        });

        ChoiceBox<String> streams = new ChoiceBox<>(FXCollections.observableArrayList(
                "Auto", "1", "2", "3", "4", "6", "8", "12", "16"));
        int cur = Config.getParallelStreams();
        streams.setValue(cur == 0 ? "Auto" : String.valueOf(cur));
        if (!streams.getItems().contains(streams.getValue())) streams.setValue("Auto");

        CheckBox auto = new CheckBox("Automatically accept files from paired devices into the download folder");
        auto.setSelected(Config.isAutoAccept());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(12);
        grid.setPadding(new Insets(10));
        grid.add(new Label("Download folder"), 0, 0);
        grid.add(new HBox(6, dirField, browse), 1, 0);
        grid.add(new Label("Parallel streams"), 0, 1);
        grid.add(new HBox(10, streams, hint("Auto = " + Config.resolveParallelStreams() + " on this machine. More streams help on fast networks; fewer on slow Wi-Fi.")), 1, 1);
        grid.add(auto, 1, 2);
        grid.add(hint("Listening on TCP port " + engine.getPort() + " and UDP port " + Protocol.DISCOVERY_PORT
                + ". If a device cannot see or reach you, allow these in the firewall."), 1, 3);
        dlg.getDialogPane().setContent(grid);

        Optional<ButtonType> r = dlg.showAndWait();
        if (r.isPresent() && r.get() == ButtonType.OK) {
            Config.setDownloadDir(Path.of(dirField.getText().trim()));
            Config.setParallelStreams("Auto".equals(streams.getValue()) ? 0 : Integer.parseInt(streams.getValue()));
            Config.setAutoAccept(auto.isSelected());
            toast("Settings saved");
        }
    }

    // ------------------------------------------------------------------ helpers

    private Label hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("hint-text");
        l.setWrapText(true);
        l.setMaxWidth(380);
        return l;
    }

    private Label placeholder(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("placeholder-text");
        l.setWrapText(true);
        l.setAlignment(Pos.CENTER);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setPadding(new Insets(30));
        return l;
    }

    private Button linkButton(String text, javafx.event.EventHandler<javafx.event.ActionEvent> h) {
        Button b = new Button(text);
        b.getStyleClass().addAll("btn", "btn-link");
        b.setOnAction(h);
        return b;
    }

    private void toast(String message) {
        if (toastLabel == null) return;
        toastLabel.setText(message);
        toastLabel.setOpacity(1);
        toastLabel.setVisible(true);
        if (toastHider != null) toastHider.stop();
        toastHider = new PauseTransition(Duration.seconds(3.5 + message.length() / 40.0));
        toastHider.setOnFinished(e -> {
            FadeTransition fade = new FadeTransition(Duration.millis(400), toastLabel);
            fade.setToValue(0);
            fade.setOnFinished(ev -> toastLabel.setVisible(false));
            fade.play();
        });
        toastHider.play();
    }

    private void showError(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.initOwner(stage);
        alert.showAndWait();
    }

    private static String sanitizeName(String input, int maxLen) {
        if (input == null) return "";
        String s = input.replaceAll("[\r\n\t|]", " ").trim();
        if (s.length() > maxLen) s = s.substring(0, maxLen);
        return s;
    }

    @Override
    public void stop() {
        logger.info("Shutting down");
        try {
            if (discovery != null) discovery.stop();
            if (engine != null) engine.shutdown();
        } finally {
            Platform.exit();
            System.exit(0);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
