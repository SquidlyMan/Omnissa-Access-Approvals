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
 * without disturbing the group.
 *
 * <ul>
 *   <li><b>grant</b> (approval): record the SCIM id + assignment type; lift any
 *       existing exclusion so access applies.</li>
 *   <li><b>revoke</b> (expiry): PUT a negative entitlement — for a group user
 *       this creates an exclusion; for a directly-assigned user it flips their
 *       entitlement to excluded. Access then deprovisions the app.</li>
 *   <li><b>restore</b> (re-requestable grants, after a short hold): lift the
 *       exclusion — DELETE it for a group user (group reapplies) or flip the
 *       direct user back to "User Provisioned" — so the app is requestable
 *       again.</li>
 * </ul>
 */
@Service
public class EntitlementsInterfaceImpl implements EntitlementsInterface {

    private static final Logger logger = LoggerFactory.getLogger(EntitlementsInterfaceImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String grantAccess(CalloutRequest request) {
        String catalogItemId = request.getResourceUuid();
        if (catalogItemId == null || catalogItemId.isBlank()) {
            return null;
        }
        OmnissaRestClient restClient = client();
        String base = RestPreconditions.omnissaServerBaseUrl();

        JsonNode items = readListing(restClient, base, catalogItemId);
        String subjectId = request.getScimUserId();
        if (subjectId == null) {
            subjectId = (items != null) ? matchSubjectId(items, requesterEmail(request), requesterUserName(request)) : null;
        }
        if (subjectId == null) {
            subjectId = scimLookup(request, restClient, base);
        }
        if (subjectId == null) {
            logger.warn("Grant requestId={}: could not resolve requester SCIM id — cannot lift exclusion; "
                    + "userId={} userAttributes keys={}", request.getRequestId(), request.getUserId(),
                    request.getUserAttributes() != null ? request.getUserAttributes().keySet() : "none");
            return null;
        }

        // Record how the user gets the app so the later restore picks the right path.
        String assignment = (items != null && hasPositiveUserEntry(items, subjectId)) ? "USER" : "GROUP";
        request.setScimUserId(subjectId);
        request.setAssignmentType(assignment);

        // Only lift an actual exclusion — never delete a directly-assigned user's
        // real (positive) entitlement. Normally a no-op at grant time.
        if (items != null && hasNegativeUserEntry(items, subjectId)) {
            liftExclusion(request, restClient, base, catalogItemId, subjectId, assignment);
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

        // PUT (upsert) a negative entitlement: creates an exclusion for a group
        // user, or flips a directly-assigned user to excluded. A plain POST 409s
        // when the user is already entitled via a group.
        String body = userEntitlementJson(catalogItemId, subjectId, true);
        try {
            put(restClient, base, catalogItemId, subjectId, body);
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

    @Override
    public RevokeOutcome restoreAccess(CalloutRequest request) {
        String catalogItemId = request.getResourceUuid();
        String subjectId = request.getScimUserId();
        if (catalogItemId == null || subjectId == null) {
            logger.warn("Cannot restore requestId={} — missing resourceUuid/scimUserId", request.getRequestId());
            return RevokeOutcome.ERROR;
        }
        OmnissaRestClient restClient = client();
        String base = RestPreconditions.omnissaServerBaseUrl();
        try {
            liftExclusion(request, restClient, base, catalogItemId, subjectId, request.getAssignmentType());
            return RevokeOutcome.REVOKED;
        } catch (ResourceAccessException e) {
            logger.warn("Restore requestId={}: Access unreachable — retry next sweep: {}",
                    request.getRequestId(), e.getMessage());
            return RevokeOutcome.UNREACHABLE;
        } catch (Exception e) {
            logger.error("Restore requestId={}: failed", request.getRequestId(), e);
            return RevokeOutcome.ERROR;
        }
    }

    /**
     * Lift a user's exclusion so access applies again. For a directly-assigned
     * user, flip their entitlement back to positive ("User Provisioned"); for a
     * group user, delete the exclusion so the group entitlement reapplies.
     */
    private void liftExclusion(CalloutRequest request, OmnissaRestClient restClient, String base,
                               String catalogItemId, String subjectId, String assignmentType) {
        if ("USER".equals(assignmentType)) {
            put(restClient, base, catalogItemId, subjectId, userEntitlementJson(catalogItemId, subjectId, false));
            logger.info("Restore requestId={}: re-provisioned direct user (app={}, subjectId={})",
                    request.getRequestId(), catalogItemId, subjectId);
        } else {
            try {
                restClient.exchange(base + Paths.ENTITLEMENTS_USER, HttpMethod.DELETE,
                        null, String.class, catalogItemId, subjectId);
                logger.info("Restore requestId={}: removed exclusion (app={}, subjectId={})",
                        request.getRequestId(), catalogItemId, subjectId);
            } catch (HttpClientErrorException e) {
                // No exclusion present — access already applies.
                logger.info("Restore requestId={}: no exclusion to remove ({})", request.getRequestId(), e.getStatusCode());
            }
        }
    }

    private void put(OmnissaRestClient restClient, String base, String catalogItemId, String subjectId, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        restClient.exchange(base + Paths.ENTITLEMENTS_USER, HttpMethod.PUT,
                new HttpEntity<>(body, headers), String.class, catalogItemId, subjectId);
    }

    private static String userEntitlementJson(String catalogItemId, String subjectId, boolean negative) {
        return "{\"catalogItemId\":\"" + catalogItemId + "\",\"subjectType\":\"USERS\",\"subjectId\":\""
                + subjectId + "\",\"activationPolicy\":\"USER_ACTIVATED\",\"negative\":" + negative + "}";
    }

    private OmnissaRestClient client() {
        OmnissaServer server = RestPreconditions.omnissaServerConfig();
        return new OmnissaRestClient(server);
    }

    private JsonNode readListing(OmnissaRestClient restClient, String base, String catalogItemId) {
        try {
            HttpHeaders accept = new HttpHeaders();
            accept.setAccept(List.of(MediaType.APPLICATION_JSON));
            String listing = restClient.exchange(base + Paths.ENTITLEMENTS_CATALOGITEM, HttpMethod.GET,
                    new HttpEntity<>(accept), String.class, catalogItemId).getBody();
            return MAPPER.readTree(listing == null ? "{}" : listing).path("items");
        } catch (Exception e) {
            logger.warn("Entitlement-listing read failed for {}: {}", catalogItemId, e.getMessage());
            return null;
        }
    }

    private String resolveSubjectId(CalloutRequest request, OmnissaRestClient restClient, String base) {
        JsonNode items = readListing(restClient, base, request.getResourceUuid());
        String sid = (items != null) ? matchSubjectId(items, requesterEmail(request), requesterUserName(request)) : null;
        return sid != null ? sid : scimLookup(request, restClient, base);
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
                // it exactly once — pre-encoding yields a double-encoded filter that
                // Access silently matches zero users against.
                String resp = restClient.exchange(base + Paths.SCIM_USERS + "?filter={f}",
                        HttpMethod.GET, new HttpEntity<>(accept), String.class,
                        "userName eq \"" + candidate + "\"").getBody();
                JsonNode resources = MAPPER.readTree(resp == null ? "{}" : resp).path("Resources");
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
        return userCount == 1 ? text(soleUser, "subjectId") : null;
    }

    /** True if the listing has a positive (non-excluded) USERS entry for this subject → directly assigned. */
    static boolean hasPositiveUserEntry(JsonNode items, String subjectId) {
        return hasUserEntry(items, subjectId, false);
    }

    /** True if the listing has an excluded (negative) USERS entry for this subject. */
    static boolean hasNegativeUserEntry(JsonNode items, String subjectId) {
        return hasUserEntry(items, subjectId, true);
    }

    private static boolean hasUserEntry(JsonNode items, String subjectId, boolean negative) {
        if (items == null || !items.isArray() || subjectId == null) {
            return false;
        }
        for (JsonNode item : items) {
            if ("USERS".equalsIgnoreCase(text(item, "subjectType"))
                    && subjectId.equals(text(item, "subjectId"))
                    && item.path("negative").asBoolean(false) == negative) {
                return true;
            }
        }
        return false;
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
