package com.blockevidence.backend.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** H2. Counts only - no personal data beyond opaque user ids already exposed elsewhere in the API. */
public record DashboardResponse(long totalEvidence, Map<String, Long> byStatus, Map<String, Long> byType,
        Map<String, Long> byCase, List<DailyCount> activityByDay) {

    public record DailyCount(LocalDate date, long count) {
    }
}
