package com.example.food.agent.state;

import java.util.List;

public interface AgentEventStore {

    AgentEvent append(String runId, String event, Object data);

    List<AgentEvent> findAfter(String runId, long afterSequence, int limit);

    long latestSequence(String runId);
}
