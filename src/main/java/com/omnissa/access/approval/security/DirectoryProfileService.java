package com.omnissa.access.approval.security;

import com.omnissa.access.approval.model.security.DirectoryProfile;
import com.omnissa.access.approval.repository.DirectoryProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;

/**
 * Remembers the Access-sourced directory attributes a signed-in OIDC user's
 * own token carried, so a later feature (escalation/notification recipient
 * resolution — #51, #53) can find someone's email without a live directory
 * lookup this codebase does not yet have.
 *
 * <p>This is deliberately capture-at-login, not a live group/member lookup:
 * it only ever knows about people who have signed in at least once, and only
 * as current as their last login. Both are known, accepted limits — see
 * {@link DirectoryProfile}'s javadoc.
 */
@Service
public class DirectoryProfileService {

    private static final Logger logger = LoggerFactory.getLogger(DirectoryProfileService.class);

    /**
     * Candidate claim keys for attributes with no verified name on this
     * tenant. First match wins; absent claims simply leave the field null.
     * {@code phone_number} is the one standard (OIDC "phone" scope) claim
     * here — the rest are unverified guesses at how Access might expose
     * synced AD attributes, to be confirmed via {@code GET /api/auth/claims}
     * against a live tenant before anything depends on them.
     */
    private static final String[] MOBILE_CLAIM_KEYS = {"mobile", "mobilePhone", "mobile_phone"};
    private static final String[] UPN_CLAIM_KEYS = {"userPrincipalName", "user_principal_name", "upn"};
    private static final String[] SAM_CLAIM_KEYS = {"samAccountName", "sAMAccountName", "sam_account_name"};

    @Autowired
    private DirectoryProfileRepository repository;

    /**
     * Upserts the profile for {@code identity}. Never throws — a directory
     * snapshot is a convenience for a later feature, not something that may
     * ever block a login, so any persistence failure is logged and dropped.
     */
    public void captureFromLogin(OidcUser user, String identity) {
        if (identity == null || identity.isBlank()) {
            return;
        }
        try {
            DirectoryProfile profile = repository.findByIdentity(identity);
            if (profile == null) {
                profile = new DirectoryProfile();
                profile.setIdentity(identity);
            }

            Map<String, Object> claims = user.getClaims();
            profile.setSubject(user.getSubject());
            profile.setDisplayName(user.getFullName());
            profile.setEmail(user.getEmail());
            profile.setPhoneNumber(user.getPhoneNumber());
            profile.setMobilePhone(firstNonBlankClaim(claims, MOBILE_CLAIM_KEYS));
            profile.setUserPrincipalName(firstNonBlankClaim(claims, UPN_CLAIM_KEYS));
            profile.setSamAccountName(firstNonBlankClaim(claims, SAM_CLAIM_KEYS));
            profile.setLastLoginAt(new Date());

            repository.save(profile);
        } catch (Exception e) {
            logger.warn("Failed to capture directory profile for {}: {}", identity, e.getMessage());
        }
    }

    private static String firstNonBlankClaim(Map<String, Object> claims, String... keys) {
        for (String key : keys) {
            Object value = claims.get(key);
            if (value instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }
}
