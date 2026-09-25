package com.example.food.memory;

import com.example.food.common.ApiResponse;
import com.example.food.security.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/memory/confirmations")
public class MemoryConfirmationController {

    private final MemoryConfirmationService service;

    public MemoryConfirmationController(MemoryConfirmationService service) {
        this.service = service;
    }

    @GetMapping("/pending")
    public ApiResponse<List<MemoryConfirmationResponse>> listPending(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ApiResponse.ok(service.listPending(principal.id(), limit));
    }

    @PostMapping("/{candidateId}/decision")
    public ApiResponse<MemoryConfirmationDecisionResult> decide(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long candidateId,
            @Valid @RequestBody MemoryConfirmationDecisionRequest request
    ) {
        return ApiResponse.ok(service.decide(principal.id(), candidateId, request));
    }
}
