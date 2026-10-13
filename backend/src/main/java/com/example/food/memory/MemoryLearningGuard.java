package com.example.food.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Serializes memory writes and clearing without holding a lock during model calls. */
@Component
public class MemoryLearningGuard {
    private final JdbcTemplate jdbcTemplate;

    public MemoryLearningGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lock(Long userId) {
        jdbcTemplate.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
    }
}
