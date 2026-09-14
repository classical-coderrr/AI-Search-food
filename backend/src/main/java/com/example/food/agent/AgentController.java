package com.example.food.agent;

import com.example.food.agent.dto.AgentChatRequest;
import com.example.food.agent.dto.AgentConversationHistoryResponse;
import com.example.food.agent.dto.AgentRunStatusResponse;
import com.example.food.common.ApiResponse;
import com.example.food.security.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping(value = "/chat/stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = {
            MediaType.TEXT_EVENT_STREAM_VALUE,
            MediaType.APPLICATION_JSON_VALUE
    })
    public SseEmitter stream(
            @Valid @RequestBody AgentChatRequest request,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return agentService.stream(request, principal);
    }

    @PostMapping(value = "/chat/stream", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = {
            MediaType.TEXT_EVENT_STREAM_VALUE,
            MediaType.APPLICATION_JSON_VALUE
    })
    public SseEmitter streamWithImage(
            @Valid @RequestPart("request") AgentChatRequest request,
            @RequestPart("image") MultipartFile image,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return agentService.stream(request, principal, image);
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<AgentRunStatusResponse> runStatus(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String runId
    ) {
        return ApiResponse.ok(agentService.runStatus(principal.id(), runId));
    }

    @GetMapping("/conversations/latest")
    public ApiResponse<AgentConversationHistoryResponse> latestConversation(
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ApiResponse.ok(agentService.latestConversationHistory(principal.id()));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ApiResponse<AgentConversationHistoryResponse> conversationMessages(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long conversationId
    ) {
        return ApiResponse.ok(agentService.conversationHistoryDetails(principal.id(), conversationId));
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ApiResponse<Void> deleteConversation(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long conversationId
    ) {
        agentService.deleteConversation(principal.id(), conversationId);
        return ApiResponse.ok(null);
    }
}
