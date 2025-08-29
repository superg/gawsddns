import * as cdk from 'aws-cdk-lib';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as apigateway from 'aws-cdk-lib/aws-apigateway';
import * as route53 from 'aws-cdk-lib/aws-route53';
import * as certificatemanager from 'aws-cdk-lib/aws-certificatemanager';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as targets from 'aws-cdk-lib/aws-route53-targets';
import { Construct } from 'constructs';

export interface DDStackProps extends cdk.StackProps {
  config: {
    certificateId: string;
    domainName: string;
    subdomainName: string;
  };
}

export class DDStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: DDStackProps) {
    super(scope, id, props);

    const ddLambda = new lambda.Function(this, 'DDLambda', {
      runtime: lambda.Runtime.JAVA_17,
      handler: 'com.example.gawsddns.DDLambda::handleRequest',
      code: lambda.Code.fromAsset('target/gawsddns-1.0-SNAPSHOT.jar'),
      memorySize: 512,
      timeout: cdk.Duration.seconds(10),
    });

    ddLambda.addToRolePolicy(new iam.PolicyStatement({
      actions: ['route53:ChangeResourceRecordSets', 'route53:ListHostedZones', 'route53:ListResourceRecordSets'],
      resources: ['*'],
      effect: iam.Effect.ALLOW,
    }));

    // Look up your existing hosted zone
    const hostedZone = route53.HostedZone.fromLookup(this, 'HostedZone', {
      domainName: props.config.domainName,
    });

    // Create the API with direct Lambda integration
    const api = new apigateway.RestApi(this, 'DDApi', {
      restApiName: 'Dynamic DNS API',
    });

    // Create Lambda integration with proxy
    const integration = new apigateway.LambdaIntegration(ddLambda, {
      proxy: true, // Enable proxy integration
    });

    // Add a proxy resource to catch any other paths
    api.root.addProxy({ defaultIntegration: integration });

    // Create custom domain with SSL certificate
    const certificate = certificatemanager.Certificate.fromCertificateArn(
      this,
      'Certificate',
      `arn:aws:acm:${this.region}:${this.account}:certificate/${props.config.certificateId}`
    );

    const customDomain = new apigateway.DomainName(this, 'CustomDomain', {
      domainName: `${props.config.subdomainName}.${props.config.domainName}`,
      certificate: certificate,
    });

    // Map the custom domain to the API
    new apigateway.BasePathMapping(this, 'BasePathMapping', {
      domainName: customDomain,
      restApi: api,
      basePath: '', // Empty string means root path
    });

    // Create DNS record for HTTPS endpoint
    new route53.ARecord(this, 'DDNSRecord', {
      zone: hostedZone,
      recordName: props.config.subdomainName,
      target: route53.RecordTarget.fromAlias(new targets.ApiGatewayDomain(customDomain)),
    });
  }
}
