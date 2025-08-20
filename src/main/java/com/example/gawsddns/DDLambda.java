package com.example.gawsddns;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.*;
import java.util.Map;
import java.util.HashMap;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DDLambda implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    private static final Logger logger = LoggerFactory.getLogger(DDLambda.class);
    
    /**
     * Extracts parameter from various sources (query params, body, headers)
     */
    private String extractParameter(Map<String, Object> input, String... paramNames) {
        for (String paramName : paramNames) {
            // Check query string parameters
            Map<String, String> queryParams = getQueryStringParameters(input);
            if (queryParams != null && queryParams.containsKey(paramName)) {
                return queryParams.get(paramName);
            }
            
            // Check body parameters (if JSON)
            String value = getFromBody(input, paramName);
            if (value != null) {
                return value;
            }
            
            // Check headers
            Map<String, String> headers = getHeaders(input);
            if (headers != null && headers.containsKey(paramName.toLowerCase())) {
                return headers.get(paramName.toLowerCase());
            }
        }
        return null;
    }
    
    /**
     * Gets query string parameters from API Gateway event
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> getQueryStringParameters(Map<String, Object> input) {
        return (Map<String, String>) input.get("queryStringParameters");
    }
    
    /**
     * Gets headers from API Gateway event
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> getHeaders(Map<String, Object> input) {
        return (Map<String, String>) input.get("headers");
    }
    
    /**
     * Extracts parameter from JSON body
     */
    private String getFromBody(Map<String, Object> input, String paramName) {
        String body = (String) input.get("body");
        if (body != null && body.trim().startsWith("{")) {
            try {
                // Simple JSON parsing for key-value pairs
                if (body.contains("\"" + paramName + "\"")) {
                    String[] parts = body.split("\"" + paramName + "\"\\s*:\\s*\"");
                    if (parts.length > 1) {
                        String value = parts[1].split("\"")[0];
                        return value;
                    }
                }
            } catch (Exception e) {
                logger.warn("Error parsing body for parameter {}: {}", paramName, e.getMessage());
            }
        }
        return null;
    }
    
    /**
     * Gets client IP from API Gateway event
     */
    @SuppressWarnings("unchecked")
    private String getClientIpFromApiGateway(Map<String, Object> input) {
        try {
            Map<String, Object> requestContext = (Map<String, Object>) input.get("requestContext");
            if (requestContext != null) {
                Map<String, Object> identity = (Map<String, Object>) requestContext.get("identity");
                if (identity != null) {
                    return (String) identity.get("sourceIp");
                }
            }
        } catch (Exception e) {
            logger.warn("Error extracting client IP: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * Basic authentication validation
     * You should customize this based on your security requirements
     */
    private boolean isValidCredentials(String username, String password) {
        // For now, allow any non-empty credentials
        // In production, you'd want to validate against a secure store
        if (username == null || password == null) {
            return false;
        }
        
        // Example: hardcoded credentials (NOT recommended for production)
        // Replace with your preferred authentication method
        return "superg".equals(username) && "DontLookHere-290".equals(password);
    }
    
    /**
     * Creates an error response in API Gateway format
     */
    private Map<String, Object> createErrorResponse(int statusCode, String message) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("statusCode", statusCode);
        
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "text/plain");
        errorResponse.put("headers", headers);
        
        errorResponse.put("body", message);
        return errorResponse;
    }
    
    /**
     * Gets the current IP address for a domain from DNS
     */
    private String getCurrentDnsRecord(String domain) {
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(domain);
            return addr.getHostAddress();
        } catch (Exception e) {
            logger.warn("Could not resolve current IP for domain {}: {}", domain, e.getMessage());
            return null; // If we can't resolve, assume it needs updating
        }
    }
    
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
        logger.info("Lambda function started with input: {}", input);
        
        try {
            // Log the entire input for debugging
            logger.info("Full input received: {}", input);
            
            // Extract parameters from different possible sources
            String ip = extractParameter(input, "myip");
            String domain = extractParameter(input, "hostname");
            String username = extractParameter(input, "login", "username");
            String password = extractParameter(input, "password");
            
            // Extract optional parameters (for Dyn compatibility - mostly ignored)
            String wildcard = extractParameter(input, "wildcard");
            String mx = extractParameter(input, "mx");
            String backmx = extractParameter(input, "backmx");
            String offline = extractParameter(input, "offline");
            String system = extractParameter(input, "system"); // Legacy parameter
            
            // Validate required parameters
            if (domain == null || domain.trim().isEmpty()) {
                throw new IllegalArgumentException("Missing required parameter: hostname");
            }
            
            // If no IP provided, try to detect client IP from API Gateway
            if (ip == null || ip.trim().isEmpty()) {
                ip = getClientIpFromApiGateway(input);
            }
            
            if (ip == null || ip.trim().isEmpty()) {
                throw new IllegalArgumentException("Unable to determine IP address");
            }
            
            // Basic authentication check (you can customize this)
            if (!isValidCredentials(username, password)) {
                return createErrorResponse(401, "Authentication failed");
            }
            
            logger.info("Authenticated request - Updating DNS record: {} -> {}", domain, ip);
            
            // Query hosted zone ID from the domain
            String hostedZoneId = getHostedZoneIdForDomain(domain);
            if (hostedZoneId == null) {
                throw new RuntimeException("Could not find hosted zone for domain: " + domain);
            }

            logger.info("Found hosted zone {} for domain {}", hostedZoneId, domain);

            // Check if IP has actually changed by querying current DNS record
            String currentIp = getCurrentDnsRecord(domain);
            if (ip.equals(currentIp)) {
                logger.info("IP address {} is unchanged for domain {}", ip, domain);
                
                Map<String, Object> nochgResponse = new HashMap<>();
                nochgResponse.put("statusCode", 200);
                nochgResponse.put("headers", Map.of("Content-Type", "text/plain"));
                nochgResponse.put("body", "nochg " + ip);
                return nochgResponse;
            }

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
            
            // Return ddclient-compatible response
            Map<String, Object> apiResponse = new HashMap<>();
            apiResponse.put("statusCode", 200);
            
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "text/plain");
            apiResponse.put("headers", headers);
            
            // ddclient expects simple text responses like "good" or "nochg"
            String responseBody = "good " + ip;
            apiResponse.put("body", responseBody);
            
            return apiResponse;
            
        } catch (Exception e) {
            logger.error("Error updating DNS record", e);
            
            // Return ddclient-compatible error response
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("statusCode", 500);
            
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "text/plain");
            errorResponse.put("headers", headers);
            
            // ddclient expects error responses like "badauth", "nohost", "abuse", etc.
            String errorBody = "911"; // Generic error code for ddclient
            if (e.getMessage().contains("Authentication") || e.getMessage().contains("Credentials")) {
                errorBody = "badauth";
            } else if (e.getMessage().contains("hosted zone") || e.getMessage().contains("domain")) {
                errorBody = "nohost";
            } else if (e.getMessage().contains("hostname")) {
                errorBody = "notfqdn";
            }
            errorResponse.put("body", errorBody);
            
            return errorResponse;
        }
    }
}
