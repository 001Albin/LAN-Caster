module org.develop.lancaster {
    requires javafx.controls;
    requires javafx.fxml;


    opens org.develop.lancaster to javafx.fxml;
    exports org.develop.lancaster;
}