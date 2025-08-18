package com.example.gawsddns;

import software.amazon.awscdk.App;

public class DDApp {
    public static void main(final String[] args) {
        App app = new App();
        new DDStack(app, "DDStack");
        app.synth();
    }
}
