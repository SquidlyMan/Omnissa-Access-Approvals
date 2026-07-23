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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.HttpEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

@Service
public class EntitlementsInterfaceImpl implements EntitlementsInterface {

    private static final Logger logger = LoggerFactory.getLogger(EntitlementsInterfaceImpl.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public RevokeOutcome revokeAccess(CalloutRequest request) {
        String catalogItemId = request.getResourceUuid();
        if (catalogItemId == null || catalogItemId.isBlank()) {
            logger.warn("Cannot revoke requestId={} — no resourceUuid", request.getRequestId());
            return RevokeOutcome.ERROR;
        }

        OmnissaServer server = RestPreconditions.omnissaServerConfig();
        OmnissaRestClient restClient = new OmnissaRestClient(server);
        String base = RestPreconditions.omnissaServerBaseUrl();

        // 1. Read the app's entitlement listing to resolve the user's SCIM id.
        JsonNode items;
        try {
            HttpHeaders accept = new HttpHeaders();
            accept.setAccept(List.of(MediaType.APPLICATION_JSON));
            String body = restClient.exchange(
                    base + Paths.ENTITLEMENTS_CATALOGITEM, HttpMethod.GET,
                    new HttpEntity<>(accept), String.class, catalogItemId).getBody();
            items = objectMapper.readTree(body == null ? "{}" : body).path("items");
        } catch (ResourceAccessException e) {
            logger.warn("Revoke requestId={}: Access unreachable while reading entitlements — retry next sweep: {}",
                    request.getRequestId(), e.getMessage());
            return RevokeOutcome.UNREACHABLE;
        } catch (Exception e) {
            logger.error("Revoke requestId={}: failed to read entitlement listing", request.getRequestId(), e);
            return RevokeOutcome.ERROR;
        }

        String subjectId = matchSubjectId(items, requesterEmail(request), requesterUserName(request));
        if (subjectId == null) {
            // User is not in the entitlement listing — access is already gone
            // (revoked out-of-band, or the grant was never provisioned). Idempotent success.
            logger.info("Revoke requestId={}: user not entitled to {} — nothing to revoke",
                    request.getRequestId(), catalogItemId);
            return RevokeOutcome.ALREADY_ABSENT;
        }

        // 2. DELETE the entitlement.
        try {
            restClient.exchange(base + Paths.ENTITLEMENTS_USER, HttpMethod.DELETE,
                    null, String.class, catalogItemId, subjectId);
            logger.info("Revoke requestId={}: deleted entitlement (app={}, subjectId={})",
                    request.getRequestId(), catalogItemId, subjectId);
            return RevokeOutcome.REVOKED;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return RevokeOutcome.ALREADY_ABSENT;
            }
            logger.error("Revoke requestId={}: Access rejected DELETE ({})",
                    request.getRequestId(), e.getStatusCode());
            return RevokeOutcome.ERROR;
        } catch (ResourceAccessException e) {
            logger.warn("Revoke requestId={}: Access unreachable on DELETE — retry next sweep: {}",
                    request.getRequestId(), e.getMessage());
            return RevokeOutcome.UNREACHABLE;
        } catch (Exception e) {
            logger.error("Revoke requestId={}: DELETE failed", request.getRequestId(), e);
            return RevokeOutcome.ERROR;
        }
    }

    /**
     * Find the entitled user's SCIM subjectId in an entitlement listing's
     * {@code items} array. Each item carries {@code subjectId}, {@code name}
     * (SCIM userName) and {@code displayName} (email/label). Match our requester
     * by email or userName; fall back to the sole entitled user. Pure/static for
     * unit testing. Returns null when no confident match is found.
     */
    static String matchSubjectId(JsonNode items, String email, String userName) {
        if (items == null || !items.isArray() || items.isEmpty()) {
            return null;
        }
        for (JsonNode item : items) {
            if (!"USERS".equalsIgnoreCase(text(item, "subjectType"))) {
                continue;
            }
            String subjectId = text(item, "subjectId");
            if (subjectId == null) {
                continue;
            }
            String name = text(item, "name");
            String displayName = text(item, "displayName");
            if (matches(email, name, displayName) || matches(userName, name, displayName)) {
                return subjectId;
            }
        }
        // Sole entitled user: unambiguous even without an attribute match.
        if (items.size() == 1 && "USERS".equalsIgnoreCase(text(items.get(0), "subjectType"))) {
            return text(items.get(0), "subjectId");
        }
        return null;
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
