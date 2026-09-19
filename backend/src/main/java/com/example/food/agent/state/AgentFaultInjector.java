package com.example.food.agent.state;

import org.springframework.stereotype.Component;

/**
 * Test seam for simulating a process crash at a durable Agent boundary.
 *
 * <p>The production implementation is intentionally a no-op. Tests can
 * replace it with a mock and throw {@link AgentCrashException} from any
 * point, leaving the latest persisted checkpoint intact.</p>
 */
@Component
public class AgentFaultInjector {

    public enum Point {
        MODEL_BEFORE,
        MODEL_AFTER,
        TOOL_BEFORE,
        TOOL_AFTER,
        WAITING_CONFIRMATION,
        FINALIZE_BEFORE,
        FINALIZE_AFTER
    }

    public void hit(String runId, Point point) {
        // No-op in normal application runs. The seam is activated by tests.
    }

    public static final class AgentCrashException extends RuntimeException {

        private final String runId;
        private final Point point;

        public AgentCrashException(String runId, Point point) {
            super("模拟 Agent 进程崩溃: " + point);
            this.runId = runId;
            this.point = point;
        }

        public String runId() {
            return runId;
        }

        public Point point() {
            return point;
        }
    }
}
