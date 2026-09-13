package com.example.food.recipe.collection;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SavedRecipeCollectionMapper {

    @Select("""
            <script>
            SELECT rr.id,
                   rr.title,
                   rr.summary,
                   sl.query_text AS search_ingredients,
                   sl.meal_type,
                   sl.goal,
                   (
                       SELECT ri.ingredient_name
                       FROM recipe_ingredients ri
                       WHERE ri.recipe_id = rr.id
                       ORDER BY ri.id ASC
                       LIMIT 1
                   ) AS cover_ingredient,
                   rr.created_at AS saved_at,
                   CASE WHEN rci.id IS NULL THEN #{defaultCollectionId} ELSE rci.collection_id END AS collection_id,
                   CASE WHEN rci.id IS NULL THEN #{defaultCollectionName} ELSE rc.name END AS collection_name
            FROM recipe_records rr
            LEFT JOIN search_logs sl ON sl.id = rr.search_log_id
            LEFT JOIN recipe_collection_items rci
              ON rci.recipe_id = rr.id
             AND rci.user_id = #{userId}
            LEFT JOIN recipe_collections rc
              ON rc.id = rci.collection_id
             AND rc.user_id = #{userId}
            WHERE rr.user_id = #{userId}
              AND rr.weekly_menu_plan_id IS NULL
            <if test="collectionId != null">
              AND (
                  (rci.id IS NULL AND #{collectionId} = #{defaultCollectionId})
                  OR rci.collection_id = #{collectionId}
              )
            </if>
            <if test="keyword != null">
              AND (
                  rr.title LIKE CONCAT('%', #{keyword}, '%')
                  OR sl.query_text LIKE CONCAT('%', #{keyword}, '%')
                  OR EXISTS (
                      SELECT 1
                      FROM recipe_ingredients keyword_ri
                      WHERE keyword_ri.recipe_id = rr.id
                        AND keyword_ri.ingredient_name LIKE CONCAT('%', #{keyword}, '%')
                  )
              )
            </if>
            <if test="mealType != null or goal != null">
              AND EXISTS (
                  SELECT 1
                  FROM search_logs filter_sl
                  WHERE filter_sl.id = rr.search_log_id
                  <if test="mealType != null">AND filter_sl.meal_type = #{mealType}</if>
                  <if test="goal != null">AND filter_sl.goal = #{goal}</if>
              )
            </if>
            <if test="tagName != null">
              AND EXISTS (
                  SELECT 1
                  FROM recipe_record_tags filter_rrt
                  INNER JOIN recipe_tags filter_rt
                    ON filter_rt.id = filter_rrt.tag_id
                   AND filter_rt.user_id = #{userId}
                  WHERE filter_rrt.user_id = #{userId}
                    AND filter_rrt.recipe_id = rr.id
                    AND filter_rt.name = #{tagName}
              )
            </if>
            <choose>
              <when test="ascending">ORDER BY rr.created_at ASC, rr.id ASC</when>
              <otherwise>ORDER BY rr.created_at DESC, rr.id DESC</otherwise>
            </choose>
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<SavedRecipeListRow> findPage(
            @Param("userId") Long userId,
            @Param("defaultCollectionId") Long defaultCollectionId,
            @Param("defaultCollectionName") String defaultCollectionName,
            @Param("collectionId") Long collectionId,
            @Param("keyword") String keyword,
            @Param("mealType") String mealType,
            @Param("goal") String goal,
            @Param("tagName") String tagName,
            @Param("ascending") boolean ascending,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM recipe_records rr
            LEFT JOIN recipe_collection_items rci
              ON rci.recipe_id = rr.id
             AND rci.user_id = #{userId}
            WHERE rr.user_id = #{userId}
              AND rr.weekly_menu_plan_id IS NULL
            <if test="collectionId != null">
              AND (
                  (rci.id IS NULL AND #{collectionId} = #{defaultCollectionId})
                  OR rci.collection_id = #{collectionId}
              )
            </if>
            <if test="keyword != null">
              AND (
                  rr.title LIKE CONCAT('%', #{keyword}, '%')
                  OR EXISTS (
                      SELECT 1
                      FROM search_logs keyword_sl
                      WHERE keyword_sl.id = rr.search_log_id
                        AND keyword_sl.query_text LIKE CONCAT('%', #{keyword}, '%')
                  )
                  OR EXISTS (
                      SELECT 1
                      FROM recipe_ingredients keyword_ri
                      WHERE keyword_ri.recipe_id = rr.id
                        AND keyword_ri.ingredient_name LIKE CONCAT('%', #{keyword}, '%')
                  )
              )
            </if>
            <if test="mealType != null or goal != null">
              AND EXISTS (
                  SELECT 1
                  FROM search_logs filter_sl
                  WHERE filter_sl.id = rr.search_log_id
                  <if test="mealType != null">AND filter_sl.meal_type = #{mealType}</if>
                  <if test="goal != null">AND filter_sl.goal = #{goal}</if>
              )
            </if>
            <if test="tagName != null">
              AND EXISTS (
                  SELECT 1
                  FROM recipe_record_tags filter_rrt
                  INNER JOIN recipe_tags filter_rt
                    ON filter_rt.id = filter_rrt.tag_id
                   AND filter_rt.user_id = #{userId}
                  WHERE filter_rrt.user_id = #{userId}
                    AND filter_rrt.recipe_id = rr.id
                    AND filter_rt.name = #{tagName}
              )
            </if>
            </script>
            """)
    long count(
            @Param("userId") Long userId,
            @Param("defaultCollectionId") Long defaultCollectionId,
            @Param("collectionId") Long collectionId,
            @Param("keyword") String keyword,
            @Param("mealType") String mealType,
            @Param("goal") String goal,
            @Param("tagName") String tagName
    );
}
