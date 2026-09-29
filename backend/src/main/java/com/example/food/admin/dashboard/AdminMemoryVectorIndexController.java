package com.example.food.admin.dashboard;

import com.example.food.admin.dashboard.dto.AdminMemoryVectorIndexRebuildResponse;
import com.example.food.admin.dashboard.dto.AdminMemoryVectorIndexStatusResponse;
import com.example.food.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/dashboard/memory-vector-index")
public class AdminMemoryVectorIndexController {
    private final AdminMemoryVectorIndexService service;

    public AdminMemoryVectorIndexController(AdminMemoryVectorIndexService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AdminMemoryVectorIndexStatusResponse> status() {
        return ApiResponse.ok(service.status());
    }

    @PostMapping("/rebuild")
    public ApiResponse<AdminMemoryVectorIndexRebuildResponse> requestRebuild() {
        return ApiResponse.ok(service.requestRebuild());
    }
}
