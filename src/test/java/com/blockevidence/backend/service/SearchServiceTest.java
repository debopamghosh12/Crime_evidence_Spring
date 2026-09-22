package com.blockevidence.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.blockevidence.backend.sync.EvidenceProjectionRepository;

/**
 * L1 (fill a real gap, not padding): H1's own docs note the query-filter combinations were verified live, not
 * in a unit test - this file targets the PURE logic {@code SearchService} itself owns before ever reaching a
 * database: the sort allow-list (an untrusted client string must never become a raw property lookup) and the
 * page-size cap. Both are cheap and deterministic to check with a mocked repository and an
 * {@link ArgumentCaptor} on the {@link Pageable} it actually builds - no database needed for this part.
 */
class SearchServiceTest {

    final EvidenceProjectionRepository projections = mock(EvidenceProjectionRepository.class);
    final SearchService service = new SearchService(projections);

    Pageable capturedPageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(projections).findAll(any(org.springframework.data.jpa.domain.Specification.class), captor.capture());
        return captor.getValue();
    }

    void search(int page, int size, String sort) {
        when(projections.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(java.util.List.of()));
        service.search(null, null, null, null, null, null, null, page, size, sort);
    }

    @Test
    void aNullSortFallsBackToUpdatedAtAscending() {
        search(0, 20, null);

        Sort sort = capturedPageable().getSort();
        assertThat(sort.getOrderFor("updatedAt")).isNotNull();
        assertThat(sort.getOrderFor("updatedAt").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void anUnknownSortKeyFallsBackToUpdatedAtRatherThanErroringOrPassingThrough() {
        search(0, 20, "'; DROP TABLE evidence_projection; --");

        Sort sort = capturedPageable().getSort();
        assertThat(sort.getOrderFor("updatedAt")).as("an unrecognised key is not a valid JPA property, so it "
                + "must never reach the query builder unmapped - the allow-list falls back instead").isNotNull();
    }

    @Test
    void eachAllowListedSortKeyIsHonoured() {
        for (String key : new String[] { "createdAt", "status", "caseId" }) {
            reset();
            search(0, 20, key);
            assertThat(capturedPageable().getSort().getOrderFor(key)).as("sort=%s", key).isNotNull();
        }
    }

    private void reset() {
        org.mockito.Mockito.reset(projections);
    }

    @Test
    void aLeadingHyphenMeansDescendingOnTheSameAllowListedKey() {
        search(0, 20, "-createdAt");

        Sort.Order order = capturedPageable().getSort().getOrderFor("createdAt");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void pageSizeIsCappedAtTwoHundredEvenIfTheCallerAsksForMore() {
        search(0, 100_000, null);

        assertThat(capturedPageable().getPageSize()).isEqualTo(200);
    }

    @Test
    void pageSizeBelowOneIsRaisedToOne() {
        search(0, 0, null);

        assertThat(capturedPageable().getPageSize()).isEqualTo(1);

        reset();
        search(0, -5, null);
        assertThat(capturedPageable().getPageSize()).isEqualTo(1);
    }

    @Test
    void aNormalPageSizeIsUnchanged() {
        search(2, 50, null);

        Pageable pageable = capturedPageable();
        assertThat(pageable.getPageSize()).isEqualTo(50);
        assertThat(pageable.getPageNumber()).isEqualTo(2);
    }
}
