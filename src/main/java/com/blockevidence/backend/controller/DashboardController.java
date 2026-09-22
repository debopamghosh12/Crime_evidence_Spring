package com.blockevidence.backend.controller;

import com.blockevidence.backend.dto.DashboardResponse;
import com.blockevidence.backend.security.Permissions;
import com.blockevidence.backend.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** H2. Aggregate counts only, same read policy as evidence itself (any authenticated role). */
@RestController
@RequestMapping("/api/dashboard")
@Tag(name = "Dashboard", description = "Aggregate counts over the G3 off-chain read model - totals by status/"
        + "type, top 20 cases by evidence count, activity for the last 30 days by day.")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    @PreAuthorize(Permissions.READ_EVIDENCE)
    @Operation(summary = "Dashboard summary", description = "Any authenticated role. No personal data beyond "
            + "the opaque user ids already exposed elsewhere in the API.")
    public DashboardResponse dashboard() {
        return dashboardService.dashboard();
    }
}
