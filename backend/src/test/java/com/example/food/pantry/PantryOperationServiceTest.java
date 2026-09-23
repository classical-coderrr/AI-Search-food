package com.example.food.pantry;

import com.example.food.memory.MemoryBehaviorEpisodeEvent;
import com.example.food.memory.MemoryBehaviorEpisodeRecorder;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.food.recipe.RecipeIngredient;
import com.example.food.recipe.RecipeIngredientMapper;
import com.example.food.recipe.RecipeRecord;
import com.example.food.recipe.RecipeRecordMapper;
import com.example.food.shopping.ShoppingItemCheckMapper;
import com.example.food.stats.IngredientNormalizer;
import com.example.food.weekly.WeeklyMenuItemMapper;
import com.example.food.weekly.WeeklyMenuPlanMapper;
import com.example.food.weekly.WeeklyMenuShoppingCheckMapper;
import com.example.food.pantry.dto.CookingPreviewResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PantryOperationServiceTest {

    @Mock private PantryOperationMapper operationMapper;
    @Mock private PantryOperationItemMapper operationItemMapper;
    @Mock private UserPantryItemMapper pantryMapper;
    @Mock private RecipeRecordMapper recipeMapper;
    @Mock private RecipeIngredientMapper ingredientMapper;
    @Mock private WeeklyMenuPlanMapper weeklyPlanMapper;
    @Mock private WeeklyMenuItemMapper weeklyItemMapper;
    @Mock private WeeklyMenuShoppingCheckMapper weeklyCheckMapper;
    @Mock private ShoppingItemCheckMapper shoppingCheckMapper;
    @Mock private MemoryBehaviorEpisodeRecorder memoryEpisodeRecorder;

    private PantryOperationService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(LocalDate.of(2026, 9, 1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(), ZoneId.of("Asia/Shanghai"));
        service = new PantryOperationService(operationMapper, operationItemMapper, pantryMapper, recipeMapper,
                ingredientMapper, weeklyPlanMapper, weeklyItemMapper, weeklyCheckMapper, shoppingCheckMapper,
                new IngredientNormalizer(), memoryEpisodeRecorder, new IngredientAmountParser(), clock);
    }

    @Test
    void previewScalesRecipeAndUsesCompatibleFefoBatches() {
        RecipeRecord recipe = new RecipeRecord();
        recipe.setId(9L);
        recipe.setUserId(7L);
        recipe.setTitle("土豆炖肉");
        recipe.setServings(2);
        RecipeIngredient ingredient = new RecipeIngredient();
        ingredient.setIngredientName("土豆");
        ingredient.setAmount("100克");
        UserPantryItem soon = pantry(1L, "土豆", "60", "g", LocalDate.of(2026, 9, 2));
        UserPantryItem later = pantry(2L, "土豆", "100", "g", LocalDate.of(2026, 9, 5));

        when(recipeMapper.selectById(9L)).thenReturn(recipe);
        when(ingredientMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(ingredient));
        when(pantryMapper.findByUserId(7L)).thenReturn(List.of(later, soon));

        CookingPreviewResponse response = service.cookingPreview(7L, 9L, 3);

        assertThat(response.actualServings()).isEqualTo(3);
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo("ENOUGH");
            assertThat(item.expectedQuantity()).isEqualByComparingTo("150");
            assertThat(item.availableQuantity()).isEqualByComparingTo("160");
            assertThat(item.batches()).extracting(CookingPreviewResponse.Batch::pantryItemId).containsExactly(1L, 2L);
        });
    }

    @Test
    void previewNeverTreatsUnquantifiedAmountAsZero() {
        RecipeRecord recipe = new RecipeRecord();
        recipe.setId(10L);
        recipe.setUserId(7L);
        recipe.setServings(2);
        RecipeIngredient ingredient = new RecipeIngredient();
        ingredient.setIngredientName("盐");
        ingredient.setAmount("适量");

        when(recipeMapper.selectById(10L)).thenReturn(recipe);
        when(ingredientMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(ingredient));
        when(pantryMapper.findByUserId(7L)).thenReturn(List.of());

        CookingPreviewResponse.Item item = service.cookingPreview(7L, 10L, null).items().get(0);

        assertThat(item.status()).isEqualTo("UNQUANTIFIED");
        assertThat(item.expectedQuantity()).isNull();
    }

    @Test
    void consumesLegacyAliasBatchAfterPreviewNormalization() {
        RecipeRecord recipe = new RecipeRecord();
        recipe.setId(11L);
        recipe.setUserId(7L);
        recipe.setServings(1);
        RecipeIngredient ingredient = new RecipeIngredient();
        ingredient.setIngredientName("西红柿");
        ingredient.setAmount("1个");
        UserPantryItem legacyBatch = pantry(3L, "西红柿", "1", "个", null);
        PantryOperation savedOperation = new PantryOperation();

        when(recipeMapper.selectById(11L)).thenReturn(recipe);
        when(ingredientMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(ingredient));
        when(pantryMapper.findByUserId(7L)).thenReturn(List.of(legacyBatch));
        when(operationMapper.findByIdempotency(7L, PantryOperationType.COOKING_CONSUME.name(), "alias-key")).thenReturn(null);
        when(pantryMapper.findAvailableForUpdate(7L, "番茄")).thenReturn(List.of(legacyBatch));
        doAnswer(invocation -> {
            PantryOperation operation = invocation.getArgument(0);
            operation.setId(100L);
            savedOperation.setId(100L);
            savedOperation.setUserId(7L);
            savedOperation.setOperationType(PantryOperationType.COOKING_CONSUME.name());
            savedOperation.setStatus(PantryOperationStatus.COMPLETED.name());
            savedOperation.setCreatedAt(operation.getCreatedAt());
            return 1;
        }).when(operationMapper).insert(any(PantryOperation.class));
        when(operationMapper.selectById(100L)).thenReturn(savedOperation);
        when(operationItemMapper.findByOperationId(100L)).thenReturn(List.of());

        service.consume(7L, new com.example.food.pantry.dto.CookingConsumptionRequest(11L, 1, " alias-key ", null));

        assertThat(legacyBatch.getQuantity()).isEqualByComparingTo("0.00");
        verify(pantryMapper).updateById(legacyBatch);
        verify(memoryEpisodeRecorder).record(any(MemoryBehaviorEpisodeEvent.class));
    }

    private UserPantryItem pantry(Long id, String name, String quantity, String unit, LocalDate expireDate) {
        UserPantryItem item = new UserPantryItem();
        item.setId(id);
        item.setUserId(7L);
        item.setIngredientName(name);
        item.setQuantity(new BigDecimal(quantity));
        item.setUnit(unit);
        item.setExpireDate(expireDate);
        return item;
    }
}
