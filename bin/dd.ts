#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { DDStack } from '../lib/dd-stack';
import * as config from '../config.json';

const app = new cdk.App();
new DDStack(app, 'DDStack', {
  env: { account: config.account, region: config.region },
  config: {
    certificateId: config.certificateId,
    domainName: config.domainName,
    subdomainName: config.subdomainName,
    username: config.username,
    password: config.password
  },
});
