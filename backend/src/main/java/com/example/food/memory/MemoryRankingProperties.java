package com.example.food.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Tunable ranking weights and recency half-lives for different memory kinds. */
@Component
@ConfigurationProperties(prefix = "app.memory.ranking")
public class MemoryRankingProperties {
    private double semanticWeight = 0.35;
    private double recencyWeight = 0.20;
    private double importanceWeight = 0.15;
    private double confidenceWeight = 0.15;
    private double feedbackWeight = 0.10;
    private double contextWeight = 0.05;
    private double explicitPreferenceHalfLifeDays = 3650;
    private double implicitPreferenceHalfLifeDays = 180;
    private double shortTermTrendHalfLifeDays = 30;
    private double behaviorPatternHalfLifeDays = 365;
    private double temporaryContextHalfLifeDays = 7;
    private int candidateLimit = 200;
    private int defaultTokenBudget = 1800;

    public double semanticWeight() { return semanticWeight; }
    public void setSemanticWeight(double value) { semanticWeight = value; }
    public double recencyWeight() { return recencyWeight; }
    public void setRecencyWeight(double value) { recencyWeight = value; }
    public double importanceWeight() { return importanceWeight; }
    public void setImportanceWeight(double value) { importanceWeight = value; }
    public double confidenceWeight() { return confidenceWeight; }
    public void setConfidenceWeight(double value) { confidenceWeight = value; }
    public double feedbackWeight() { return feedbackWeight; }
    public void setFeedbackWeight(double value) { feedbackWeight = value; }
    public double contextWeight() { return contextWeight; }
    public void setContextWeight(double value) { contextWeight = value; }
    public double explicitPreferenceHalfLifeDays() { return explicitPreferenceHalfLifeDays; }
    public void setExplicitPreferenceHalfLifeDays(double value) { explicitPreferenceHalfLifeDays = value; }
    public double implicitPreferenceHalfLifeDays() { return implicitPreferenceHalfLifeDays; }
    public void setImplicitPreferenceHalfLifeDays(double value) { implicitPreferenceHalfLifeDays = value; }
    public double shortTermTrendHalfLifeDays() { return shortTermTrendHalfLifeDays; }
    public void setShortTermTrendHalfLifeDays(double value) { shortTermTrendHalfLifeDays = value; }
    public double behaviorPatternHalfLifeDays() { return behaviorPatternHalfLifeDays; }
    public void setBehaviorPatternHalfLifeDays(double value) { behaviorPatternHalfLifeDays = value; }
    public double temporaryContextHalfLifeDays() { return temporaryContextHalfLifeDays; }
    public void setTemporaryContextHalfLifeDays(double value) { temporaryContextHalfLifeDays = value; }
    public int candidateLimit() { return candidateLimit; }
    public void setCandidateLimit(int value) { candidateLimit = value; }
    public int defaultTokenBudget() { return defaultTokenBudget; }
    public void setDefaultTokenBudget(int value) { defaultTokenBudget = value; }
}
