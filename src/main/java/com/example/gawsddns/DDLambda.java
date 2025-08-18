package com.example.gawsddns;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.Context;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.*;
import java.util.Map;
import java.util.HashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DDLambda implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    private static final Logger logger = LoggerFactory.getLogger(DDLambda.class);
    
    /**
     * Queries the hosted zone ID for a given domain name
     * @param domain The domain name to find the hosted zone for
     * @return The hosted zone ID, or null if not found
     */
    private String getHostedZoneIdForDomain(String domain) {
        try {
            Route53Client client = Route53Client.create();
            
            // Extract the root domain (e.g., "gretrostuff.com" from "test.gretrostuff.com")
            String rootDomain = extractRootDomain(domain);
            
            ListHostedZonesRequest request = ListHostedZonesRequest.builder().build();
            ListHostedZonesResponse response = client.listHostedZones(request);
            
            for (HostedZone zone : response.hostedZones()) {
                String zoneName = zone.name();
                // Remove trailing dot if present
                if (zoneName.endsWith(".")) {
                    zoneName = zoneName.substring(0, zoneName.length() - 1);
                }
                
                if (zoneName.equals(rootDomain) || domain.endsWith("." + zoneName)) {
                    String zoneId = zone.id();
                    // Remove the "/hostedzone/" prefix if present
                    if (zoneId.startsWith("/hostedzone/")) {
                        zoneId = zoneId.substring(12);
                    }
                    logger.info("Found hosted zone {} for domain {}", zoneId, domain);
                    return zoneId;
                }
            }
            
            logger.warn("No hosted zone found for domain: {}", domain);
            return null;
            
        } catch (Exception e) {
            logger.error("Error querying hosted zones for domain: " + domain, e);
            return null;
        }
    }
    
    /**
     * Extracts the root domain from a full domain name
     * @param domain The full domain name
     * @return The root domain
     */
    private String extractRootDomain(String domain) {
        String[] parts = domain.split("\\.");
        if (parts.length >= 2) {
            return parts[parts.length - 2] + "." + parts[parts.length - 1];
        }
        return domain;
    }
    
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        logger.info("Lambda function started");
        
        try {
            String ip = "1.2.3.4"; // Extract from input
            String domain = "test.gretrostuff.com";
            
            // Query hosted zone ID from the domain
            String hostedZoneId = getHostedZoneIdForDomain(domain);
            if (hostedZoneId == null) {
                throw new RuntimeException("Could not find hosted zone for domain: " + domain);
            }

            logger.info("Updating DNS record: {} -> {} in zone {}", domain, ip, hostedZoneId);

            Route53Client client = Route53Client.create();
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

            ChangeResourceRecordSetsResponse response = client.changeResourceRecordSets(request);
            
            String result = "Updated " + domain + " to " + ip;
            logger.info("Successfully updated DNS record: {}", result);
            
            // Return proper API Gateway response format
            Map<String, Object> apiResponse = new HashMap<>();
            apiResponse.put("statusCode", 200);
            
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            apiResponse.put("headers", headers);
            
            Map<String, Object> body = new HashMap<>();
            body.put("message", result);
            body.put("changeId", response.changeInfo().id());
            body.put("status", response.changeInfo().status().toString());
            
            // Simple JSON string construction
            String jsonBody = String.format(
                "{\"message\":\"%s\",\"changeId\":\"%s\",\"status\":\"%s\"}", 
                result, response.changeInfo().id(), response.changeInfo().status().toString()
            );
            apiResponse.put("body", jsonBody);
            
            return apiResponse;
            
        } catch (Exception e) {
            logger.error("Error updating DNS record", e);
            
            // Return proper error response format
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("statusCode", 500);
            
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            errorResponse.put("headers", headers);
            
            String errorBody = String.format("{\"error\":\"Failed to update DNS record: %s\"}", 
                e.getMessage().replace("\"", "\\\"")); // Escape quotes
            errorResponse.put("body", errorBody);
            
            return errorResponse;
        }
    }
}
