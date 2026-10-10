package org.develop.lancaster.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.develop.lancaster.core.transfer.TransferSession;
import org.develop.lancaster.core.transfer.TransferSession.State;

import java.nio.file.Path;

/**
 * One row in the transfer list. {@link #refresh()} is cheap and is called by a
 * timer a few times per second while the transfer is active.
 */
final class TransferCard extends VBox {

    interface Actions {
        void accept(TransferSession s, boolean chooseFolder);
        void decline(TransferSession s);
        void cancel(TransferSession s);
        void retry(TransferSession s);
        void openFolder(Path p);
        void dismiss(TransferSession s);
    }

    private final TransferSession session;
    private final Actions actions;

    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final Label chip = new Label();
    private final ProgressBar bar = new ProgressBar(0);
    private final Label detail = new Label();
    private final HBox actionRow = new HBox(8);
    private State renderedState;

    TransferCard(TransferSession session, Actions actions) {
        this.session = session;
        this.actions = actions;
        getStyleClass().add("card");
        setSpacing(10);

        Label icon = new Label(session.getDirection() == TransferSession.Direction.SEND ? "↑" : "↓");
        icon.getStyleClass().add(session.getDirection() == TransferSession.Direction.SEND ? "dir-icon-send" : "dir-icon-recv");

        title.getStyleClass().add("card-title");
        title.setText(session.getFileName());
        title.setWrapText(true);
        Tooltip.install(title, new Tooltip(session.getFileName()));

        subtitle.getStyleClass().add("card-subtitle");
        subtitle.setText((session.getDirection() == TransferSession.Direction.SEND ? "To " : "From ")
                + session.getPeerName() + "  ·  " + Fmt.bytes(session.getFileSize()));

        VBox text = new VBox(2, title, subtitle);
        HBox.setHgrow(text, Priority.ALWAYS);

        chip.getStyleClass().add("chip");

        HBox header = new HBox(12, icon, text, chip);
        header.setAlignment(Pos.CENTER_LEFT);

        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(10);

        detail.getStyleClass().add("card-detail");
        detail.setWrapText(true);

        actionRow.setAlignment(Pos.CENTER_RIGHT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(10, detail, spacer, actionRow);
        footer.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(detail, Priority.ALWAYS);

        getChildren().addAll(header, bar, footer);
        refresh();
    }

    TransferSession getSession() { return session; }

    /** Update progress text; rebuilds buttons only when the state changed. */
    void refresh() {
        State st = session.getState();
        if (st != renderedState) {
            renderedState = st;
            renderState(st);
        }
        subtitle.setText((session.getDirection() == TransferSession.Direction.SEND ? "To " : "From ")
                + session.getPeerName() + "  ·  " + Fmt.bytes(session.getFileSize()));

        switch (st) {
            case TRANSFERRING: {
                double p = session.progress();
                bar.setProgress(p);
                StringBuilder sb = new StringBuilder();
                sb.append((int) Math.floor(p * 100)).append("%  ·  ")
                        .append(Fmt.bytes(session.getBytesDone())).append(" of ").append(Fmt.bytes(session.getFileSize()))
                        .append("  ·  ").append(Fmt.speed(session.bytesPerSecond()));
                long eta = session.etaSeconds();
                if (eta >= 0) sb.append("  ·  ").append(Fmt.duration(eta)).append(" left");
                if (session.getStreams() > 1) sb.append("  ·  ").append(session.getStreams()).append(" streams");
                if (session.isResumed()) sb.append("  ·  resumed");
                detail.setText(sb.toString());
                break;
            }
            case COMPLETED: {
                bar.setProgress(1);
                long secs = Math.max(0, (session.getFinishedAt() - (session.getStartedAt() > 0 ? session.getStartedAt() : session.getCreatedAt())) / 1000);
                String summary = Fmt.bytes(session.getFileSize()) + " in " + Fmt.duration(secs)
                        + "  ·  avg " + Fmt.speed(session.averageBytesPerSecond());
                detail.setText(session.getMessage().isEmpty() ? summary : summary + "\n" + session.getMessage());
                break;
            }
            case INCOMING_OFFER:
                detail.setText(session.getMessage());
                break;
            default:
                detail.setText(session.getMessage());
        }
    }

    private void renderState(State st) {
        chip.getStyleClass().removeAll("chip-info", "chip-success", "chip-danger", "chip-warn", "chip-muted");
        actionRow.getChildren().clear();
        getStyleClass().removeAll("card-success", "card-danger", "card-offer");
        bar.setVisible(true);
        bar.setManaged(true);

        switch (st) {
            case INCOMING_OFFER: {
                chip.setText("Incoming");
                chip.getStyleClass().add("chip-warn");
                getStyleClass().add("card-offer");
                bar.setVisible(false);
                bar.setManaged(false);
                Button accept = button("Accept", "btn-primary", e -> actions.accept(session, false));
                Button saveTo = button("Save to…", "btn-secondary", e -> actions.accept(session, true));
                Button decline = button("Decline", "btn-ghost", e -> actions.decline(session));
                actionRow.getChildren().addAll(decline, saveTo, accept);
                break;
            }
            case CONNECTING:
            case WAITING_ACCEPT: {
                chip.setText(st == State.CONNECTING ? "Connecting" : "Waiting");
                chip.getStyleClass().add("chip-info");
                bar.setProgress(-1);
                actionRow.getChildren().add(button("Cancel", "btn-ghost", e -> actions.cancel(session)));
                break;
            }
            case TRANSFERRING: {
                chip.setText(session.getDirection() == TransferSession.Direction.SEND ? "Sending" : "Receiving");
                chip.getStyleClass().add("chip-info");
                bar.setProgress(session.progress());
                actionRow.getChildren().add(button("Cancel", "btn-ghost", e -> actions.cancel(session)));
                break;
            }
            case FINALIZING: {
                chip.setText("Finishing");
                chip.getStyleClass().add("chip-info");
                bar.setProgress(-1);
                break;
            }
            case COMPLETED: {
                chip.setText("Completed");
                chip.getStyleClass().add("chip-success");
                getStyleClass().add("card-success");
                bar.setProgress(1);
                Path local = session.getLocalFile();
                if (local != null) {
                    actionRow.getChildren().add(button("Open folder", "btn-secondary", e -> actions.openFolder(local.getParent())));
                }
                actionRow.getChildren().add(button("Dismiss", "btn-ghost", e -> actions.dismiss(session)));
                break;
            }
            case DECLINED:
            case CANCELLED:
            case FAILED: {
                chip.setText(st == State.FAILED ? "Failed" : st == State.CANCELLED ? "Cancelled" : "Declined");
                chip.getStyleClass().add(st == State.FAILED ? "chip-danger" : "chip-muted");
                if (st == State.FAILED) getStyleClass().add("card-danger");
                bar.setProgress(session.progress());
                if (session.getDirection() == TransferSession.Direction.SEND && st != State.DECLINED) {
                    actionRow.getChildren().add(button("Retry", "btn-secondary", e -> actions.retry(session)));
                }
                actionRow.getChildren().add(button("Dismiss", "btn-ghost", e -> actions.dismiss(session)));
                break;
            }
            default:
                break;
        }
    }

    private static Button button(String text, String styleClass, javafx.event.EventHandler<javafx.event.ActionEvent> h) {
        Button b = new Button(text);
        b.getStyleClass().addAll("btn", styleClass);
        b.setOnAction(h);
        return b;
    }
}
