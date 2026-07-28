package com.omnissa.access.approval.dto;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

/**
 * Wire model for paged endpoints (#64).
 *
 * <p>Returning Spring Data's {@link Page} straight out of a
 * {@code @RestController} leaves the JSON to be whatever {@code PageImpl}'s
 * bean properties happen to be. Spring Data does not treat that structure as
 * API — it logs a warning saying there is "no guarantee about the stability of
 * the resulting JSON structure" — so a Spring Data upgrade could rename or drop
 * a field the SPA reads and nothing would fail until it reached a browser.
 * Declaring the shape here means a change to it is a change to this file.
 *
 * <p>The field set is exactly what {@code PageImpl} was already emitting, down
 * to {@code pageable} and {@code sort}. Those two are Spring Data's internals
 * and nothing in the UI reads them, but they are already on the wire: removing
 * them would break any external caller for no functional gain. Declaring them
 * freezes them where they are, and a future major release can drop them as a
 * deliberate, announced change rather than a silent one.
 *
 * <p>Deliberately not Spring Data's own {@code PagedModel} (which is what
 * {@code spring.data.web.pageable.serialization-mode=VIA_DTO} would switch to):
 * that nests the counts under a {@code page} object, moving {@code totalPages}
 * and {@code totalElements} and breaking the SPA's paging controls.
 */
public record PagedResponse<T>(
        List<T> content,
        int number,
        int size,
        long totalElements,
        int totalPages,
        int numberOfElements,
        boolean first,
        boolean last,
        boolean empty,
        PageableInfo pageable,
        SortInfo sort) {

    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.getNumberOfElements(),
                page.isFirst(),
                page.isLast(),
                page.isEmpty(),
                PageableInfo.from(page),
                SortInfo.from(page.getSort()));
    }

    /** The request that produced the page — {@code PageImpl}'s {@code pageable} block. */
    public record PageableInfo(
            int pageNumber,
            int pageSize,
            long offset,
            boolean paged,
            boolean unpaged,
            SortInfo sort) {

        static PageableInfo from(Page<?> page) {
            Pageable pageable = page.getPageable();
            boolean paged = pageable.isPaged();
            // Pageable.unpaged() throws rather than returning defaults from
            // getPageNumber()/getPageSize()/getOffset(), so take the numbers off
            // the Page, which resolves them for both cases.
            return new PageableInfo(
                    page.getNumber(),
                    page.getSize(),
                    paged ? pageable.getOffset() : 0L,
                    paged,
                    !paged,
                    SortInfo.from(pageable.getSort()));
        }
    }

    /** Sort state — {@code PageImpl}'s {@code sort} block, in both places it appears. */
    public record SortInfo(boolean sorted, boolean unsorted, boolean empty) {

        static SortInfo from(Sort sort) {
            return new SortInfo(sort.isSorted(), sort.isUnsorted(), sort.isEmpty());
        }
    }
}
