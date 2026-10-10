module org.develop.lancaster {
    requires javafx.controls;
    requires java.logging;

    exports org.develop.lancaster;
    exports org.develop.lancaster.core.discovery;
    exports org.develop.lancaster.core.network;
    exports org.develop.lancaster.core.transfer;
    exports org.develop.lancaster.core.util;
    exports org.develop.lancaster.ui;

    opens org.develop.lancaster.ui to javafx.graphics;
}
