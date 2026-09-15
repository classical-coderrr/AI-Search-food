package com.example.food.recipe;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;
import java.math.BigDecimal;

@TableName("recipe_records")
public class RecipeRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long searchLogId;
    private Long parentRecipeId;
    private Long weeklyMenuPlanId;
    private String agentIdempotencyKey;
    @TableField(exist = false)
    private String mealType;
    private String title;
    private Integer servings;
    private BigDecimal caloriesKcal;
    private BigDecimal proteinG;
    private BigDecimal fatG;
    private BigDecimal carbohydrateG;
    private String nutritionSource;
    private String summary;
    private String effects;
    private String tips;
    private String videoKeywords;
    private String aiModel;
    private String rawResponse;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getSearchLogId() {
        return searchLogId;
    }

    public void setSearchLogId(Long searchLogId) {
        this.searchLogId = searchLogId;
    }

    public Long getParentRecipeId() {
        return parentRecipeId;
    }

    public void setParentRecipeId(Long parentRecipeId) {
        this.parentRecipeId = parentRecipeId;
    }

    public Long getWeeklyMenuPlanId() {
        return weeklyMenuPlanId;
    }

    public void setWeeklyMenuPlanId(Long weeklyMenuPlanId) {
        this.weeklyMenuPlanId = weeklyMenuPlanId;
    }

    public String getAgentIdempotencyKey() {
        return agentIdempotencyKey;
    }

    public void setAgentIdempotencyKey(String agentIdempotencyKey) {
        this.agentIdempotencyKey = agentIdempotencyKey;
    }

    public String getMealType() {
        return mealType;
    }

    public void setMealType(String mealType) {
        this.mealType = mealType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Integer getServings() {
        return servings;
    }

    public void setServings(Integer servings) {
        this.servings = servings;
    }

    public BigDecimal getCaloriesKcal() {
        return caloriesKcal;
    }

    public void setCaloriesKcal(BigDecimal caloriesKcal) {
        this.caloriesKcal = caloriesKcal;
    }

    public BigDecimal getProteinG() {
        return proteinG;
    }

    public void setProteinG(BigDecimal proteinG) {
        this.proteinG = proteinG;
    }

    public BigDecimal getFatG() {
        return fatG;
    }

    public void setFatG(BigDecimal fatG) {
        this.fatG = fatG;
    }

    public BigDecimal getCarbohydrateG() {
        return carbohydrateG;
    }

    public void setCarbohydrateG(BigDecimal carbohydrateG) {
        this.carbohydrateG = carbohydrateG;
    }

    public String getNutritionSource() {
        return nutritionSource;
    }

    public void setNutritionSource(String nutritionSource) {
        this.nutritionSource = nutritionSource;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getEffects() {
        return effects;
    }

    public void setEffects(String effects) {
        this.effects = effects;
    }

    public String getTips() {
        return tips;
    }

    public void setTips(String tips) {
        this.tips = tips;
    }

    public String getVideoKeywords() {
        return videoKeywords;
    }

    public void setVideoKeywords(String videoKeywords) {
        this.videoKeywords = videoKeywords;
    }

    public String getAiModel() {
        return aiModel;
    }

    public void setAiModel(String aiModel) {
        this.aiModel = aiModel;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public void setRawResponse(String rawResponse) {
        this.rawResponse = rawResponse;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
