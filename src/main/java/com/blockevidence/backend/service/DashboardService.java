package com.blockevidence.backend.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.blockevidence.backend.dto.DashboardResponse;
import com.blockevidence.backend.sync.EvidenceActivityRepository;
import com.blockevidence.backend.sync.EvidenceProjectionRepository;
import org.springframework.stereotype.Service;

/** H2 (design docs/G3_SYNC_DESIGN.md section 11): aggregate queries over the G3 read model. */
@Service
public class DashboardService {

    private static final int ACTIVITY_DAYS = 30;

    private final EvidenceProjectionRepository projections;
    private final EvidenceActivityRepository activities;
    private final Clock clock;

    public DashboardService(EvidenceProjectionRepository projections, EvidenceActivityRepository activities, Clock clock) {
        this.projections = projections;
        this.activities = activities;
        this.clock = clock;
    }

    public DashboardResponse dashboard() {
        Map<String, Long> byStatus = asMap(projections.countByStatus());
        Map<String, Long> byType = asMap(projections.countByType());
        Map<String, Long> byCase = asMap(projections.countByCaseTop20());
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();

        Instant since = clock.instant().minus(ACTIVITY_DAYS, ChronoUnit.DAYS);
        List<DashboardResponse.DailyCount> activityByDay = activities.countByDaySince(since).stream()
                .map(row -> new DashboardResponse.DailyCount(toLocalDate(row[0]), ((Number) row[1]).longValue()))
                .toList();

        return new DashboardResponse(total, byStatus, byType, byCase, activityByDay);
    }

    private static Map<String, Long> asMap(List<Object[]> rows) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (Object[] row : rows) {
            map.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return map;
    }

    private static LocalDate toLocalDate(Object dbValue) {
        if (dbValue instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (dbValue instanceof Instant instant) {
            return instant.atZone(ZoneOffset.UTC).toLocalDate();
        }
        return LocalDate.parse(String.valueOf(dbValue).substring(0, 10));
    }
}
