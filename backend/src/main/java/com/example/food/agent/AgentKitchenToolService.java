package com.example.food.agent;

import com.example.food.agent.AgentToolRegistry.Tool;
import com.example.food.notification.NotificationService;
import com.example.food.pantry.PantryOperationService;
import com.example.food.pantry.UserPantryService;
import com.example.food.pantry.dto.PantryReadinessRequest;
import com.example.food.recipe.RecommendationFeedbackService;
import com.example.food.recipe.SavedRecipeService;
import com.example.food.recipe.SearchHistoryService;
import com.example.food.recipe.collection.SavedRecipeCollectionService;
import com.example.food.recipe.share.RecipeShareService;
import com.example.food.review.FinishedDishReviewService;
import com.example.food.review.dto.FinishedDishReviewRequest;
import com.example.food.review.dto.FinishedDishReviewResponse;
import com.example.food.security.AuthPrincipal;
import com.example.food.shopping.ShoppingItemCheckService;
import com.example.food.stats.HotIngredientStatsService;
import com.example.food.user.character.UserKitchenCharacterNamesService;
import com.example.food.video.VideoSearchService;
import com.example.food.weekly.WeeklyMenuService;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AgentKitchenToolService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> PANTRY_MUTATIONS = Set.of("create", "update", "consume", "delete", "undo", "cooking_consume");
    private static final Set<String> MENU_MUTATIONS = Set.of("generate", "save", "clear", "shopping_update", "recipe_shopping_update");
    private static final Set<String> NOTIFICATION_MUTATIONS = Set.of("read", "read_all", "archive", "update_preferences");
    private static final Set<String> RECIPE_MUTATIONS = Set.of(
            "delete", "collection_create", "collection_rename", "collection_delete", "move", "replace_tags",
            "batch_move", "batch_tags", "batch_delete", "share_create", "share_disable",
            "reaction_set", "reaction_clear", "mark_cooked"
    );
    private static final Set<String> PROFILE_MUTATIONS = Set.of(
            "health_update", "health_delete", "diet_update", "nutrition_update", "nutrition_delete",
            "character_update", "character_reset"
    );

    private final UserPantryService pantryService;
    private final PantryOperationService pantryOperationService;
    private final WeeklyMenuService weeklyMenuService;
    private final ShoppingItemCheckService shoppingItemCheckService;
    private final NotificationService notificationService;
    private final SavedRecipeService savedRecipeService;
    private final SavedRecipeCollectionService collectionService;
    private final RecipeShareService shareService;
    private final RecommendationFeedbackService feedbackService;
    private final SearchHistoryService searchHistoryService;
    private final VideoSearchService videoSearchService;
    private final HotIngredientStatsService hotIngredientStatsService;
    private final UserKitchenCharacterNamesService characterNamesService;
    private final FinishedDishReviewService finishedDishReviewService;
    private final ObjectMapper objectMapper;
    private AgentMemoryToolService memoryToolService;

    public AgentKitchenToolService(
            UserPantryService pantryService,
            PantryOperationService pantryOperationService,
            WeeklyMenuService weeklyMenuService,
            ShoppingItemCheckService shoppingItemCheckService,
            NotificationService notificationService,
            SavedRecipeService savedRecipeService,
            SavedRecipeCollectionService collectionService,
            RecipeShareService shareService,
            RecommendationFeedbackService feedbackService,
            SearchHistoryService searchHistoryService,
            VideoSearchService videoSearchService,
            HotIngredientStatsService hotIngredientStatsService,
            UserKitchenCharacterNamesService characterNamesService,
            FinishedDishReviewService finishedDishReviewService,
            ObjectMapper objectMapper
    ) {
        this.pantryService = pantryService;
        this.pantryOperationService = pantryOperationService;
        this.weeklyMenuService = weeklyMenuService;
        this.shoppingItemCheckService = shoppingItemCheckService;
        this.notificationService = notificationService;
        this.savedRecipeService = savedRecipeService;
        this.collectionService = collectionService;
        this.shareService = shareService;
        this.feedbackService = feedbackService;
        this.searchHistoryService = searchHistoryService;
        this.videoSearchService = videoSearchService;
        this.hotIngredientStatsService = hotIngredientStatsService;
        this.characterNamesService = characterNamesService;
        this.finishedDishReviewService = finishedDishReviewService;
        this.objectMapper = objectMapper;
    }

    @Autowired
    void setMemoryToolService(AgentMemoryToolService memoryToolService) {
        this.memoryToolService = memoryToolService;
    }

    public boolean isMutation(Tool tool, JsonNode arguments) {
        if (memoryToolService != null && memoryToolService.isMutation(tool)) {
            return true;
        }
        return switch (tool) {
            case PANTRY_MANAGE -> PANTRY_MUTATIONS.contains(action(arguments));
            case MEAL_PLAN_MANAGE -> MENU_MUTATIONS.contains(action(arguments));
            case NOTIFICATION_MANAGE -> NOTIFICATION_MUTATIONS.contains(action(arguments));
            case RECIPE_LIBRARY_MANAGE -> RECIPE_MUTATIONS.contains(action(arguments));
            case PROFILE_MANAGE -> PROFILE_MUTATIONS.contains(action(arguments));
            case FINISHED_DISH_MANAGE -> "delete".equals(action(arguments));
            default -> false;
        };
    }

    public String actionType(Tool tool, JsonNode arguments) {
        if (memoryToolService != null && memoryToolService.isMutation(tool)) {
            return memoryToolService.actionType(tool);
        }
        String action = action(arguments);
        return switch (tool) {
            case PANTRY_MANAGE -> switch (action) {
                case "create" -> "PANTRY_CREATE";
                case "update" -> "PANTRY_UPDATE";
                case "consume" -> "PANTRY_CONSUME";
                case "delete" -> "PANTRY_DELETE";
                case "undo" -> "PANTRY_UNDO";
                case "cooking_consume" -> "COOKING_CONSUME";
                default -> unsupportedAction();
            };
            case MEAL_PLAN_MANAGE -> switch (action) {
                case "generate" -> "WEEKLY_MENU_GENERATE";
                case "save" -> "WEEKLY_MENU_SAVE";
                case "clear" -> "WEEKLY_MENU_CLEAR";
                case "shopping_update" -> "WEEKLY_SHOPPING_UPDATE";
                case "recipe_shopping_update" -> "RECIPE_SHOPPING_UPDATE";
                default -> unsupportedAction();
            };
            case NOTIFICATION_MANAGE -> switch (action) {
                case "read" -> "NOTIFICATION_READ";
                case "read_all" -> "NOTIFICATION_READ_ALL";
                case "archive" -> "NOTIFICATION_ARCHIVE";
                case "update_preferences" -> "NOTIFICATION_PREFERENCES_UPDATE";
                default -> unsupportedAction();
            };
            case RECIPE_LIBRARY_MANAGE -> switch (action) {
                case "delete" -> "RECIPE_DELETE";
                case "collection_create" -> "COLLECTION_CREATE";
                case "collection_rename" -> "COLLECTION_RENAME";
                case "collection_delete" -> "COLLECTION_DELETE";
                case "move" -> "RECIPE_MOVE";
                case "replace_tags" -> "RECIPE_TAGS_REPLACE";
                case "batch_move" -> "RECIPE_BATCH_MOVE";
                case "batch_tags" -> "RECIPE_BATCH_TAGS";
                case "batch_delete" -> "RECIPE_BATCH_DELETE";
                case "share_create" -> "RECIPE_SHARE_CREATE";
                case "share_disable" -> "RECIPE_SHARE_DISABLE";
                case "reaction_set" -> "RECOMMENDATION_REACTION_SET";
                case "reaction_clear" -> "RECOMMENDATION_REACTION_CLEAR";
                case "mark_cooked" -> "RECOMMENDATION_MARK_COOKED";
                default -> unsupportedAction();
            };
            case PROFILE_MANAGE -> switch (action) {
                case "health_update" -> "HEALTH_PROFILE_UPDATE";
                case "health_delete" -> "HEALTH_PROFILE_DELETE";
                case "diet_update" -> "DIET_PREFERENCE_UPDATE";
                case "nutrition_update" -> "NUTRITION_TARGET_UPDATE";
                case "nutrition_delete" -> "NUTRITION_TARGET_DELETE";
                case "character_update" -> "CHARACTER_NAMES_UPDATE";
                case "character_reset" -> "CHARACTER_NAMES_RESET";
                default -> unsupportedAction();
            };
            case FINISHED_DISH_MANAGE -> "delete".equals(action) ? "FINISHED_DISH_REVIEW_DELETE" : unsupportedAction();
            default -> unsupportedAction();
        };
    }

    public String impact(Tool tool, JsonNode arguments) {
        return impact(tool, arguments, null);
    }

    public String impact(Tool tool, JsonNode arguments, Long userId) {
        if (memoryToolService != null && memoryToolService.isMutation(tool)) {
            return memoryToolService.impact(tool, arguments, userId);
        }
        String action = action(arguments);
        return switch (tool) {
            case PANTRY_MANAGE -> "将执行库存操作“" + action + "”，库存数量或记录可能发生变化。";
            case MEAL_PLAN_MANAGE -> "将执行菜单操作“" + action + "”，本周菜单或购物状态会发生变化。";
            case NOTIFICATION_MANAGE -> "将执行提醒操作“" + action + "”，通知状态或偏好会发生变化。";
            case RECIPE_LIBRARY_MANAGE -> "将执行菜谱操作“" + action + "”，收藏、标签、分享或反馈数据会发生变化。";
            case PROFILE_MANAGE -> "将执行设置操作“" + action + "”，健康、营养或厨房角色配置会发生变化。";
            case FINISHED_DISH_MANAGE -> "将删除指定成品评价及其图片，此操作完成后无法恢复。";
            default -> "将修改当前账号的厨房数据。";
        };
    }

    public ToolResult execute(
            Tool tool,
            JsonNode arguments,
            AuthPrincipal principal,
            AgentAttachment attachment
    ) {
        return switch (tool) {
            case CURRENT_DATETIME -> currentDateTime();
            case PANTRY_MANAGE -> pantry(arguments, principal.id());
            case MEAL_PLAN_MANAGE -> mealPlan(arguments, principal.id());
            case NOTIFICATION_MANAGE -> notification(arguments, principal.id());
            case RECIPE_LIBRARY_MANAGE -> recipe(arguments, principal);
            case PROFILE_MANAGE -> profile(arguments, principal.id());
            case FINISHED_DISH_MANAGE -> finishedDish(arguments, principal, attachment);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该工具由主 Agent 处理");
        };
    }

    private ToolResult currentDateTime() {
        ZonedDateTime now = ZonedDateTime.now(BUSINESS_ZONE);
        DayOfWeek day = now.getDayOfWeek();
        Map<String, Object> payload = Map.of(
                "date", now.toLocalDate().toString(),
                "time", now.toLocalTime().withNano(0).toString(),
                "weekday", day.getDisplayName(TextStyle.FULL, Locale.CHINA),
                "timezone", BUSINESS_ZONE.getId()
        );
        return result("当前日期时间", "已读取当前日期和时间", payload);
    }

    private ToolResult pantry(JsonNode arguments, Long userId) {
        String action = action(arguments);
        JsonNode payload = payload(arguments);
        return switch (action) {
            case "readiness" -> result("菜谱可做度", "已核对菜谱所需食材", pantryService.readiness(userId, convert(payload, PantryReadinessRequest.class)));
            case "operations" -> result("库存变动记录", "已读取最近库存操作", pantryOperationService.recent(userId, intValue(payload, "limit", 10)));
            case "cooking_preview" -> result("做菜扣减预览", "已核算本次预计消耗", pantryOperationService.cookingPreview(userId, longValue(payload, "recipeId"), nullableInt(payload, "servings")));
            default -> throw unsupported();
        };
    }

    private ToolResult mealPlan(JsonNode arguments, Long userId) {
        String action = action(arguments);
        JsonNode payload = payload(arguments);
        return switch (action) {
            case "get" -> result("周菜单", "已读取指定周菜单", weeklyMenuService.get(userId, nullableDate(payload, "weekStart")));
            case "recipe_shopping_list" -> result("菜谱购物清单", "已读取购物项状态", shoppingItemCheckService.list(userId, longValue(payload, "searchLogId")));
            default -> throw unsupported();
        };
    }

    private ToolResult notification(JsonNode arguments, Long userId) {
        String action = action(arguments);
        JsonNode payload = payload(arguments);
        return switch (action) {
            case "list" -> result("提醒列表", "已读取提醒", notificationService.list(
                    userId, text(payload, "status", "UNREAD"), intValue(payload, "page", 1), intValue(payload, "size", 10)));
            case "detail" -> result("提醒详情", "已读取提醒详情", notificationService.detail(userId, longValue(payload, "id")));
            case "preferences" -> result("提醒偏好", "已读取提醒偏好", notificationService.getPreferences(userId));
            default -> throw unsupported();
        };
    }

    private ToolResult recipe(JsonNode arguments, AuthPrincipal principal) {
        String action = action(arguments);
        JsonNode payload = payload(arguments);
        Long userId = principal.id();
        return switch (action) {
            case "detail" -> result("菜谱详情", "已读取菜谱详情", savedRecipeService.detail(userId, longValue(payload, "recipeId")));
            case "collections" -> result("菜谱收藏夹", "已读取收藏夹", collectionService.listCollections(userId));
            case "tags" -> result("菜谱标签", "已读取菜谱标签", collectionService.listTags(userId));
            case "search_history" -> result("最近搜索", "已读取最近菜谱搜索", searchHistoryService.recent(userId));
            case "shares" -> result("菜谱分享", "已读取分享记录", shareService.listShares(userId));
            case "feedback" -> result("推荐反馈", "已读取推荐反馈", feedbackService.get(longValue(payload, "searchLogId"), principal, null));
            case "videos" -> result("烹饪视频", "已搜索烹饪视频", videoSearchService.search(
                    userId, principal, text(payload, "recipeTitle", ""), text(payload, "keyword", null),
                    intValue(payload, "page", 1), nullableInt(payload, "limit")));
            case "hot_ingredients" -> result("热门食材", "已读取热门食材", hotIngredientStatsService.get(
                    text(payload, "period", "7d"), intValue(payload, "limit", 10)));
            default -> throw unsupported();
        };
    }

    private ToolResult profile(JsonNode arguments, Long userId) {
        String action = action(arguments);
        return switch (action) {
            case "character_names" -> result("厨房角色名称", "已读取厨房角色名称", characterNamesService.get(userId));
            default -> throw unsupported();
        };
    }

    private ToolResult finishedDish(JsonNode arguments, AuthPrincipal principal, AgentAttachment attachment) {
        String action = action(arguments);
        JsonNode payload = payload(arguments);
        if ("list".equals(action)) {
            return result("成品评价", "已读取成品评价记录", finishedDishReviewService.list(
                    principal.id(), nullableLong(payload, "recipeId"), intValue(payload, "limit", 10)));
        }
        if (!"review".equals(action)) {
            throw unsupported();
        }
        if (attachment == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请在对话框中附上一张成品图片");
        }
        Long recipeId = nullableLong(payload, "recipeId");
        String title = text(payload, "recipeTitle", "本次成品");
        List<String> ingredients = stringList(payload.path("ingredients"));
        List<String> steps = stringList(payload.path("steps"));
        FinishedDishReviewResponse review = finishedDishReviewService.create(
                new FinishedDishReviewRequest(recipeId, title, ingredients, steps),
                attachment.asMultipartFile(), principal);
        return result("成品评价", "成品图片评价已完成", review, "finished-dish-card");
    }

    private ToolResult result(String title, String summary, Object payload) {
        return result(title, summary, payload, "operation-result-card");
    }

    private ToolResult result(String title, String summary, Object payload, String cardType) {
        return new ToolResult(title, summary, payload, cardType);
    }

    private String action(JsonNode arguments) {
        String value = arguments == null ? "" : arguments.path("action").asText("").trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具 action 不能为空");
        }
        return value;
    }

    private JsonNode payload(JsonNode arguments) {
        JsonNode payload = arguments == null ? null : arguments.path("payload");
        return payload == null || !payload.isObject() ? objectMapper.createObjectNode() : payload;
    }

    public JsonNode actionPayload(Tool tool, JsonNode arguments) {
        if (tool == Tool.MEMORY_EPISODE_SAVE || tool == Tool.MEMORY_PREFERENCE_UPDATE) {
            return arguments == null || !arguments.isObject()
                    ? objectMapper.createObjectNode()
                    : arguments;
        }
        return payload(arguments);
    }

    private <T> T convert(JsonNode node, Class<T> type) {
        try {
            return objectMapper.treeToValue(node, type);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具参数格式不正确", exception);
        }
    }

    private Long longValue(JsonNode node, String field) {
        Long value = nullableLong(node, field);
        if (value == null || value <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 必须是有效编号");
        }
        return value;
    }

    private Long nullableLong(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.canConvertToLong() && value.longValue() > 0 ? value.longValue() : null;
    }

    private int intValue(JsonNode node, String field, int fallback) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.canConvertToInt() ? value.intValue() : fallback;
    }

    private Integer nullableInt(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.canConvertToInt() ? value.intValue() : null;
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.isTextual() && !value.textValue().isBlank() ? value.textValue().trim() : fallback;
    }

    private LocalDate nullableDate(JsonNode node, String field) {
        String value = text(node, field, null);
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 必须使用 yyyy-MM-dd 格式");
        }
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                .filter(JsonNode::isTextual)
                .map(JsonNode::textValue)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .limit(30)
                .toList();
    }

    private ResponseStatusException unsupported() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "该厨房工具不支持这个 action");
    }

    private String unsupportedAction() {
        throw unsupported();
    }

    public record ToolResult(String title, String summary, Object payload, String cardType) {
    }
}
