package com.blockevidence.backend.controller;

import com.blockevidence.backend.dto.DashboardResponse;
import com.blockevidence.backend.security.Permissions;
import com.blockevidence.backend.service.DashboardService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** H2. Aggregate counts only, same read policy as evidence itself (any authenticated role). */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    @PreAuthorize(Permissions.READ_EVIDENCE)
    public DashboardResponse dashboard() {
        return dashboardService.dashboard();
    }
}
