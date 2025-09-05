import * as cdk from 'aws-cdk-lib';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as apigateway from 'aws-cdk-lib/aws-apigateway';
import * as route53 from 'aws-cdk-lib/aws-route53';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import * as logs from 'aws-cdk-lib/aws-logs';
import { Construct } from 'constructs';

export interface DDStackProps extends cdk.StackProps {
  config: {
    certificateId: string;
    domainName: string;
    subdomainName: string;
    username: string;
    password: string;
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
      timeout: cdk.Duration.seconds(30),
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

    // Create IAM role for API Gateway logging
    const apiGatewayLogRole = new iam.Role(this, 'ApiGatewayLogRole', {
      assumedBy: new iam.ServicePrincipal('apigateway.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonAPIGatewayPushToCloudWatchLogs'),
      ],
    });

    // Create the API Gateway REST API using CfnRestApi
    const api = new apigateway.CfnRestApi(this, 'DDApi', {
      name: 'Dynamic DNS API',
      endpointConfiguration: {
        types: ['REGIONAL'],
        ipAddressType: "dualstack"
      },
    });

    // Set up API Gateway account settings for logging
    new apigateway.CfnAccount(this, 'ApiGatewayAccount', {
      cloudWatchRoleArn: apiGatewayLogRole.roleArn,
    });

    // Create Lambda permission for API Gateway to invoke the Lambda
    new lambda.CfnPermission(this, 'ApiGatewayInvokePermission', {
      action: 'lambda:InvokeFunction',
      functionName: ddLambda.functionName,
      principal: 'apigateway.amazonaws.com',
      sourceArn: `arn:aws:execute-api:${this.region}:${this.account}:${api.ref}/*/*/*`,
    });

    // Create the root resource ("/")
    const rootResource = new apigateway.CfnResource(this, 'RootResource', {
      parentId: api.attrRootResourceId,
      pathPart: '{proxy+}',
      restApiId: api.ref,
    });

    // Create the ANY method with Lambda proxy integration
    const anyMethod = new apigateway.CfnMethod(this, 'AnyMethod', {
      restApiId: api.ref,
      resourceId: rootResource.ref,
      httpMethod: 'ANY',
      authorizationType: 'NONE',
      integration: {
        type: 'AWS_PROXY',
        integrationHttpMethod: 'POST',
        uri: `arn:aws:apigateway:${this.region}:lambda:path/2015-03-31/functions/${ddLambda.functionArn}/invocations`,
      },
      requestParameters: {
        'method.request.path.proxy': true,
      },
    });

    // Deploy the API, depends on method
    const deployment = new apigateway.CfnDeployment(this, 'ApiDeployment', {
      restApiId: api.ref,
      description: 'Deployment without stage for logging config',
    });
    deployment.node.addDependency(anyMethod);

    // Create stage with minimal logging
    const stage = new apigateway.CfnStage(this, 'ApiStage', {
      restApiId: api.ref,
      deploymentId: deployment.ref,
      stageName: 'prod',
      accessLogSetting: {
        destinationArn: `arn:aws:logs:${this.region}:${this.account}:log-group:/aws/apigateway/gawsddns`,
        format: '$context.requestId $context.status $context.error.message $context.integration.error',
      },
    });
    stage.node.addDependency(deployment);

    // Create custom domain with SSL certificate using CfnDomainName
    const certificateArn = `arn:aws:acm:${this.region}:${this.account}:certificate/${props.config.certificateId}`;
    const customDomain = new apigateway.CfnDomainName(this, 'CustomDomain', {
      domainName: `${props.config.subdomainName}.${props.config.domainName}`,
      regionalCertificateArn: certificateArn,
      endpointConfiguration: {
        types: ['REGIONAL'],
        ipAddressType: 'dualstack'
      },
    });

    // Base path mapping for custom domain, depends on domain and deployment
    const basePathMapping = new apigateway.CfnBasePathMapping(this, 'BasePathMapping', {
      domainName: customDomain.domainName!,
      restApiId: api.ref,
      stage: 'prod',
      basePath: '',
    });
    basePathMapping.node.addDependency(customDomain);
    basePathMapping.node.addDependency(stage);

    // Create DNS record for HTTPS endpoint using CfnDomainName outputs
    new route53.ARecord(this, 'DDNSRecord', {
      zone: hostedZone,
      recordName: props.config.subdomainName,
      target: route53.RecordTarget.fromAlias({
        bind: () => ({
          dnsName: customDomain.attrRegionalDomainName,
          hostedZoneId: customDomain.attrRegionalHostedZoneId,
        }),
      }),
    });

    // Create AAAA record for HTTPS endpoint using CfnDomainName outputs
    new route53.AaaaRecord(this, 'DDNSRecordAAAA', {
      zone: hostedZone,
      recordName: props.config.subdomainName,
      target: route53.RecordTarget.fromAlias({
        bind: () => ({
          dnsName: customDomain.attrRegionalDomainName,
          hostedZoneId: customDomain.attrRegionalHostedZoneId,
        }),
      }),
    });

    // Store credentials in SSM Parameter Store
    const usernameParam = new ssm.StringParameter(this, 'DDNSUsernameParam', {
      parameterName: '/gawsddns/username',
      stringValue: props.config.username,
      tier: ssm.ParameterTier.STANDARD
    });
    const passwordParam = new ssm.StringParameter(this, 'DDNSPasswordParam', {
      parameterName: '/gawsddns/password',
      stringValue: props.config.password,
      tier: ssm.ParameterTier.STANDARD
    });

    // Grant Lambda permission to read these parameters
    usernameParam.grantRead(ddLambda);
    passwordParam.grantRead(ddLambda);
  }
}
