package com.example.food.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemorySessionServiceTest {

    @Mock
    private MemorySessionMapper mapper;

    @Test
    void opensSessionWithUserScopedIdempotentKey() {
        MemorySession persisted = session(12L, 7L, 0);
        when(mapper.findOwnedByKey(7L, "session-1")).thenReturn(null);
        when(mapper.findOwned(7L, 12L)).thenReturn(persisted);
        when(mapper.insert(any(MemorySession.class))).thenAnswer(invocation -> {
            MemorySession inserted = invocation.getArgument(0);
            inserted.setId(12L);
            return 1;
        });

        MemorySessionService service = new MemorySessionService(mapper);

        MemorySession result = service.open(7L, new MemorySessionOpenCommand(
                null,
                "session-1",
                "RECOMMEND_RECIPE",
                "训练后晚餐",
                "{\"mealType\":\"dinner\"}",
                null,
                null,
                null,
                null
        ));

        assertThat(result.getId()).isEqualTo(12L);
        assertThat(result.getUserId()).isEqualTo(7L);
        verify(mapper).insert(any(MemorySession.class));
    }

    @Test
    void rejectsStaleSessionVersionInsteadOfOverwritingNewState() {
        MemorySession current = session(12L, 7L, 3);
        when(mapper.findOwned(7L, 12L)).thenReturn(current);
        when(mapper.updateOwned(
                eq(7L), eq(12L), eq(3), any(), any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(0);

        MemorySessionService service = new MemorySessionService(mapper);

        assertThatThrownBy(() -> service.touch(
                7L,
                12L,
                new MemorySessionUpdate(null, "新任务", null, null, null, null, null, null)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("已被其他请求更新");
    }

    @Test
    void doesNotAllowAUserToReadAnotherUsersSession() {
        when(mapper.findOwned(8L, 12L)).thenReturn(null);
        MemorySessionService service = new MemorySessionService(mapper);

        assertThatThrownBy(() -> service.findOwned(8L, 12L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("记忆会话不存在");
        verify(mapper, never()).updateOwned(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void expiresOnlyDueSessionsInABoundedBatch() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 29, 12, 0);
        List<Long> dueIds = List.of(12L, 13L);
        when(mapper.findExpiredIds(now, 1000)).thenReturn(dueIds);
        when(mapper.expireDue(dueIds, now)).thenReturn(2);

        MemorySessionService service = new MemorySessionService(mapper);

        assertThat(service.expireDue(now, 5000)).isEqualTo(2);
        verify(mapper).findExpiredIds(now, 1000);
        verify(mapper).expireDue(dueIds, now);
    }

    @Test
    void skipsExpiryWhenThereIsNoValidBatch() {
        MemorySessionService service = new MemorySessionService(mapper);

        assertThat(service.expireDue(null, 10)).isZero();
        assertThat(service.expireDue(LocalDateTime.now(), 0)).isZero();
        verify(mapper, never()).findExpiredIds(any(), anyInt());
        verify(mapper, never()).expireDue(anyList(), any());
    }

    private MemorySession session(Long id, Long userId, int version) {
        MemorySession session = new MemorySession();
        session.setId(id);
        session.setUserId(userId);
        session.setSessionKey("session-1");
        session.setStatus(MemorySessionStatus.ACTIVE.name());
        session.setVersion(version);
        return session;
    }
}
