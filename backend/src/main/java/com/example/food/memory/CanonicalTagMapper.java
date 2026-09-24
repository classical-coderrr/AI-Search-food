package com.example.food.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CanonicalTagMapper extends BaseMapper<CanonicalTag> {

    @Select("""
            <script>
            SELECT ct.*
            FROM canonical_tags ct
            INNER JOIN canonical_tag_aliases ca ON ca.canonical_tag_id = ct.id
            WHERE ca.normalized_alias = #{normalizedAlias}
              AND ca.enabled = TRUE
              AND ct.enabled = TRUE
              AND ct.deleted_at IS NULL
            <if test='category != null and category != ""'>
              AND ct.category = #{category}
            </if>
            ORDER BY ca.confidence DESC, ct.id ASC
            LIMIT 1
            </script>
            """)
    CanonicalTag findByNormalizedAlias(
            @Param("normalizedAlias") String normalizedAlias,
            @Param("category") String category
    );
}
