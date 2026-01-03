module org.develop.lancaster {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.base;
    requires java.logging;

    // Export core packages so other parts of the app can see them
    exports org.develop.lancaster.core.discovery;
    exports org.develop.lancaster.core.network;
    exports org.develop.lancaster.core.transfer;
    exports org.develop.lancaster.ui; // We are about to create this

    // Allow JavaFX to access the UI classes
    opens org.develop.lancaster.ui to javafx.fxml;
}