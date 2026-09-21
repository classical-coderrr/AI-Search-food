package com.example.food.admin.dashboard;

import com.example.food.admin.dashboard.dto.AdminAgentEvaluationResponse;
import com.example.food.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/dashboard")
public class AdminAgentEvaluationController {

    private final AgentEvaluationService service;

    public AdminAgentEvaluationController(AgentEvaluationService service) {
        this.service = service;
    }

    @GetMapping("/agent-evaluation")
    public ApiResponse<AdminAgentEvaluationResponse> latest() {
        return ApiResponse.ok(service.latest());
    }

    @PostMapping("/agent-evaluation/run")
    public ApiResponse<AdminAgentEvaluationResponse> run() {
        return ApiResponse.ok(service.run());
    }
}
