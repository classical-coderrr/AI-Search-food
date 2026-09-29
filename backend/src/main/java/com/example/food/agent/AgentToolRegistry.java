package com.example.food.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class AgentToolRegistry {

    private final Set<Tool> allowedTools = EnumSet.allOf(Tool.class);
    private static final Set<Tool> FAST_PATH_TOOLS = EnumSet.of(
            Tool.CURRENT_DATETIME,
            Tool.PANTRY_LIST,
            Tool.PANTRY_EXPIRY,
            Tool.NOTIFICATIONS,
            Tool.WEEKLY_MENU,
            Tool.SAVED_RECIPES,
            Tool.RECIPE_GENERATE,
            Tool.NUTRITION_PROFILE
    );

    public Tool require(String name) {
        if (name == null) {
            throw new IllegalArgumentException("工具名称不能为空");
        }
        for (Tool tool : allowedTools) {
            if (tool.toolName.equals(name.trim().toLowerCase(Locale.ROOT))) {
                return tool;
            }
        }
        throw new IllegalArgumentException("未注册的厨房助手工具");
    }

    public Tool requireFunction(String functionName) {
        if (functionName == null) {
            throw new IllegalArgumentException("工具函数名称不能为空");
        }
        for (Tool tool : allowedTools) {
            if (tool.functionName.equals(functionName.trim().toLowerCase(Locale.ROOT))) {
                return tool;
            }
        }
        throw new IllegalArgumentException("模型请求了未注册的厨房助手工具");
    }

    public List<Map<String, Object>> functionDefinitions() {
        return allowedTools.stream().map(Tool::functionDefinition).toList();
    }

    public Map<String, Object> functionDefinition(Tool tool) {
        if (tool == null || !allowedTools.contains(tool)) {
            throw new IllegalArgumentException("工具不能为空或未注册");
        }
        return tool.functionDefinition();
    }

    public List<Map<String, Object>> functionDefinitions(String message, boolean hasImage) {
        return functionDefinitions(message, hasImage, true);
    }

    public List<Map<String, Object>> functionDefinitions(
            String message,
            boolean hasImage,
            boolean includeMemoryTools
    ) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        Set<Tool> selected = EnumSet.noneOf(Tool.class);

        if (isRecipeSaveIntent(text)) {
            selected.add(Tool.RECIPE_SAVE);
        }

        if (containsAny(text, "今天", "日期", "几号", "星期", "时间", "几点", "当前")) {
            selected.add(Tool.CURRENT_DATETIME);
        }
        if (containsAny(text, "食材", "库存", "冰箱", "临期", "过期", "消耗", "用掉", "入库", "撤销", "够不够", "能不能做")) {
            selected.add(Tool.PANTRY_LIST);
            selected.add(Tool.PANTRY_EXPIRY);
            selected.add(Tool.PANTRY_MANAGE);
        }
        if (containsAny(text, "菜单", "餐单", "早餐", "午餐", "晚餐", "购物", "采购", "买菜", "本周", "下周")) {
            selected.add(Tool.WEEKLY_MENU);
            selected.add(Tool.MEAL_PLAN_MANAGE);
        }
        if (containsAny(text, "通知", "提醒", "已读", "未读", "归档")) {
            selected.add(Tool.NOTIFICATIONS);
            selected.add(Tool.NOTIFICATION_MANAGE);
        }
        boolean recipeOpinion = containsAny(text, "喜欢", "不喜欢")
                && containsAny(text, "这道菜", "这个菜", "这份菜", "它", "菜谱", "食谱", "推荐的菜");
        if (containsAny(text, "菜谱", "食谱", "收藏", "收藏夹", "标签", "分享", "视频", "做过", "推荐", "热门")
                || recipeOpinion) {
            selected.add(Tool.SAVED_RECIPES);
            selected.add(Tool.RECIPE_GENERATE);
            selected.add(Tool.RECIPE_LIBRARY_MANAGE);
        }
        if (containsAny(text, "营养", "健康", "忌口", "过敏", "口味", "热量", "蛋白", "脂肪", "碳水", "身高", "体重", "目标", "角色名", "小仓", "阿灶")) {
            selected.add(Tool.NUTRITION_PROFILE);
            selected.add(Tool.PROFILE_MANAGE);
        }
        if (containsAny(text, "记忆", "记得", "以前", "之前", "上次", "历史偏好", "历史行为", "我曾经")) {
            selected.add(Tool.MEMORY_SEARCH);
        }
        if (containsAny(text, "画像", "我的偏好", "长期偏好", "记住我", "你记得我", "个性化记忆")) {
            selected.add(Tool.MEMORY_PROFILE_GET);
        }
        if (containsAny(text, "之前", "以前", "上次", "历史", "做过", "收藏过", "评价过", "发生过")) {
            selected.add(Tool.MEMORY_EPISODES_LIST);
            if (containsAny(text, "菜谱", "食谱", "收藏", "烹饪", "做过", "评价")) {
                selected.add(Tool.MEMORY_RECIPE_HISTORY);
            }
        }
        if (containsAny(text, "按我的习惯", "我的习惯", "个性化策略", "个性化技能", "怎么给我推荐")) {
            selected.add(Tool.MEMORY_SKILL_GET);
        }
        if (isMemoryWriteIntent(text)) {
            selected.add(Tool.MEMORY_EPISODE_SAVE);
        }
        if (isMemoryUpdateIntent(text)) {
            selected.add(Tool.MEMORY_SEARCH);
            selected.add(Tool.MEMORY_PROFILE_GET);
            selected.add(Tool.MEMORY_PREFERENCE_UPDATE);
        }
        if (hasImage || containsAny(text, "图片", "照片", "识别", "成品", "摆盘", "火候", "打分", "评价", "复盘")) {
            selected.add(Tool.FINISHED_DISH_MANAGE);
            selected.add(Tool.PANTRY_MANAGE);
            selected.add(Tool.RECIPE_GENERATE);
        }

        if (selected.isEmpty() && containsAny(text, "删除", "修改", "更新", "清空", "撤销", "确认", "执行", "第一个", "这条", "这道", "它")) {
            selected.add(Tool.PANTRY_MANAGE);
            selected.add(Tool.MEAL_PLAN_MANAGE);
            selected.add(Tool.NOTIFICATION_MANAGE);
            selected.add(Tool.RECIPE_LIBRARY_MANAGE);
            selected.add(Tool.PROFILE_MANAGE);
            selected.add(Tool.FINISHED_DISH_MANAGE);
        }

        if (selected.isEmpty()) {
            // Let the main Agent model answer ordinary language and choose a
            // read-only tool in one request. This avoids a separate semantic
            // routing request for messages such as “我想吃点清淡的”.
            selected.addAll(FAST_PATH_TOOLS);
        }
        if (!includeMemoryTools) {
            selected.removeIf(Tool::isMemoryTool);
        }
        return selected.stream().map(Tool::functionDefinition).toList();
    }

    public boolean isMemoryWriteIntent(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        boolean explicitPreferenceIntent = !isMemoryReadRequest(text)
                && (containsAny(text, "我喜欢", "我不喜欢", "我不吃", "我忌口", "长期记住", "记下这个偏好")
                || isExplicitMemoryDeclarationRequest(text));
        return explicitPreferenceIntent || isMemoryUpdateIntent(text);
    }

    public boolean isMemoryReadIntent(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(text, "记忆", "记得我", "历史偏好", "历史行为", "以前", "之前", "上次", "我曾经");
    }

    private boolean isMemoryUpdateIntent(String text) {
        return containsAny(text, "修改记忆", "更新记忆", "修改我的偏好", "更新我的偏好", "更改记忆")
                || text.contains("记忆") && containsAny(text, "改成", "修改", "更新")
                || text.contains("偏好") && containsAny(text, "改成", "修改", "更新");
    }

    public boolean isExplicitMemoryDeclarationRequest(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return !isMemoryReadRequest(text)
                && containsAny(text, "记住", "记下", "长期偏好", "长期记忆")
                && containsAny(text, "喜欢", "不喜欢", "不吃", "忌口", "偏好", "饮食目标");
    }

    private boolean isMemoryReadRequest(String text) {
        return containsAny(text, "检索", "查询", "查看", "回忆", "告诉我", "有哪些", "哪些偏好",
                "什么偏好", "保存了什么", "记录过什么", "你记得我", "记得哪些",
                "个人记忆", "个人长期记忆", "长期记忆", "根据记忆", "根据我的记忆",
                "我是否喜欢", "我是否不喜欢", "我是否吃", "我是否不吃",
                "我是不是喜欢", "我是不是不喜欢", "我喜欢吗", "我不喜欢吗", "喜不喜欢");
    }

    private boolean isRecipeSaveIntent(String text) {
        boolean saveVerb = containsAny(text, "保存", "收藏", "存起来", "存下", "加入收藏");
        boolean recipeReference = containsAny(
                text,
                "菜谱",
                "食谱",
                "这道菜",
                "这道菜谱",
                "这份菜",
                "这份菜谱",
                "当前菜",
                "刚刚生成的菜",
                "刚才的菜",
                "它"
        );
        return saveVerb && recipeReference;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    public enum Tool {
        CURRENT_DATETIME("general.current_datetime", "current_datetime", "小厨灵正在查看当前时间", "获取服务器当前日期、时间、星期和时区。"),
        PANTRY_LIST("pantry.list", "pantry_list", "小仓正在读取食材库存", "查询当前登录用户的真实食材库存、数量、单位与到期状态。"),
        PANTRY_EXPIRY("pantry.expiry", "pantry_expiry", "小仓正在检查临期食材", "查询当前登录用户已经过期和即将过期的食材。"),
        PANTRY_MANAGE(
                "pantry.manage", "pantry_manage", "小仓正在处理库存任务",
                "管理厨房库存。action 可选 readiness、operations、cooking_preview、create、update、consume、delete、undo、cooking_consume。写操作会先请求确认。payload 按动作提供：readiness={ingredients:[{name,amount}]}；cooking_preview={recipeId,servings}；create={request:{ingredientName,category,quantity,unit,expireDate}}；update={id,request:{...}}；consume={id,quantity}；delete/undo={id}；cooking_consume={request:{recipeId,actualServings,items:[{ingredientName,quantity,unit,selected}]}}。"
        ),
        NOTIFICATIONS("notification.list", "notification_list", "小铃正在整理提醒", "查询当前登录用户最近的未读提醒。"),
        NOTIFICATION_MANAGE(
                "notification.manage", "notification_manage", "小铃正在处理提醒任务",
                "管理通知。action 可选 list、detail、preferences、read、read_all、archive、update_preferences。payload：detail/read/archive={id}；list={status,page,size}；update_preferences={request:{pantryExpiringEnabled,pantryExpiredEnabled,weeklyMenuPreparationEnabled}}。写操作会先请求确认。"
        ),
        WEEKLY_MENU("weekly.menu", "weekly_menu", "小厨灵正在整理本周菜单", "查询当前登录用户指定周或本周的菜单和购物清单。"),
        MEAL_PLAN_MANAGE(
                "meal_plan.manage", "meal_plan_manage", "小厨灵正在处理菜单任务",
                "管理周菜单和购物状态。action 可选 get、generate、save、clear、shopping_update、recipe_shopping_list、recipe_shopping_update。payload：get/clear={weekStart}；generate={request:{weekStart,overwrite}}；save={request:{weekStart,items:[{menuDate,mealType,recipeId}]}}；shopping_update={request:{weekStart,ingredientName,status}}；recipe_shopping_list={searchLogId}；recipe_shopping_update={request:{searchLogId,ingredientName,status,checked}}。写操作会先请求确认。"
        ),
        SAVED_RECIPES("recipe.saved", "saved_recipes", "阿灶正在读取已保存菜谱", "查询当前登录用户最近保存的菜谱。"),
        RECIPE_GENERATE("recipe.generate", "recipe_generate", "阿灶正在生成菜谱", "根据用户要求、真实库存、临期食材和饮食偏好生成一道菜谱。"),
        RECIPE_SAVE("recipe.save", "recipe_save", "小厨灵正在准备保存菜谱", "请求保存本次会话最近生成的菜谱，只发起用户确认。"),
        RECIPE_LIBRARY_MANAGE(
                "recipe.library_manage", "recipe_library_manage", "阿灶正在处理菜谱资料",
                "管理菜谱资料。action 可选 detail、collections、tags、search_history、shares、feedback、videos、hot_ingredients、delete、collection_create、collection_rename、collection_delete、move、replace_tags、batch_move、batch_tags、batch_delete、share_create、share_disable、reaction_set、reaction_clear、mark_cooked。payload：detail/delete={recipeId}；feedback/reaction_clear/mark_cooked={searchLogId}；videos={recipeTitle,keyword,page,limit}；hot_ingredients={period,limit}；collection_create={request:{name}}；collection_rename={collectionId,request:{name}}；collection_delete={collectionId}；move={recipeId,request:{collectionId}}；replace_tags={recipeId,request:{tags:[...]}}；batch_move={request:{recipeIds:[...],collectionId}}；batch_tags={request:{recipeIds:[...],addTags:[...],removeTags:[...]}}；batch_delete={request:{recipeIds:[...]}}；share_create={recipeId,request:{validity:\"1|7|30|PERMANENT\"}}；share_disable={shareId}；reaction_set={searchLogId,request:{reaction}}。写操作会先请求确认。"
        ),
        NUTRITION_PROFILE("nutrition.profile", "nutrition_profile", "小衡正在读取营养设置", "查询当前登录用户的健康档案、饮食偏好和每日营养目标。"),
        PROFILE_MANAGE(
                "profile.manage", "profile_manage", "小衡正在处理健康设置",
                "管理厨房相关个人设置。action 可选 character_names、health_update、health_delete、diet_update、nutrition_update、nutrition_delete、character_update、character_reset。payload：health_update={request:{ageRange,heightCm,weightKg,activityLevel}}；diet_update={request:{taste,defaultGoal,avoidIngredients,allergenIngredients}}；nutrition_update={request:{enabled,caloriesKcal,proteinG,fatG,carbohydrateG}}；character_update={request:{names:{角色标识:新名称}}}；删除和重置动作无需 request。写操作会先请求确认。"
        ),
        FINISHED_DISH_MANAGE(
                "finished_dish.manage", "finished_dish_manage", "小衡正在查看成品记录",
                "管理成品评价。action 可选 list、review、delete。list payload={recipeId,limit}；review 必须有本轮上传图片，可提供 {recipeId,recipeTitle,ingredients,steps}；delete={id}。删除会先请求确认。"
        ),
        MEMORY_SEARCH("memory.search", "memory_search", "小厨灵正在检索个人记忆",
                "只检索当前登录用户的个人记忆和历史事件，不查询外部菜谱或知识库。提供具体 query 和可选 limit。"),
        MEMORY_PROFILE_GET("memory.profile.get", "memory_profile_get", "小厨灵正在读取个人画像",
                "读取当前登录用户的结构化长期画像与记忆状态。"),
        MEMORY_EPISODES_LIST("memory.episodes.list", "memory_episodes_list", "小厨灵正在读取历史事件",
                "读取当前登录用户的个人历史事件，可按 episodeType 限定并设置 limit。"),
        MEMORY_RECIPE_HISTORY("memory.recipe.history", "memory_recipe_history", "小厨灵正在读取菜谱经历",
                "读取当前登录用户的菜谱收藏、反馈、烹饪和成品评价历史。"),
        MEMORY_SKILL_GET("memory.skill.get", "memory_skill_get", "小厨灵正在读取个性化策略",
                "根据用户当前画像读取指定任务的个性化执行策略。"),
        MEMORY_EPISODE_SAVE("memory.episode.save", "memory_episode_save", "小厨灵正在准备记录明确偏好",
                "仅可记录用户本轮原话明确表达的食材偏好、饮食目标或执行习惯；必须引用逐字证据并通过当前用户确认。执行习惯 entity 只能是 CANDIDATE_COUNT、MAX_COOKING_TIME、RESPONSE_STYLE、INCLUDE_INGREDIENT_WEIGHT，value 必须使用参数允许值。"),
        MEMORY_PREFERENCE_UPDATE("memory.preference.update", "memory_preference_update", "小厨灵正在准备修改记忆",
                "按 memory_search 返回的当前用户记忆编号和版本修改一条偏好；操作需用户确认。只接受用户明确要求的修改。"
        );

        private final String toolName;
        private final String functionName;
        private final String label;
        private final String description;

        Tool(String toolName, String functionName, String label, String description) {
            this.toolName = toolName;
            this.functionName = functionName;
            this.label = label;
            this.description = description;
        }

        public String toolName() { return toolName; }
        public String functionName() { return functionName; }
        public String label() { return label; }
        public boolean isMemoryTool() { return functionName.startsWith("memory_"); }

        private Map<String, Object> functionDefinition() {
            Map<String, Object> parameters;
            if (this == MEMORY_SEARCH) {
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put("query", Map.of("type", "string", "description", "当前用户提出的记忆检索问题"));
                properties.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 30));
                parameters = objectSchema(properties, List.of("query"));
            } else if (this == MEMORY_EPISODES_LIST || this == MEMORY_RECIPE_HISTORY) {
                Map<String, Object> properties = new LinkedHashMap<>();
                if (this == MEMORY_EPISODES_LIST) {
                    properties.put("episodeType", Map.of("type", "string", "description", "可选的事件类型过滤条件"));
                }
                properties.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 30));
                parameters = objectSchema(properties, List.of());
            } else if (this == MEMORY_SKILL_GET) {
                parameters = objectSchema(Map.of("task", Map.of("type", "string", "description", "任务名称或用户当前提出的任务")), List.of("task"));
            } else if (this == MEMORY_EPISODE_SAVE) {
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put("entity", Map.of("type", "string", "description",
                        "食材/饮食目标的原名，或执行习惯键 CANDIDATE_COUNT、MAX_COOKING_TIME、RESPONSE_STYLE、INCLUDE_INGREDIENT_WEIGHT"));
                properties.put("preference", Map.of("type", "string", "enum", List.of(
                        "LIKE", "DISLIKE", "AVOID", "PURSUE", "1", "2", "3", "4", "5",
                        "15", "20", "30", "45", "60", "CONCISE", "DETAILED", "YES", "NO")));
                properties.put("candidateType", Map.of("type", "string", "enum",
                        List.of("INGREDIENT_PREFERENCE", "DIET_GOAL", "SKILL_PREFERENCE")));
                properties.put("evidence", Map.of("type", "string", "description",
                        "必须逐字摘自用户本轮原话；食材和饮食目标证据要包含实体及明确偏好表达，执行习惯证据要直接支持所选值"));
                parameters = objectSchema(properties, List.of("entity", "preference", "evidence"));
            } else if (this == MEMORY_PREFERENCE_UPDATE) {
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put("memoryId", Map.of("type", "integer", "minimum", 1));
                properties.put("version", Map.of("type", "integer", "minimum", 0));
                properties.put("preference", Map.of("type", "string", "enum", List.of(
                        "LIKE", "DISLIKE", "AVOID", "PURSUE", "1", "2", "3", "4", "5",
                        "15", "20", "30", "45", "60", "CONCISE", "DETAILED", "YES", "NO")));
                properties.put("strength", Map.of("type", "number", "minimum", 0, "maximum", 1));
                parameters = objectSchema(properties, List.of("memoryId", "version", "preference"));
            } else if (this == RECIPE_GENERATE) {
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put("request", Map.of("type", "string", "description", "用户对餐次、口味和菜谱的要求；如直接提供 ingredients，可省略"));
                properties.put("ingredients", Map.of(
                        "type", "array",
                        "items", Map.of("type", "string"),
                        "description", "仅列出用户本轮明确提供的具体主食材；未提供时省略或返回空数组。不得把餐次、口味、偏好、解释性句子或从记忆推断的食材放入列表"
                ));
                properties.put("meal_type", Map.of("type", "string", "enum", List.of("breakfast", "lunch", "dinner")));
                properties.put("goal", Map.of("type", "string", "enum", List.of("balanced", "fat_loss", "muscle_gain", "low_sugar")));
                properties.put("prioritize_expiring", Map.of("type", "boolean", "description", "是否优先使用临期食材"));
                parameters = objectSchema(properties, List.of());
            } else if (this == PANTRY_MANAGE || this == NOTIFICATION_MANAGE || this == MEAL_PLAN_MANAGE
                    || this == RECIPE_LIBRARY_MANAGE || this == PROFILE_MANAGE || this == FINISHED_DISH_MANAGE) {
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put("action", Map.of("type", "string", "description", "工具描述中列出的 action"));
                properties.put("payload", Map.of("type", "object", "description", "动作参数", "additionalProperties", true));
                parameters = objectSchema(properties, List.of("action"));
            } else {
                parameters = objectSchema(Map.of(), List.of());
            }
            return Map.of("type", "function", "function", Map.of(
                    "name", functionName,
                    "description", description,
                    "parameters", parameters
            ));
        }

        private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", new ArrayList<>(required));
            }
            schema.put("additionalProperties", false);
            return schema;
        }
    }
}
