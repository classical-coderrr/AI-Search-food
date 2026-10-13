package com.example.food.memory;

import org.springframework.stereotype.Service;

/** Server-owned defaults for the actual recipe planner and generator. */
@Service
public class RecipeMemoryPersonalization {
    private final MemoryPersonalizationService personalization;
    private final MemoryProfileMapper profiles;
    private final PersonalizedSkillProjectionService skills;

    public RecipeMemoryPersonalization(MemoryPersonalizationService personalization, MemoryProfileMapper profiles,
                                       PersonalizedSkillProjectionService skills) {
        this.personalization = personalization;
        this.profiles = profiles;
        this.skills = skills;
    }

    public String context(Long userId, String query) {
        if (userId == null || !personalization.isEnabled(userId)) return "";
        MemoryProfile profile = profiles.findOwned(userId);
        var skill = skills.resolve(userId, "推荐菜谱：" + (query == null ? "" : query), profile);
        return skill == null ? "" : "\n【个人记忆提供的默认参考】\n" + skill.promptContext()
                + "\n以上仅作默认偏好参考，不得覆盖本轮明确要求、用户手动设置的禁忌和过敏限制。\n";
    }
}
