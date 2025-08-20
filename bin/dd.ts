#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { DDStack } from '../lib/dd-stack';

const app = new cdk.App();
new DDStack(app, 'DDStack', {
  // Specify your account and region for hosted zone lookup
  env: { account: '223166462382', region: 'us-east-1' },
});
