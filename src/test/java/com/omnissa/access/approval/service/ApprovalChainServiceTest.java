package com.omnissa.access.approval.service;

import com.omnissa.access.approval.model.ApprovalChain;
import com.omnissa.access.approval.model.ApprovalStage;
import com.omnissa.access.approval.model.CalloutOperation;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.model.GroupMember;
import com.omnissa.access.approval.model.HubNotificationOutcome;
import com.omnissa.access.approval.repository.ApprovalChainRepository;
import com.omnissa.access.approval.repository.ApprovalStageRepository;
import com.omnissa.access.approval.util.RuleEngine;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Chain matching and per-stage eligibility (#53) — new ground for this
 * codebase, since every other decision path treats any APPROVER as eligible
 * for any request. These tests exist to pin down the one place that isn't
 * true anymore, and to make sure it stays scoped to chained requests only.
 */
class ApprovalChainServiceTest {

    private final ApprovalChainRepository chainRepository = mock(ApprovalChainRepository.class);
    private final ApprovalStageRepository stageRepository = mock(ApprovalStageRepository.class);
    private final AccessGroupService accessGroupService = mock(AccessGroupService.class);
    private final HubNotificationService hubNotificationService = mock(HubNotificationService.class);
    private final ApprovalChainService service = new ApprovalChainService();

    {
        ReflectionTestUtils.setField(service, "chainRepository", chainRepository);
        ReflectionTestUtils.setField(service, "stageRepository", stageRepository);
        ReflectionTestUtils.setField(service, "ruleEngine", new RuleEngine());
        ReflectionTestUtils.setField(service, "accessGroupService", accessGroupService);
        ReflectionTestUtils.setField(service, "hubNotificationService", hubNotificationService);
        ReflectionTestUtils.setField(service, "appBaseUrl", "https://approvals.example.com");
    }

    private static CalloutRequest requestFor(String appName) {
        CalloutRequest request = new CalloutRequest(CalloutOperation.activation, "req-1", "uuid-1",
                appName, "jdoe", null, null, null, null, null, null);
        request.setState("pending");
        return request;
    }

    private static ApprovalChain chain(long id, String appPattern) {
        ApprovalChain chain = new ApprovalChain();
        chain.setId(id);
        chain.setEnabled(true);
        chain.setName("Chain " + id);
        chain.setAppPattern(appPattern);
        return chain;
    }

    private static ApprovalStage roleStage(long chainId, int order, String role) {
        ApprovalStage stage = new ApprovalStage();
        stage.setChainId(chainId);
        stage.setStageOrder(order);
        stage.setApproverType("ROLE");
        stage.setApproverValue(role);
        return stage;
    }

    private static ApprovalStage userStage(long chainId, int order, String who) {
        ApprovalStage stage = new ApprovalStage();
        stage.setChainId(chainId);
        stage.setStageOrder(order);
        stage.setApproverType("USER");
        stage.setApproverValue(who);
        return stage;
    }

    private static ApprovalStage groupStage(long chainId, int order, String groupId) {
        ApprovalStage stage = new ApprovalStage();
        stage.setChainId(chainId);
        stage.setStageOrder(order);
        stage.setApproverType("GROUP");
        stage.setApproverValue(groupId);
        return stage;
    }

    // --- matchChain ---

    @Test
    void matchesAnEnabledChainWithAMatchingPatternAndAtLeastOneStage() {
        ApprovalChain chain = chain(1, "Salesforce");
        when(chainRepository.findAll(org.mockito.ArgumentMatchers.<org.springframework.data.domain.Sort>any()))
                .thenReturn(List.of(chain));
        when(stageRepository.findByChainIdOrderByStageOrderAsc(1L))
                .thenReturn(List.of(roleStage(1, 1, "ROLE_APPROVER")));

        assertThat(service.matchChain(requestFor("Salesforce"))).isEqualTo(chain);
    }

    @Test
    void aChainWithNoStagesIsNeverMatchedEvenIfItsCriteriaMatch() {
        ApprovalChain chain = chain(1, "Salesforce");
        when(chainRepository.findAll(org.mockito.ArgumentMatchers.<org.springframework.data.domain.Sort>any()))
                .thenReturn(List.of(chain));
        when(stageRepository.findByChainIdOrderByStageOrderAsc(1L)).thenReturn(List.of());

        assertThat(service.matchChain(requestFor("Salesforce")))
                .as("an empty chain would create a request nobody is ever eligible to decide")
                .isNull();
    }

    @Test
    void aDisabledChainIsNeverMatched() {
        ApprovalChain chain = chain(1, "Salesforce");
        chain.setEnabled(false);
        when(chainRepository.findAll(org.mockito.ArgumentMatchers.<org.springframework.data.domain.Sort>any()))
                .thenReturn(List.of(chain));

        assertThat(service.matchChain(requestFor("Salesforce"))).isNull();
    }

    @Test
    void nonMatchingAppNameReturnsNull() {
        ApprovalChain chain = chain(1, "Salesforce");
        when(chainRepository.findAll(org.mockito.ArgumentMatchers.<org.springframework.data.domain.Sort>any()))
                .thenReturn(List.of(chain));

        assertThat(service.matchChain(requestFor("Workday"))).isNull();
    }

    // --- ineligibilityReason ---

    @Test
    void aNonChainedRequestHasNoRestriction() {
        CalloutRequest request = requestFor("Salesforce"); // chainId null
        Authentication auth = new TestingAuthenticationToken("bob", "n/a",
                AuthorityUtils.createAuthorityList("ROLE_VIEWER"));

        assertThat(service.ineligibilityReason(request, auth)).isNull();
    }

    @Test
    void adminAlwaysPassesRegardlessOfStageType() {
        CalloutRequest request = requestFor("Salesforce");
        request.setChainId(1L);
        request.setCurrentStage(1);
        Authentication auth = new TestingAuthenticationToken("admin", "n/a",
                AuthorityUtils.createAuthorityList("ROLE_ADMIN"));

        assertThat(service.ineligibilityReason(request, auth)).isNull();
    }

    @Test
    void unauthenticatedIsIneligible() {
        CalloutRequest request = requestFor("Salesforce");
        request.setChainId(1L);

        assertThat(service.ineligibilityReason(request, mockUnauthenticated())).isNotNull();
    }

    private static Authentication mockUnauthenticated() {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(false);
        return auth;
    }

    @Test
    void roleStagePassesWhenAuthenticationHoldsTheRequiredRole() {
        CalloutRequest request = requestFor("Salesforce");
        request.setChainId(1L);
        request.setCurrentStage(2);
        when(stageRepository.findByChainIdOrderByStageOrderAsc(1L)).thenReturn(List.of(
                roleStage(1, 1, "ROLE_APPROVER"),
                roleStage(1, 2, "ROLE_ADMIN_APPROVER")));
        Authentication auth = new TestingAuthenticationToken("carol", "n/a",
                AuthorityUtils.createAuthorityList("ROLE_ADMIN_APPROVER"));

        assertThat(service.ineligibilityReason(request, auth)).isNull();
    }

    @Test
    void roleStageFailsWhenAuthenticationLacksTheRole() {
        CalloutRequest request = requestFor("Salesforce");
        request.setChainId(1L);
        request.setCurrentStage(1);
        when(stageRepository.findByChainIdOrderByStageOrderAsc(1L))
                .thenReturn(List.of(roleStage(1, 1, "ROLE_APPROVER")));
        Authentication auth = new TestingAuthenticationToken("carol", "n/a",
                AuthorityUtils.createAuthorityList("ROLE_VIEWER"));

        assertThat(service.ineligibilityReason(request, auth)).contains("ROLE_APPROVER");
    }

    @Test
    void groupStagePassesWhenTheOidcCallerEmailIsAResolvedMember() {
        CalloutRequest request = requestFor("Salesforce");
        request.setChainId(1L);
        request.setCurrentStage(1);
        when(stageRepository.findByChainIdOrderByStageOrderAsc(1L))
                .thenReturn(List.of(groupStage(1, 1, "group-abc")));
        when(accessGroupService.resolveMembers("group-abc")).thenReturn(List.of(
                new GroupMember("u1", "Jane Doe", "jdoe", "jane@corp.com", null)));

        OidcUser oidcUser = mock(OidcUser.class);
        when(oidcUser.getEmail()).thenReturn("JANE@corp.com"); // case-insensitive match
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        doReturn(AuthorityUtils.createAuthorityList("ROLE_APPROVER")).when(auth).getAuthorities();
        when(auth.getPrincipal()).thenReturn(oidcUser);

        assertThat(service.ineligibilityReason(request, auth)).isNull();
    }

    @Test
    void groupStageFailsWhenTheOidcCallerIsNotAResolvedMember() {
        CalloutRequest request = requestFor("Salesforce");
        request.setChainId(1L);
        request.setCurrentStage(1);
        when(stageRepository.findByChainIdOrderByStageOrderAsc(1L))
                .thenReturn(List.of(groupStage(1, 1, "group-abc")));
        when(accessGroupService.resolveMembers("group-abc")).thenReturn(List.of(
                new GroupMember("u1", "Jane Doe", "jdoe", "jane@corp.com", null)));

        OidcUser oidcUser = mock(OidcUser.class);
        when(oidcUser.getEmail()).thenReturn("someone-else@corp.com");
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        doReturn(AuthorityUtils.createAuthorityList("ROLE_APPROVER")).when(auth).getAuthorities();
        when(auth.getPrincipal()).thenReturn(oidcUser);

        assertThat(service.ineligibilityReason(request, auth)).isNotNull();
    }

    @Test
    void groupStageFailsClosedForALocalAccountEvenWithApproverRole() {
        CalloutRequest request = requestFor("Salesforce");
        request.setChainId(1L);
        request.setCurrentStage(1);
        when(stageRepository.findByChainIdOrderByStageOrderAsc(1L))
                .thenReturn(List.of(groupStage(1, 1, "group-abc")));

        // A local UserAccount principal, not an OidcUser — no Access identity to check.
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        doReturn(AuthorityUtils.createAuthorityList("ROLE_APPROVER")).when(auth).getAuthorities();
        when(auth.getPrincipal()).thenReturn("local-principal-placeholder");

        assertThat(service.ineligibilityReason(request, auth))
                .as("local accounts carry no Access group membership, so a GROUP stage must fail closed")
                .contains("local accounts");
    }

    @Test
    void aMissingStageConfigurationIsReportedRatherThanThrowing() {
        CalloutRequest request = requestFor("Salesforce");
        request.setChainId(1L);
        request.setCurrentStage(5); // no stage 5 configured
        when(stageRepository.findByChainIdOrderByStageOrderAsc(1L))
                .thenReturn(List.of(roleStage(1, 1, "ROLE_APPROVER")));
        Authentication auth = new TestingAuthenticationToken("carol", "n/a",
                AuthorityUtils.createAuthorityList("ROLE_APPROVER"));

        assertThat(service.ineligibilityReason(request, auth)).contains("stage 5");
    }

    // --- USER stages: one named individual ---

    @Test
    void userStagePassesForTheNamedLocalAccount() {
        CalloutRequest request = requestFor("Salesforce");
        request.setChainId(1L);
        request.setCurrentStage(1);
        when(stageRepository.findByChainIdOrderByStageOrderAsc(1L))
                .thenReturn(List.of(userStage(1, 1, "dave")));
        Authentication auth = new TestingAuthenticationToken("dave", "n/a",
                AuthorityUtils.createAuthorityList("ROLE_APPROVER"));

        assertThat(service.ineligibilityReason(request, auth))
                .as("unlike a GROUP stage, a local account CAN satisfy a named-user stage")
                .isNull();
    }

    @Test
    void userStageFailsForAnyoneElse() {
        CalloutRequest request = requestFor("Salesforce");
        request.setChainId(1L);
        request.setCurrentStage(1);
        when(stageRepository.findByChainIdOrderByStageOrderAsc(1L))
                .thenReturn(List.of(userStage(1, 1, "dave")));
        Authentication auth = new TestingAuthenticationToken("erin", "n/a",
                AuthorityUtils.createAuthorityList("ROLE_APPROVER"));

        assertThat(service.ineligibilityReason(request, auth)).contains("dave");
    }

    @Test
    void userStageMatchesAnyIdentityFormTheSessionAnswersTo() {
        // An operator naming a person will write whichever identity they know.
        OidcUser oidcUser = mock(OidcUser.class);
        when(oidcUser.getPreferredUsername()).thenReturn("jdoe");
        when(oidcUser.getEmail()).thenReturn("jane@corp.com");
        when(oidcUser.getSubject()).thenReturn("sub-123");
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn(oidcUser);

        assertThat(ApprovalChainService.matchesNamedUser(auth, "jdoe")).isTrue();
        assertThat(ApprovalChainService.matchesNamedUser(auth, "JANE@CORP.COM")).isTrue();
        assertThat(ApprovalChainService.matchesNamedUser(auth, "sub-123")).isTrue();
        assertThat(ApprovalChainService.matchesNamedUser(auth, "someone-else")).isFalse();
        assertThat(ApprovalChainService.matchesNamedUser(auth, "")).isFalse();
        assertThat(ApprovalChainService.matchesNamedUser(auth, null)).isFalse();
    }

    @Test
    void adminOverridesAUserStageToo() {
        CalloutRequest request = requestFor("Salesforce");
        request.setChainId(1L);
        request.setCurrentStage(1);
        Authentication auth = new TestingAuthenticationToken("admin", "n/a",
                AuthorityUtils.createAuthorityList("ROLE_ADMIN"));

        assertThat(service.ineligibilityReason(request, auth))
                .as("a named-user stage goes undecidable when that person leaves — "
                        + "the admin override is what stops that being a dead end")
                .isNull();
    }

    // --- notifyStageApprovers / resolveStageRecipients ---

    @Test
    @SuppressWarnings("unchecked")
    void groupStageNotifiesThatGroupsResolvedMembers() {
        ApprovalChain chain = chain(1, "Salesforce");
        when(chainRepository.findById(1L)).thenReturn(Optional.of(chain));
        when(accessGroupService.resolveMembers("group-abc")).thenReturn(List.of(
                new GroupMember("u1", "Jane Doe", "jdoe", "jane@corp.com", null),
                new GroupMember("u2", "John Smith", "jsmith", "john@corp.com", null)));
        when(hubNotificationService.notifyUsers(anyList(), anyString(), anyString(), any()))
                .thenReturn(Map.of());

        service.notifyStageApprovers(requestFor("Salesforce"), 1L, groupStage(1, 1, "group-abc"));

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        verify(hubNotificationService).notifyUsers(recipients.capture(), anyString(), anyString(), anyString());
        assertThat(recipients.getValue()).containsExactlyInAnyOrder("u1", "u2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void roleStageNotifiesMembersOfEveryGroupMappedToThatRoleMergedAndDeduped() {
        ApprovalChain chain = chain(1, "Salesforce");
        when(chainRepository.findById(1L)).thenReturn(Optional.of(chain));
        ReflectionTestUtils.setField(service, "roleMap", "group-1:APPROVER,group-2:APPROVER,group-3:ADMIN");
        when(accessGroupService.resolveMembers("group-1")).thenReturn(List.of(
                new GroupMember("u1", "Jane", "jdoe", "jane@corp.com", null)));
        when(accessGroupService.resolveMembers("group-2")).thenReturn(List.of(
                new GroupMember("u1", "Jane", "jdoe", "jane@corp.com", null), // same person, both groups
                new GroupMember("u2", "John", "jsmith", "john@corp.com", null)));
        when(hubNotificationService.notifyUsers(anyList(), anyString(), anyString(), any()))
                .thenReturn(Map.of());

        service.notifyStageApprovers(requestFor("Salesforce"), 1L, roleStage(1, 1, "ROLE_APPROVER"));

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        verify(hubNotificationService).notifyUsers(recipients.capture(), anyString(), anyString(), anyString());
        assertThat(recipients.getValue())
                .as("group-3 maps to ADMIN, not APPROVER, so its members must not be notified")
                .containsExactlyInAnyOrder("u1", "u2");
    }

    @Test
    void noRecipientsMeansNoNotificationSent() {
        ApprovalChain chain = chain(1, "Salesforce");
        when(chainRepository.findById(1L)).thenReturn(Optional.of(chain));
        when(accessGroupService.resolveMembers("group-abc")).thenReturn(List.of());

        service.notifyStageApprovers(requestFor("Salesforce"), 1L, groupStage(1, 1, "group-abc"));

        verify(hubNotificationService, never()).notifyUsers(any(), any(), any(), any());
    }

    @Test
    void aDeletedChainIsHandledWithoutThrowing() {
        when(chainRepository.findById(99L)).thenReturn(Optional.empty());

        service.notifyStageApprovers(requestFor("Salesforce"), 99L, groupStage(99, 1, "group-abc"));

        verify(hubNotificationService, never()).notifyUsers(any(), any(), any(), any());
    }

    @Test
    void aHubNotificationFailureIsSwallowedRatherThanPropagated() {
        ApprovalChain chain = chain(1, "Salesforce");
        when(chainRepository.findById(1L)).thenReturn(Optional.of(chain));
        when(accessGroupService.resolveMembers("group-abc"))
                .thenThrow(new RuntimeException("Access unreachable"));

        service.notifyStageApprovers(requestFor("Salesforce"), 1L, groupStage(1, 1, "group-abc"));
        // No exception reaching here is the assertion: a notification failure
        // must never affect the decision it's reporting on.
    }
}
