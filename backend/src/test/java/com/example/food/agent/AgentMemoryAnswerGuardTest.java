package com.example.food.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMemoryAnswerGuardTest {

    private static final List<String> SECTIONS = List.of("PERSONAL_MEMORY", "STRUCTURED_PROFILE");
    private static final List<String> POSITIVE_GINGER_MEMORY = List.of("近期行为推断：喜欢生姜");

    @Test
    void softensClaimsAboutTheEntireHistoryWhenOnlyCurrentMemoryWasChecked() {
        String answer = "明确表达：没有。你从未直接说过喜欢或不喜欢生姜。近期行为显示可能喜欢生姜。";

        String guarded = AgentMemoryAnswerGuard.guard(answer, SECTIONS, POSITIVE_GINGER_MEMORY);

        assertThat(guarded)
                .contains("本轮检索到的记忆不足以核实完整历史")
                .doesNotContain("你从未直接说过")
                .contains("近期行为显示可能喜欢生姜");
    }

    @Test
    void correctsAttributedNegativeClaimsThatContradictPositiveMemory() {
        String answer = "我根据你近期反馈，完全省略生姜。";

        String guarded = AgentMemoryAnswerGuard.guard(answer, SECTIONS, POSITIVE_GINGER_MEMORY);

        assertThat(guarded)
                .contains("本轮记忆记录为“近期行为推断：喜欢生姜”")
                .contains("不能据此声称你反馈要求避开该食材")
                .doesNotContain("完全省略生姜");
    }

    @Test
    void recognizesPositivePreferenceSummariesFromStructuredProfile() {
        String answer = "我根据你近期反馈，完全省略生姜。";

        String guarded = AgentMemoryAnswerGuard.guard(answer, SECTIONS,
                List.of("食材偏好（近期行为推断）：喜欢生姜"));

        assertThat(guarded)
                .contains("食材偏好（近期行为推断）：喜欢生姜")
                .contains("不能据此声称你反馈要求避开该食材");
    }

    @Test
    void leavesMixedPolarityClaimsForConflictAwareReasoning() {
        String answer = "我根据你近期反馈，完全省略生姜。";

        assertThat(AgentMemoryAnswerGuard.guard(answer, SECTIONS,
                List.of("近期行为推断：喜欢生姜", "长期偏好：不喜欢生姜")))
                .isEqualTo(answer);
    }

    @Test
    void preservesAnExplicitlyNegatedExplanation() {
        String answer = "并非因为某道菜没放生姜，就认为你忌口。";

        assertThat(AgentMemoryAnswerGuard.guard(answer, SECTIONS, POSITIVE_GINGER_MEMORY))
                .isEqualTo(answer);
    }

    @Test
    void doesNothingWhenNoPersonalMemoryWasInjected() {
        String answer = "你从未说过要放生姜。";

        assertThat(AgentMemoryAnswerGuard.guard(answer, List.of("PERSONALIZED_SKILL"),
                POSITIVE_GINGER_MEMORY)).isEqualTo(answer);
    }
}
