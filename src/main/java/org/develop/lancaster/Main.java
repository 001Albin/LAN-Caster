package org.develop.lancaster;

import javafx.application.Application;
import org.develop.lancaster.ui.MainWindow;

import java.util.logging.Logger;

public class Main {
    private static final Logger logger = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> logger.info("[Main] JVM shutting down")));
        Application.launch(MainWindow.class, args);
    }
}
