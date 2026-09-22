package com.blockevidence.backend.controller;

import com.blockevidence.backend.dto.ActivityEntryResponse;
import com.blockevidence.backend.dto.PageResponse;
import com.blockevidence.backend.security.Permissions;
import com.blockevidence.backend.service.ActivityService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** H3. */
@RestController
@RequestMapping("/api/activity")
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping
    @PreAuthorize(Permissions.READ_EVIDENCE)
    public PageResponse<ActivityEntryResponse> feed(@RequestParam(required = false) String caseId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return activityService.feed(caseId, page, size);
    }
}
