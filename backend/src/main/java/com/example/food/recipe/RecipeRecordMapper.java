package com.example.food.recipe;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RecipeRecordMapper extends BaseMapper<RecipeRecord> {

    @Select("""
            <script>
            SELECT rr.*, sl.meal_type AS meal_type
            FROM recipe_records rr
            LEFT JOIN search_logs sl ON sl.id = rr.search_log_id
            WHERE rr.user_id = #{userId}
            <if test="keyword != null">
              AND rr.title LIKE CONCAT('%', #{keyword}, '%')
            </if>
            <if test="mealType != null or goal != null">
              AND EXISTS (
                  SELECT 1
                  FROM search_logs sl
                  WHERE sl.id = rr.search_log_id
                  <if test="mealType != null">
                    AND sl.meal_type = #{mealType}
                  </if>
                  <if test="goal != null">
                    AND sl.goal = #{goal}
                  </if>
              )
            </if>
            ORDER BY rr.created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<RecipeRecord> findSavedRecipes(
            @Param("userId") Long userId,
            @Param("keyword") String keyword,
            @Param("mealType") String mealType,
            @Param("goal") String goal,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Delete("DELETE FROM recipe_records WHERE id = #{recipeId} AND user_id = #{userId}")
    int deleteOwnedRecipe(@Param("recipeId") Long recipeId, @Param("userId") Long userId);

    @Select("""
            SELECT EXISTS(
                SELECT 1
                FROM recipe_records
                WHERE user_id = #{userId}
                  AND search_log_id = #{searchLogId}
            )
            """)
    boolean existsByUserIdAndSearchLogId(
            @Param("userId") Long userId,
            @Param("searchLogId") Long searchLogId
    );

    @Select("""
            SELECT *
            FROM recipe_records
            WHERE user_id = #{userId}
              AND search_log_id = #{searchLogId}
            ORDER BY id DESC
            LIMIT 1
            """)
    RecipeRecord findByUserIdAndSearchLogId(
            @Param("userId") Long userId,
            @Param("searchLogId") Long searchLogId
    );
}
