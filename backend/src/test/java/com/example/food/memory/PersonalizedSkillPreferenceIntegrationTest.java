package com.example.food.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PersonalizedSkillPreferenceIntegrationTest {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MemoryEpisodeService episodeService;
    @Autowired private MemoryCandidateService candidateService;
    @Autowired private MemoryConsolidationService consolidationService;
    @Autowired private PersonalizedSkillProjectionService skillProjectionService;
    @Autowired private PersonalizedSkillMapper skillMapper;

    @Test
    void explicitExecutionPreferenceFlowsThroughEvidenceProfileAndPersistedSkill() {
        Long userId = insertUser("13900000991");
        MemoryEpisode earlier = saveCountPreference(userId, "count-three", "3", "以后每次给我三个选项");
        MemoryEpisode latest = saveCountPreference(userId, "count-two", "2", "以后每次给我两个选项");

        candidateService.extractAndPersist(userId, earlier.getId());
        candidateService.extractAndPersist(userId, latest.getId());
        consolidationService.consolidate(userId);

        MemoryProfile profile = consolidationService.getOwnedProfile(userId);
        assertThat(profile.getProfileJson())
                .contains("skillPreferences", "CANDIDATE_COUNT", "\"preference\":\"2\"")
                .doesNotContain("以后每次给我两个选项");
        String[] evidence = jdbcTemplate.queryForObject(
                "SELECT evidence_text FROM memory_evidence WHERE user_id = ? AND episode_id = ?",
                (rs, rowNum) -> new String[]{rs.getString(1)}, userId, latest.getId());
        assertThat(evidence).containsExactly("以后每次给我两个选项");

        PersonalizedSkillService.SkillContext skill = skillProjectionService.resolve(
                userId, "推荐一道晚餐", profile);
        assertThat(skill.strategy()).containsEntry("candidateCount", List.of("2"));
        assertThat(skill.promptContext()).contains("不超过 2 个候选");

        PersonalizedSkill persisted = skillMapper.findActiveOwned(userId, "RECOMMEND_RECIPE");
        assertThat(persisted).isNotNull();
        assertThat(persisted.getStrategyJson()).contains("candidateCount", "2");
        assertThat(persisted.getSourceMemoryIdsJson()).isNotEqualTo("[]");
        assertThat(persisted.getEvidenceCount()).isEqualTo(1);
    }

    private MemoryEpisode saveCountPreference(Long userId, String key, String count, String quote) {
        String payload = "{\"candidateType\":\"SKILL_PREFERENCE\",\"entity\":\"CANDIDATE_COUNT\","
                + "\"preference\":\"" + count + "\",\"evidence\":\"" + quote + "\"}";
        return episodeService.record(userId, new MemoryEpisodeCommand(null, null,
                "USER_PREFERENCE_DECLARED", "AGENT_DECLARATION", key, key, key,
                "用户明确表达执行习惯", payload, null, null)).episode();
    }

    private Long insertUser(String phone) {
        jdbcTemplate.update("INSERT INTO users (phone, nickname) VALUES (?, ?)", phone, "执行习惯测试用户");
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
    }
}
