package com.example.food.memory;

import com.example.food.common.ApiResponse;
import com.example.food.security.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/memory/management")
public class MemoryManagementController {

    private final MemoryManagementService managementService;
    private final MemoryPersonalizationService personalizationService;

    public MemoryManagementController(
            MemoryManagementService managementService,
            MemoryPersonalizationService personalizationService
    ) {
        this.managementService = managementService;
        this.personalizationService = personalizationService;
    }

    @GetMapping
    public ApiResponse<MemoryManagementResponse> getOverview(
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ApiResponse.ok(managementService.getOverview(principal.id()));
    }

    @PutMapping("/personalization")
    public ApiResponse<MemoryPersonalizationState> updatePersonalization(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody MemoryPersonalizationRequest request
    ) {
        return ApiResponse.ok(personalizationService.update(principal.id(), request));
    }

    @PatchMapping("/items/{memoryId}")
    public ApiResponse<MemoryManagementItemResponse> updateItem(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long memoryId,
            @Valid @RequestBody MemoryItemUpdateRequest request
    ) {
        return ApiResponse.ok(managementService.updateItem(principal.id(), memoryId, request));
    }

    @DeleteMapping("/items/{memoryId}")
    public ApiResponse<MemoryItemDeleteResult> deleteItem(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long memoryId,
            @RequestParam Integer version
    ) {
        return ApiResponse.ok(managementService.deleteItem(principal.id(), memoryId, version));
    }

    @DeleteMapping("/all")
    public ApiResponse<MemoryClearResult> clearAll(
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ApiResponse.ok(managementService.clearAll(principal.id()));
    }
}
