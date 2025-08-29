package com.example.gawsddns;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.Context;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.*;
import java.util.Map;
import java.util.HashMap;
import java.util.Base64;
import org.apache.commons.validator.routines.DomainValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.gson.Gson;

public class DDLambda implements RequestHandler<Map<String, Object>, Map<String, Object>>
{
    private static final Logger logger = LoggerFactory.getLogger(DDLambda.class);
    
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context)
    {
        try {
            logger.info("input: {}", new Gson().toJson(input));
            
            // get authentication info
            String auth_header = null;
            Object headers_obj = input.get("headers");
            if(headers_obj instanceof Map)
            {
                @SuppressWarnings("unchecked")
                Map<String, String> headers = (Map<String, String>)headers_obj;
                auth_header = getCaseInsensitiveParam(headers, "Authorization");
            }
            
            if(!isValidAuth(auth_header))
                return createResponse(401, "badauth");
            
            // validate update path
            String path = (String)input.get("path");
            if(path != null && !path.equals("/nic/update") && !path.equals("/v3/update"))
                return createResponse(404, "Not Found");

            // get arguments
            String hostname = null;
            String myip = null;
            Object query_params_obj = input.get("queryStringParameters");
            if((query_params_obj instanceof Map))
            {
                @SuppressWarnings("unchecked")
                Map<String, String> query_params = (Map<String, String>)query_params_obj;
                hostname = getCaseInsensitiveParam(query_params, "hostname");
                myip = getCaseInsensitiveParam(query_params, "myip");
            }

            if(!DomainValidator.getInstance().isValid(hostname))
                return createResponse(400, "notfqdn");
            
            // infer IP from the request if not provided
            if(myip == null)
            {
                @SuppressWarnings("unchecked")
                Map<String, Object> request_context = (Map<String, Object>)input.get("requestContext");
                if(request_context != null)
                {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> identity = (Map<String, Object>)request_context.get("identity");
                    if(identity != null)
                    {
                        Object source_ip_obj = identity.get("sourceIp");
                        if(source_ip_obj instanceof String)
                            myip = (String)source_ip_obj;
                    }
                }
            }
            
            // check if IP has changed
            if(myip.equals(getCurrentDnsRecord(hostname)))
                return createResponse(200, "nochg " + myip);
            
            // update DNS record
            updateDnsRecord(hostname, myip);
            
            return createResponse(200, "good " + myip);
        }
        catch(Exception e)
        {
            logger.error("exception", e);
            return createResponse(500, "dnserr");
        }
    }

    private String getCaseInsensitiveParam(Map<String, String> params, String key)
    {
        for(Map.Entry<String, String> entry : params.entrySet())
            if(key.equalsIgnoreCase(entry.getKey()))
                return entry.getValue();

        return null;
    }

    private Map<String, Object> createResponse(int statusCode, String body)
    {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "text/plain");

        Map<String, Object> response = new HashMap<>();
        response.put("statusCode", statusCode);
        response.put("headers", headers);
        response.put("body", body);

        return response;
    }

    private boolean isValidAuth(String authHeader)
    {
        if(authHeader == null || !authHeader.startsWith("Basic "))
            return false;
        
        try
        {
            String base64Credentials = authHeader.substring("Basic ".length());
            String credentials = new String(Base64.getDecoder().decode(base64Credentials));
            String[] parts = credentials.split(":", 2);
            
            return parts.length == 2 && "superg".equals(parts[0]) && "DontLookHere-290".equals(parts[1]);
        }
        catch(Exception e)
        {
            return false;
        }
    }
    
    private String getCurrentDnsRecord(String domain)
    {
        try
        {
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
        }
        catch (Exception e)
        {
            logger.error("Error checking current DNS record for {}: {}", domain, e.getMessage());
            return null;
        }
    }
    
    private void updateDnsRecord(String domain, String ip) throws Exception
    {
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
}
