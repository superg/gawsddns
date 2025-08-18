package com.example.gawsddns;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.apigateway.LambdaRestApi;
import software.amazon.awscdk.services.apigateway.DomainName;
import software.amazon.awscdk.services.apigateway.BasePathMapping;
import software.amazon.awscdk.services.certificatemanager.ICertificate;
import software.amazon.awscdk.services.certificatemanager.Certificate;
import software.amazon.awscdk.services.route53.IHostedZone;
import software.amazon.awscdk.services.route53.HostedZone;
import software.amazon.awscdk.services.route53.HostedZoneProviderProps;
import software.amazon.awscdk.services.route53.ARecord;
import software.amazon.awscdk.services.route53.RecordTarget;
import software.amazon.awscdk.services.route53.targets.ApiGatewayDomain;
import software.constructs.Construct;
import java.util.List;

public class DDStack extends Stack {
    public DDStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        Function lambda = Function.Builder.create(this, "DDLambda")
            .runtime(Runtime.JAVA_17)
            .handler("com.example.gawsddns.DDLambda::handleRequest")
            .code(Code.fromAsset("target/gawsddns-1.0-SNAPSHOT.jar"))
            .memorySize(512)
            .timeout(Duration.seconds(10))
            .build();

        lambda.addToRolePolicy(PolicyStatement.Builder.create()
            .actions(List.of("route53:ChangeResourceRecordSets", "route53:ListHostedZones"))
            .resources(List.of("*")) // You can scope this to specific hosted zone
            .effect(Effect.ALLOW)
            .build());

        // Look up your existing wildcard certificate for *.gretrostuff.com
        ICertificate certificate = Certificate.fromCertificateArn(this, "Certificate",
            "arn:aws:acm:us-east-1:223166462382:certificate/b8d54284-3534-4b4f-bac6-726c8fed930a");

        // Look up your existing hosted zone for gretrostuff.com
        IHostedZone hostedZone = HostedZone.fromLookup(this, "HostedZone", 
            HostedZoneProviderProps.builder()
                .domainName("gretrostuff.com")
                .build());

        // Create the custom domain
        DomainName customDomain = DomainName.Builder.create(this, "CustomDomain")
            .domainName("ddns.gretrostuff.com")
            .certificate(certificate)
            .build();

        // Create the API
        LambdaRestApi api = LambdaRestApi.Builder.create(this, "DDApi")
            .handler(lambda)
            .build();

        // Map the custom domain to the API
        BasePathMapping.Builder.create(this, "BasePathMapping")
            .domainName(customDomain)
            .restApi(api)
            .basePath("") // Empty string means root path
            .build();

        // Create DNS record to point ddns.gretrostuff.com to the custom domain
        ARecord.Builder.create(this, "DDNSRecord")
            .zone(hostedZone)
            .recordName("ddns")
            .target(RecordTarget.fromAlias(new ApiGatewayDomain(customDomain)))
            .build();
    }
}
