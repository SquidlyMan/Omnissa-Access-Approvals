package com.omnissa.access.approval.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnissa.access.approval.model.AuditEvent;
import com.omnissa.access.approval.model.CalloutOperation;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.repository.ApprovalsRepository;
import com.omnissa.access.approval.repository.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * Pins the JSON of the two paged endpoints (#64).
 *
 * <p>Both used to return Spring Data's {@code Page} straight out of the
 * controller, so the wire format was whatever {@code PageImpl}'s bean
 * properties happened to be — a structure Spring Data explicitly declines to
 * guarantee across versions. A Spring Data upgrade that renamed
 * {@code totalPages} or moved the counts under a nested object would have
 * compiled, passed every test, and broken the queue's paging controls in the
 * browser.
 *
 * <p>These tests therefore assert the serialized JSON, not the Java types: the
 * exact key set at every level, and the value of each field for a known page.
 * A test that only checked {@code PagedResponse} was returned would not catch
 * the regression this exists to prevent.
 *
 * <p>The expectations below were captured from the pre-refactor endpoints, so
 * they double as proof that introducing the DTO left the contract untouched.
 */
class PagedResponseShapeTest {

    /** Every key the paged endpoints have ever put at the top level. */
    private static final Set<String> TOP_LEVEL_KEYS = Set.of(
            "content", "number", "size", "totalElements", "totalPages",
            "numberOfElements", "first", "last", "empty", "pageable", "sort");

    private static final Set<String> PAGEABLE_KEYS = Set.of(
            "pageNumber", "pageSize", "offset", "paged", "unpaged", "sort");

    private static final Set<String> SORT_KEYS = Set.of("sorted", "unsorted", "empty");

    private final ObjectMapper mapper = new ObjectMapper();

    /** Page 2 of 4 (2 per page, 7 rows) — neither first nor last, so no flag is trivially right. */
    private static <T> Page<T> secondPageOfSeven(T row, Sort sort) {
        return new PageImpl<>(List.of(row), PageRequest.of(1, 2, sort), 7);
    }

    private static CalloutRequest request() {
        return new CalloutRequest(CalloutOperation.activation, "req-1", "uuid-1",
                "Salesforce", "jane@example.com", null, null, null, null, null, null);
    }

    private static AuditEvent auditEvent() {
        AuditEvent event = new AuditEvent();
        event.setId(1L);
        event.setAction("approved");
        event.setRequestId("req-1");
        event.setTimestamp(new Date(0));
        return event;
    }

    private static MockMvc approvalsMvc(Page<CalloutRequest> page) {
        ApprovalsRepository repository = mock(ApprovalsRepository.class);
        when(repository.findByStateOrderByIdDesc(anyString(), any(Pageable.class))).thenReturn(page);

        ApprovalController controller = new ApprovalController();
        controller.approvalsRepository = repository;
        // The endpoint takes a Pageable, which only resolves with Spring Data's
        // argument resolver — standaloneSetup does not register it.
        return MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    private static MockMvc auditMvc(Page<AuditEvent> page) {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        when(repository.findAllByOrderByIdDesc(any(Pageable.class))).thenReturn(page);

        AuditController controller = new AuditController();
        controller.auditEventRepository = repository;
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static Set<String> keysOf(JsonNode node) {
        return StreamSupport.stream(((Iterable<String>) node::fieldNames).spliterator(), false)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void approvalQueuePageHasExactlyTheDocumentedKeys() throws Exception {
        String json = approvalsMvc(secondPageOfSeven(request(), Sort.by(Sort.Direction.DESC, "id")))
                .perform(get("/api/approvals/requests?state=pending&page=1&size=2&sort=id,desc"))
                .andReturn().getResponse().getContentAsString();

        JsonNode root = mapper.readTree(json);
        assertThat(keysOf(root)).isEqualTo(new TreeSet<>(TOP_LEVEL_KEYS));
        assertThat(keysOf(root.get("pageable"))).isEqualTo(new TreeSet<>(PAGEABLE_KEYS));
        assertThat(keysOf(root.get("pageable").get("sort"))).isEqualTo(new TreeSet<>(SORT_KEYS));
        assertThat(keysOf(root.get("sort"))).isEqualTo(new TreeSet<>(SORT_KEYS));
    }

    @Test
    void approvalQueuePageReportsTheRightValues() throws Exception {
        approvalsMvc(secondPageOfSeven(request(), Sort.by(Sort.Direction.DESC, "id")))
                .perform(get("/api/approvals/requests?state=pending&page=1&size=2&sort=id,desc"))
                .andExpect(jsonPath("$.content", org.hamcrest.Matchers.hasSize(1)))
                // The rows themselves are unwrapped entities, exactly as before.
                .andExpect(jsonPath("$.content[0].requestId").value("req-1"))
                .andExpect(jsonPath("$.content[0].userId").value("jane@example.com"))
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(7))
                .andExpect(jsonPath("$.totalPages").value(4))
                .andExpect(jsonPath("$.numberOfElements").value(1))
                .andExpect(jsonPath("$.first").value(false))
                .andExpect(jsonPath("$.last").value(false))
                .andExpect(jsonPath("$.empty").value(false))
                .andExpect(jsonPath("$.pageable.pageNumber").value(1))
                .andExpect(jsonPath("$.pageable.pageSize").value(2))
                .andExpect(jsonPath("$.pageable.offset").value(2))
                .andExpect(jsonPath("$.pageable.paged").value(true))
                .andExpect(jsonPath("$.pageable.unpaged").value(false))
                .andExpect(jsonPath("$.sort.sorted").value(true))
                .andExpect(jsonPath("$.sort.unsorted").value(false))
                .andExpect(jsonPath("$.sort.empty").value(false));
    }

    @Test
    void auditPageHasExactlyTheDocumentedKeys() throws Exception {
        String json = auditMvc(secondPageOfSeven(auditEvent(), Sort.unsorted()))
                .perform(get("/api/audit?page=1&size=2"))
                .andReturn().getResponse().getContentAsString();

        JsonNode root = mapper.readTree(json);
        assertThat(keysOf(root)).isEqualTo(new TreeSet<>(TOP_LEVEL_KEYS));
        assertThat(keysOf(root.get("pageable"))).isEqualTo(new TreeSet<>(PAGEABLE_KEYS));
        assertThat(keysOf(root.get("sort"))).isEqualTo(new TreeSet<>(SORT_KEYS));
    }

    @Test
    void auditPageReportsTheRightValues() throws Exception {
        auditMvc(secondPageOfSeven(auditEvent(), Sort.unsorted()))
                .perform(get("/api/audit?page=1&size=2"))
                .andExpect(jsonPath("$.content", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.content[0].action").value("approved"))
                .andExpect(jsonPath("$.content[0].requestId").value("req-1"))
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(7))
                .andExpect(jsonPath("$.totalPages").value(4))
                .andExpect(jsonPath("$.numberOfElements").value(1))
                .andExpect(jsonPath("$.first").value(false))
                .andExpect(jsonPath("$.last").value(false))
                .andExpect(jsonPath("$.empty").value(false))
                // An unsorted page still carries the sort block, with its flags flipped.
                .andExpect(jsonPath("$.sort.sorted").value(false))
                .andExpect(jsonPath("$.sort.unsorted").value(true))
                .andExpect(jsonPath("$.sort.empty").value(true));
    }

    /** The first page of an empty table — where the boolean flags all invert. */
    @Test
    void emptyResultStillCarriesTheWholeShape() throws Exception {
        String json = auditMvc(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0))
                .perform(get("/api/audit"))
                .andExpect(jsonPath("$.content", org.hamcrest.Matchers.hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.empty").value(true))
                .andExpect(jsonPath("$.pageable.offset").value(0))
                .andReturn().getResponse().getContentAsString();

        assertThat(keysOf(mapper.readTree(json))).isEqualTo(new TreeSet<>(TOP_LEVEL_KEYS));
    }

    /**
     * The guard that keeps the DTO from being bypassed. Returning a {@code Page}
     * (or {@code Slice}) from a handler puts the API back on Spring Data's
     * unguaranteed structure, and no shape test above would notice a NEW
     * endpoint doing it.
     */
    @Test
    void noHandlerReturnsASpringDataPage() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<Class<?>> controllers = scanner.findCandidateComponents("com.omnissa.access.approval").stream()
                .map(BeanDefinition::getBeanClassName)
                .map(PagedResponseShapeTest::loadClass)
                .toList();
        // A scan that finds nothing would pass silently and guard nothing.
        assertThat(controllers).as("classpath scan found no @RestController").isNotEmpty();

        List<String> offenders = controllers.stream()
                .flatMap(type -> java.util.Arrays.stream(type.getDeclaredMethods())
                        .filter(m -> exposesPage(m.getGenericReturnType()))
                        .map(m -> type.getSimpleName() + "." + m.getName()))
                .sorted()
                .toList();

        assertThat(offenders)
                .as("handlers must return PagedResponse, not Spring Data's Page/Slice")
                .isEmpty();
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True if {@code Page}/{@code Slice} appears anywhere in the type, including inside {@code ResponseEntity<…>}. */
    private static boolean exposesPage(Type type) {
        if (type instanceof Class<?> raw) {
            return Slice.class.isAssignableFrom(raw);
        }
        if (type instanceof ParameterizedType parameterized) {
            if (exposesPage(parameterized.getRawType())) {
                return true;
            }
            return java.util.Arrays.stream(parameterized.getActualTypeArguments())
                    .anyMatch(PagedResponseShapeTest::exposesPage);
        }
        if (type instanceof WildcardType wildcard) {
            return java.util.Arrays.stream(wildcard.getUpperBounds())
                    .anyMatch(PagedResponseShapeTest::exposesPage);
        }
        return false;
    }
}
