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
        try
        {
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
            if(path != null && !path.equals("/nic/update"))
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
            
            Route53Client client = Route53Client.create();
            
            String hosted_zone_id = findHostedZone(client, hostname);
            if(hosted_zone_id == null)
                return createResponse(400, "nohost");
            
            // check if IP has changed
            String current_ip = getCurrentDnsRecord(client, hosted_zone_id, hostname);
            if(current_ip != null && myip.equals(current_ip))
                return createResponse(200, "nochg " + myip);
            
            // update DNS record
            updateDnsRecord(client, hosted_zone_id, hostname, myip);
            
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

    // FIXME: implement proper authorization with secrets
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
    
    private String findHostedZone(Route53Client client, String domain)
    {
        String domain_to_check = domain;
        while(domain_to_check.contains("."))
        {
            ListHostedZonesResponse zones = client.listHostedZones();
            for(HostedZone zone : zones.hostedZones())
            {
                String zone_name = zone.name();
                if(zone_name.endsWith("."))
                    zone_name = zone_name.substring(0, zone_name.length() - 1);

                if(domain_to_check.equals(zone_name) || domain.endsWith("." + zone_name))
                    return zone.id();
            }

            int dot_index = domain_to_check.indexOf('.');
            if(dot_index == -1)
                break;

            domain_to_check = domain_to_check.substring(dot_index + 1);
        }
        
        return null;
    }

    private String getCurrentDnsRecord(Route53Client client, String hosted_zone_id, String domain)
    {
        ListResourceRecordSetsRequest request = ListResourceRecordSetsRequest.builder()
            .hostedZoneId(hosted_zone_id)
            .startRecordName(domain)
            .startRecordType(RRType.A)
            .build();
            
        ListResourceRecordSetsResponse response = client.listResourceRecordSets(request);
        
        for(ResourceRecordSet record_set : response.resourceRecordSets())
        {
            String record_name = record_set.name();
            if(record_name.endsWith("."))
                record_name = record_name.substring(0, record_name.length() - 1);
            
            if(record_name.equals(domain) && record_set.type() == RRType.A)
            {
                if(!record_set.resourceRecords().isEmpty())
                {
                    String current_ip = record_set.resourceRecords().get(0).value();
                    return current_ip;
                }
            }
        }
        
        return null;
    }
    
    private void updateDnsRecord(Route53Client client, String hosted_zone_id, String domain, String ip)
    {
        ChangeResourceRecordSetsRequest request = ChangeResourceRecordSetsRequest.builder()
            .hostedZoneId(hosted_zone_id)
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
    }
}
