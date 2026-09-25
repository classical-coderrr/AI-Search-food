package com.example.food.admin.dashboard;

import com.example.food.admin.dashboard.dto.AdminMemoryEvaluationResponse;
import com.example.food.admin.dashboard.dto.AdminMemoryObservabilityResponse;
import com.example.food.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/dashboard")
public class AdminMemoryEvaluationController {
    private final MemoryEvaluationService evaluationService;
    private final AdminMemoryObservabilityService observabilityService;

    public AdminMemoryEvaluationController(MemoryEvaluationService evaluationService,
                                           AdminMemoryObservabilityService observabilityService) {
        this.evaluationService = evaluationService;
        this.observabilityService = observabilityService;
    }

    @GetMapping("/memory-evaluation")
    public ApiResponse<AdminMemoryEvaluationResponse> latestEvaluation() {
        return ApiResponse.ok(evaluationService.latest());
    }

    @PostMapping("/memory-evaluation/run")
    public ApiResponse<AdminMemoryEvaluationResponse> runEvaluation() {
        return ApiResponse.ok(evaluationService.run());
    }

    @GetMapping("/memory-observability")
    public ApiResponse<AdminMemoryObservabilityResponse> memoryObservability(
            @RequestParam(defaultValue = "24h") String range
    ) {
        return ApiResponse.ok(observabilityService.snapshot(range));
    }
}
