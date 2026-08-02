package com.omnissa.access.approval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnissa.access.approval.model.HubNotificationOutcome;
import com.omnissa.access.approval.model.OmnissaServer;
import com.omnissa.access.approval.util.OmnissaRestClient;
import com.omnissa.access.approval.util.Paths;
import com.omnissa.access.approval.util.RestPreconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.omnissa.access.approval.model.HubNotificationOutcome.FAILED;
import static com.omnissa.access.approval.model.HubNotificationOutcome.NOT_CONFIGURED;
import static com.omnissa.access.approval.model.HubNotificationOutcome.SENT;
import static com.omnissa.access.approval.model.HubNotificationOutcome.UNAVAILABLE;

/**
 * Pushes Hub Notification cards to Access users — an additional delivery
 * channel for #51 (escalation) and #53 (stage notifications) on top of the
 * existing chat webhook, sitting next to {@link AccessGroupService} which
 * resolves who to notify.
 *
 * <p><strong>Notify-only, permanently.</strong> Omnissa's notification API
 * supports an {@code actions} field that renders callback buttons (GET/POST/
 * PUT/PATCH/DELETE against a URL of the sender's choosing) directly on the
 * card — the exact shape of the Slack inline-approval feature this project
 * already removed once, because a decision made from a chat/notification
 * surface is a second, unauthenticated path to authority that can drift from
 * (and outlive) the real one. This class deliberately never sets {@code
 * actions}; it only ever sends {@code links}, which just open a URL in a
 * browser — identical in kind to the deep links {@code WebhookNotifier}
 * already sends for Slack/Teams, where the click still goes through this
 * tool's own login and role checks. Do not add {@code actions} support here
 * without re-litigating that decision.
 *
 * <p>Schema verified against Omnissa's own "Workspace ONE Notifications
 * Service Guide" (developer.omnissa.com/ws1-notification-services-api/),
 * and the target user id confirmed live to be the same SCIM id {@link
 * AccessGroupService} already resolves — no separate id lookup needed.
 * Reachability was also confirmed live: {@code GET
 * /ws1notifications/api/v1/notifications} returned 200 on this tenant, but
 * that is a per-tenant fact, not a guarantee — hence {@link
 * HubNotificationOutcome#UNAVAILABLE} rather than assuming every tenant has
 * this enabled.
 */
@Service
public class HubNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(HubNotificationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The synchronous multi-user endpoint's documented cap; beyond this Access has an async variant this class does not use. */
    private static final int MAX_SYNCHRONOUS_RECIPIENTS = 100;

    /**
     * Sends one notification to one user.
     *
     * @param scimUserId  the recipient's SCIM id (e.g. from {@link
     *                    com.omnissa.access.approval.model.GroupMember#scimId()})
     * @param title       required — {@code header.title}
     * @param description required — {@code body.description}
     * @param deepLinkUrl optional; when set, adds a single plain (non-actionable) link
     */
    public HubNotificationOutcome notifyUser(String scimUserId, String title, String description,
                                             String deepLinkUrl) {
        if (scimUserId == null || scimUserId.isBlank() || title == null || title.isBlank()
                || description == null || description.isBlank()) {
            return FAILED;
        }

        OmnissaServer server;
        try {
            server = RestPreconditions.omnissaServerConfig();
        } catch (Exception e) {
            return NOT_CONFIGURED;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = buildCard(title, description, deepLinkUrl);

        try {
            new OmnissaRestClient(server).exchange(
                    RestPreconditions.omnissaServerBaseUrl() + Paths.HUB_NOTIFICATIONS_USER,
                    HttpMethod.POST, new HttpEntity<>(body, headers), String.class, scimUserId);
            return SENT;
        } catch (HttpClientErrorException.NotFound e) {
            logger.warn("Hub Notifications is not available on this tenant (404) — not sending");
            return UNAVAILABLE;
        } catch (ResourceAccessException e) {
            logger.warn("Hub notification to {} failed — Access unreachable: {}", scimUserId, e.getMessage());
            return FAILED;
        } catch (Exception e) {
            logger.warn("Hub notification to {} failed: {}", scimUserId, e.getMessage());
            return FAILED;
        }
    }

    /**
     * Sends one notification to up to {@value #MAX_SYNCHRONOUS_RECIPIENTS}
     * users via the synchronous {@code distributed_notifications} endpoint,
     * returning a per-recipient outcome (matching what Access itself
     * reports, not just whether the overall call succeeded).
     *
     * <p>Recipients beyond the cap are dropped and logged, not silently
     * truncated — call this once per {@link AccessGroupService#resolveMembers}
     * batch rather than assuming an unbounded group is safe.
     */
    public Map<String, HubNotificationOutcome> notifyUsers(List<String> scimUserIds, String title,
                                                            String description, String deepLinkUrl) {
        if (scimUserIds == null || scimUserIds.isEmpty() || title == null || title.isBlank()
                || description == null || description.isBlank()) {
            return Map.of();
        }

        List<String> recipients = scimUserIds;
        if (recipients.size() > MAX_SYNCHRONOUS_RECIPIENTS) {
            logger.warn("notifyUsers: {} recipients exceeds the synchronous endpoint's cap of {} — "
                            + "notifying only the first {}",
                    recipients.size(), MAX_SYNCHRONOUS_RECIPIENTS, MAX_SYNCHRONOUS_RECIPIENTS);
            recipients = recipients.subList(0, MAX_SYNCHRONOUS_RECIPIENTS);
        }

        OmnissaServer server;
        try {
            server = RestPreconditions.omnissaServerConfig();
        } catch (Exception e) {
            return allOutcome(recipients, NOT_CONFIGURED);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("notification_card", buildCard(title, description, deepLinkUrl));
        body.put("user_ids", recipients);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            String responseBody = new OmnissaRestClient(server).exchange(
                    RestPreconditions.omnissaServerBaseUrl() + Paths.HUB_NOTIFICATIONS_DISTRIBUTED,
                    HttpMethod.POST, new HttpEntity<>(body, headers), String.class).getBody();
            return parseDistributedResponse(recipients, responseBody);
        } catch (HttpClientErrorException.NotFound e) {
            logger.warn("Hub Notifications is not available on this tenant (404) — not sending");
            return allOutcome(recipients, UNAVAILABLE);
        } catch (Exception e) {
            logger.warn("Distributed hub notification to {} recipients failed: {}", recipients.size(), e.getMessage());
            return allOutcome(recipients, FAILED);
        }
    }

    /** {@code header.title} + {@code body.description}, plus one optional plain (non-actionable) link. */
    static Map<String, Object> buildCard(String title, String description, String deepLinkUrl) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("title", title);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", description);

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("header", header);
        card.put("body", body);

        if (deepLinkUrl != null && !deepLinkUrl.isBlank()) {
            Map<String, Object> link = new LinkedHashMap<>();
            link.put("href", deepLinkUrl);
            link.put("text", "Open in Approval Tool");
            card.put("links", List.of(link));
        }
        return card;
    }

    /**
     * Parses the documented {@code {"<userId>": {"status_code":"200",...}}}
     * response shape. Any recipient missing from the response, or a recipient
     * whose {@code status_code} isn't 2xx, is reported {@code FAILED} rather
     * than assumed sent.
     */
    static Map<String, HubNotificationOutcome> parseDistributedResponse(List<String> recipients, String body) {
        Map<String, HubNotificationOutcome> outcomes = new LinkedHashMap<>();
        JsonNode root;
        try {
            root = MAPPER.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            return allOutcome(recipients, FAILED);
        }
        for (String id : recipients) {
            JsonNode entry = root.path(id);
            String status = entry.path("status_code").asText("");
            outcomes.put(id, status.startsWith("2") ? SENT : FAILED);
        }
        return outcomes;
    }

    private static Map<String, HubNotificationOutcome> allOutcome(List<String> ids, HubNotificationOutcome outcome) {
        Map<String, HubNotificationOutcome> result = new LinkedHashMap<>();
        ids.forEach(id -> result.put(id, outcome));
        return result;
    }
}
