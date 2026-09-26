package com.example.food.memory;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class MemoryEpisodeService {

    private static final BigDecimal DEFAULT_IMPORTANCE = new BigDecimal("0.5000");
    private static final int MAX_SUMMARY_LENGTH = 512;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_BATCH_SIZE = 500;
    private final MemoryEpisodeMapper mapper;
    private final Clock clock;

    @Autowired
    public MemoryEpisodeService(MemoryEpisodeMapper mapper) {
        this(mapper, Clock.systemDefaultZone());
    }

    MemoryEpisodeService(MemoryEpisodeMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public RecordResult record(Long userId, MemoryEpisodeCommand command) {
        requireUser(userId);
        validate(command);
        String idempotencyKey = normalizeIdempotencyKey(command.idempotencyKey());

        MemoryEpisode existing = mapper.findOwnedByIdempotencyKey(userId, idempotencyKey);
        if (existing != null) {
            return new RecordResult(existing, true);
        }

        MemoryEpisode episode = new MemoryEpisode();
        episode.setUserId(userId);
        episode.setSessionId(command.sessionId());
        episode.setConversationId(command.conversationId());
        episode.setEpisodeType(command.episodeType().trim());
        episode.setSourceType(command.sourceType().trim());
        episode.setSourceId(normalize(command.sourceId()));
        episode.setEventId(normalize(command.eventId()));
        episode.setIdempotencyKey(idempotencyKey);
        episode.setSummary(normalize(command.summary()));
        episode.setPayloadJson(command.payloadJson());
        episode.setOccurredAt(command.occurredAt() == null ? LocalDateTime.now(clock) : command.occurredAt());
        episode.setStatus(MemoryEpisodeStatus.RAW.name());
        episode.setImportance(normalizeImportance(command.importance()));
        episode.setVersion(0);
        try {
            mapper.insert(episode);
            return new RecordResult(findOwned(userId, episode.getId()), false);
        } catch (DuplicateKeyException duplicate) {
            MemoryEpisode raced = mapper.findOwnedByIdempotencyKey(userId, idempotencyKey);
            if (raced == null) {
                throw duplicate;
            }
            return new RecordResult(raced, true);
        }
    }

    public MemoryEpisode findOwned(Long userId, Long episodeId) {
        requireUser(userId);
        MemoryEpisode episode = episodeId == null ? null : mapper.findOwned(userId, episodeId);
        if (episode == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记忆事件不存在");
        }
        return episode;
    }

    public List<MemoryEpisode> listOwned(Long userId, Long sessionId, String episodeType, int limit) {
        requireUser(userId);
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 20 : limit, MAX_LIMIT));
        return mapper.listOwned(userId, sessionId, normalize(episodeType), safeLimit);
    }

    public List<MemoryEpisode> findOwnedByIds(Long userId, List<Long> episodeIds) {
        requireUser(userId);
        if (episodeIds == null || episodeIds.isEmpty()) return List.of();
        List<Long> safeIds = episodeIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .limit(MAX_BATCH_SIZE)
                .toList();
        return safeIds.isEmpty() ? List.of() : mapper.findOwnedByIds(userId, safeIds);
    }

    @Transactional
    public void delete(Long userId, Long episodeId) {
        requireUser(userId);
        MemoryEpisode episode = findOwned(userId, episodeId);
        if (mapper.softDeleteOwned(userId, episodeId, episode.getVersion()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "记忆事件已被其他请求更新，请重试");
        }
    }

    private void validate(MemoryEpisodeCommand command) {
        Objects.requireNonNull(command, "command");
        if (!StringUtils.hasText(command.episodeType()) || !StringUtils.hasText(command.sourceType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆事件类型和来源不能为空");
        }
        if (!StringUtils.hasText(command.idempotencyKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆事件必须提供幂等键");
        }
        if (!StringUtils.hasText(command.payloadJson())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆事件必须保留原始载荷");
        }
        if (command.summary() != null && command.summary().length() > MAX_SUMMARY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆事件摘要超出长度限制");
        }
    }

    private String normalizeIdempotencyKey(String value) {
        String normalized = value == null ? null : value.trim();
        if (!StringUtils.hasText(normalized) || normalized.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆事件幂等键无效");
        }
        return normalized;
    }

    private BigDecimal normalizeImportance(BigDecimal value) {
        BigDecimal normalized = value == null ? DEFAULT_IMPORTANCE : value;
        if (normalized.compareTo(BigDecimal.ZERO) < 0 || normalized.compareTo(BigDecimal.ONE) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆事件重要性必须在 0 到 1 之间");
        }
        return normalized;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户身份无效");
        }
    }

    public record RecordResult(MemoryEpisode episode, boolean duplicate) {
    }
}
