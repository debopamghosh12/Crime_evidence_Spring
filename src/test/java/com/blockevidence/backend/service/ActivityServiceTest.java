package com.blockevidence.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.blockevidence.backend.sync.EvidenceActivityRepository;

/** L1 (a real gap, not padding): H3's caseId-present-vs-absent branch and the page-size cap were verified live
 *  only; a mocked repository checks both deterministically, including the "blank string counts as absent"
 *  edge case a curl script would not naturally think to send. */
class ActivityServiceTest {

    final EvidenceActivityRepository activities = mock(EvidenceActivityRepository.class);
    final ActivityService service = new ActivityService(activities);

    @SuppressWarnings("unchecked")
    Page<com.blockevidence.backend.sync.EvidenceActivity> emptyPage() {
        return new PageImpl<>(java.util.List.of());
    }

    @Test
    void noCaseIdQueriesTheUnfilteredFeed() {
        when(activities.findAllByOrderByLedgerAtDesc(any(Pageable.class))).thenReturn(emptyPage());

        service.feed(null, 0, 20);

        verify(activities).findAllByOrderByLedgerAtDesc(any(Pageable.class));
        verify(activities, never()).findByCaseIdOrderByLedgerAtDesc(any(), any());
    }

    @Test
    void aBlankCaseIdIsTreatedTheSameAsNoCaseId() {
        when(activities.findAllByOrderByLedgerAtDesc(any(Pageable.class))).thenReturn(emptyPage());

        service.feed("   ", 0, 20);

        verify(activities).findAllByOrderByLedgerAtDesc(any(Pageable.class));
        verify(activities, never()).findByCaseIdOrderByLedgerAtDesc(any(), any());
    }

    @Test
    void aRealCaseIdNarrowsToThatCaseOnly() {
        when(activities.findByCaseIdOrderByLedgerAtDesc(eq("CASE-1"), any(Pageable.class))).thenReturn(emptyPage());

        service.feed("CASE-1", 0, 20);

        verify(activities).findByCaseIdOrderByLedgerAtDesc(eq("CASE-1"), any(Pageable.class));
        verify(activities, never()).findAllByOrderByLedgerAtDesc(any(Pageable.class));
    }

    @Test
    void pageSizeIsCappedAtTwoHundred() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        when(activities.findAllByOrderByLedgerAtDesc(captor.capture())).thenReturn(emptyPage());

        service.feed(null, 0, 5000);

        assertThat(captor.getValue().getPageSize()).isEqualTo(200);
    }

    @Test
    void pageSizeBelowOneIsRaisedToOne() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        when(activities.findAllByOrderByLedgerAtDesc(captor.capture())).thenReturn(emptyPage());

        service.feed(null, 0, -1);

        assertThat(captor.getValue().getPageSize()).isEqualTo(1);
    }
}
