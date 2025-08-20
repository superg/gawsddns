import * as cdk from 'aws-cdk-lib';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as apigateway from 'aws-cdk-lib/aws-apigateway';
import * as route53 from 'aws-cdk-lib/aws-route53';
import * as certificatemanager from 'aws-cdk-lib/aws-certificatemanager';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as targets from 'aws-cdk-lib/aws-route53-targets';
import { Construct } from 'constructs';

export class DDStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    // Create Lambda function (currently Java, will be converted to Rust later)
    const ddLambda = new lambda.Function(this, 'DDLambda', {
      runtime: lambda.Runtime.JAVA_17,
      handler: 'com.example.gawsddns.DDLambda::handleRequest',
      code: lambda.Code.fromAsset('target/gawsddns-1.0-SNAPSHOT.jar'),
      memorySize: 512,
      timeout: cdk.Duration.seconds(10),
    });

    // Add IAM permissions for Route53
    ddLambda.addToRolePolicy(new iam.PolicyStatement({
      actions: ['route53:ChangeResourceRecordSets', 'route53:ListHostedZones'],
      resources: ['*'], // You can scope this to specific hosted zone
      effect: iam.Effect.ALLOW,
    }));

    // Look up your existing wildcard certificate for *.gretrostuff.com
    const certificate = certificatemanager.Certificate.fromCertificateArn(
      this,
      'Certificate',
      'arn:aws:acm:us-east-1:223166462382:certificate/b8d54284-3534-4b4f-bac6-726c8fed930a'
    );

    // Look up your existing hosted zone for gretrostuff.com
    const hostedZone = route53.HostedZone.fromLookup(this, 'HostedZone', {
      domainName: 'gretrostuff.com',
    });

    // Create the custom domain
    const customDomain = new apigateway.DomainName(this, 'CustomDomain', {
      domainName: 'members.gretrostuff.com',
      certificate: certificate,
    });

    // Create the API with direct Lambda integration
    const api = new apigateway.RestApi(this, 'DDApi', {
      restApiName: 'Dynamic DNS API',
    });

    // Create Lambda integration with proxy
    const integration = new apigateway.LambdaIntegration(ddLambda, {
      proxy: true, // Enable proxy integration
    });

    // Add /nic/update path for Dyn compatibility
    api.root
      .addResource('nic')
      .addResource('update')
      .addMethod('GET', integration);

    // Add /v3/update path for Dyn API v3 compatibility
    api.root
      .addResource('v3')
      .addResource('update')
      .addMethod('GET', integration);

    // Add a proxy resource to catch any other paths
    api.root.addProxy();

    // Map the custom domain to the API for Dyn ddclient compatibility
    new apigateway.BasePathMapping(this, 'BasePathMapping', {
      domainName: customDomain,
      restApi: api,
      basePath: '', // Empty string means root path - Lambda will handle /nic/update routing
    });

    // Create DNS record to point members.gretrostuff.com to the custom domain
    new route53.ARecord(this, 'DDNSRecord', {
      zone: hostedZone,
      recordName: 'members',
      target: route53.RecordTarget.fromAlias(new targets.ApiGatewayDomain(customDomain)),
    });
  }
}
