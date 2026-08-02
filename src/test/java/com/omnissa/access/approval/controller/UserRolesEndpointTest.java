package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.model.security.Authority;
import com.omnissa.access.approval.model.security.AuthorityName;
import com.omnissa.access.approval.model.security.UserAccount;
import com.omnissa.access.approval.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT /api/users/{id}/roles} through the real save path — end to end,
 * not the service method in isolation. {@link com.omnissa.access.approval.service.LocalAccountService#setRoles}
 * used to replace the account's authority collection with an immutable
 * {@code Stream.toList()} result; Hibernate's collection-replace logic needs
 * to clear and refill the collection it's tracking, and a locked list throws
 * on {@code .clear()} — so every call 500'd. Nothing exercised this endpoint
 * through the actual save path before, which is exactly how that shipped.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:user-roles-endpoint;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "omnissa.api.username=",
        "omnissa.bootstrap.url="
})
class UserRolesEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void clean() {
        userAccountRepository.deleteAll();
    }

    private Long seedViewerAccount() {
        UserAccount user = new UserAccount();
        user.setUsername("roleschange");
        user.setPassword(passwordEncoder.encode("SomeStr0ngPassword!"));
        user.setFirstName("Roles");
        user.setLastName("Change");
        user.setEmail("roleschange@example.com");
        user.setEnabled(true);
        Authority viewer = new Authority();
        viewer.setAuthorityName(AuthorityName.ROLE_VIEWER);
        List<Authority> authorities = new ArrayList<>();
        authorities.add(viewer);
        user.setAuthorities(authorities);
        return userAccountRepository.save(user).getId();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("changing an account's roles succeeds and actually changes them")
    void changingRolesSucceeds() throws Exception {
        Long id = seedViewerAccount();

        mockMvc.perform(put("/api/users/" + id + "/roles")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"APPROVER\"]}"))
                .andExpect(status().isOk());

        UserAccount reloaded = userAccountRepository.findById(id).orElseThrow();
        List<AuthorityName> roleNames = reloaded.getAuthorityEntities().stream()
                .map(Authority::getAuthorityName)
                .toList();
        assertThat(roleNames)
                .as("the replacement must actually take — not just avoid crashing")
                .containsExactly(AuthorityName.ROLE_APPROVER);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("roles can be changed more than once in a row")
    void rolesCanBeChangedRepeatedly() throws Exception {
        Long id = seedViewerAccount();

        mockMvc.perform(put("/api/users/" + id + "/roles")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"APPROVER\"]}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/users/" + id + "/roles")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"AUDITOR\",\"VIEWER\"]}"))
                .andExpect(status().isOk());

        UserAccount reloaded = userAccountRepository.findById(id).orElseThrow();
        List<AuthorityName> roleNames = reloaded.getAuthorityEntities().stream()
                .map(Authority::getAuthorityName)
                .toList();
        assertThat(roleNames).containsExactlyInAnyOrder(AuthorityName.ROLE_AUDITOR, AuthorityName.ROLE_VIEWER);
    }
}
