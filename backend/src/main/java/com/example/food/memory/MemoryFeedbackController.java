package com.example.food.memory;

import com.example.food.common.ApiResponse;
import com.example.food.security.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/memory/feedback")
public class MemoryFeedbackController {
    private final MemoryFeedbackService feedbackService;

    public MemoryFeedbackController(MemoryFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @GetMapping("/{traceId}")
    public ApiResponse<MemoryFeedbackStatusResponse> status(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String traceId
    ) {
        return ApiResponse.ok(feedbackService.status(principal.id(), traceId));
    }

    @PostMapping
    public ApiResponse<MemoryFeedbackResponse> submit(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody MemoryFeedbackRequest request
    ) {
        return ApiResponse.ok(feedbackService.submit(principal.id(), request));
    }
}
