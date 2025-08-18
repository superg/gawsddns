package com.example.gawsddns;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.Context;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.*;
import java.util.Map;

public class DDLambda implements RequestHandler<Map<String, Object>, String> {
    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        String ip = "1.2.3.4"; // Extract from input
        String domain = "yourdomain.example.com";
        String hostedZoneId = "Z1234567890"; // Replace with your zone ID

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

        client.changeResourceRecordSets(request);
        return "Updated " + domain + " to " + ip;
    }
}
