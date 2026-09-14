package com.example.food.agent.state;

public enum AgentNode {
    CONVERSATION,
    MODEL_DECISION,
    TOOL_EXECUTE,
    OBSERVE,
    WAITING_CONFIRMATION,
    FINALIZE
}
