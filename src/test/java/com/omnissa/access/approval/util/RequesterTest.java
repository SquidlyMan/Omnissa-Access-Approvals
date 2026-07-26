package com.omnissa.access.approval.util;

import com.omnissa.access.approval.model.CalloutOperation;
import com.omnissa.access.approval.model.CalloutRequest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who an access request is for (#60). The audit trail stores this on the event
 * rather than resolving it through the request, because an admin can delete a
 * request while its audit history remains — so the identity has to be captured
 * at write time or it is lost.
 */
class RequesterTest {

    private CalloutRequest request(HashMap<String, List<String>> attrs, String userId) {
        return new CalloutRequest(CalloutOperation.activation, "req-1", "app-uuid",
                "I Am Showcase (Access)", userId, attrs, null, null, null, null, null);
    }

    private HashMap<String, List<String>> attrs(String... pairs) {
        HashMap<String, List<String>> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], List.of(pairs[i + 1]));
        }
        return map;
    }

    @Test
    void prefersFullNameAndKeepsEmailAndId() {
        Requester r = Requester.from(request(
                attrs("firstName", "Dean", "lastName", "Flaming", "email", "dean@flaming.ws"), "751802"));

        assertEquals("Dean Flaming", r.name());
        assertEquals("dean@flaming.ws", r.email());
        assertEquals("751802", r.id());
        assertEquals("Dean Flaming (dean@flaming.ws) · 751802", r.label());
    }

    @Test
    void fallsBackToUserNameThenEmail() {
        Requester withUserName = Requester.from(request(attrs("userName", "dean"), "751802"));
        assertEquals("dean", withUserName.name());

        Requester emailOnly = Requester.from(request(attrs("email", "dean@flaming.ws"), "751802"));
        assertNull(emailOnly.name());
        assertEquals("(dean@flaming.ws) · 751802", emailOnly.label());
        assertEquals("dean@flaming.ws", emailOnly.shortLabel());
    }

    /** The numeric id is the only field always present — never lose it. */
    @Test
    void bareIdStillProducesAReadableLabel() {
        Requester r = Requester.from(request(new HashMap<>(), "751802"));

        assertNull(r.name());
        assertNull(r.email());
        assertEquals("751802", r.id());
        assertEquals("user 751802", r.label());
        assertTrue(r.isKnown());
    }

    @Test
    void nullRequestIsUnknownRatherThanAnException() {
        Requester r = Requester.from(null);

        assertFalse(r.isKnown());
        assertEquals("unknown user", r.label());
        assertEquals(Requester.UNKNOWN, r);
    }

    @Test
    void blankAttributeValuesAreIgnored() {
        Requester r = Requester.from(request(
                attrs("firstName", "  ", "lastName", "  ", "email", " "), "751802"));

        assertNull(r.name());
        assertNull(r.email());
        assertEquals("user 751802", r.label());
    }
}
