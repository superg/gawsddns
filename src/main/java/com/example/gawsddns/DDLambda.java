package com.example.gawsddns;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.Context;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.*;
import java.util.Map;
import java.util.HashMap;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class DDLambda implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    private static final Logger logger = LoggerFactory.getLogger(DDLambda.class);
    
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        logger.info("input: {}", new Gson().toJson(input));
        
        try {
            // Extract parameters from API Gateway proxy format
            String hostname = null;
            String myip = null;
            String authHeader = null;
            
            // Get query parameters (hostname and myip)
            Object queryParamsObj = input.get("queryStringParameters");
            if (queryParamsObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> queryParams = (Map<String, String>) queryParamsObj;
                hostname = queryParams.get("hostname");
                myip = queryParams.get("myip");
            }
            
            // Get authorization header
            Object headersObj = input.get("headers");
            if (headersObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> headers = (Map<String, String>) headersObj;
                authHeader = headers.get("Authorization");
                if (authHeader == null) {
                    authHeader = headers.get("authorization"); // case-insensitive fallback
                }
            }
            
            logger.info("Extracted - hostname: {}, myip: {}, hasAuth: {}", hostname, myip, authHeader != null);
            
            // Validate path for Dyn API specification compliance - only accept official endpoints
            String path = (String) input.get("path");
            if (path != null && !path.equals("/nic/update") && !path.equals("/v3/update")) {
                logger.info("Invalid path: {} - only /nic/update and /v3/update are supported", path);
                return createResponse(404, "Not Found");
            }
            
            // Validate hostname
            if (hostname == null || hostname.trim().isEmpty()) {
                return createResponse(400, "notfqdn");
            }
            
            // Check authentication
            if (!isValidAuth(authHeader)) {
                return createResponse(401, "badauth");
            }
            
            // Use default IP if none provided
            if (myip == null || myip.trim().isEmpty()) {
                myip = "1.2.3.4"; // Default for testing
            }
            
            // Check if IP has changed
            String currentIP = getCurrentDnsRecord(hostname);
            if (myip.equals(currentIP)) {
                return createResponse(200, "nochg " + myip);
            }
            
            // Update DNS record
            updateDnsRecord(hostname, myip);
            
            return createResponse(200, "good " + myip);
            
        } catch (Exception e) {
            logger.error("Error: ", e);
            return createResponse(500, "dnserr");
        }
    }
    
    private boolean isValidAuth(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return false;
        }
        
        try {
            String base64Credentials = authHeader.substring("Basic ".length());
            String credentials = new String(Base64.getDecoder().decode(base64Credentials));
            String[] parts = credentials.split(":", 2);
            
            return parts.length == 2 && 
                   "superg".equals(parts[0]) && 
                   "DontLookHere-290".equals(parts[1]);
        } catch (Exception e) {
            return false;
        }
    }
    
    private String getCurrentDnsRecord(String domain) {
        try {
            Route53Client client = Route53Client.create();
            
            // Find the hosted zone first
            String hostedZoneId = null;
            String domainToCheck = domain;
            
            while (domainToCheck.contains(".")) {
                ListHostedZonesResponse zones = client.listHostedZones();
                for (HostedZone zone : zones.hostedZones()) {
                    String zoneName = zone.name();
                    if (zoneName.endsWith(".")) {
                        zoneName = zoneName.substring(0, zoneName.length() - 1);
                    }
                    if (domainToCheck.equals(zoneName) || domain.endsWith("." + zoneName)) {
                        hostedZoneId = zone.id();
                        break;
                    }
                }
                if (hostedZoneId != null) break;
                
                int dotIndex = domainToCheck.indexOf('.');
                if (dotIndex == -1) break;
                domainToCheck = domainToCheck.substring(dotIndex + 1);
            }
            
            if (hostedZoneId == null) {
                logger.info("No hosted zone found for {}, treating as new record", domain);
                return null;
            }
            
            // Query the current record
            ListResourceRecordSetsRequest request = ListResourceRecordSetsRequest.builder()
                .hostedZoneId(hostedZoneId)
                .startRecordName(domain)
                .startRecordType(RRType.A)
                .build();
                
            ListResourceRecordSetsResponse response = client.listResourceRecordSets(request);
            
            for (ResourceRecordSet recordSet : response.resourceRecordSets()) {
                String recordName = recordSet.name();
                if (recordName.endsWith(".")) {
                    recordName = recordName.substring(0, recordName.length() - 1);
                }
                
                if (recordName.equals(domain) && recordSet.type() == RRType.A) {
                    if (!recordSet.resourceRecords().isEmpty()) {
                        String currentIp = recordSet.resourceRecords().get(0).value();
                        logger.info("Current IP for {} is {}", domain, currentIp);
                        return currentIp;
                    }
                }
            }
            
            logger.info("No A record found for {}", domain);
            return null;
        } catch (Exception e) {
            logger.error("Error checking current DNS record for {}: {}", domain, e.getMessage());
            return null;
        }
    }
    
    private void updateDnsRecord(String domain, String ip) throws Exception {
        // Find the hosted zone
        Route53Client client = Route53Client.create();
        
        String hostedZoneId = null;
        String domainToCheck = domain;
        
        // Try to find the hosted zone by removing subdomains
        while (domainToCheck.contains(".")) {
            ListHostedZonesResponse zones = client.listHostedZones();
            for (HostedZone zone : zones.hostedZones()) {
                String zoneName = zone.name();
                if (zoneName.endsWith(".")) {
                    zoneName = zoneName.substring(0, zoneName.length() - 1);
                }
                if (domainToCheck.equals(zoneName) || domain.endsWith("." + zoneName)) {
                    hostedZoneId = zone.id();
                    break;
                }
            }
            if (hostedZoneId != null) break;
            
            int dotIndex = domainToCheck.indexOf('.');
            if (dotIndex == -1) break;
            domainToCheck = domainToCheck.substring(dotIndex + 1);
        }
        
        if (hostedZoneId == null) {
            throw new RuntimeException("No hosted zone found for " + domain);
        }
        
        // Update the record
        ChangeResourceRecordSetsRequest request = ChangeResourceRecordSetsRequest.builder()
            .hostedZoneId(hostedZoneId)
            .changeBatch(ChangeBatch.builder()
                .changes(Change.builder()
                    .action(ChangeAction.UPSERT)
                    .resourceRecordSet(ResourceRecordSet.builder()
                        .name(domain)
                        .type(RRType.A)
                        .ttl(300L)
                        .resourceRecords(ResourceRecord.builder().value(ip).build())
                        .build())
                    .build())
                .build())
            .build();
            
        client.changeResourceRecordSets(request);
        logger.info("Updated {} to {}", domain, ip);
    }
    
    private Map<String, Object> createResponse(int statusCode, String body) {
        Map<String, Object> response = new HashMap<>();
        response.put("statusCode", statusCode);
        
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "text/plain");
        response.put("headers", headers);
        
        response.put("body", body);
        return response;
    }
}
