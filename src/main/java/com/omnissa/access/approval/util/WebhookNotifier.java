package com.omnissa.access.approval.util;

import com.omnissa.access.approval.model.CalloutRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Optional webhook notification when a new activation request arrives.
 * Fire-and-forget: runs async, never throws, and is a no-op when
 * webhook.url is blank. Formats: generic (JSON event), slack, teams.
 */
@Service
public class WebhookNotifier {

    private static final Logger logger = LoggerFactory.getLogger(WebhookNotifier.class);

    @Value("${webhook.url:}")
    private String webhookUrl;

    @Value("${webhook.format:generic}")
    private String webhookFormat;

    private final RestTemplate restTemplate;

    public WebhookNotifier() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
    }

    public void notifyNewRequest(CalloutRequest request) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        Map<String, Object> payload = buildPayload(request);
        String requestId = request.getRequestId();
        CompletableFuture.runAsync(() -> {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                restTemplate.postForEntity(webhookUrl, new HttpEntity<>(payload, headers), String.class);
                logger.info("Webhook notification sent for requestId={}", requestId);
            } catch (Exception e) {
                logger.warn("Webhook notification failed for requestId={}: {}", requestId, e.getMessage());
            }
        });
    }

    private Map<String, Object> buildPayload(CalloutRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String format = webhookFormat == null ? "generic" : webhookFormat.trim().toLowerCase();
        if ("slack".equals(format) || "teams".equals(format)) {
            // Teams incoming webhooks accept the same simple text payload as Slack.
            payload.put("text", "New access request: " + request.getResourceName()
                    + " requested by user " + request.getUserId()
                    + " — approve or reject in the Access Approval Tool.");
        } else {
            payload.put("event", "request.created");
            payload.put("requestId", request.getRequestId());
            payload.put("resourceName", request.getResourceName());
            payload.put("userId", request.getUserId());
            payload.put("operation", "activation");
            payload.put("receivedDate", request.getReceivedDate() != null
                    ? request.getReceivedDate().toInstant().toString()
                    : Instant.now().toString());
        }
        return payload;
    }
}
