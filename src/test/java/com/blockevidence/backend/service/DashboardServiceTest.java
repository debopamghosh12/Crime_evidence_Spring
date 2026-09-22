package com.blockevidence.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import com.blockevidence.backend.dto.DashboardResponse;
import com.blockevidence.backend.sync.EvidenceActivityRepository;
import com.blockevidence.backend.sync.EvidenceProjectionRepository;
import org.junit.jupiter.api.Test;

/**
 * L1 (a real gap, not padding): H2 was verified live only, which exercises exactly ONE of the raw JDBC row
 * shapes {@code DashboardService.toLocalDate} defends against - whichever type the real driver happens to
 * return for a {@code date_trunc} aggregate. The other two branches (a plain {@link Instant}, and the
 * string-parse fallback) were never exercised by anything. A mocked repository lets all three be checked
 * directly, cheaply, without a database.
 */
class DashboardServiceTest {

    final Clock clock = Clock.fixed(Instant.parse("2026-03-15T00:00:00Z"), ZoneOffset.UTC);
    final EvidenceProjectionRepository projections = mock(EvidenceProjectionRepository.class);
    final EvidenceActivityRepository activities = mock(EvidenceActivityRepository.class);
    final DashboardService service = new DashboardService(projections, activities, clock);

    void stubEmptyCounts() {
        when(projections.countByStatus()).thenReturn(List.of());
        when(projections.countByType()).thenReturn(List.of());
        when(projections.countByCaseTop20()).thenReturn(List.of());
    }

    @Test
    void totalEvidenceIsTheSumOfTheStatusCounts() {
        when(projections.countByStatus()).thenReturn(List.of(
                new Object[] { "COLLECTED", 91L }, new Object[] { "DISPOSED", 9L }));
        when(projections.countByType()).thenReturn(List.of());
        when(projections.countByCaseTop20()).thenReturn(List.of());
        when(activities.countByDaySince(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        DashboardResponse dashboard = service.dashboard();

        assertThat(dashboard.totalEvidence()).isEqualTo(100);
        assertThat(dashboard.byStatus()).containsEntry("COLLECTED", 91L).containsEntry("DISPOSED", 9L);
    }

    @Test
    void anEmptyProjectionTableProducesZerosNotAnException() {
        stubEmptyCounts();
        when(activities.countByDaySince(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        DashboardResponse dashboard = service.dashboard();

        assertThat(dashboard.totalEvidence()).isZero();
        assertThat(dashboard.byStatus()).isEmpty();
        assertThat(dashboard.activityByDay()).isEmpty();
    }

    @Test
    void aSqlTimestampDayRowIsConvertedToTheCorrectUtcLocalDate() {
        stubEmptyCounts();
        Timestamp ts = Timestamp.from(Instant.parse("2026-03-10T23:30:00Z"));
        when(activities.countByDaySince(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.<Object[]>of(new Object[] { ts, 5L }));

        DashboardResponse dashboard = service.dashboard();

        assertThat(dashboard.activityByDay()).hasSize(1);
        assertThat(dashboard.activityByDay().get(0).date()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(dashboard.activityByDay().get(0).count()).isEqualTo(5L);
    }

    @Test
    void aPlainInstantDayRowIsConvertedTheSameWay() {
        stubEmptyCounts();
        Instant instant = Instant.parse("2026-03-11T05:00:00Z");
        when(activities.countByDaySince(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.<Object[]>of(new Object[] { instant, 3L }));

        DashboardResponse dashboard = service.dashboard();

        assertThat(dashboard.activityByDay().get(0).date()).isEqualTo(LocalDate.of(2026, 3, 11));
    }

    @Test
    void anythingElseFallsBackToParsingTheFirstTenCharactersAsAnIsoDate() {
        stubEmptyCounts();
        // Some drivers hand back a plain java.sql.Date, whose toString() is already "yyyy-MM-dd" - the
        // fallback branch must handle that (and anything else stringifying to an ISO date prefix) too.
        when(activities.countByDaySince(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.<Object[]>of(new Object[] { java.sql.Date.valueOf("2026-03-12"), 7L }));

        DashboardResponse dashboard = service.dashboard();

        assertThat(dashboard.activityByDay().get(0).date()).isEqualTo(LocalDate.of(2026, 3, 12));
    }

    @Test
    void activitySinceWindowIsExactlyThirtyDaysBeforeNow() {
        stubEmptyCounts();
        org.mockito.ArgumentCaptor<Instant> captor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        when(activities.countByDaySince(captor.capture())).thenReturn(List.of());

        service.dashboard();

        assertThat(captor.getValue()).isEqualTo(clock.instant().minus(30, java.time.temporal.ChronoUnit.DAYS));
    }
}
