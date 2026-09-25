package com.example.food.memory;

public record MemoryConfirmationDecisionResult(
        Long candidateId,
        MemoryConfirmationDecision decision,
        String status,
        boolean profileUpdated,
        boolean alreadyProcessed
) {
}
