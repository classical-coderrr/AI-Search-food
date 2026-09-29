package com.example.food.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentToolRegistryTest {

    private final AgentToolRegistry registry = new AgentToolRegistry();

    @Test
    void onlyRegisteredToolsCanBeResolved() {
        assertThat(registry.require("PANTRY.LIST"))
                .isEqualTo(AgentToolRegistry.Tool.PANTRY_LIST);
        assertThat(registry.require("nutrition.profile"))
                .isEqualTo(AgentToolRegistry.Tool.NUTRITION_PROFILE);
        assertThat(registry.requireFunction("recipe_generate"))
                .isEqualTo(AgentToolRegistry.Tool.RECIPE_GENERATE);
        assertThat(registry.requireFunction("recipe_save"))
                .isEqualTo(AgentToolRegistry.Tool.RECIPE_SAVE);
        java.util.List<String> functionNames = registry.functionDefinitions().stream()
                .map(definition -> ((java.util.Map<?, ?>) definition.get("function")).get("name").toString())
                .toList();
        assertThat(functionNames)
                .contains("pantry_list", "recipe_generate", "recipe_save")
                .allSatisfy(name -> assertThat(name).doesNotContain("."));
    }

    @Test
    void ordinaryChatUsesReadOnlyFastPathTools() {
        java.util.List<String> tools = registry.functionDefinitions("你好，讲个笑话", false).stream()
                .map(definition -> ((java.util.Map<?, ?>) definition.get("function")).get("name").toString())
                .toList();

        assertThat(tools).contains("recipe_generate", "pantry_list", "nutrition_profile")
                .doesNotContain("pantry_manage", "profile_manage", "recipe_save");
    }

    @Test
    void selectsOnlyRelevantDomainTools() {
        java.util.List<String> dateTools = registry.functionDefinitions("今天几号", false).stream()
                .map(definition -> ((java.util.Map<?, ?>) definition.get("function")).get("name").toString())
                .toList();
        java.util.List<String> pantryTools = registry.functionDefinitions("帮我消耗两个鸡蛋", false).stream()
                .map(definition -> ((java.util.Map<?, ?>) definition.get("function")).get("name").toString())
                .toList();

        assertThat(dateTools).containsExactly("current_datetime");
        assertThat(pantryTools).contains("pantry_list", "pantry_expiry", "pantry_manage")
                .doesNotContain("notification_manage", "profile_manage");
    }

    @Test
    void exposesRecipeSaveForNaturalSaveIntents() {
        java.util.List<String> buttonIntentTools = registry.functionDefinitions("保存这道菜", false).stream()
                .map(definition -> ((java.util.Map<?, ?>) definition.get("function")).get("name").toString())
                .toList();
        java.util.List<String> pronounIntentTools = registry.functionDefinitions("把它收藏起来", false).stream()
                .map(definition -> ((java.util.Map<?, ?>) definition.get("function")).get("name").toString())
                .toList();
        java.util.List<String> menuIntentTools = registry.functionDefinitions("保存本周菜单", false).stream()
                .map(definition -> ((java.util.Map<?, ?>) definition.get("function")).get("name").toString())
                .toList();

        assertThat(buttonIntentTools).containsExactly("recipe_save");
        assertThat(pronounIntentTools).contains("recipe_save");
        assertThat(menuIntentTools).doesNotContain("recipe_save");
    }

    @Test
    void doesNotExposeRecipeSaveForRecipeQueriesAlone() {
        java.util.List<String> queryTools = registry.functionDefinitions("查看我的菜谱", false).stream()
                .map(definition -> ((java.util.Map<?, ?>) definition.get("function")).get("name").toString())
                .toList();

        assertThat(queryTools).contains("saved_recipes", "recipe_generate", "recipe_library_manage")
                .doesNotContain("recipe_save");
    }

    @Test
    void exposesMemoryToolsForRelevantQueriesAndKeepsWritesBehindExplicitIntents() {
        java.util.List<String> historyTools = functionNames("我之前收藏过哪些菜谱？");
        java.util.List<String> preferenceTools = functionNames("我不吃香菜，推荐一个晚餐");
        java.util.List<String> rememberedPreferenceTools = functionNames("我明确喜欢鸡胸肉，请把它作为我的长期食材偏好记住");
        java.util.List<String> updateTools = functionNames("修改我的偏好，不再喜欢鸡胸肉");
        java.util.List<String> naturalUpdateTools = functionNames("把记忆里的鸡胸肉改成不喜欢");
        java.util.List<String> ordinaryTools = functionNames("给我推荐一道清淡的晚餐");
        java.util.List<String> preferenceRecallTools = functionNames(
                "请从当前账号的长期记忆中检索：我刚才确认保存了什么鸡胸肉偏好？");

        assertThat(historyTools).contains("memory_search", "memory_episodes_list", "memory_recipe_history")
                .doesNotContain("memory_episode_save", "memory_preference_update");
        assertThat(preferenceTools).contains("memory_episode_save")
                .doesNotContain("memory_preference_update");
        assertThat(rememberedPreferenceTools).contains("memory_episode_save");
        assertThat(registry.isExplicitMemoryDeclarationRequest(
                "我喜欢清淡少辣鸡胸肉，尤其柠檬鸡胸肉，请记下来作为长期偏好")).isTrue();
        assertThat(registry.isExplicitMemoryDeclarationRequest("我喜欢清淡口味的菜")).isFalse();
        assertThat(registry.isExplicitMemoryDeclarationRequest(
                "请从当前账号的长期记忆中检索：我刚才确认保存了什么鸡胸肉偏好？")).isFalse();
        String preferenceQuestion = "仅根据个人长期记忆回答：我是否喜欢鸡胸肉？不要依据聊天记录推断。";
        assertThat(registry.isMemoryReadIntent(preferenceQuestion)).isTrue();
        assertThat(registry.isMemoryWriteIntent(preferenceQuestion)).isFalse();
        assertThat(updateTools).contains("memory_search", "memory_profile_get", "memory_preference_update");
        assertThat(naturalUpdateTools).contains("memory_search", "memory_preference_update");
        assertThat(ordinaryTools).doesNotContain("memory_episode_save", "memory_preference_update");
        assertThat(preferenceRecallTools).contains("memory_search")
                .doesNotContain("memory_episode_save");
        assertThat(registry.requireFunction("memory_search")).isEqualTo(AgentToolRegistry.Tool.MEMORY_SEARCH);
    }

    @Test
    void omitsMemoryToolsWhenPersonalizationIsDisabled() {
        java.util.List<String> declarationTools = registry.functionDefinitions(
                        "我喜欢紫薯，请记住", false, false).stream()
                .map(this::functionName)
                .toList();
        java.util.List<String> recommendationTools = registry.functionDefinitions(
                        "我之前喜欢鸡胸肉，今晚推荐晚餐", false, false).stream()
                .map(this::functionName)
                .toList();

        assertThat(declarationTools).doesNotContain("memory_episode_save");
        assertThat(recommendationTools).contains("recipe_generate", "weekly_menu")
                .noneMatch(name -> name.startsWith("memory_"));
        assertThat(registry.isMemoryWriteIntent("我喜欢紫薯，请记住")).isTrue();
        assertThat(registry.isMemoryReadIntent("请查看我的历史偏好")).isTrue();
    }

    @Test
    void declaresStrictEvidenceAndOptimisticVersionForMemoryWrites() {
        java.util.Map<?, ?> saveFunction = function("我喜欢鸡胸肉");
        java.util.Map<?, ?> saveParameters = (java.util.Map<?, ?>) saveFunction.get("parameters");
        assertThat(saveParameters.get("required")).asList().contains("entity", "preference", "evidence");

        java.util.Map<?, ?> update = registry.functionDefinition(AgentToolRegistry.Tool.MEMORY_PREFERENCE_UPDATE);
        java.util.Map<?, ?> updateFunction = (java.util.Map<?, ?>) update.get("function");
        java.util.Map<?, ?> updateParameters = (java.util.Map<?, ?>) updateFunction.get("parameters");
        assertThat(updateParameters.get("required")).asList().contains("memoryId", "version", "preference");
    }

    private java.util.List<String> functionNames(String message) {
        return registry.functionDefinitions(message, false).stream()
                .map(this::functionName)
                .toList();
    }

    private java.util.Map<?, ?> function(String message) {
        return registry.functionDefinitions(message, false).stream()
                .map(definition -> (java.util.Map<?, ?>) definition.get("function"))
                .filter(definition -> "memory_episode_save".equals(definition.get("name")))
                .findFirst()
                .orElseThrow();
    }

    private String functionName(java.util.Map<String, Object> definition) {
        return ((java.util.Map<?, ?>) definition.get("function")).get("name").toString();
    }

    @Test
    void rejectsUnregisteredOrBlankTools() {
        assertThatThrownBy(() -> registry.require("admin.users"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.require("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.require(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.requireFunction("admin_users"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
