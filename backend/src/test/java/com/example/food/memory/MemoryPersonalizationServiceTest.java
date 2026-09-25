package com.example.food.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryPersonalizationServiceTest {

    @Mock
    private UserMemorySettingsMapper settingsMapper;

    @Test
    void defaultsExistingUsersToEnabledUntilTheyChooseOtherwise() {
        when(settingsMapper.findOwned(7L)).thenReturn(null);

        MemoryPersonalizationState state = new MemoryPersonalizationService(settingsMapper).getState(7L);

        assertThat(state.enabled()).isTrue();
        assertThat(state.version()).isZero();
    }

    @Test
    void persistsTheFirstExplicitChoiceWithAnOptimisticVersion() {
        when(settingsMapper.findOwned(7L)).thenReturn(null);
        when(settingsMapper.insert(any(UserMemorySettings.class))).thenReturn(1);

        MemoryPersonalizationState state = new MemoryPersonalizationService(settingsMapper)
                .update(7L, new MemoryPersonalizationRequest(false, 0));

        assertThat(state.enabled()).isFalse();
        assertThat(state.version()).isEqualTo(1);
        verify(settingsMapper).insert(any(UserMemorySettings.class));
    }

    @Test
    void reportsStaleSettingWritesAsConflict() {
        UserMemorySettings current = new UserMemorySettings();
        current.setUserId(7L);
        current.setPersonalizationEnabled(true);
        current.setVersion(3);
        when(settingsMapper.findOwned(7L)).thenReturn(current);

        assertThatThrownBy(() -> new MemoryPersonalizationService(settingsMapper)
                .update(7L, new MemoryPersonalizationRequest(false, 2)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("已更新");
        verify(settingsMapper, never()).updateOwned(anyLong(), anyInt(), anyBoolean());
    }

    @Test
    void reportsConcurrentFirstWritesAsConflict() {
        when(settingsMapper.findOwned(7L)).thenReturn(null);
        when(settingsMapper.insert(any(UserMemorySettings.class)))
                .thenThrow(new DuplicateKeyException("concurrent insert"));

        assertThatThrownBy(() -> new MemoryPersonalizationService(settingsMapper)
                .update(7L, new MemoryPersonalizationRequest(false, 0)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("已更新");
    }
}
