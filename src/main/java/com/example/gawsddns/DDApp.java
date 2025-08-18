package com.example.gawsddns;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public class DDApp {
    public static void main(final String[] args) {
        App app = new App();
        
        // Specify the environment (account and region) for hosted zone lookup
        Environment env = Environment.builder()
            .account("223166462382")  // Your AWS account ID
            .region("us-east-1")      // Your region
            .build();
            
        StackProps props = StackProps.builder()
            .env(env)
            .build();
            
        new DDStack(app, "DDStack", props);
        app.synth();
    }
}
