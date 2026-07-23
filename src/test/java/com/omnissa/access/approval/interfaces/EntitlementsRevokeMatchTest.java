package com.omnissa.access.approval.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the JIT revocation subjectId matcher (#49). This decides which
 * user's entitlement gets DELETEd, so a wrong or unsure match MUST return null
 * rather than risk revoking the wrong person.
 */
class EntitlementsRevokeMatchTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode items(String json) throws Exception {
        return mapper.readTree(json).path("items");
    }

    /** Mirrors the real listing: subjectId + name (userName) + displayName (email). */
    private static final String LISTING = """
        {"items":[
          {"subjectType":"USERS","subjectId":"scim-dean","name":"dean","displayName":"dean@flaming.ws"},
          {"subjectType":"USERS","subjectId":"scim-amy","name":"amy","displayName":"amy@flaming.ws"}
        ]}""";

    @Test
    void matchesByEmail() throws Exception {
        assertEquals("scim-dean",
                EntitlementsInterfaceImpl.matchSubjectId(items(LISTING), "dean@flaming.ws", null));
    }

    @Test
    void matchesByUserName() throws Exception {
        assertEquals("scim-amy",
                EntitlementsInterfaceImpl.matchSubjectId(items(LISTING), null, "amy"));
    }

    @Test
    void matchIsCaseInsensitive() throws Exception {
        assertEquals("scim-dean",
                EntitlementsInterfaceImpl.matchSubjectId(items(LISTING), "DEAN@FLAMING.WS", null));
    }

    @Test
    void noMatchAmongMultipleReturnsNull() throws Exception {
        // Neither email nor userName matches anyone — must NOT guess.
        assertNull(EntitlementsInterfaceImpl.matchSubjectId(items(LISTING), "ghost@nowhere.io", "ghost"));
    }

    @Test
    void soleUserIsMatchedEvenWithoutAttributeMatch() throws Exception {
        String sole = """
            {"items":[{"subjectType":"USERS","subjectId":"scim-only","name":"x","displayName":"x@y.z"}]}""";
        assertEquals("scim-only",
                EntitlementsInterfaceImpl.matchSubjectId(items(sole), "unrelated@z.z", null));
    }

    @Test
    void emptyOrMissingItemsReturnsNull() throws Exception {
        assertNull(EntitlementsInterfaceImpl.matchSubjectId(items("{\"items\":[]}"), "dean@flaming.ws", "dean"));
        assertNull(EntitlementsInterfaceImpl.matchSubjectId(items("{}"), "dean@flaming.ws", "dean"));
    }

    @Test
    void nonUserSubjectsAreSkipped() throws Exception {
        // A GROUPS entitlement must never be selected as a user subject.
        String withGroup = """
            {"items":[
              {"subjectType":"GROUPS","subjectId":"grp-1","name":"admins","displayName":"Admins"},
              {"subjectType":"USERS","subjectId":"scim-dean","name":"dean","displayName":"dean@flaming.ws"}
            ]}""";
        assertEquals("scim-dean",
                EntitlementsInterfaceImpl.matchSubjectId(items(withGroup), "dean@flaming.ws", null));
        // Sole entry is a group → no user to match.
        String onlyGroup = """
            {"items":[{"subjectType":"GROUPS","subjectId":"grp-1","name":"admins","displayName":"Admins"}]}""";
        assertNull(EntitlementsInterfaceImpl.matchSubjectId(items(onlyGroup), "dean@flaming.ws", "dean"));
    }
}
