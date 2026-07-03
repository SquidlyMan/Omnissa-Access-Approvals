package com.omnissa.access.approval.interfaces;

import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.model.CalloutResponse;
import com.omnissa.access.approval.model.OmnissaServer;
import com.omnissa.access.approval.repository.ApprovalsRepository;
import com.omnissa.access.approval.util.AuditService;
import com.omnissa.access.approval.util.CustomContentTypes;
import com.omnissa.access.approval.util.OmnissaRestClient;
import com.omnissa.access.approval.util.Paths;
import com.omnissa.access.approval.util.RestPreconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class ApprovalsInterfaceImpl implements ApprovalsInterface {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalsInterfaceImpl.class);

    @Autowired
    private ApprovalsRepository repository;

    @Autowired
    private AuditService auditService;

    @Override
    public CalloutRequest[] getPendingApprovals() {
        OmnissaServer server = RestPreconditions.omnissaServerConfig();
        OmnissaRestClient restClient = new OmnissaRestClient(server);

        ResponseEntity<CalloutRequest[]> response = restClient.exchange(
                RestPreconditions.omnissaServerBaseUrl() + Paths.APPROVALS,
                HttpMethod.GET,
                null,
                CalloutRequest[].class);

        return response.getBody();
    }

    @Override
    public void requestResponse(CalloutResponse response) {
        CalloutRequest calloutRequest = repository.findByRequestId(response.getRequestId());

        // Access 415s the legacy vendor types on this PUT; it wants plain JSON
        HttpHeaders headers = CustomContentTypes.add("application/json");
        HttpEntity<CalloutResponse> httpEntity = new HttpEntity<>(response, headers);

        OmnissaServer server = RestPreconditions.omnissaServerConfig();
        OmnissaRestClient restClient = new OmnissaRestClient(server);

        try {
            restClient.exchange(
                    RestPreconditions.omnissaServerBaseUrl() + Paths.APPROVALS,
                    HttpMethod.PUT,
                    httpEntity,
                    CalloutResponse.class);

            calloutRequest.setResponseDate(new Date());

            if (response.getMessage() != null && !response.getMessage().isEmpty()) {
                calloutRequest.setResponseMessage(response.getMessage());
            }

            calloutRequest.setState(response.isApproved() ? "approved" : "rejected");
            calloutRequest.setDecidedBy(auditService.currentAdmin());
            repository.save(calloutRequest);
        } catch (Exception e) {
            logger.error("Failed to submit approval response for requestId={}: {}",
                    response.getRequestId(), e.getMessage(), e);
        }
    }

    @Override
    public void deleteRemoteCallouts() {
        OmnissaServer server = RestPreconditions.omnissaServerConfig();
        OmnissaRestClient restClient = new OmnissaRestClient(server);

        HttpHeaders headers = CustomContentTypes.add("application/json");

        for (CalloutRequest request : getPendingApprovals()) {
            CalloutResponse response = new CalloutResponse(request.getRequestId(), false, "deactivation");
            HttpEntity<CalloutResponse> httpEntity = new HttpEntity<>(response, headers);
            try {
                restClient.exchange(
                        RestPreconditions.omnissaServerBaseUrl() + Paths.APPROVALS,
                        HttpMethod.PUT,
                        httpEntity,
                        CalloutResponse.class);
                logger.info("Deleted remote callout for requestId={}", request.getRequestId());
            } catch (Exception e) {
                logger.error("Failed to delete callout for requestId={}: {}",
                        request.getRequestId(), e.getMessage());
            }
        }
    }
}
