package com.example.food.ai.qwen;

import com.example.food.ai.recipe.dto.RecipeGenerateResponse;
import com.example.food.ai.config.AiModelConfigService;
import com.example.food.ai.config.AiModelRuntimeConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class QwenRecipeClientTest {

    @Test
    void keepsRecipeWhenNutritionEstimateContainsInvalidValues() throws Exception {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        QwenProperties properties = new QwenProperties(
                "test-api-key",
                "qwen-plus",
                "https://dashscope.test/compatible-mode/v1/chat/completions"
        );
        QwenRecipeClient client = new QwenRecipeClient(restTemplate, new ObjectMapper(), properties);

        String recipeJson = "{\"title\":\"测试菜\",\"ingredients\":[],\"steps\":[],"
                + "\"nutritionEstimate\":{\"servings\":2,\"caloriesKcal\":\"not-a-number\","
                + "\"proteinG\":20,\"fatG\":10,\"carbohydrateG\":30,\"source\":\"AI_ESTIMATE\"}}";
        String qwenResponse = new ObjectMapper().writeValueAsString(java.util.Map.of(
                "choices", java.util.List.of(java.util.Map.of(
                        "message", java.util.Map.of("content", recipeJson)
                ))
        ));
        server.expect(once(), requestTo(properties.endpoint()))
                .andRespond(withSuccess(qwenResponse, MediaType.APPLICATION_JSON));

        RecipeGenerateResponse response = client.generateRecipe("测试");

        assertThat(response.title()).isEqualTo("测试菜");
        assertThat(response.nutritionEstimate()).isNull();
        server.verify();
    }

    @Test
    void generateRecipeCallsQwenAndParsesStructuredContent() {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        QwenProperties properties = new QwenProperties(
                "test-api-key",
                "qwen-plus",
                "https://dashscope.test/compatible-mode/v1/chat/completions"
        );
        QwenRecipeClient client = new QwenRecipeClient(restTemplate, new ObjectMapper(), properties);

        server.expect(once(), requestTo(properties.endpoint()))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-api-key"))
                .andExpect(jsonPath("$.model").value("qwen-plus"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content")
                        .value(org.hamcrest.Matchers.containsString("只输出可解析的 JSON")))
                .andRespond(withSuccess("""
                        {
                          "choices": [
                            {
                              "message": {
                                "content": "{\\"title\\":\\"番茄炒蛋\\",\\"summary\\":\\"家常快手菜\\",\\"effects\\":[\\"补充蛋白质\\"],\\"ingredients\\":[{\\"name\\":\\"番茄\\",\\"amount\\":\\"2个\\"},{\\"name\\":\\"鸡蛋\\",\\"amount\\":\\"2个\\"}],\\"missingIngredients\\":[{\\"name\\":\\"鸡蛋\\",\\"amount\\":\\"2个\\",\\"substitutes\\":[\\"嫩豆腐\\"],\\"reason\\":\\"用户已有食材中没有鸡蛋\\"}],\\"steps\\":[{\\"order\\":1,\\"title\\":\\"备菜\\",\\"description\\":\\"番茄切块。\\",\\"durationMinutes\\":5}],\\"tips\\":[\\"先炒鸡蛋\\"],\\"videoKeywords\\":[\\"番茄炒蛋 做法\\"],\\"explanation\\":{\\"pairingLogic\\":\\"酸甜与鲜香搭配\\",\\"nutrition\\":\\"包含蛋白质和维生素\\",\\"cookingPrinciple\\":\\"分开炒制有利于保持口感\\"},\\"nutritionEstimate\\":{\\"servings\\":2,\\"caloriesKcal\\":420,\\"proteinG\\":24,\\"fatG\\":16,\\"carbohydrateG\\":42,\\"source\\":\\"AI_ESTIMATE\\"}}"
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        RecipeGenerateResponse response = client.generateRecipe("请根据番茄和鸡蛋生成菜谱");

        assertThat(response.title()).isEqualTo("番茄炒蛋");
        assertThat(response.effects()).containsExactly("补充蛋白质");
        assertThat(response.ingredients()).extracting(RecipeGenerateResponse.Ingredient::name)
                .containsExactly("番茄", "鸡蛋");
        assertThat(response.missingIngredients()).hasSize(1);
        assertThat(response.missingIngredients().get(0).name()).isEqualTo("鸡蛋");
        assertThat(response.missingIngredients().get(0).substitutes()).containsExactly("嫩豆腐");
        assertThat(response.explanation().pairingLogic()).isEqualTo("酸甜与鲜香搭配");
        assertThat(response.explanation().nutrition()).isEqualTo("包含蛋白质和维生素");
        assertThat(response.explanation().cookingPrinciple()).isEqualTo("分开炒制有利于保持口感");
        assertThat(response.nutritionEstimate()).isNotNull();
        assertThat(response.nutritionEstimate().caloriesKcal()).isEqualByComparingTo("420");
        assertThat(response.steps()).extracting(RecipeGenerateResponse.Step::title).containsExactly("备菜");
        assertThat(response.provider()).isEqualTo("qwen");
        assertThat(response.model()).isEqualTo("qwen-plus");
        server.verify();
    }

    @Test
    void generateRecipeUsesRuntimeConfigWhenAdminSavedIt() {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        QwenProperties properties = new QwenProperties(
                "env-api-key",
                "qwen-plus",
                "https://dashscope.env/compatible-mode/v1/chat/completions"
        );
        AiModelConfigService configService = mock(AiModelConfigService.class);
        when(configService.textRecipeRuntimeConfig()).thenReturn(new AiModelRuntimeConfig(
                "qwen",
                "qwen-max",
                "https://dashscope.admin/compatible-mode/v1/chat/completions",
                "admin-api-key"
        ));
        QwenRecipeClient client = new QwenRecipeClient(restTemplate, new ObjectMapper(), properties, configService);

        server.expect(once(), requestTo("https://dashscope.admin/compatible-mode/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer admin-api-key"))
                .andExpect(jsonPath("$.model").value("qwen-max"))
                .andRespond(withSuccess("""
                        {
                          "choices": [
                            {
                              "message": {
                                "content": "{\\"title\\":\\"青椒肉丝\\",\\"summary\\":\\"家常下饭菜\\",\\"effects\\":[\\"补充能量\\"],\\"ingredients\\":[{\\"name\\":\\"青椒\\",\\"amount\\":\\"2个\\"}],\\"steps\\":[{\\"order\\":1,\\"title\\":\\"切配\\",\\"description\\":\\"青椒切丝。\\",\\"durationMinutes\\":5}],\\"tips\\":[\\"大火快炒\\"],\\"videoKeywords\\":[\\"青椒肉丝 做法\\"]}"
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        RecipeGenerateResponse response = client.generateRecipe("请根据青椒和猪肉生成菜谱");

        assertThat(response.title()).isEqualTo("青椒肉丝");
        assertThat(response.model()).isEqualTo("qwen-max");
        assertThat(response.missingIngredients()).isEmpty();
        assertThat(response.explanation()).isNotNull();
        assertThat(response.explanation().pairingLogic()).isEmpty();
        assertThat(response.explanation().nutrition()).isEmpty();
        assertThat(response.explanation().cookingPrinciple()).isEmpty();
        server.verify();
    }

    @Test
    void disablesThinkingAndLimitsOutputForQwen3RecipeModels() {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AiModelConfigService configService = mock(AiModelConfigService.class);
        when(configService.textRecipeRuntimeConfig()).thenReturn(new AiModelRuntimeConfig(
                "qwen",
                "openai",
                "qwen3.8-max",
                "https://dashscope.test/compatible-mode/v1",
                "admin-api-key"
        ));
        QwenRecipeClient client = new QwenRecipeClient(
                restTemplate,
                new ObjectMapper(),
                new QwenProperties("env-api-key", "qwen-plus", "https://dashscope.env/v1"),
                configService
        );

        server.expect(once(), requestTo("https://dashscope.test/compatible-mode/v1/chat/completions"))
                .andExpect(jsonPath("$.model").value("qwen3.8-max"))
                .andExpect(jsonPath("$.enable_thinking").value(false))
                .andExpect(jsonPath("$.max_tokens").value(2400))
                .andRespond(withSuccess("""
                        {
                          "choices": [
                            {
                              "message": {
                                "content": "{\\\"title\\\":\\\"快速菜谱\\\",\\\"summary\\\":\\\"简短做法\\\",\\\"ingredients\\\":[],\\\"steps\\\":[]}"
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.generateRecipe("请生成快速菜谱").title()).isEqualTo("快速菜谱");
        server.verify();
    }

    @Test
    void enablesThinkingForShortRecipePlanningOnQwen3() throws Exception {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AiModelConfigService configService = mock(AiModelConfigService.class);
        when(configService.textRecipeRuntimeConfig()).thenReturn(new AiModelRuntimeConfig(
                "qwen",
                "openai",
                "qwen3.8-max",
                "https://dashscope.test/compatible-mode/v1",
                "admin-api-key"
        ));
        QwenRecipeClient client = new QwenRecipeClient(
                restTemplate,
                new ObjectMapper(),
                new QwenProperties("env-api-key", "qwen-plus", "https://dashscope.env/v1"),
                configService
        );
        String planJson = new ObjectMapper().writeValueAsString(Map.of(
                "recipes", List.of(Map.of(
                        "title", "回锅肉",
                        "coreIngredients", List.of("猪肉"),
                        "videoSearchKeywords", List.of("回锅肉 家常做法")
                ))
        ));
        String qwenResponse = new ObjectMapper().writeValueAsString(Map.of(
                "choices", List.of(Map.of(
                        "message", Map.of(
                                "reasoning_content", "先选择具体常见的单道菜名",
                                "content", planJson
                        )
                ))
        ));

        server.expect(once(), requestTo("https://dashscope.test/compatible-mode/v1/chat/completions"))
                .andExpect(jsonPath("$.model").value("qwen3.8-max"))
                .andExpect(jsonPath("$.enable_thinking").value(true))
                .andExpect(jsonPath("$.thinking_budget").value(768))
                .andExpect(jsonPath("$.max_tokens").value(1800))
                .andRespond(withSuccess(qwenResponse, MediaType.APPLICATION_JSON));

        List<QwenRecipeClient.RecipePlan> plans = client.planRecipeSelection("请规划猪肉家常菜");

        assertThat(plans).singleElement().satisfies(plan -> {
            assertThat(plan.title()).isEqualTo("回锅肉");
            assertThat(plan.coreIngredients()).containsExactly("猪肉");
            assertThat(plan.videoSearchKeywords()).containsExactly("回锅肉 家常做法");
        });
        server.verify();
    }

    @Test
    void generateWeeklyMenuParsesStructuredSelections() {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        QwenProperties properties = new QwenProperties(
                "test-api-key",
                "qwen-plus",
                "https://dashscope.test/compatible-mode/v1/chat/completions"
        );
        QwenRecipeClient client = new QwenRecipeClient(restTemplate, new ObjectMapper(), properties);

        server.expect(once(), requestTo(properties.endpoint()))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-api-key"))
                .andExpect(jsonPath("$.model").value("qwen-plus"))
                .andRespond(withSuccess("""
                        {
                          "choices": [
                            {
                              "message": {
                                "content": "{\\"items\\":[{\\"menuDate\\":\\"2026-08-31\\",\\"mealType\\":\\"BREAKFAST\\",\\"recipeId\\":7}]}"
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<QwenRecipeClient.WeeklyMenuSelection> selections = client.generateWeeklyMenu("请安排一周菜单");

        assertThat(selections).containsExactly(new QwenRecipeClient.WeeklyMenuSelection(
                "2026-08-31",
                "BREAKFAST",
                7L
        ));
        server.verify();
    }

    @Test
    void generateWeeklyMenuAcceptsAJsonArrayResponse() {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        QwenProperties properties = new QwenProperties(
                "test-api-key",
                "qwen-plus",
                "https://dashscope.test/compatible-mode/v1/chat/completions"
        );
        QwenRecipeClient client = new QwenRecipeClient(restTemplate, new ObjectMapper(), properties);

        server.expect(once(), requestTo(properties.endpoint()))
                .andRespond(withSuccess("""
                        {
                          "choices": [
                            {
                              "message": {
                                "content": "[{\\"menuDate\\":\\"2026-09-01\\",\\"mealType\\":\\"DINNER\\",\\"recipeId\\":8}]"
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.generateWeeklyMenu("请安排一周菜单")).containsExactly(
                new QwenRecipeClient.WeeklyMenuSelection("2026-09-01", "DINNER", 8L)
        );
        server.verify();
    }

    @Test
    void independentWeeklyMenuEnablesThinkingAndParsesRecipeDetails() throws Exception {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AiModelConfigService configService = mock(AiModelConfigService.class);
        when(configService.textRecipeRuntimeConfig()).thenReturn(new AiModelRuntimeConfig(
                "qwen",
                "openai",
                "qwen3.8-max",
                "https://dashscope.test/compatible-mode/v1",
                "admin-api-key"
        ));
        QwenRecipeClient client = new QwenRecipeClient(
                restTemplate,
                new ObjectMapper(),
                new QwenProperties("env-api-key", "qwen-plus", "https://dashscope.env/v1"),
                configService
        );
        String menuJson = new ObjectMapper().writeValueAsString(Map.of(
                "items", List.of(Map.of(
                        "menuDate", "2026-08-31",
                        "mealType", "BREAKFAST",
                        "title", "鲜肉包子",
                        "summary", "家常早餐",
                        "ingredients", List.of(Map.of("name", "面粉", "amount", "300克"), Map.of("name", "猪肉", "amount", "200克"))
                ))
        ));
        String qwenResponse = new ObjectMapper().writeValueAsString(Map.of(
                "choices", List.of(Map.of(
                        "message", Map.of(
                                "reasoning_content", "先核对早餐场景和真实菜名",
                                "content", menuJson
                        )
                ))
        ));

        server.expect(once(), requestTo("https://dashscope.test/compatible-mode/v1/chat/completions"))
                .andExpect(jsonPath("$.model").value("qwen3.8-max"))
                .andExpect(jsonPath("$.enable_thinking").value(true))
                .andExpect(jsonPath("$.thinking_budget").value(768))
                .andExpect(jsonPath("$.max_tokens").value(7000))
                .andRespond(withSuccess(qwenResponse, MediaType.APPLICATION_JSON));

        List<QwenRecipeClient.IndependentWeeklyMenuRecipe> recipes = client
                .generateIndependentWeeklyMenu("请独立生成早餐推荐");

        assertThat(recipes).singleElement().satisfies(recipe -> {
            assertThat(recipe.title()).isEqualTo("鲜肉包子");
            assertThat(recipe.mealType()).isEqualTo("BREAKFAST");
            assertThat(recipe.ingredients()).extracting(RecipeGenerateResponse.Ingredient::name)
                    .containsExactly("面粉", "猪肉");
        });
        server.verify();
    }

    @Test
    void generateRecipeUsesAnthropicCompatibleRequestAndResponse() throws Exception {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        QwenProperties properties = new QwenProperties(
                "env-api-key",
                "qwen-plus",
                "https://dashscope.env/compatible-mode/v1/chat/completions"
        );
        AiModelConfigService configService = mock(AiModelConfigService.class);
        when(configService.textRecipeRuntimeConfig()).thenReturn(new AiModelRuntimeConfig(
                "qwen",
                "anthropic",
                "qwen-plus",
                "https://dashscope.test/apps/anthropic",
                "anthropic-api-key"
        ));
        QwenRecipeClient client = new QwenRecipeClient(restTemplate, new ObjectMapper(), properties, configService);

        String recipeJson = """
                {"title":"清蒸螃蟹","summary":"原汁原味","ingredients":[],"steps":[]}
                """;
        String anthropicResponse = new ObjectMapper().writeValueAsString(Map.of(
                "content", List.of(Map.of("type", "text", "text", recipeJson))
        ));

        server.expect(once(), requestTo("https://dashscope.test/apps/anthropic"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "anthropic-api-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(jsonPath("$.model").value("qwen-plus"))
                .andExpect(jsonPath("$.max_tokens").value(4096))
                .andExpect(jsonPath("$.system").value(org.hamcrest.Matchers.containsString("只输出可解析的 JSON")))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andRespond(withSuccess(anthropicResponse, MediaType.APPLICATION_JSON));

        RecipeGenerateResponse response = client.generateRecipe("请生成螃蟹菜谱");

        assertThat(response.title()).isEqualTo("清蒸螃蟹");
        assertThat(response.provider()).isEqualTo("qwen");
        server.verify();
    }
}
