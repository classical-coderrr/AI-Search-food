package com.example.food.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryManagementServiceTest {

    @Mock private MemoryItemMapper itemMapper;
    @Mock private MemoryItemCandidateMapper itemCandidateMapper;
    @Mock private MemoryCandidateMapper candidateMapper;
    @Mock private MemoryEvidenceMapper evidenceMapper;
    @Mock private MemoryEpisodeMapper episodeMapper;
    @Mock private MemoryProfileMapper profileMapper;
    @Mock private MemorySessionMapper sessionMapper;
    @Mock private MemoryRetrievalTraceMapper retrievalTraceMapper;
    @Mock private MemoryConsolidationService consolidationService;
    @Mock private MemoryPersonalizationService personalizationService;

    @Test
    void listsOnlyUserScopedActiveMemoriesAndCurrentSettings() {
        MemoryItem item = item(13L, 7L, "INGREDIENT_PREFERENCE", "香菜", "LIKE", 2);
        when(itemMapper.listActive(7L, 500)).thenReturn(List.of(item));
        when(itemMapper.countActiveOwned(7L)).thenReturn(1L);
        when(personalizationService.getState(7L)).thenReturn(new MemoryPersonalizationState(false, 4));

        MemoryManagementResponse response = service().getOverview(7L);

        assertThat(response.total()).isEqualTo(1);
        assertThat(response.personalization().enabled()).isFalse();
        assertThat(response.memories()).singleElement().satisfies(memory -> {
            assertThat(memory.id()).isEqualTo(13L);
            assertThat(memory.entity()).isEqualTo("香菜");
            assertThat(memory.editable()).isTrue();
        });
        verify(itemMapper).listActive(7L, 500);
    }

    @Test
    void userEditChangesPreferenceAndPreservesTheOverrideDuringFutureMerges() {
        MemoryItem before = item(13L, 7L, "INGREDIENT_PREFERENCE", "香菜", "LIKE", 2);
        MemoryItem after = item(13L, 7L, "INGREDIENT_PREFERENCE", "香菜", "DISLIKE", 3);
        when(itemMapper.findActiveOwned(7L, 13L)).thenReturn(before, after);
        when(itemMapper.updateUserManaged(7L, 13L, 2, "DISLIKE", new BigDecimal("0.9000")))
                .thenReturn(1);

        MemoryManagementItemResponse response = service().updateItem(7L, 13L,
                new MemoryItemUpdateRequest("dislike", new BigDecimal("0.9"), 2));

        assertThat(response.preference()).isEqualTo("DISLIKE");
        assertThat(response.version()).isEqualTo(3);
        verify(candidateMapper).rejectCandidatesForMemory(7L, 13L);
        verify(consolidationService).refreshProfileForManagement(7L);
    }

    @Test
    void rejectsUnsupportedPreferenceAndStaleEditsWithoutWriting() {
        MemoryItem current = item(13L, 7L, "RECIPE_BEHAVIOR", "菜谱", "COOKED", 2);
        when(itemMapper.findActiveOwned(7L, 13L)).thenReturn(current);

        assertThatThrownBy(() -> service().updateItem(7L, 13L,
                new MemoryItemUpdateRequest("LIKE", new BigDecimal("0.8"), 2)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不支持");
        verify(itemMapper, never()).updateUserManaged(any(), any(), any(), any(), any());
    }

    @Test
    void deletesSingleMemoryOptimisticallyAndRefreshesProfile() {
        MemoryItem current = item(13L, 7L, "INGREDIENT_PREFERENCE", "香菜", "LIKE", 2);
        when(itemMapper.findActiveOwned(7L, 13L)).thenReturn(current);
        when(itemMapper.softDeleteOwned(7L, 13L, 2)).thenReturn(1);

        MemoryItemDeleteResult result = service().deleteItem(7L, 13L, 2);

        assertThat(result.deleted()).isTrue();
        verify(candidateMapper).rejectCandidatesForMemory(7L, 13L);
        verify(consolidationService).refreshProfileForManagement(7L);
    }

    @Test
    void clearAllDeletesOnlyTheUsersMemoryGraphAndLeavesSettingsIntact() {
        when(itemCandidateMapper.deleteAllOwned(7L)).thenReturn(3);
        when(evidenceMapper.deleteAllOwned(7L)).thenReturn(3);
        when(candidateMapper.deleteAllOwned(7L)).thenReturn(2);
        when(episodeMapper.deleteAllOwned(7L)).thenReturn(4);
        when(itemMapper.deleteAllOwned(7L)).thenReturn(2);
        when(sessionMapper.deleteAllOwned(7L)).thenReturn(1);
        when(retrievalTraceMapper.deleteAllOwned(7L)).thenReturn(6);

        MemoryClearResult result = service().clearAll(7L);

        assertThat(result.episodesDeleted()).isEqualTo(4);
        assertThat(result.candidatesDeleted()).isEqualTo(2);
        assertThat(result.memoriesDeleted()).isEqualTo(2);
        assertThat(result.sessionsDeleted()).isEqualTo(1);
        assertThat(result.retrievalTracesDeleted()).isEqualTo(6);
        verify(retrievalTraceMapper).deleteAllOwned(7L);
        verify(profileMapper).deleteOwned(7L);
    }

    private MemoryManagementService service() {
        return new MemoryManagementService(itemMapper, itemCandidateMapper, candidateMapper,
                evidenceMapper, episodeMapper, profileMapper, sessionMapper, retrievalTraceMapper, consolidationService,
                personalizationService);
    }

    private MemoryItem item(Long id, Long userId, String type, String entity, String preference, int version) {
        MemoryItem item = new MemoryItem();
        item.setId(id);
        item.setUserId(userId);
        item.setMemoryType(type);
        item.setCanonicalEntity(entity);
        item.setPreference(preference);
        item.setStrength(new BigDecimal("0.8000"));
        item.setConfidence(new BigDecimal("0.7000"));
        item.setEvidenceCount(2);
        item.setOccurrenceCount(2);
        item.setSourceCount(2);
        item.setScope("USER");
        item.setTemporalType("LONG_TERM");
        item.setFirstSeenAt(LocalDateTime.now().minusDays(2));
        item.setLastSeenAt(LocalDateTime.now());
        item.setVersion(version);
        return item;
    }

}
