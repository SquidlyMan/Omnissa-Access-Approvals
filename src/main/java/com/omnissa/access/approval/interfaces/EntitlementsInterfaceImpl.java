package com.omnissa.access.approval.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.model.OmnissaServer;
import com.omnissa.access.approval.model.RevokeOutcome;
import com.omnissa.access.approval.util.OmnissaRestClient;
import com.omnissa.access.approval.util.Paths;
import com.omnissa.access.approval.util.RestPreconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

/**
 * JIT / time-bound access enforcement (#49) via per-user <em>exclusions</em>.
 *
 * <p>Access grants an app to users or groups. Deleting a user entitlement only
 * works when the grant is user-level; group-provisioned access has no per-user
 * entitlement to remove. Instead we toggle a per-user <b>exclusion</b> (a
 * {@code negative} entitlement), which overrides group access for that one user
 * without disturbing the group:
 * <ul>
 *   <li><b>grant</b> (approval) → DELETE the exclusion so access applies;</li>
 *   <li><b>revoke</b> (expiry) → PUT the exclusion so access is blocked.</li>
 * </ul>
 * The requester's SCIM id is resolved from the app's entitlement listing (or the
 * SCIM directory as a fallback) at grant time and persisted, because the inbound
 * callout's numeric userId cannot be mapped back to a SCIM id afterward.
 */
@Service
public class EntitlementsInterfaceImpl implements EntitlementsInterface {

    private static final Logger logger = LoggerFactory.getLogger(EntitlementsInterfaceImpl.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String grantAccess(CalloutRequest request) {
        String catalogItemId = request.getResourceUuid();
        if (catalogItemId == null || catalogItemId.isBlank()) {
            return null;
        }
        OmnissaRestClient restClient = client();
        String base = RestPreconditions.omnissaServerBaseUrl();

        String subjectId = request.getScimUserId();
        if (subjectId == null) {
            subjectId = resolveSubjectId(request, restClient, base);
        }
        if (subjectId == null) {
            logger.warn("Grant requestId={}: could not resolve requester SCIM id — cannot lift exclusion; "
                    + "userId={} userAttributes keys={}", request.getRequestId(), request.getUserId(),
                    request.getUserAttributes() != null ? request.getUserAttributes().keySet() : "none");
            return null;
        }
        try {
            restClient.exchange(base + Paths.ENTITLEMENTS_USER, HttpMethod.DELETE,
                    null, String.class, catalogItemId, subjectId);
            logger.info("Grant requestId={}: removed exclusion (app={}, subjectId={})",
                    request.getRequestId(), catalogItemId, subjectId);
        } catch (HttpClientErrorException e) {
            // 403/404 = there was no exclusion to remove — fine, access already applies.
            logger.info("Grant requestId={}: no exclusion to remove ({})", request.getRequestId(), e.getStatusCode());
        } catch (Exception e) {
            logger.warn("Grant requestId={}: exclusion removal failed (non-fatal): {}",
                    request.getRequestId(), e.getMessage());
        }
        return subjectId;
    }

    @Override
    public RevokeOutcome revokeAccess(CalloutRequest request) {
        String catalogItemId = request.getResourceUuid();
        if (catalogItemId == null || catalogItemId.isBlank()) {
            logger.warn("Cannot revoke requestId={} — no resourceUuid", request.getRequestId());
            return RevokeOutcome.ERROR;
        }
        OmnissaRestClient restClient = client();
        String base = RestPreconditions.omnissaServerBaseUrl();

        String subjectId = request.getScimUserId();
        if (subjectId == null) {
            subjectId = resolveSubjectId(request, restClient, base);
        }
        if (subjectId == null) {
            logger.warn("Revoke requestId={}: could not resolve requester SCIM id — will retry next sweep",
                    request.getRequestId());
            return RevokeOutcome.ERROR;
        }

        // PUT (upsert) a negative entitlement — a plain POST 409s when the user is
        // already entitled via a group.
        String body = "{\"catalogItemId\":\"" + catalogItemId + "\",\"subjectType\":\"USERS\",\"subjectId\":\""
                + subjectId + "\",\"activationPolicy\":\"USER_ACTIVATED\",\"negative\":true}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            restClient.exchange(base + Paths.ENTITLEMENTS_USER, HttpMethod.PUT,
                    new HttpEntity<>(body, headers), String.class, catalogItemId, subjectId);
            logger.info("Revoke requestId={}: added exclusion (app={}, subjectId={})",
                    request.getRequestId(), catalogItemId, subjectId);
            return RevokeOutcome.REVOKED;
        } catch (ResourceAccessException e) {
            logger.warn("Revoke requestId={}: Access unreachable — retry next sweep: {}",
                    request.getRequestId(), e.getMessage());
            return RevokeOutcome.UNREACHABLE;
        } catch (Exception e) {
            logger.error("Revoke requestId={}: exclusion PUT failed", request.getRequestId(), e);
            return RevokeOutcome.ERROR;
        }
    }

    private OmnissaRestClient client() {
        OmnissaServer server = RestPreconditions.omnissaServerConfig();
        return new OmnissaRestClient(server);
    }

    /**
     * Resolve the requester's SCIM id. Primary: the app's entitlement listing —
     * if the user has an entry (e.g. a default exclusion), it carries subjectId.
     * Fallback: the SCIM directory, filtered by userName candidates.
     */
    private String resolveSubjectId(CalloutRequest request, OmnissaRestClient restClient, String base) {
        String catalogItemId = request.getResourceUuid();
        try {
            HttpHeaders accept = new HttpHeaders();
            accept.setAccept(List.of(MediaType.APPLICATION_JSON));
            String listing = restClient.exchange(base + Paths.ENTITLEMENTS_CATALOGITEM, HttpMethod.GET,
                    new HttpEntity<>(accept), String.class, catalogItemId).getBody();
            JsonNode items = objectMapper.readTree(listing == null ? "{}" : listing).path("items");
            String sid = matchSubjectId(items, requesterEmail(request), requesterUserName(request));
            if (sid != null) {
                return sid;
            }
        } catch (Exception e) {
            logger.warn("Resolve requestId={}: entitlement-listing lookup failed: {}",
                    request.getRequestId(), e.getMessage());
        }
        return scimLookup(request, restClient, base);
    }

    /** SCIM directory fallback: try userName candidates derived from the callout attributes. */
    private String scimLookup(CalloutRequest request, OmnissaRestClient restClient, String base) {
        String userName = requesterUserName(request);
        String email = requesterEmail(request);
        String emailLocal = (email != null && email.contains("@")) ? email.substring(0, email.indexOf('@')) : null;
        HttpHeaders accept = new HttpHeaders();
        accept.setAccept(List.of(MediaType.APPLICATION_JSON));
        for (String candidate : new String[]{userName, emailLocal, email}) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                // Pass the filter as a URI template variable so RestTemplate encodes
                // it exactly once — pre-encoding here yields a double-encoded filter
                // that Access silently matches zero users against.
                String resp = restClient.exchange(base + Paths.SCIM_USERS + "?filter={f}",
                        HttpMethod.GET, new HttpEntity<>(accept), String.class,
                        "userName eq \"" + candidate + "\"").getBody();
                JsonNode resources = objectMapper.readTree(resp == null ? "{}" : resp).path("Resources");
                if (resources.isArray() && !resources.isEmpty()) {
                    String id = text(resources.get(0), "id");
                    if (id != null) {
                        logger.info("Resolve requestId={}: SCIM matched userName='{}' -> {}",
                                request.getRequestId(), candidate, id);
                        return id;
                    }
                }
            } catch (Exception e) {
                logger.debug("SCIM lookup for '{}' failed: {}", candidate, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Find the requester's SCIM subjectId among an entitlement listing's
     * {@code items}. Each item carries {@code subjectId}, {@code name} (userName)
     * and {@code displayName} (email). Match by email/userName; fall back to the
     * sole USERS entry (groups ignored). Pure/static for unit testing; returns
     * null when no confident match exists.
     */
    static String matchSubjectId(JsonNode items, String email, String userName) {
        if (items == null || !items.isArray() || items.isEmpty()) {
            return null;
        }
        JsonNode soleUser = null;
        int userCount = 0;
        for (JsonNode item : items) {
            if (!"USERS".equalsIgnoreCase(text(item, "subjectType"))) {
                continue;
            }
            String subjectId = text(item, "subjectId");
            if (subjectId == null) {
                continue;
            }
            userCount++;
            soleUser = item;
            String name = text(item, "name");
            String displayName = text(item, "displayName");
            if (matches(email, name, displayName) || matches(userName, name, displayName)) {
                return subjectId;
            }
        }
        // Exactly one USERS subject (groups ignored): unambiguous even without an attribute match.
        return userCount == 1 ? text(soleUser, "subjectId") : null;
    }

    private static boolean matches(String candidate, String name, String displayName) {
        return candidate != null && !candidate.isBlank()
                && (candidate.equalsIgnoreCase(name) || candidate.equalsIgnoreCase(displayName));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && v.isTextual() && !v.asText().isBlank()) ? v.asText() : null;
    }

    private static String requesterEmail(CalloutRequest request) {
        return firstAttr(request, "email");
    }

    private static String requesterUserName(CalloutRequest request) {
        return firstAttr(request, "userName");
    }

    private static String firstAttr(CalloutRequest request, String key) {
        if (request.getUserAttributes() == null) {
            return null;
        }
        List<String> vals = request.getUserAttributes().get(key);
        return (vals != null && !vals.isEmpty()) ? vals.get(0) : null;
    }
}
