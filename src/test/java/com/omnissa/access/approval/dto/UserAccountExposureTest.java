package com.omnissa.access.approval.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnissa.access.approval.model.security.Authority;
import com.omnissa.access.approval.model.security.AuthorityName;
import com.omnissa.access.approval.model.security.UserAccount;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two ways {@code /api/users} used to leak (#52 phase 0).
 *
 * <p>{@code GET} serialized the {@link UserAccount} entity, whose
 * {@code getPassword()} carries no {@code @JsonIgnore}, so every authenticated
 * caller could read the local admin's bcrypt hash. {@code POST} bound the same
 * entity, so a caller could post {@code authorities} and mint itself an admin.
 */
class UserAccountExposureTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static UserAccount admin() {
        Authority role = new Authority();
        role.setAuthorityName(AuthorityName.ROLE_ADMIN);

        UserAccount user = new UserAccount();
        user.setUsername("admin");
        user.setPassword("$2a$10$D9sVUxNMEr6oCsLtQxbMOubOaFsNzZq1s.notARealHash");
        user.setFirstName("Admin");
        user.setLastName("");
        user.setEmail("admin@localhost");
        user.setEnabled(true);
        user.setAuthorities(List.of(role));
        return user;
    }

    @Test
    void summaryNeverCarriesThePasswordHash() throws Exception {
        String json = mapper.writeValueAsString(UserSummary.from(admin()));

        assertFalse(json.contains("password"), "response must not expose a password field");
        assertFalse(json.contains("$2a$"), "response must not expose a bcrypt hash");
        assertTrue(json.contains("\"username\":\"admin\""));
    }

    @Test
    void summaryReportsRoles() {
        assertEquals(List.of("ROLE_ADMIN"), UserSummary.from(admin()).roles());
    }

    @Test
    void createRequestIgnoresInjectedAuthorities() throws Exception {
        String hostile = """
                {"username":"mallory","password":"hunter2","firstName":"M","lastName":"",
                 "email":"mallory@example.com",
                 "authorities":[{"authorityName":"ROLE_ADMIN"}],
                 "enabled":true}
                """;

        CreateUserRequest request = mapper
                .readerFor(CreateUserRequest.class)
                .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(hostile);

        assertEquals("mallory", request.username());
        // The record has no authorities/enabled component at all, so there is
        // nothing for a caller to set — role assignment stays server-side.
        assertFalse(java.util.Arrays.stream(CreateUserRequest.class.getRecordComponents())
                        .anyMatch(c -> c.getName().equals("authorities") || c.getName().equals("enabled")),
                "CreateUserRequest must not expose authorities or enabled");
    }

    @Test
    void userWithNoAuthoritiesDoesNotBlowUp() {
        UserAccount user = new UserAccount();
        user.setUsername("orphan");
        user.setAuthorities(null);

        assertTrue(user.getAuthorities().isEmpty());
        assertEquals(List.of(), UserSummary.from(user).roles());
    }
}
