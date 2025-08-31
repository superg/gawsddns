package com.example.gawsddns;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.Context;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.*;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Base64;
import org.apache.commons.validator.routines.DomainValidator;
import org.apache.commons.validator.routines.InetAddressValidator;
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
            
            // validate update path
            String path = (String)input.get("path");
            if(path != null && !path.equals("/nic/update") && !path.equals("/v3/update"))
                return createResponse(404, List.of("Not Found"));

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
                return createResponse(401, List.of("badauth"));
            
            // get arguments
            List<String> hostnames = List.of("");
            List<String> ips = null;
            Object query_params_obj = input.get("queryStringParameters");
            if(query_params_obj instanceof Map)
            {
                @SuppressWarnings("unchecked")
                Map<String, String> query_params = (Map<String, String>)query_params_obj;
                String hostname = getCaseInsensitiveParam(query_params, "hostname");
                if(hostname != null)
                    hostnames = Arrays.asList(hostname.split(",", -1));
                
                String myip = getCaseInsensitiveParam(query_params, "myip");
                if(myip != null)
                    ips = Arrays.asList(myip.split(",", -1));
            }

            // infer IP from the request if not provided
            if(ips == null)
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
                            ips = List.of((String)source_ip_obj);
                    }
                }

                // save on null checks later
                if(ips == null)
                    ips = List.of("");
            }

            // separate out IPv4 and IPv6
            List<String> ipsv4 = new ArrayList<>();
            List<String> ipsv6 = new ArrayList<>();
            for(String ip : ips)
            {
                InetAddressValidator validator = InetAddressValidator.getInstance();
                if(validator.isValidInet6Address(ip))
                    // RFC 5952 recommends lowercase
                    ipsv6.add(ip.toLowerCase());
                else
                    ipsv4.add(ip);
            }
            
            boolean success = true;
            List<String> messages = new ArrayList<>();
            for(String hostname : hostnames)
            {
                if(!DomainValidator.getInstance().isValid(hostname))
                {
                    messages.add("notfqdn");
                    success = false;
                    continue;
                }
                
                Route53Client client = Route53Client.create();
                
                String hosted_zone_id = findHostedZone(client, hostname);
                if(hosted_zone_id == null)
                {
                    messages.add("nohost");
                    success = false;
                    continue;
                }

                // check if IP has changed
                List<String> current_ipsv4 = getCurrentDnsRecord(client, hosted_zone_id, hostname, false);
                List<String> current_ipsv6 = getCurrentDnsRecord(client, hosted_zone_id, hostname, true);

                boolean update_ipv4 = !ipsv4.isEmpty() && !new java.util.HashSet<>(ipsv4).equals(new java.util.HashSet<>(current_ipsv4));
                boolean update_ipv6 = !ipsv6.isEmpty() && !new java.util.HashSet<>(ipsv6).equals(new java.util.HashSet<>(current_ipsv6));

                if(update_ipv4)
                    updateDnsRecord(client, hosted_zone_id, hostname, ipsv4, false);
                if(update_ipv6)
                    updateDnsRecord(client, hosted_zone_id, hostname, ipsv6, true);
                
                messages.add((update_ipv4 || update_ipv6 ? "good " : "nochg ") + String.join(",", ips));
            }

            return createResponse(success ? 200 : 400, messages);
        }
        catch(Exception e)
        {
            logger.error("exception", e);
            return createResponse(500, List.of("dnserr"));
        }
    }

    private String getCaseInsensitiveParam(Map<String, String> params, String key)
    {
        for(Map.Entry<String, String> entry : params.entrySet())
            if(key.equalsIgnoreCase(entry.getKey()))
                return entry.getValue();

        return null;
    }

    private Map<String, Object> createResponse(int status_code, List<String> messages)
    {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "text/plain");

        Map<String, Object> response = new HashMap<>();
        response.put("statusCode", status_code);
        response.put("headers", headers);
        response.put("body", String.join("\n", messages));

        return response;
    }

    private boolean isValidAuth(String auth_header)
    {
        if(auth_header == null || !auth_header.startsWith("Basic "))
            return false;

        try {
            String base64Credentials = auth_header.substring("Basic ".length());
            String credentials = new String(Base64.getDecoder().decode(base64Credentials));
            String[] parts = credentials.split(":", 2);
            if(parts.length != 2)
                return false;

            // Fetch username and password from SSM Parameter Store (SecureString)
            SsmClient ssmClient = SsmClient.create();

            GetParameterRequest usernameReq = GetParameterRequest.builder()
                    .name("/gawsddns/username")
                    .withDecryption(true)
                    .build();
            GetParameterRequest passwordReq = GetParameterRequest.builder()
                    .name("/gawsddns/password")
                    .withDecryption(true)
                    .build();

            String expectedUsername = ssmClient.getParameter(usernameReq).parameter().value();
            String expectedPassword = ssmClient.getParameter(passwordReq).parameter().value();

            return expectedUsername.equals(parts[0]) && expectedPassword.equals(parts[1]);
        } catch(Exception e) {
            logger.error("Auth error", e);
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

    private List<String> getCurrentDnsRecord(Route53Client client, String hosted_zone_id, String domain, boolean ipv6)
    {
        List<String> ips = new ArrayList<>();

        RRType rr_type = ipv6 ? RRType.AAAA : RRType.A;
        ListResourceRecordSetsRequest request = ListResourceRecordSetsRequest.builder()
            .hostedZoneId(hosted_zone_id)
            .startRecordName(domain)
            .startRecordType(rr_type)
            .build();

        ListResourceRecordSetsResponse response = client.listResourceRecordSets(request);

        for(ResourceRecordSet record_set : response.resourceRecordSets())
        {
            String record_name = record_set.name();
            if(record_name.endsWith("."))
                record_name = record_name.substring(0, record_name.length() - 1);

            if(record_name.equals(domain) && record_set.type() == rr_type)
                for(ResourceRecord rr : record_set.resourceRecords())
                    ips.add(rr.value());
        }

        return ips;
    }
    
    private void updateDnsRecord(Route53Client client, String hosted_zone_id, String domain, List<String> ips, boolean ipv6)
    {
        List<ResourceRecord> resource_record_list = new ArrayList<>();
        for(String ip : ips)
            resource_record_list.add(ResourceRecord.builder().value(ip).build());

        ChangeResourceRecordSetsRequest request = ChangeResourceRecordSetsRequest.builder()
            .hostedZoneId(hosted_zone_id)
            .changeBatch(ChangeBatch.builder()
                .changes(Change.builder()
                    .action(ChangeAction.UPSERT)
                    .resourceRecordSet(ResourceRecordSet.builder()
                        .name(domain)
                        .type(ipv6 ? RRType.AAAA : RRType.A)
                        .ttl(300L)
                        .resourceRecords(resource_record_list)
                        .build())
                    .build())
                .build())
            .build();

        client.changeResourceRecordSets(request);
    }
}
