package com.example.food.pantry;

import com.example.food.memory.MemoryBehaviorEpisodeEvent;
import com.example.food.memory.MemoryBehaviorEpisodeRecorder;
import com.example.food.pantry.dto.PantryItemRequest;
import com.example.food.pantry.dto.PantryItemResponse;
import com.example.food.pantry.dto.PantryReadinessRequest;
import com.example.food.pantry.dto.PantryReadinessResponse;
import com.example.food.stats.IngredientNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserPantryServiceTest {

    @Mock
    private UserPantryItemMapper mapper;

    @Mock
    private MemoryBehaviorEpisodeRecorder memoryEpisodeRecorder;

    private UserPantryService service;

    @BeforeEach
    void setUp() {
        service = new UserPantryService(
                mapper,
                new IngredientNormalizer(),
                memoryEpisodeRecorder,
                new IngredientAmountParser(),
                Clock.systemDefaultZone()
        );
    }

    @Test
    void listsUserInventoryAndIngredientNames() {
        when(mapper.findByUserId(7L)).thenReturn(List.of(
                item(1L, 7L, "番茄"),
                item(2L, 7L, "鸡蛋"),
                item(3L, 7L, "番茄")
        ));

        List<PantryItemResponse> items = service.list(7L);

        assertThat(items).extracting(PantryItemResponse::ingredientName)
                .containsExactly("番茄", "鸡蛋", "番茄");
        assertThat(service.listIngredientNames(7L)).containsExactly("番茄", "鸡蛋");
    }

    @Test
    void summarizesActiveExpiredAndSoonToExpireItems() {
        Clock clock = Clock.fixed(
                LocalDate.of(2026, 8, 31).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),
                ZoneId.of("Asia/Shanghai")
        );
        UserPantryService fixedService = new UserPantryService(mapper, new IngredientNormalizer(), clock);

        UserPantryItem expired = item(1L, 7L, "西兰花");
        expired.setQuantity(new BigDecimal("1"));
        expired.setExpireDate(LocalDate.of(2026, 8, 30));
        UserPantryItem soon = item(2L, 7L, "牛奶");
        soon.setQuantity(new BigDecimal("2"));
        soon.setExpireDate(LocalDate.of(2026, 9, 2));
        when(mapper.findExpiryAlertsByUserId(7L, LocalDate.of(2026, 9, 7)))
                .thenReturn(List.of(expired, soon));

        var summary = fixedService.expirySummary(7L);

        assertThat(summary.asOf()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(summary.warningDays()).isEqualTo(7);
        assertThat(summary.expiredItems()).extracting(PantryItemResponse::ingredientName)
                .containsExactly("西兰花");
        assertThat(summary.expiringSoonItems()).extracting(PantryItemResponse::ingredientName)
                .containsExactly("牛奶");
    }

    @Test
    void createsNormalizedPantryItem() {
        PantryItemRequest request = new PantryItemRequest(
                " 西红柿 ",
                "蔬菜",
                new BigDecimal("2.50"),
                "个",
                LocalDate.of(2026, 8, 31)
        );

        PantryItemResponse response = service.create(7L, request);

        ArgumentCaptor<UserPantryItem> captor = ArgumentCaptor.forClass(UserPantryItem.class);
        verify(mapper).insert(captor.capture());
        UserPantryItem saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getIngredientName()).isEqualTo("番茄");
        assertThat(saved.getCategory()).isEqualTo("蔬菜");
        assertThat(saved.getQuantity()).isEqualByComparingTo("2.50");
        assertThat(saved.getUnit()).isEqualTo("个");
        assertThat(saved.getExpireDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(response.ingredientName()).isEqualTo("番茄");
    }

    @Test
    void recordsEpisodeWhenDirectPantryItemIsCreated() {
        PantryItemRequest request = new PantryItemRequest(
                "tomato", "vegetable", new BigDecimal("2"), "piece", null
        );

        service.create(7L, request);

        ArgumentCaptor<MemoryBehaviorEpisodeEvent> captor =
                ArgumentCaptor.forClass(MemoryBehaviorEpisodeEvent.class);
        verify(memoryEpisodeRecorder).record(captor.capture());
        MemoryBehaviorEpisodeEvent event = captor.getValue();
        assertThat(event.userId()).isEqualTo(7L);
        assertThat(event.episodeType()).isEqualTo("PANTRY_STOCK_IN");
        assertThat(event.sourceType()).isEqualTo("PANTRY_DIRECT");
        assertThat(event.payload()).containsEntry("quantity", new BigDecimal("2"));
        assertThat(event.payload()).containsEntry("ingredientName", "tomato");
    }

    @Test
    void mergesPantryItemsWithSameNameCategoryAndExpiryDate() {
        LocalDate expireDate = LocalDate.of(2026, 8, 31);
        UserPantryItem existing = item(5L, 7L, "番茄");
        existing.setCategory("蔬菜");
        existing.setQuantity(new BigDecimal("2.50"));
        existing.setUnit("个");
        existing.setExpireDate(expireDate);
        when(mapper.findByIdentityForUpdate(7L, "番茄", "蔬菜", expireDate)).thenReturn(existing);

        PantryItemResponse response = service.create(7L, new PantryItemRequest(
                "西红柿", "蔬菜", new BigDecimal("1.25"), "个", expireDate
        ));

        verify(mapper).updateById(existing);
        verify(mapper, org.mockito.Mockito.never()).insert(org.mockito.ArgumentMatchers.any(UserPantryItem.class));
        assertThat(existing.getQuantity()).isEqualByComparingTo("3.75");
        assertThat(response.id()).isEqualTo(5L);
    }

    @Test
    void updatesOnlyOwnedPantryItem() {
        UserPantryItem existing = item(5L, 7L, "鸡蛋");
        when(mapper.selectById(5L)).thenReturn(existing);

        PantryItemResponse response = service.update(7L, 5L, new PantryItemRequest(
                "土豆",
                "根茎",
                new BigDecimal("500"),
                "克",
                null
        ));

        verify(mapper).updateById(existing);
        assertThat(existing.getIngredientName()).isEqualTo("土豆");
        assertThat(existing.getCategory()).isEqualTo("根茎");
        assertThat(response.id()).isEqualTo(5L);
    }

    @Test
    void doesNotUpdateAnotherUsersPantryItem() {
        when(mapper.selectById(5L)).thenReturn(item(5L, 8L, "鸡蛋"));

        assertThatThrownBy(() -> service.update(7L, 5L, new PantryItemRequest(
                "鸡蛋", null, null, null, null
        )))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("食材不存在");
    }

    @Test
    void consumesOwnedPantryItemAtomically() {
        UserPantryItem existing = item(5L, 7L, "鸡蛋");
        existing.setQuantity(new BigDecimal("2.50"));
        UserPantryItem updated = item(5L, 7L, "鸡蛋");
        updated.setQuantity(new BigDecimal("1.25"));
        when(mapper.selectById(5L)).thenReturn(existing, updated);
        when(mapper.consumeByUserIdAndId(7L, 5L, new BigDecimal("1.25"))).thenReturn(1);

        PantryItemResponse response = service.consume(7L, 5L, new BigDecimal("1.25"));

        verify(mapper).consumeByUserIdAndId(7L, 5L, new BigDecimal("1.25"));
        assertThat(response.quantity()).isEqualByComparingTo("1.25");
    }

    @Test
    void recordsEpisodeWhenDirectPantryItemIsConsumed() {
        UserPantryItem existing = item(5L, 7L, "楦¤泲");
        existing.setQuantity(new BigDecimal("2.50"));
        existing.setUnit("piece");
        UserPantryItem updated = item(5L, 7L, "楦¤泲");
        updated.setQuantity(new BigDecimal("1.25"));
        updated.setUnit("piece");
        when(mapper.selectById(5L)).thenReturn(existing, updated);
        when(mapper.consumeByUserIdAndId(7L, 5L, new BigDecimal("1.25"))).thenReturn(1);

        service.consume(7L, 5L, new BigDecimal("1.25"));

        ArgumentCaptor<MemoryBehaviorEpisodeEvent> captor =
                ArgumentCaptor.forClass(MemoryBehaviorEpisodeEvent.class);
        verify(memoryEpisodeRecorder).record(captor.capture());
        MemoryBehaviorEpisodeEvent event = captor.getValue();
        assertThat(event.episodeType()).isEqualTo("PANTRY_CONSUMED");
        assertThat(event.sourceType()).isEqualTo("PANTRY_DIRECT");
        assertThat(event.payload()).containsEntry("beforeQuantity", new BigDecimal("2.50"));
        assertThat(event.payload()).containsEntry("afterQuantity", new BigDecimal("1.25"));
    }

    @Test
    void rejectsConsumptionWhenInventoryIsInsufficient() {
        UserPantryItem existing = item(5L, 7L, "鸡蛋");
        existing.setQuantity(new BigDecimal("1.00"));
        when(mapper.selectById(5L)).thenReturn(existing);
        when(mapper.consumeByUserIdAndId(7L, 5L, new BigDecimal("1.01"))).thenReturn(0);

        assertThatThrownBy(() -> service.consume(7L, 5L, new BigDecimal("1.01")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("库存数量不足");
    }

    @Test
    void rejectsConsumptionWhenInventoryQuantityIsNull() {
        UserPantryItem existing = item(5L, 7L, "鸡蛋");
        when(mapper.selectById(5L)).thenReturn(existing);

        assertThatThrownBy(() -> service.consume(7L, 5L, new BigDecimal("1")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("当前库存数量为空");
    }

    @Test
    void rejectsConsumptionForMissingOrAnotherUsersItem() {
        when(mapper.selectById(5L)).thenReturn(null);
        assertThatThrownBy(() -> service.consume(7L, 5L, new BigDecimal("1")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("食材不存在");

        when(mapper.selectById(6L)).thenReturn(item(6L, 8L, "鸡蛋"));
        assertThatThrownBy(() -> service.consume(7L, 6L, new BigDecimal("1")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("食材不存在");
    }

    @Test
    void rejectsInvalidConsumptionQuantity() {
        assertThatThrownBy(() -> service.consume(7L, 5L, BigDecimal.ZERO))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("必须大于 0");
        assertThatThrownBy(() -> service.consume(7L, 5L, new BigDecimal("1.001")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("最多支持");
    }

    @Test
    void calculatesReadinessWithShortageUnitMismatchAndExpiringItems() {
        Clock clock = Clock.fixed(
                LocalDate.of(2026, 8, 31).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),
                ZoneId.of("Asia/Shanghai")
        );
        UserPantryService fixedService = new UserPantryService(mapper, new IngredientNormalizer(), new IngredientAmountParser(), clock);

        UserPantryItem enough = item(1L, 7L, "tomato");
        enough.setQuantity(new BigDecimal("600"));
        enough.setUnit("g");
        enough.setExpireDate(LocalDate.of(2026, 9, 2));
        UserPantryItem partial = item(2L, 7L, "beef");
        partial.setQuantity(new BigDecimal("100"));
        partial.setUnit("g");
        UserPantryItem wrongUnit = item(3L, 7L, "onion");
        wrongUnit.setQuantity(new BigDecimal("2"));
        wrongUnit.setUnit("piece");
        when(mapper.findByUserId(7L)).thenReturn(List.of(enough, partial, wrongUnit));

        var response = fixedService.readiness(7L, new PantryReadinessRequest(List.of(
                new PantryReadinessRequest.Ingredient("tomato", "500g"),
                new PantryReadinessRequest.Ingredient("beef", "300g"),
                new PantryReadinessRequest.Ingredient("onion", "100g"),
                new PantryReadinessRequest.Ingredient("potato", "200g")
        )));

        assertThat(response.itemCount()).isEqualTo(4);
        assertThat(response.readyCount()).isEqualTo(1);
        assertThat(response.shortageCount()).isEqualTo(2);
        assertThat(response.expiringSoonCount()).isEqualTo(1);
        assertThat(response.readinessPercent()).isEqualTo(25);
        assertThat(response.items()).extracting(PantryReadinessResponse.Item::status)
                .containsExactly("ENOUGH", "PARTIAL", "UNIT_MISMATCH", "MISSING");
        assertThat(response.items().get(0).expiringSoon()).isTrue();
        assertThat(response.items().get(1).shortage()).isTrue();
        assertThat(response.items().get(2).shortage()).isFalse();
    }

    @Test
    void doesNotTreatUnknownAmountAsMissingOrReady() {
        when(mapper.findByUserId(7L)).thenReturn(List.of());

        var response = service.readiness(7L, new PantryReadinessRequest(List.of(
                new PantryReadinessRequest.Ingredient("salt", "适量")
        )));

        assertThat(response.readyCount()).isZero();
        assertThat(response.shortageCount()).isZero();
        assertThat(response.items().get(0).status()).isEqualTo("UNQUANTIFIED");
    }

    private UserPantryItem item(Long id, Long userId, String ingredientName) {
        UserPantryItem item = new UserPantryItem();
        item.setId(id);
        item.setUserId(userId);
        item.setIngredientName(ingredientName);
        return item;
    }
}
