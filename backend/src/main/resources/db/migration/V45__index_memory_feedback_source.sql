CREATE INDEX idx_memory_episodes_feedback_source
    ON memory_episodes(user_id, episode_type, source_id, id);
