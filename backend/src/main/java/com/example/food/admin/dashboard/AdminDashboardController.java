package com.example.food.admin.dashboard;

import com.example.food.admin.dashboard.dto.AdminAgentObservabilityResponse;
import com.example.food.admin.dashboard.dto.AdminDashboardOverviewResponse;
import com.example.food.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/dashboard")
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;
    private final AdminAgentObservabilityService agentObservabilityService;

    public AdminDashboardController(
            AdminDashboardService dashboardService,
            AdminAgentObservabilityService agentObservabilityService
    ) {
        this.dashboardService = dashboardService;
        this.agentObservabilityService = agentObservabilityService;
    }

    @GetMapping("/overview")
    public ApiResponse<AdminDashboardOverviewResponse> overview(
            @RequestParam(defaultValue = "7d") String period
    ) {
        return ApiResponse.ok(dashboardService.overview(period));
    }

    @GetMapping("/agent-observability")
    public ApiResponse<AdminAgentObservabilityResponse> agentObservability() {
        return ApiResponse.ok(agentObservabilityService.snapshot());
    }
}
