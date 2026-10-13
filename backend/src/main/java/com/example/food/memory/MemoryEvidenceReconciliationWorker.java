package com.example.food.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class MemoryEvidenceReconciliationWorker {
    private static final Logger log = LoggerFactory.getLogger(MemoryEvidenceReconciliationWorker.class);
    private final JdbcTemplate jdbcTemplate;
    private final MemoryConsolidationService consolidation;
    private final MemoryCandidateService candidates;
    private final MemoryEpisodeService episodes;

    public MemoryEvidenceReconciliationWorker(JdbcTemplate jdbcTemplate, MemoryConsolidationService consolidation,
                                               MemoryCandidateService candidates, MemoryEpisodeService episodes) {
        this.jdbcTemplate = jdbcTemplate;
        this.consolidation = consolidation;
        this.candidates = candidates;
        this.episodes = episodes;
    }

    @Scheduled(fixedDelayString = "${app.memory.evidence-rebuild.interval:PT1M}", initialDelayString = "PT30S")
    public void rebuildLegacyEvidence() {
        // Recheck daily even without new activity so inferred preferences lose expired evidence.
        var today = LocalDate.now(ZoneId.of("Asia/Shanghai")).atStartOfDay();
        for (Long userId : jdbcTemplate.queryForList("SELECT p.user_id FROM memory_profiles p"
                + " LEFT JOIN user_memory_settings s ON s.user_id = p.user_id"
                + " WHERE p.deleted_at IS NULL AND (s.personalization_enabled IS NULL OR s.personalization_enabled = TRUE)"
                + " AND (p.profile_json NOT LIKE '%\"evidencePolicyVersion\":1%' OR p.rebuilt_at < ?)"
                + " ORDER BY p.rebuilt_at, p.user_id LIMIT 20", Long.class, today)) {
            try {
                var history = episodes.listOwnedRecipeHistory(userId, 1);
                if (!history.isEmpty()) candidates.extractAndPersist(userId, history.get(0).getId());
                consolidation.consolidate(userId);
            } catch (RuntimeException failure) {
                log.warn("历史记忆证据稍后重新计算 userId={}", userId);
            }
        }
    }
}
