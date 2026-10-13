package db.migration;

import com.example.food.recipe.SavedRecipeIdentityService;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

public class V59__backfill_saved_recipe_identity extends BaseJavaMigration {
    @Override
    public void migrate(Context context) {
        SavedRecipeIdentityService.indexLegacy(new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(), true)), null);
    }
}
