package com.example.gawsddns;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.apigateway.LambdaRestApi;
import software.constructs.Construct;
import java.util.List;

public class DDStack extends Stack {
    public DDStack(final Construct scope, final String id) {
        super(scope, id);

        Function lambda = Function.Builder.create(this, "DyndnsLambda")
            .runtime(Runtime.JAVA_17)
            .handler("com.example.gawsddns.DDLambda::handleRequest")
            .code(Code.fromAsset("target/gawsddns-1.0-SNAPSHOT.jar"))
            .memorySize(512)
            .timeout(Duration.seconds(10))
            .build();

        lambda.addToRolePolicy(PolicyStatement.Builder.create()
            .actions(List.of("route53:ChangeResourceRecordSets"))
            .resources(List.of("*")) // You can scope this to specific hosted zone
            .effect(Effect.ALLOW)
            .build());

        LambdaRestApi.Builder.create(this, "DyndnsApi")
            .handler(lambda)
            .build();
    }
}
