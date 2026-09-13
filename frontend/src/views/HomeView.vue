<template>
  <main
    class="home-page"
    :class="{
      'is-result-expanded': hasSearch,
      'is-result-priority': resultPriorityMode,
      'is-detail-view': detailViewOpen,
      'is-embedded': embedded
    }"
  >
    <section class="command-shell" aria-labelledby="home-title">
      <header class="workspace-heading">
        <div>
          <p class="eyebrow">AI 菜谱指挥舱</p>
          <h1 id="home-title">AI 食材智能工作台</h1>
        </div>
        <div class="signal-strip" aria-label="当前推荐信号">
          <span>{{ searchModeLabel }}</span>
          <span>{{ mealTypeLabel }}</span>
          <span>{{ goalLabel }}</span>
        </div>
      </header>

      <div v-if="auth.isUser && pantryExpiryNotice" class="pantry-expiry-banner" role="alert">
        <TriangleAlert :size="17" aria-hidden="true" />
        <div>
          <strong>库存保质期提醒</strong>
          <span>{{ pantryExpiryNoticeText }}</span>
        </div>
        <button v-if="embedded" type="button" class="pantry-expiry-link" @click="emit('open-feature', 'pantry')">查看库存</button>
        <RouterLink v-else class="pantry-expiry-link" to="/pantry">查看库存</RouterLink>
      </div>

      <div class="command-grid" :class="{ 'result-priority-grid': resultPriorityMode }">
        <section v-if="!resultPriorityMode" class="search-panel" aria-label="菜谱搜索表单">
          <div class="panel-title">
            <ScanSearch :size="20" aria-hidden="true" />
            <div>
              <span>输入信号</span>
              <strong>食材识别与推荐参数</strong>
            </div>
          </div>

          <el-form class="search-form" label-position="top" @submit.prevent="runSearch">
            <el-form-item label="食材清单">
              <RecentSearchPopover
                :items="recentSearches"
                :loading="recentSearchLoading"
                :visible="auth.isUser && recentSearchVisible"
                @select="applyRecentSearch"
                @update:visible="recentSearchVisible = $event"
              >
                <el-input
                  v-model="ingredients"
                  type="textarea"
                  :rows="3"
                  resize="none"
                  maxlength="240"
                  show-word-limit
                  placeholder="例如：番茄、鸡蛋、菠菜"
                  @keydown="handleIngredientsKeydown"
                  @focus="openRecentSearches"
                  @click="openRecentSearches"
                />
                <p class="ingredient-input-hint">按 Enter 生成，Shift + Enter 换行</p>
              </RecentSearchPopover>
            </el-form-item>

            <div class="filters">
              <el-form-item label="餐次">
                <el-select v-model="mealType" placeholder="请选择餐次">
                  <el-option label="不限餐次" value="any" />
                  <el-option label="早餐" value="breakfast" />
                  <el-option label="午餐" value="lunch" />
                  <el-option label="晚餐" value="dinner" />
                </el-select>
              </el-form-item>

              <el-form-item>
                <template #label>
                  <div class="goal-label-row">
                    <span>目标</span>
                    <el-button v-if="auth.isUser" link type="primary" @click="preferenceDialogVisible = true">
                      <SlidersHorizontal :size="14" aria-hidden="true" />
                      <span>饮食偏好</span>
                    </el-button>
                  </div>
                </template>
                <el-select v-model="goal" placeholder="请选择烹饪目标" @change="goalManuallySelected = true">
                  <el-option label="营养均衡" value="balanced" />
                  <el-option label="高蛋白" value="protein" />
                  <el-option label="低热量" value="light" />
                  <el-option label="快速烹饪" value="quick" />
                  <el-option label="减脂" value="fat_loss" />
                  <el-option label="增肌" value="muscle_gain" />
                  <el-option label="控糖" value="low_sugar" />
                </el-select>
              </el-form-item>
            </div>

            <div class="mode-panel" aria-label="搜索方式">
              <span class="field-label">搜索方式</span>
              <div class="mode-grid">
                <button
                  v-for="mode in modeCards"
                  :key="mode.value"
                  class="mode-card"
                  :class="{ active: searchMode === mode.value }"
                  type="button"
                  :aria-pressed="searchMode === mode.value"
                  :disabled="mode.disabled"
                  @click="searchMode = mode.value"
                >
                  <component :is="mode.icon" :size="18" aria-hidden="true" />
                  <strong>{{ mode.label }}</strong>
                  <span>{{ mode.description }}</span>
                </button>
              </div>
            </div>

            <div v-if="auth.isUser" class="reference-controls" aria-label="本次生成参考范围">
              <div class="reference-control">
                <div>
                  <strong>参考我的库存</strong>
                  <span>开启后才会读取数量和临期状态，并优先匹配可用食材。</span>
                </div>
                <el-switch
                  v-model="usePantry"
                  aria-label="参考我的库存"
                  @change="handlePantryReferenceChanged"
                />
              </div>
              <div class="reference-control">
                <div>
                  <strong>参考健康与营养</strong>
                  <span>仅用于一般饮食搭配，不会替代专业建议。</span>
                </div>
                <el-switch
                  v-model="useHealthNutrition"
                  aria-label="参考健康与营养"
                  @change="handleHealthNutritionReferenceChanged"
                />
              </div>
            </div>

            <div v-if="showImageUpload" class="image-upload-panel" aria-label="上传图片识别食材">
              <input
                ref="imageInput"
                class="visually-hidden"
                type="file"
                accept="image/jpeg,image/png,image/webp"
                @change="handleImageSelected"
              />

              <button class="upload-dropzone" type="button" @click="openImagePicker">
                <img v-if="selectedImagePreview" :src="selectedImagePreview" alt="已选择的食材图片预览" />
                <span v-else class="upload-empty">
                  <ImagePlus :size="20" aria-hidden="true" />
                  <strong>上传食材图片</strong>
                  <em>支持 JPG、PNG、WebP，最大 5MB</em>
                </span>
              </button>

              <div class="image-actions">
                <el-button plain @click="openImagePicker">
                  <ImagePlus :size="16" aria-hidden="true" />
                  <span>{{ selectedImageFile ? '更换图片' : '选择图片' }}</span>
                </el-button>
                <el-button
                  type="primary"
                  :disabled="!selectedImageFile"
                  :loading="recognizing"
                  @click="recognizeUploadedImage"
                >
                  <ScanSearch :size="16" aria-hidden="true" />
                  <span>识别食材</span>
                </el-button>
              </div>

              <div v-if="recognizedIngredients.length" class="recognized-result">
                <span v-for="ingredient in recognizedIngredients" :key="ingredient">
                  {{ ingredient }}
                </span>
              </div>
              <p v-if="recognitionDescription" class="recognition-summary">
                {{ recognitionDescription }}
              </p>
            </div>

            <div v-else-if="showCameraCapture" class="camera-capture-panel" aria-label="拍照识别食材">
              <div class="camera-capture-copy">
                <span class="camera-capture-icon" aria-hidden="true">
                  <Camera :size="20" />
                </span>
                <div>
                  <strong>拍照识别食材</strong>
                  <p>打开摄像头拍摄食材，照片会自动提交给 AI 识别。</p>
                </div>
              </div>
              <el-button type="primary" :loading="recognizing" @click="openCameraCapture">
                <Camera :size="16" aria-hidden="true" />
                <span>打开摄像头</span>
              </el-button>

              <div v-if="recognizedIngredients.length" class="recognized-result camera-recognized-result">
                <span v-for="ingredient in recognizedIngredients" :key="ingredient">
                  {{ ingredient }}
                </span>
              </div>
              <p v-if="recognitionDescription" class="recognition-summary camera-recognition-summary">
                {{ recognitionDescription }}
              </p>
            </div>

            <div class="search-actions">
                <el-button
                  type="primary"
                  size="large"
                  :loading="generating || (auth.isUser && preferenceLoading)"
                  @click="runSearch"
                >
                  <Sparkles :size="18" aria-hidden="true" />
                  <span>{{ editingConditions && hasSearch ? '重新生成菜谱' : '生成推荐' }}</span>
                </el-button>
              <el-button size="large" plain @click="resetSearch">
                <RotateCcw :size="18" aria-hidden="true" />
                <span>重置</span>
              </el-button>
            </div>
          </el-form>
        </section>

        <section v-else-if="!detailViewOpen" class="query-summary-bar" aria-label="当前查询条件">
          <div class="query-summary-copy">
            <span class="eyebrow">当前查询</span>
            <strong>{{ cleanIngredients }}</strong>
            <div class="query-summary-meta">
              <span>{{ mealTypeLabel }}</span>
              <span>{{ goalLabel }}</span>
              <span>{{ searchModeLabel }}</span>
            </div>
          </div>
          <div class="query-summary-actions">
            <el-button plain @click="editSearchConditions">
              <SlidersHorizontal :size="16" aria-hidden="true" />
              <span>修改条件</span>
            </el-button>
            <el-button
              type="primary"
              plain
              :loading="regenerating"
              :disabled="!recipeComplete"
              @click="regenerateCurrentRecipe('simple')"
            >
              <RefreshCw :size="16" aria-hidden="true" />
              <span>重新生成</span>
            </el-button>
          </div>
        </section>

        <section class="result-panel" aria-label="菜谱搜索结果">
          <div v-if="!detailViewOpen && generating" class="recipe-generation-loading" role="status" aria-live="polite">
            <div class="recipe-generation-loading-stage">
              <div class="recipe-generation-loading-art" aria-hidden="true">
                <img src="/images/chef-cooking.png" alt="" />
              </div>
              <div class="recipe-generation-loading-copy">
                <strong>正在生成菜谱</strong>
                <span>{{ generationStageLabel }}，稍候即可查看结果</span>
              </div>
            </div>
          </div>
          <div v-else-if="!detailViewOpen" class="result-header">
              <div>
                <p class="eyebrow">菜谱输出窗口</p>
                <h2>{{ resultTitle }}</h2>
                <p v-if="recipe?.summary" class="result-summary-line">{{ recipe.summary }}</p>
                <div v-if="recipe?.effects?.length" class="result-header-tags" aria-label="菜谱关键标签">
                  <span v-for="effect in recipe.effects" :key="effect" class="system-tag">{{ effect }}</span>
                </div>
                <div v-if="recipe" class="result-context-tags" aria-label="本次生成参考范围">
                  <span v-if="recipe.pantryReferenced" class="context-tag context-tag-stock">已参考库存</span>
                  <span v-else-if="recipe.pantryIncompatible" class="context-tag context-tag-warning">当前库存食材不可参考</span>
                  <span v-else class="context-tag">未参考库存</span>
                  <span v-if="recipe.healthNutritionReferenced" class="context-tag">已参考健康与营养</span>
                </div>
              </div>
            <div class="result-header-actions">
              <el-button
                v-if="recipe?.steps?.length"
                class="start-cooking-button"
                type="primary"
                :disabled="!recipeComplete"
                @click="openCookingMode"
              >
                <Play :size="16" aria-hidden="true" />
                <span>开始烹饪</span>
              </el-button>
              <el-button
                v-if="false && recipe"
                class="finished-dish-review-button"
                plain
                :disabled="generating"
                @click="openFinishedDishReview"
              >
                <Sparkles :size="16" aria-hidden="true" />
                <span>评价成品</span>
              </el-button>
              <el-dropdown
                v-if="false && recipe"
                trigger="click"
                :disabled="generating || savingRecipe"
                @command="regenerateCurrentRecipe"
              >
                <el-button
                  class="regenerate-recipe-button"
                  plain
                  :loading="regenerating"
                  :disabled="generating || savingRecipe"
                >
                  <RefreshCw :size="16" aria-hidden="true" />
                  <span>重新生成</span>
                  <ChevronDown :size="15" aria-hidden="true" />
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item
                      v-for="option in regenerationOptions"
                      :key="option.value"
                      :command="option.value"
                    >
                      {{ option.label }}
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            <el-button
                v-if="recipe"
                class="save-recipe-button"
                :type="savedRecipeId ? 'success' : 'primary'"
                plain
                :loading="savingRecipe"
                :disabled="Boolean(savedRecipeId) || !recipeComplete"
                @click="saveCurrentRecipe"
            >
                <Bookmark :size="16" aria-hidden="true" />
                <span>{{ savedRecipeId ? '已保存' : '保存到我的菜谱' }}</span>
              </el-button>
              <el-dropdown
                v-if="recipe"
                class="secondary-action-menu"
                trigger="click"
                :disabled="!recipeComplete || savingRecipe"
                @command="handleSecondaryAction"
              >
                <el-button plain size="small" :disabled="!recipeComplete || savingRecipe">
                  <ChevronDown :size="15" aria-hidden="true" />
                  <span>更多操作</span>
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="review">
                      <Sparkles :size="14" aria-hidden="true" />
                      <span>评价成品</span>
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <span class="status-pill">{{ generating ? '生成中' : '已就绪' }}</span>
            </div>
          </div>

          <section
            v-if="recommendationRecipes.length > 1"
            class="recommendation-selector"
            aria-label="菜谱推荐选择"
          >
            <div class="recommendation-selector-heading">
              <div>
                <span class="eyebrow">本次推荐</span>
                <strong>{{ recommendationModeLabel }}</strong>
              </div>
              <span>{{ recommendationReadyCount }} / {{ recommendationRecipes.length }} 道已完成</span>
            </div>
            <div
              class="recommendation-selector-track"
              role="tablist"
              :aria-label="`${recommendationRecipes.length}道推荐菜谱`"
            >
              <button
                v-for="(item, index) in recommendationRecipes"
                :id="`recommendation-card-${item.id}`"
                :key="item.id"
                class="recommendation-card"
                :class="{ active: item.id === activeRecipeId, ready: isRecipeReady(item) }"
                type="button"
                role="tab"
                :aria-selected="item.id === activeRecipeId"
                @click="selectRecommendationRecipe(item.id)"
                @keydown.left.prevent="moveRecommendationSelection(-1)"
                @keydown.right.prevent="moveRecommendationSelection(1)"
              >
                <span class="recommendation-card-index">{{ String(index + 1).padStart(2, '0') }}</span>
                <span class="recommendation-card-copy">
                  <strong>{{ item.title || item.label || `菜谱 ${index + 1}` }}</strong>
                  <small>{{ item.label || '推荐处理中' }} · {{ recipeCardStatus(item) }}</small>
                </span>
                <span class="recommendation-card-state" aria-hidden="true">
                  {{ isRecipeReady(item) ? '✓' : '…' }}
                </span>
              </button>
            </div>
          </section>

          <div v-if="recipe && (generating || streamFailed)" class="stream-status" role="status" aria-live="polite">
            <span class="stream-status-dot" aria-hidden="true"></span>
            <div>
              <strong>{{ generationStageLabel }}</strong>
              <span>{{ streamErrorMessage || '内容会按模块逐步显示，完成后即可保存和开始烹饪。' }}</span>
            </div>
            <el-button v-if="streamFailed" link type="primary" @click="retryStreamGeneration">
              重试
            </el-button>
          </div>

          <div v-if="recipe?.pantryIncompatible" class="context-fallback-alert" role="alert">
            当前库存食材不可参考，已按原输入食材生成菜谱。
          </div>
          <div v-else-if="recipe?.pantryFallback" class="context-fallback-alert" role="alert">
            本次库存读取失败，已降级为仅按输入食材生成；你可以稍后开启“参考我的库存”重试。
          </div>

          <div v-if="showLatestContent" class="latest-content-bar" role="status">
            <span>新内容已生成</span>
            <button type="button" @click="scrollRecipeToLatest">查看最新内容</button>
          </div>

          <div v-if="detailViewOpen" class="recipe-detail-view-header">
            <button class="detail-back-button" type="button" @click="closeDetailView">
              <ArrowLeft :size="18" aria-hidden="true" />
              <span>返回菜谱结果</span>
            </button>
            <div class="detail-view-title">
              <p class="eyebrow">菜谱详情</p>
              <h2>{{ detailSectionLabel }} · {{ recipe?.title || '菜谱' }}</h2>
            </div>
            <div class="detail-view-actions">
              <el-button
                v-if="detailSection === 'steps' && recipe?.steps?.length"
                type="primary"
                :disabled="!recipeComplete"
                @click="openCookingMode"
              >
                <Play :size="16" aria-hidden="true" />
                <span>开始烹饪</span>
              </el-button>
            </div>
          </div>

          <div v-if="!hasSearch" class="empty-stage">
            <ChefHat :size="44" aria-hidden="true" />
            <strong>等待食材信号</strong>
            <span>输入食材后，系统会在右侧生成菜谱、功效、步骤和相关烹饪视频。</span>
          </div>

          <div
            v-else
            ref="recipeScrollContainer"
            class="result-content"
            tabindex="0"
            aria-label="菜谱生成结果，可使用鼠标滚轮、键盘或触摸滚动"
            @scroll="handleRecipeScroll"
          >
            <dl v-if="false" class="brief-grid">
              <div>
                <dt>食材</dt>
                <dd>{{ cleanIngredients }}</dd>
              </div>
              <div>
                <dt>餐次</dt>
                <dd>{{ mealTypeLabel }}</dd>
              </div>
              <div>
                <dt>目标</dt>
                <dd>{{ goalLabel }}</dd>
              </div>
            </dl>

            <div v-if="recipe" class="recipe-detail">
              <div v-if="generating || streamFailed" class="stream-progress-panel" aria-label="菜谱内容生成进度">
                <div class="stream-progress-card">
                  <div class="stream-progress-card-head">
                    <h3>准备食材</h3>
                    <span>{{ recipe.ingredients?.length ? `${recipe.ingredients.length} 项` : '等待中' }}</span>
                  </div>
                  <ul v-if="recipe.ingredients?.length" class="stream-preview-list">
                    <li v-for="ingredient in recipe.ingredients.slice(0, 4)" :key="`${ingredient.name}-${ingredient.amount}`">
                      <strong>{{ ingredient.name }}</strong>
                      <span>{{ ingredient.amount }}</span>
                    </li>
                  </ul>
                  <div v-else class="stream-preview-skeleton" aria-hidden="true">
                    <span class="recipe-skeleton-line"></span>
                    <span class="recipe-skeleton-line recipe-skeleton-line-short"></span>
                  </div>
                </div>
                <div class="stream-progress-card">
                  <div class="stream-progress-card-head">
                    <h3>烹饪过程</h3>
                    <span>{{ recipe.steps?.length ? `${recipe.steps.length} 步` : '等待中' }}</span>
                  </div>
                  <ol v-if="recipe.steps?.length" class="stream-preview-list stream-preview-steps">
                    <li v-for="step in recipe.steps.slice(0, 3)" :key="step.order || step.title">
                      <strong>{{ step.title || '烹饪步骤' }}</strong>
                      <span>{{ step.description }}</span>
                    </li>
                  </ol>
                  <div v-else class="stream-preview-skeleton" aria-hidden="true">
                    <span class="recipe-skeleton-line"></span>
                    <span class="recipe-skeleton-line recipe-skeleton-line-short"></span>
                  </div>
                </div>
              </div>

              <div v-if="false" class="recipe-summary-block">
                <p>{{ recipe.summary }}</p>
                <div v-if="recipe.effects?.length" class="tag-row" aria-label="菜谱功效">
                  <span v-for="effect in recipe.effects" :key="effect" class="system-tag">
                    {{ effect }}
                  </span>
                </div>
              </div>

              <RecommendationFeedbackButtons
                v-if="!detailViewOpen"
                :reaction="feedbackReaction"
                :cooked="feedbackCooked"
                :loading="feedbackLoading"
                :disabled="!recipeComplete || !recipe.searchLogId"
                @toggle-reaction="toggleRecommendationReaction"
              />

              <NutritionEstimateCard
                v-if="!detailViewOpen"
                :nutrition="recipe.nutritionEstimate"
                :nutrition-target="nutritionTarget"
              />

              <nav v-if="false" class="recipe-entry-nav" aria-label="菜谱详情入口">
                <button
                  v-for="section in recipeSections"
                  :key="section.key"
                  class="recipe-entry-button"
                  type="button"
                  :disabled="!canOpenDetail(section.key)"
                  @click="openDetail(section.key, $event)"
                >
                  <span class="recipe-entry-index">{{ section.index }}</span>
                  <span class="recipe-entry-copy">
                    <strong>{{ section.label }}</strong>
                    <small>{{ detailEntryDescription(section.key) }}</small>
                  </span>
                  <ChevronRight :size="17" aria-hidden="true" />
                </button>
              </nav>

              <div v-if="detailViewOpen" class="recipe-sections" aria-label="菜谱详情章节">
                <nav v-if="false" class="recipe-section-nav" role="tablist" aria-label="菜谱章节导航">
                  <button
                    v-for="section in recipeSections"
                    :key="section.key"
                    class="recipe-section-link"
                    :class="{ active: detailSection === section.key }"
                    type="button"
                    :id="`recipe-tab-${section.key}`"
                    :aria-selected="detailSection === section.key"
                    :aria-controls="`recipe-section-${section.key}`"
                    role="tab"
                    @click="selectDetailSection(section.key)"
                    @keydown.left.prevent="moveDetailTab(-1)"
                    @keydown.right.prevent="moveDetailTab(1)"
                  >
                    <span>{{ section.index }}</span>
                    {{ section.label }}
                  </button>
                </nav>

                <section v-if="detailSection === 'overview'" id="recipe-section-overview" class="recipe-section" aria-labelledby="recipe-overview-title" role="tabpanel">
                  <div class="section-heading">
                    <div>
                      <span class="section-index">01</span>
                      <h3 id="recipe-overview-title">概览</h3>
                    </div>
                    <button
                      class="detail-close-button"
                      type="button"
                      aria-label="关闭菜谱详情"
                      title="关闭菜谱详情"
                      @click="closeDetailView"
                    >
                      <X :size="19" aria-hidden="true" />
                    </button>
                  </div>
                  <div class="overview-card">
                    <p>{{ recipe.summary || '暂无菜谱简介' }}</p>
                    <div v-if="recipe.effects?.length" class="tag-row" aria-label="菜谱功效">
                      <span v-for="effect in recipe.effects" :key="effect" class="system-tag">
                        {{ effect }}
                      </span>
                    </div>
                  </div>
                  <div v-if="explanationItems.length" class="explanation-grid">
                    <article v-for="item in explanationItems" :key="item.key" class="explanation-item">
                      <component :is="item.icon" :size="18" aria-hidden="true" />
                      <div>
                        <h4>{{ item.label }}</h4>
                        <p>{{ item.content }}</p>
                      </div>
                    </article>
                  </div>
                  <NutritionEstimateCard :nutrition="recipe.nutritionEstimate" :nutrition-target="nutritionTarget" />
                  <el-empty v-if="!explanationItems.length" description="暂无额外说明" :image-size="56" />
                </section>

                <section v-if="detailSection === 'ingredients'" id="recipe-section-ingredients" class="recipe-section" aria-labelledby="recipe-ingredients-title" role="tabpanel">
                  <div class="section-heading">
                    <div>
                      <span class="section-index">02</span>
                      <h3 id="recipe-ingredients-title">准备食材</h3>
                    </div>
                    <button
                      class="detail-close-button"
                      type="button"
                      aria-label="关闭菜谱详情"
                      title="关闭菜谱详情"
                      @click="closeDetailView"
                    >
                      <X :size="19" aria-hidden="true" />
                    </button>
                  </div>
                  <div class="section-card-grid">
                    <article v-if="recipe.ingredients?.length" class="detail-card ingredients-detail-card">
                      <div class="detail-card-head">
                        <h4>所需食材</h4>
                        <span>{{ recipe.ingredients.length }} 项</span>
                      </div>
                      <el-table class="ingredients-table" :data="recipe.ingredients" size="large">
                        <el-table-column label="食材" min-width="120">
                          <template #default="scope">
                            <div class="ingredient-name-cell">
                              <strong>{{ scope.row.name }}</strong>
                              <span v-if="readinessBadge(getReadinessItem(scope.row.name))" class="readiness-ingredient-badge" :class="{ soon: getReadinessItem(scope.row.name)?.expiringSoon && !getReadinessItem(scope.row.name)?.shortage }">
                                {{ readinessBadge(getReadinessItem(scope.row.name)) }}
                              </span>
                            </div>
                          </template>
                        </el-table-column>
                        <el-table-column prop="amount" label="用量" min-width="120" />
                      </el-table>
                      <div class="ingredient-mobile-list">
                        <div v-for="ingredient in recipe.ingredients" :key="`${ingredient.name}-${ingredient.amount}`" class="ingredient-mobile-row">
                          <div class="ingredient-name-cell">
                            <strong>{{ ingredient.name }}</strong>
                            <span v-if="readinessBadge(getReadinessItem(ingredient.name))" class="readiness-ingredient-badge" :class="{ soon: getReadinessItem(ingredient.name)?.expiringSoon && !getReadinessItem(ingredient.name)?.shortage }">
                              {{ readinessBadge(getReadinessItem(ingredient.name)) }}
                            </span>
                          </div>
                          <span>{{ ingredient.amount }}</span>
                        </div>
                      </div>
                    </article>

                    <article v-if="ingredientAnalysisRows.length" class="detail-card detail-card-wide analysis-detail-card">
                      <div class="detail-card-head">
                        <h4>食材分析</h4>
                        <span>库存状态</span>
                      </div>
                      <el-table class="analysis-table" :data="ingredientAnalysisRows" size="large">
                        <el-table-column prop="name" label="食材" min-width="100" />
                        <el-table-column prop="amount" label="用量" min-width="88" />
                        <el-table-column label="状态" min-width="82">
                          <template #default="scope">
                            <span class="ingredient-state" :class="scope.row.alreadyOwned ? 'owned' : 'missing'">
                              {{ scope.row.alreadyOwned ? '已有' : '缺失' }}
                            </span>
                          </template>
                        </el-table-column>
                        <el-table-column label="替代建议" min-width="160">
                          <template #default="scope">
                            {{ scope.row.substitutesText || '暂无替代建议' }}
                          </template>
                        </el-table-column>
                        <el-table-column label="说明" min-width="180">
                          <template #default="scope">
                            {{ scope.row.reason || (scope.row.alreadyOwned ? '可直接使用现有食材' : '建议按清单补充') }}
                          </template>
                        </el-table-column>
                      </el-table>
                      <div class="analysis-mobile-list">
                        <article v-for="item in ingredientAnalysisRows" :key="`${item.name}-${item.amount}`" class="analysis-mobile-card">
                          <div class="analysis-mobile-heading">
                            <strong>{{ item.name }}</strong>
                            <span>{{ item.amount }}</span>
                          </div>
                          <div class="analysis-mobile-meta">
                            <span class="ingredient-state" :class="item.alreadyOwned ? 'owned' : 'missing'">
                              {{ item.alreadyOwned ? '已有' : '缺失' }}
                            </span>
                            <span>{{ item.reason || (item.alreadyOwned ? '可直接使用现有食材' : '建议按清单补充') }}</span>
                          </div>
                          <p v-if="item.substitutesText">替代建议：{{ item.substitutesText }}</p>
                        </article>
                      </div>
                    </article>

                    <article v-if="shoppingList.length" class="detail-card detail-card-wide shopping-detail-card">
                      <div class="detail-card-head">
                        <h4>采购清单</h4>
                        <span>可勾选跟踪</span>
                      </div>
                      <ShoppingChecklistTable
                        :items="shoppingList"
                        compact
                        :overrides="shoppingCheckOverrides"
                        :saving-key="shoppingCheckSavingKey"
                        source-type="RECIPE"
                        :source-id="savedRecipeId"
                        :stock-in-key="stockInKey"
                        @status-change="toggleShoppingItem"
                        @purchase-search="preparePlatformSearch"
                        @stock-in="handleStockIn"
                      />
                    </article>
                  </div>
                  <el-empty v-if="!recipe.ingredients?.length && !ingredientAnalysisRows.length && !shoppingList.length" description="暂无食材信息" :image-size="56" />
                </section>

                <section v-if="detailSection === 'steps'" id="recipe-section-steps" class="recipe-section" aria-labelledby="recipe-steps-title" role="tabpanel">
                  <div class="section-heading">
                    <div>
                      <span class="section-index">03</span>
                      <h3 id="recipe-steps-title">烹饪过程</h3>
                    </div>
                    <button
                      class="detail-close-button"
                      type="button"
                      aria-label="关闭菜谱详情"
                      title="关闭菜谱详情"
                      @click="closeDetailView"
                    >
                      <X :size="19" aria-hidden="true" />
                    </button>
                  </div>
                  <ol v-if="recipe.steps?.length" class="full-step-list">
                    <li v-for="(step, index) in recipe.steps" :key="step.order || `${step.title}-${index}`" class="full-step-item">
                      <span class="full-step-index">{{ step.order || index + 1 }}</span>
                      <div class="full-step-copy">
                        <div class="full-step-head">
                          <h4>{{ step.title || `步骤 ${step.order || index + 1}` }}</h4>
                          <span v-if="step.durationMinutes">预计 {{ step.durationMinutes }} 分钟</span>
                        </div>
                        <p>{{ step.description || '请按当前步骤完成烹饪。' }}</p>
                        <p v-if="step.tip || step.note" class="full-step-note">
                          注意：{{ step.tip || step.note }}
                        </p>
                      </div>
                    </li>
                  </ol>
                  <div v-if="false && recipe.tips?.length" class="tips-card">
                    <div class="detail-card-head">
                      <h4>烹饪建议</h4>
                      <span>操作提醒</span>
                    </div>
                    <ul class="tip-list">
                      <li v-for="tip in recipe.tips" :key="tip">{{ tip }}</li>
                    </ul>
                  </div>
                  <el-empty v-if="!recipe.steps?.length" description="暂无可执行的烹饪步骤" :image-size="56" />
                </section>

                <section v-if="detailSection === 'more'" id="recipe-section-more" class="recipe-section" aria-labelledby="recipe-more-title" role="tabpanel">
                  <div class="section-heading">
                    <div>
                      <span class="section-index">04</span>
                      <h3 id="recipe-more-title">更多信息</h3>
                    </div>
                    <button
                      class="detail-close-button"
                      type="button"
                      aria-label="关闭菜谱详情"
                      title="关闭菜谱详情"
                      @click="closeDetailView"
                    >
                      <X :size="19" aria-hidden="true" />
                    </button>
                  </div>
                  <div v-if="recipe.tips?.length" class="tips-card">
                    <div class="detail-card-head">
                      <h4>烹饪建议</h4>
                      <span>操作提醒</span>
                    </div>
                    <ul class="tip-list">
                      <li v-for="tip in recipe.tips" :key="tip">{{ tip }}</li>
                    </ul>
                  </div>
                  <CookingVideoSearch
                    :recipe-title="recipe.title"
                    :keywords="videoKeywords"
                    :recipe-ready="recipeComplete"
                  />
                </section>
              </div>

              <div v-if="false" class="recipe-pages" aria-label="菜谱详情分页">
                <div class="page-toolbar">
                  <button
                    class="page-arrow"
                    type="button"
                    aria-label="上一页"
                    :disabled="recipePages.length <= 1"
                    @click="previousRecipePage"
                  >
                    <ChevronLeft :size="19" aria-hidden="true" />
                  </button>

                  <div class="page-tabs" role="tablist" aria-label="菜谱页面">
                    <button
                      v-for="(page, index) in recipePages"
                      :key="page.key"
                      class="page-tab"
                      :class="{ active: index === activeRecipePageIndex }"
                      type="button"
                      role="tab"
                      :aria-selected="index === activeRecipePageIndex"
                      @click="currentRecipePage = index"
                    >
                      {{ page.label }}
                    </button>
                  </div>

                  <button
                    class="page-arrow"
                    type="button"
                    aria-label="下一页"
                    :disabled="recipePages.length <= 1"
                    @click="nextRecipePage"
                  >
                    <ChevronRight :size="19" aria-hidden="true" />
                  </button>
                </div>

                <section
                  v-if="activeRecipePage"
                  class="recipe-page-window"
                  :aria-labelledby="`recipe-page-${activeRecipePage.key}`"
                >
                  <div class="window-head">
                    <h3 :id="`recipe-page-${activeRecipePage.key}`">{{ activeRecipePage.label }}</h3>
                    <span>{{ activeRecipePageIndex + 1 }} / {{ recipePages.length || 1 }}</span>
                  </div>

                  <el-table v-if="activeRecipePage.key === 'ingredients'" :data="recipe.ingredients" size="large">
                    <el-table-column prop="name" label="食材" min-width="120" />
                    <el-table-column prop="amount" label="用量" min-width="120" />
                  </el-table>

                  <el-table
                    v-else-if="activeRecipePage.key === 'analysis'"
                    :data="ingredientAnalysisRows"
                    size="large"
                  >
                    <el-table-column prop="name" label="食材" min-width="100" />
                    <el-table-column prop="amount" label="用量" min-width="88" />
                    <el-table-column label="状态" min-width="82">
                      <template #default="scope">
                        <span class="ingredient-state" :class="scope.row.alreadyOwned ? 'owned' : 'missing'">
                          {{ scope.row.alreadyOwned ? '已有' : '缺失' }}
                        </span>
                      </template>
                    </el-table-column>
                    <el-table-column label="替代建议" min-width="160">
                      <template #default="scope">
                        {{ scope.row.substitutesText || '暂无替代建议' }}
                      </template>
                    </el-table-column>
                    <el-table-column label="说明" min-width="180">
                      <template #default="scope">
                        {{ scope.row.reason || (scope.row.alreadyOwned ? '可直接使用现有食材' : '建议按清单补充') }}
                      </template>
                    </el-table-column>
                  </el-table>

                  <ShoppingChecklistTable
                    v-else-if="activeRecipePage.key === 'shopping'"
                    :items="shoppingList"
                    :overrides="shoppingCheckOverrides"
                    :saving-key="shoppingCheckSavingKey"
                    source-type="RECIPE"
                    :source-id="savedRecipeId"
                    :stock-in-key="stockInKey"
                    @status-change="toggleShoppingItem"
                    @purchase-search="preparePlatformSearch"
                    @stock-in="handleStockIn"
                  />

                  <div v-else-if="activeRecipePage.key === 'explanation'" class="explanation-grid">
                    <article v-for="item in explanationItems" :key="item.key" class="explanation-item">
                      <component :is="item.icon" :size="18" aria-hidden="true" />
                      <div>
                        <h4>{{ item.label }}</h4>
                        <p>{{ item.content }}</p>
                      </div>
                    </article>
                  </div>

                  <ol v-else-if="activeRecipePage.key === 'steps'" class="step-list">
                    <li v-for="step in recipe.steps" :key="step.order || step.title">
                      <strong>{{ step.title }}</strong>
                      <span v-if="step.durationMinutes">约 {{ step.durationMinutes }} 分钟</span>
                      <p>{{ step.description }}</p>
                    </li>
                  </ol>

                  <ul v-else-if="activeRecipePage.key === 'tips'" class="tip-list">
                    <li v-for="tip in recipe.tips" :key="tip">{{ tip }}</li>
                  </ul>

                  <div v-else-if="activeRecipePage.key === 'videos'" class="video-keywords">
                    <a
                      v-for="keyword in videoKeywords"
                      :key="keyword"
                      :href="buildBilibiliSearchLink(keyword)"
                      target="_blank"
                      rel="noopener noreferrer"
                    >
                      <Video :size="15" aria-hidden="true" />
                      {{ keyword }}
                      <ExternalLink :size="13" aria-hidden="true" />
                    </a>
                  </div>
                </section>

                <el-empty v-else description="暂无菜谱详情" />
              </div>
            </div>

            <div v-else class="next-steps">
              <div class="window-head">
                <h3>推荐生成队列</h3>
                <span>就绪</span>
              </div>
              <el-table :data="recommendationRows" size="large">
                <el-table-column prop="name" label="关注点" min-width="140" />
                <el-table-column prop="value" label="信号" min-width="140" />
              </el-table>
            </div>
          </div>

          <section v-if="hasSearch && !detailViewOpen && recipe && usePantry" class="pantry-readiness-card" aria-labelledby="pantry-readiness-title">
            <div class="pantry-readiness-head">
              <div>
                <p class="eyebrow">开做前准备</p>
                <h3 id="pantry-readiness-title">先确认食材，再开始烹饪</h3>
              </div>
              <span class="pantry-readiness-status" role="status">{{ pantryReadinessStatusText }}</span>
            </div>

            <div v-if="pantryReadinessLoading" class="pantry-readiness-skeleton" aria-live="polite">
              <span v-for="index in 6" :key="index" class="pantry-readiness-skeleton-block"></span>
            </div>

            <template v-else>
              <div class="pantry-readiness-metrics">
                <div class="pantry-readiness-metric pantry-readiness-metric-primary">
                  <span>食材准备度</span>
                  <strong>{{ pantryReadinessHasData ? `${pantryReadiness.readinessPercent}%` : '—' }}</strong>
                  <div class="pantry-readiness-progress" role="progressbar" :aria-valuenow="pantryReadinessHasData ? pantryReadiness.readinessPercent : undefined" aria-valuemin="0" aria-valuemax="100">
                    <i :style="{ width: `${pantryReadinessHasData ? pantryReadiness.readinessPercent : 0}%` }"></i>
                  </div>
                </div>
                <div class="pantry-readiness-metric">
                  <span>已准备项</span>
                  <strong>{{ pantryReadinessHasData ? `${pantryReadiness.readyCount} / ${pantryReadiness.itemCount}` : '—' }}</strong>
                </div>
                <div class="pantry-readiness-metric">
                  <span>缺少食材</span>
                  <strong>{{ pantryReadinessHasData ? pantryReadiness.shortageCount : '—' }}</strong>
                </div>
                <div class="pantry-readiness-metric">
                  <span>临期食材</span>
                  <strong>{{ pantryReadinessHasData ? pantryReadiness.expiringSoonCount : '—' }}</strong>
                </div>
                <div class="pantry-readiness-metric">
                  <span>预计烹饪时间</span>
                  <strong>{{ pantryReadinessTimeLabel }}</strong>
                </div>
                <div class="pantry-readiness-metric">
                  <span>烹饪难度</span>
                  <strong>{{ pantryReadinessDifficultyLabel }}</strong>
                </div>
              </div>

              <div class="pantry-readiness-footer">
                <div class="pantry-readiness-missing" aria-live="polite">
                  <span class="pantry-readiness-missing-label">待准备</span>
                  <span v-if="!auth.isUser" class="pantry-readiness-ready-copy">登录后匹配库存</span>
                  <span v-else-if="pantryReadinessError" class="pantry-readiness-ready-copy">{{ pantryReadinessErrorText || '库存匹配失败，请重试' }}</span>
                  <span v-else-if="!pantryItems.length" class="pantry-readiness-ready-copy">暂无库存记录</span>
                  <span v-else-if="!pantryReadinessMissingItems.length" class="pantry-readiness-ready-copy">食材已齐全</span>
                  <span v-for="item in pantryReadinessMissingPreview" :key="item.ingredientName" class="pantry-readiness-tag">
                    {{ item.ingredientName }}
                  </span>
                  <span v-if="pantryReadinessMissingOverflow" class="pantry-readiness-overflow">+{{ pantryReadinessMissingOverflow }}</span>
                </div>
                <div class="pantry-readiness-actions">
                  <el-button plain :disabled="pantryReadinessLoading || !recipe" @click="viewPreparedIngredients($event)">
                    查看准备食材
                  </el-button>
                  <el-button
                    plain
                    :loading="pantryReadinessBulkSaving"
                    :disabled="pantryReadinessLoading || !pantryReadinessMissingItems.length || !auth.isUser"
                    @click="addMissingToShoppingList"
                  >
                    加入采购清单
                  </el-button>
                </div>
              </div>
              <p v-if="pantryReadinessError" class="pantry-readiness-error" role="alert">
                {{ pantryReadinessErrorText || '库存匹配暂时失败' }}，<button type="button" @click="retryPantryReadiness">重试</button>
              </p>
              <p v-else class="pantry-readiness-disclaimer">库存状态仅用于开做前参考，不会自动修改菜单或库存。</p>
            </template>
          </section>

          <nav v-if="hasSearch && !detailViewOpen" class="recipe-entry-nav" aria-label="菜谱详情入口">
            <button
              v-for="section in recipeSections"
              :key="section.key"
              class="recipe-entry-button"
              type="button"
              :disabled="!canOpenDetail(section.key)"
              @click="openDetail(section.key, $event)"
            >
              <span class="recipe-entry-index">{{ section.index }}</span>
              <span class="recipe-entry-copy">
                <strong>{{ section.label }}</strong>
                <small>{{ detailEntryDescription(section.key) }}</small>
              </span>
              <ChevronRight :size="17" aria-hidden="true" />
            </button>
          </nav>

          <nav v-if="detailViewOpen" class="recipe-detail-bottom-nav" role="tablist" aria-label="菜谱详情导航">
            <button
              v-for="section in recipeSections"
              :key="section.key"
              class="recipe-detail-bottom-link"
              :class="{ active: detailSection === section.key }"
              type="button"
              role="tab"
              :id="`recipe-detail-tab-${section.key}`"
              :aria-selected="detailSection === section.key"
              :aria-current="detailSection === section.key ? 'page' : undefined"
              :aria-controls="`recipe-section-${section.key}`"
              :disabled="!canOpenDetail(section.key)"
              @click="selectDetailSection(section.key)"
              @keydown.left.prevent="moveDetailTab(-1)"
              @keydown.right.prevent="moveDetailTab(1)"
            >
              <span>{{ section.index }}</span>
              {{ section.label }}
            </button>
          </nav>
        </section>
      </div>
    </section>
  </main>

  <DietPreferenceDialog
    v-if="auth.isUser"
    v-model="preferenceDialogVisible"
    :preference="dietPreference"
    :saving="preferenceSaving"
    @save="persistDietPreference"
  />
  <CameraIngredientCapture v-model="cameraCaptureVisible" @captured="handleCameraCaptured" />
  <CookingModeDialog
    v-if="recipe"
    v-model="cookingModeVisible"
    :recipe="recipe"
    :storage-key="cookingStorageKey"
    :recipe-id="savedRecipeId"
    :search-log-id="recipe?.searchLogId"
  />
  <StockInDialog
    v-model="stockInDialogVisible"
    :item="stockInItem"
    :loading="Boolean(stockInKey)"
    @confirm="handleStockInConfirm"
  />
  <FinishedDishReviewDialog
    v-if="recipe"
    v-model="finishedDishReviewVisible"
    :recipe="recipe"
    :recipe-id="savedRecipeId"
  />
</template>

<script setup>
import { computed, markRaw, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft,
  Camera,
  Bookmark,
  ChefHat,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  ExternalLink,
  Flame,
  HeartPulse,
  ImagePlus,
  Network,
  Play,
  RefreshCw,
  RotateCcw,
  ScanSearch,
  SlidersHorizontal,
  Sparkles,
  TriangleAlert,
  Video,
  X
} from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import {
  clearRecommendationReaction,
  generateRecipeStream,
  getRecommendationFeedback,
  recognizeIngredients,
  saveRecipe,
  setRecommendationReaction
} from '../api/recipes'
import { searchCookingVideos } from '../api/videos'
import { getRecentSearches } from '../api/searchHistory'
import { getDietPreference, saveDietPreference } from '../api/userPreferences'
import { getNutritionTarget } from '../api/nutritionTargets'
import { getPantryExpiryAlerts, getPantryItems, getPantryReadiness, stockInPantry } from '../api/pantry'
import { getShoppingItemChecks, saveShoppingItemCheck } from '../api/shoppingChecks'
import CameraIngredientCapture from '../components/CameraIngredientCapture.vue'
import CookingModeDialog from '../components/CookingModeDialog.vue'
import CookingVideoSearch from '../components/CookingVideoSearch.vue'
import { getPantryReadinessErrorMessage } from '../utils/pantryReadiness'
import DietPreferenceDialog from '../components/DietPreferenceDialog.vue'
import FinishedDishReviewDialog from '../components/FinishedDishReviewDialog.vue'
import RecentSearchPopover from '../components/RecentSearchPopover.vue'
import ShoppingChecklistTable from '../components/ShoppingChecklistTable.vue'
import NutritionEstimateCard from '../components/NutritionEstimateCard.vue'
import RecommendationFeedbackButtons from '../components/RecommendationFeedbackButtons.vue'
import StockInDialog from '../components/StockInDialog.vue'
import { useAuthStore } from '../stores/auth'
import {
  buildRecipeDietPreference,
  normalizeDietPreference,
  requiresDietPreferenceLoad,
  resolveGoalWithPreference,
  toSearchForm
} from '../utils/personalization'
import {
  nextRecommendationReaction,
  normalizeRecommendationFeedback
} from '../utils/recommendationFeedback'
import { normalizeCookingVideoItems } from '../utils/cookingVideos'
import {
  getRecipeVideoSearchKeyword,
  prioritizeRecipeRecommendations
} from '../utils/recipeVideoPriority'
import {
  buildPurchaseLinks,
  buildBilibiliSearchLink,
  buildShoppingList,
  copyIngredientName,
  filterVideoKeywords,
  normalizeShoppingStatus,
  parseIngredientNames,
  shoppingChecklistKey
} from '../utils/recipeEnhancements'
import {
  applyRecipeStreamEvent,
  createRecipeBatchDraft,
  createRecipeDraft,
  isRecipeReady,
  isRecipeResultPriority,
  RecipeStreamError,
  shouldSubmitIngredientsKey
} from '../utils/recipeStream'
import { emptyNutritionTarget, normalizeNutritionTarget } from '../utils/nutritionTarget'
import {
  mergeIngredientNames,
  validateIngredientImageFile
} from '../utils/ingredientRecognition'

const props = defineProps({
  embedded: { type: Boolean, default: false },
  initialSearch: { type: Object, default: null }
})
const emit = defineEmits(['open-feature'])

const ingredients = ref('')
const mealType = ref('any')
const goal = ref('balanced')
const goalManuallySelected = ref(false)
const searchMode = ref('text')
const cameraCaptureVisible = ref(false)
const imageInput = ref(null)
const selectedImageFile = ref(null)
const selectedImagePreview = ref('')
const recognizedIngredients = ref([])
const recognitionDescription = ref('')
const lastSearch = ref(null)
const recipe = ref(null)
const recommendationRecipes = ref([])
const recommendationBatch = ref(null)
const activeRecipeId = ref(null)
const generating = ref(false)
const regenerating = ref(false)
const generationStage = ref('idle')
const generationCompleted = ref(false)
const streamFailed = ref(false)
const streamErrorMessage = ref('')
const streamAbortController = ref(null)
const generationRequestId = ref(0)
const recipeScrollContainer = ref(null)
const isFollowingLatest = ref(true)
let recipeScrollParent = null
const usePantry = ref(false)
const useHealthNutrition = ref(false)
const recognizing = ref(false)
const savingRecipe = ref(false)
const savedRecipeId = ref(null)
const savedRecipeIds = ref({})
const feedbackReaction = ref(null)
const feedbackCooked = ref(false)
const feedbackLoading = ref(false)
const currentRecipePage = ref(0)
const editingConditions = ref(false)
const detailViewOpen = ref(false)
const detailSection = ref('overview')
const detailTriggerElement = ref(null)
const cookingModeVisible = ref(false)
const finishedDishReviewVisible = ref(false)
const dietPreference = ref(normalizeDietPreference())
const preferenceDialogVisible = ref(false)
const preferenceLoaded = ref(false)
const preferenceLoading = ref(false)
const preferenceSaving = ref(false)
const nutritionTarget = ref(emptyNutritionTarget())
const nutritionTargetLoading = ref(false)
const recentSearches = ref([])
const recentSearchLoading = ref(false)
const recentSearchLoaded = ref(false)
const recentSearchVisible = ref(false)
const pantryItems = ref([])
const pantryLoading = ref(false)
const pantryExpirySummary = ref(emptyPantryExpirySummary())
const pantryReadiness = ref(emptyPantryReadiness())
const pantryReadinessLoading = ref(false)
const pantryReadinessError = ref(false)
const pantryReadinessErrorText = ref('')
const pantryReadinessRequestId = ref(0)
const pantryReadinessBulkSaving = ref(false)
const shoppingCheckOverrides = ref({})
const shoppingCheckSavingKey = ref('')
const stockInKey = ref('')
const stockInDialogVisible = ref(false)
const stockInItem = ref(null)
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const pantryReferenceStorageKey = computed(() => (
  `ai_smart_recipe:use-pantry:${auth.isUser ? auth.displayName || 'account' : 'guest'}`
))
const healthNutritionReferenceStorageKey = computed(() => (
  `ai_smart_recipe:use-health-nutrition:${auth.isUser ? auth.displayName || 'account' : 'guest'}`
))
const showLatestContent = computed(() => Boolean(
  generating.value && recipeScrollContainer.value && !isFollowingLatest.value && !detailViewOpen.value
))

const PENDING_RECIPE_KEY = 'ai_smart_recipe_pending_save'

const regenerationOptions = [
  { value: 'simple', label: '更简单' },
  { value: 'light', label: '低油低卡' },
  { value: 'quick', label: '缩短时间' },
  { value: 'taste', label: '调整口味' }
]

const modeCards = [
  { value: 'text', label: '文字输入', description: '手动输入现有食材', icon: markRaw(ScanSearch) },
  { value: 'image', label: '图片识别', description: '上传图片识别食材', icon: markRaw(ImagePlus) },
  { value: 'camera', label: '拍照识别', description: '使用摄像头拍摄食材', icon: markRaw(Camera) }
]

const mealLabels = {
  any: '不限餐次',
  breakfast: '早餐',
  lunch: '午餐',
  dinner: '晚餐'
}

const goalLabels = {
  balanced: '营养均衡',
  protein: '高蛋白',
  light: '低热量',
  quick: '快速烹饪',
  fat_loss: '减脂',
  muscle_gain: '增肌',
  low_sugar: '控糖'
}

const modeLabels = {
  text: '文字输入',
  image: '图片识别',
  camera: '拍照识别'
}

const hasSearch = computed(() => Boolean(lastSearch.value))
const recommendationReadyCount = computed(() => recommendationRecipes.value
  .filter((item) => isRecipeReady(item)).length)
const recommendationModeLabel = computed(() => (
  recommendationBatch.value?.mode === 'MEAL_COMBO'
    ? '组合搭配'
    : '单食材多风格'
))
const recipeComplete = computed(() => Boolean(
  generationCompleted.value
  && !generating.value
  && (recommendationRecipes.value.length <= 1
    || recommendationReadyCount.value >= (recommendationBatch.value?.total || recommendationRecipes.value.length))
  && isRecipeReady(recipe.value)
))
const resultPriorityMode = computed(() => isRecipeResultPriority(lastSearch.value, editingConditions.value))
const showImageUpload = computed(() => searchMode.value === 'image')
const showCameraCapture = computed(() => searchMode.value === 'camera')
const cookingStorageKey = computed(() => {
  const userKey = auth.isUser ? auth.displayName || 'user' : 'guest'
  const recipeKey = recipe.value?.searchLogId || recipe.value?.title || 'draft'
  return `ai_smart_recipe:cooking:${encodeURIComponent(userKey)}:${encodeURIComponent(String(recipeKey))}`
})
const cleanIngredients = computed(() => {
  if (lastSearch.value?.useAiIngredientRecommendation) {
    return 'AI 自主推荐食材'
  }
  return lastSearch.value?.ingredients || '暂无'
})
const ownedIngredients = computed(() => [
  lastSearch.value?.useAiIngredientRecommendation
    ? ''
    : (lastSearch.value?.ingredients || ingredients.value),
  ...(usePantry.value ? pantryItems.value.map((item) => item.ingredientName) : [])
])
const pantryExpiryNotice = computed(() => {
  const summary = pantryExpirySummary.value
  return summary.expiredItems.length + summary.expiringSoonItems.length > 0
})
const pantryExpiryNoticeText = computed(() => {
  const summary = pantryExpirySummary.value
  const messages = []
  if (summary.expiredItems.length) {
    messages.push(`${summary.expiredItems.length} 种食材已过期`)
  }
  if (summary.expiringSoonItems.length) {
    messages.push(`${summary.expiringSoonItems.length} 种食材将在 ${summary.warningDays} 天内到期`)
  }
  return messages.join('，')
})
const mealTypeLabel = computed(() => mealLabels[lastSearch.value?.mealType || mealType.value] || mealLabels.any)
const goalLabel = computed(() => goalLabels[lastSearch.value?.goal || goal.value] || goalLabels.balanced)
const searchModeLabel = computed(() => modeLabels[lastSearch.value?.searchMode || searchMode.value])
const resultTitle = computed(() => {
  if (recipe.value?.title) {
    return recipe.value.title
  }
  if (streamFailed.value) {
    return '生成未完成'
  }
  return hasSearch.value ? '菜谱匹配简报' : '等待搜索'
})
const recommendationRows = computed(() => [
  { name: '营养目标', value: goalLabel.value },
  { name: '用餐时间', value: mealTypeLabel.value },
  { name: '输入方式', value: modeLabels[lastSearch.value?.searchMode] || modeLabels.text }
])
const shoppingList = computed(() => buildRecipeShoppingList(
  recipe.value,
  ownedIngredients.value
))
const pantryReadinessItems = computed(() => (
  Array.isArray(pantryReadiness.value?.items) ? pantryReadiness.value.items : []
))
const pantryReadinessMissingItems = computed(() => pantryReadinessItems.value.filter((item) => item?.shortage))
const pantryReadinessMissingPreview = computed(() => pantryReadinessMissingItems.value.slice(0, 3))
const pantryReadinessMissingOverflow = computed(() => Math.max(0, pantryReadinessMissingItems.value.length - 3))
const pantryReadinessHasData = computed(() => (
  auth.isUser && !pantryReadinessError.value && pantryReadiness.value.itemCount > 0
))
const pantryReadinessStatusText = computed(() => {
  if (!auth.isUser) return '登录后匹配库存'
  if (pantryReadinessLoading.value) return '正在匹配库存'
  if (pantryReadinessError.value) return '库存匹配失败'
  if (!pantryItems.value.length) return '暂无库存记录'
  return '已匹配库存'
})
const pantryReadinessTimeLabel = computed(() => {
  const source = recipe.value || {}
  const explicitMinutes = [source.totalDurationMinutes, source.estimatedMinutes, source.totalCookingMinutes]
    .find((value) => Number.isFinite(Number(value)) && Number(value) > 0)
  if (explicitMinutes) return `${Math.round(Number(explicitMinutes))} 分钟`
  const stepMinutes = (Array.isArray(source.steps) ? source.steps : [])
    .map((step) => Number(step?.durationMinutes))
    .filter((value) => Number.isFinite(value) && value > 0)
    .reduce((total, value) => total + value, 0)
  return stepMinutes > 0 ? `${Math.round(stepMinutes)} 分钟` : '待估算'
})
const pantryReadinessDifficultyLabel = computed(() => (
  recipe.value?.difficulty || recipe.value?.cookingDifficulty || '待评估'
))
const ingredientAnalysisRows = computed(() => shoppingList.value.map((item) => {
  const missing = findMissingIngredient(recipe.value?.missingIngredients, item.name)
  return {
    ...item,
    substitutesText: item.alreadyOwned ? '' : formatSubstitutes(missing?.substitutes),
    reason: item.alreadyOwned ? '可直接使用现有食材' : missing?.reason || ''
  }
}))
const explanationItems = computed(() => {
  const explanation = recipe.value?.explanation || {}
  return [
    { key: 'pairingLogic', label: '搭配逻辑', content: explanation.pairingLogic, icon: markRaw(Network) },
    { key: 'nutrition', label: '营养说明', content: explanation.nutrition, icon: markRaw(HeartPulse) },
    { key: 'cookingPrinciple', label: '烹饪原理', content: explanation.cookingPrinciple, icon: markRaw(Flame) }
  ].filter((item) => item.content)
})
const videoKeywords = computed(() => filterVideoKeywords(recipe.value?.videoKeywords))
const recipeSections = [
  { key: 'overview', index: '01', label: '概览' },
  { key: 'ingredients', index: '02', label: '准备食材' },
  { key: 'steps', index: '03', label: '烹饪过程' },
  { key: 'more', index: '04', label: '更多信息' }
]
const detailSectionLabel = computed(() => (
  recipeSections.find((section) => section.key === detailSection.value)?.label || '菜谱详情'
))
const detailDescriptions = {
  overview: 'AI 解释与营养摘要',
  ingredients: '食材与采购清单',
  steps: '完整烹饪步骤',
  more: '建议与相关烹饪视频'
}
const recipePages = computed(() => {
  if (!recipe.value) {
    return []
  }

  return [
    recipe.value.ingredients?.length ? { key: 'ingredients', label: '所需食材' } : null,
    ingredientAnalysisRows.value.length ? { key: 'analysis', label: '食材分析' } : null,
    shoppingList.value.length ? { key: 'shopping', label: '采购清单' } : null,
    explanationItems.value.length ? { key: 'explanation', label: 'AI 解释' } : null,
    recipe.value.steps?.length ? { key: 'steps', label: '烹饪步骤' } : null,
    recipe.value.tips?.length ? { key: 'tips', label: '烹饪建议' } : null,
    videoKeywords.value.length ? { key: 'videos', label: '相关烹饪视频' } : null
  ].filter(Boolean)
})
const activeRecipePageIndex = computed(() => {
  if (!recipePages.value.length) {
    return 0
  }
  return Math.min(currentRecipePage.value, recipePages.value.length - 1)
})
const activeRecipePage = computed(() => recipePages.value[activeRecipePageIndex.value])
const generationStageLabel = computed(() => ({
  idle: '准备生成',
  preparing: '正在准备',
  generating: '正在连接 AI',
  retrying: '正在更换重复做法',
  receiving: '正在生成菜谱',
  parsing: '正在整理内容',
  saving: '正在保存记录',
  'matching-videos': '正在匹配 B 站参考',
  complete: '菜谱已生成',
  error: '生成未完成'
}[generationStage.value] || '正在处理'))

function detailEntryDescription(sectionKey) {
  if (generating.value) {
    return '生成中，完成后可查看'
  }
  return detailDescriptions[sectionKey] || '查看菜谱详情'
}

function canOpenDetail(sectionKey) {
  return recipeComplete.value
}

function selectDetailSection(sectionKey) {
  if (recipeSections.some((section) => section.key === sectionKey) && canOpenDetail(sectionKey)) {
    detailSection.value = sectionKey
    if (detailViewOpen.value && window.history.state?.recipeDetail) {
      window.history.replaceState(
        { ...(window.history.state || {}), recipeDetailSection: sectionKey },
        '',
        `${window.location.pathname}${window.location.search}#recipe-${sectionKey}`
      )
    }
  }
}

function moveDetailTab(delta) {
  const currentIndex = recipeSections.findIndex((section) => section.key === detailSection.value)
  const nextIndex = (currentIndex + delta + recipeSections.length) % recipeSections.length
  let nextSection = recipeSections[nextIndex]
  for (let offset = 0; offset < recipeSections.length && !canOpenDetail(nextSection.key); offset += 1) {
    nextSection = recipeSections[(nextIndex + (delta < 0 ? -offset : offset) + recipeSections.length) % recipeSections.length]
  }
  if (!canOpenDetail(nextSection.key)) {
    return
  }
  detailSection.value = nextSection.key
  window.setTimeout(() => document.getElementById(`recipe-detail-tab-${nextSection.key}`)?.focus(), 0)
}

function openDetail(sectionKey, event) {
  if (!canOpenDetail(sectionKey)) {
    return
  }
  detailTriggerElement.value = event?.currentTarget || null
  detailSection.value = sectionKey
  detailViewOpen.value = true
  const hash = `#recipe-${sectionKey}`
  window.history.pushState(
    { ...(window.history.state || {}), recipeDetail: true, recipeDetailSection: sectionKey },
    '',
    `${window.location.pathname}${window.location.search}${hash}`
  )
}

function focusDetailTrigger() {
  const trigger = detailTriggerElement.value
  if (trigger?.isConnected) {
    window.requestAnimationFrame(() => trigger.focus())
  }
}

function closeDetailView({ fromHistory = false } = {}) {
  if (!detailViewOpen.value) {
    return
  }
  detailViewOpen.value = false
  detailSection.value = 'overview'
  if (!fromHistory && window.history.state?.recipeDetail) {
    window.history.back()
  } else if (window.location.hash.startsWith('#recipe-')) {
    window.history.replaceState(
      { ...(window.history.state || {}), recipeDetail: false },
      '',
      `${window.location.pathname}${window.location.search}`
    )
  }
  focusDetailTrigger()
}

function handlePopState() {
  if (detailViewOpen.value) {
    closeDetailView({ fromHistory: true })
  }
}

function handleSecondaryAction(command) {
  if (command === 'review') {
    openFinishedDishReview()
  }
}

onBeforeUnmount(() => {
  cancelRecipeStream()
  cameraCaptureVisible.value = false
  cookingModeVisible.value = false
  finishedDishReviewVisible.value = false
  window.removeEventListener('popstate', handlePopState)
  removeRecipeScrollParent()
  revokeImagePreview()
})

onMounted(() => {
  window.addEventListener('popstate', handlePopState)
  syncRecipeScrollParent(recipeScrollContainer.value)
  restoreReferenceChoices()
  if (!applyInitialSearch() && !applyRouteIngredient()) {
    restorePendingRecipe()
  }
  if (auth.isUser) {
    loadDietPreference()
    loadNutritionTarget()
    if (usePantry.value) loadPantryItems()
  }
})

watch(recipeScrollContainer, syncRecipeScrollParent, { flush: 'post' })

watch(() => [auth.token, auth.role], () => {
  cancelRecipeStream()
  clearPersonalizationState()
  clearPantryState()
  restoreReferenceChoices()
  if (auth.isUser) {
    loadDietPreference()
    loadNutritionTarget()
    if (usePantry.value) loadPantryItems()
  }
})

watch(recipe, (currentRecipe, previousRecipe) => {
  if (!currentRecipe) {
    detailSection.value = 'overview'
    if (detailViewOpen.value) {
      closeDetailView({ fromHistory: true })
    }
  }
})

function applyInitialSearch() {
  const form = toSearchForm(props.initialSearch)
  if (!form.ingredients) return false
  ingredients.value = form.ingredients
  mealType.value = form.mealType
  goal.value = form.goal
  goalManuallySelected.value = true
  searchMode.value = ['image', 'camera'].includes(props.initialSearch?.searchMode)
    ? props.initialSearch.searchMode
    : 'text'
  return true
}

function applyRouteIngredient() {
  const routeIngredient = Array.isArray(route.query.ingredients)
    ? route.query.ingredients[0]
    : route.query.ingredients
  if (!routeIngredient?.trim()) {
    return false
  }
  ingredients.value = routeIngredient.trim()
  searchMode.value = 'text'
  return true
}

async function runSearch() {
  if (generating.value || recognizing.value) {
    return
  }
  if (requiresDietPreferenceLoad(auth.isUser, preferenceLoaded.value)) {
    const loaded = await loadDietPreference()
    if (!loaded) {
      ElMessage.warning('饮食偏好加载失败，请重试后再生成')
      return
    }
  }

  const normalizedIngredients = parseIngredientNames(ingredients.value).join(', ')

  if (!normalizedIngredients) {
    ElMessage.warning('请至少输入一种食材')
    return
  }

  const request = {
    ingredients: normalizedIngredients,
    mealType: mealType.value,
    goal: goal.value,
    searchMode: searchMode.value,
    dietPreference: buildRecipeDietPreference(dietPreference.value),
    usePantry: usePantry.value,
    useHealthNutrition: useHealthNutrition.value
  }
  await runRecipeGeneration(request, '菜谱推荐已生成')
}

function resetRecommendationBatch() {
  recommendationBatch.value = null
  recommendationRecipes.value = createRecipeBatchDraft(3)
  activeRecipeId.value = recommendationRecipes.value[0]?.id || null
  recipe.value = recommendationRecipes.value[0] || createRecipeDraft()
  savedRecipeIds.value = {}
  savedRecipeId.value = null
}

function replaceRecommendationRecipe(recipeId, nextRecipe) {
  const index = recommendationRecipes.value.findIndex((item) => item.id === recipeId)
  if (index < 0) return
  const nextItems = [...recommendationRecipes.value]
  nextItems[index] = nextRecipe
  recommendationRecipes.value = nextItems
  if (activeRecipeId.value === recipeId) {
    recipe.value = nextRecipe
  }
}

function handleBatchStart(data) {
  const parsedTotal = Number(data?.total)
  const total = Number.isInteger(parsedTotal) && parsedTotal >= 3 ? parsedTotal : 3
  const batchId = data?.batchId || `batch-${Date.now()}`
  recommendationBatch.value = {
    batchId,
    mode: data?.mode || 'STYLE_VARIANTS',
    total
  }
  recommendationRecipes.value = createRecipeBatchDraft(total).map((item) => ({
    ...item,
    id: `${batchId}-recipe-${item.index + 1}`
  }))
  activeRecipeId.value = recommendationRecipes.value[0]?.id || null
  recipe.value = recommendationRecipes.value[0] || createRecipeDraft()
}

function handleRecipeStart(data) {
  const current = recommendationRecipes.value.find((item) => item.id === data?.recipeId)
  if (!current) return
  replaceRecommendationRecipe(data.recipeId, {
    ...current,
    label: data.label || current.label,
    index: Number.isFinite(Number(data.index)) ? Number(data.index) : current.index
  })
}

function handleRecipeStreamEvent(event) {
  const recipeId = event?.data?.recipeId
  if (!recipeId) return
  const current = recommendationRecipes.value.find((item) => item.id === recipeId)
  if (!current) return
  replaceRecommendationRecipe(recipeId, applyRecipeStreamEvent(current, event))
}

function handleRecipeComplete(data) {
  if (!data?.recipeId || !data.recipe) return
  const current = recommendationRecipes.value.find((item) => item.id === data.recipeId)
  if (!current) return
  replaceRecommendationRecipe(data.recipeId, {
    ...current,
    ...data.recipe,
    id: data.recipeId,
    index: Number.isFinite(Number(data.index)) ? Number(data.index) : current.index
  })
}

function handleBatchComplete(data) {
  if (!data || !Array.isArray(data.recipes)) return
  recommendationBatch.value = {
    ...(recommendationBatch.value || {}),
    batchId: data.batchId || recommendationBatch.value?.batchId,
    mode: data.mode || recommendationBatch.value?.mode || 'STYLE_VARIANTS',
    total: data.total || data.recipes.length
  }
  data.recipes.forEach((item, index) => {
    const recipeId = recommendationRecipes.value[index]?.id || `${data.batchId}-recipe-${index + 1}`
    replaceRecommendationRecipe(recipeId, {
      ...item,
      id: recipeId,
      index
    })
  })
  const active = recommendationRecipes.value.find((item) => item.id === activeRecipeId.value)
  if (active) recipe.value = active
}

async function prioritizeRecommendationsByBilibili(requestId) {
  if (recommendationBatch.value?.mode !== 'MEAL_COMBO') {
    return
  }

  const candidates = recommendationRecipes.value.filter((item) => isRecipeReady(item))
  if (!candidates.length) {
    return
  }

  const responses = await Promise.allSettled(candidates.map((item) => {
    const keyword = getRecipeVideoSearchKeyword(item)
    if (!keyword) {
      return Promise.resolve(null)
    }
    return searchCookingVideos({
      recipeTitle: item.title,
      keyword,
      page: 1,
      limit: 1
    })
  }))

  if (requestId !== generationRequestId.value) {
    return
  }

  const availability = {}
  responses.forEach((result, index) => {
    if (result.status !== 'fulfilled') return
    const payload = result.value?.data?.data || {}
    if (!payload.degraded && normalizeCookingVideoItems(payload.items).length) {
      availability[candidates[index].id] = true
    }
  })

  if (!Object.keys(availability).length) {
    return
  }

  const activeId = activeRecipeId.value
  recommendationRecipes.value = prioritizeRecipeRecommendations(
    recommendationRecipes.value,
    availability
  )
  recipe.value = recommendationRecipes.value.find((item) => item.id === activeId)
    || recommendationRecipes.value[0]
}

function selectRecommendationRecipe(recipeId) {
  const selected = recommendationRecipes.value.find((item) => item.id === recipeId)
  if (!selected || selected.id === activeRecipeId.value) return
  activeRecipeId.value = selected.id
  recipe.value = selected
  savedRecipeId.value = savedRecipeIds.value[selected.id] || null
  resetRecommendationFeedback()
  detailSection.value = 'overview'
  void loadRecommendationFeedback(selected.searchLogId)
  void loadShoppingChecks()
  void loadPantryReadiness()
}

function moveRecommendationSelection(delta) {
  const currentIndex = recommendationRecipes.value.findIndex((item) => item.id === activeRecipeId.value)
  if (currentIndex < 0 || recommendationRecipes.value.length < 2) return
  const nextIndex = (currentIndex + delta + recommendationRecipes.value.length) % recommendationRecipes.value.length
  const next = recommendationRecipes.value[nextIndex]
  selectRecommendationRecipe(next.id)
  window.setTimeout(() => document.getElementById(`recommendation-card-${next.id}`)?.focus(), 0)
}

function recipeCardStatus(item) {
  if (isRecipeReady(item)) return '已生成'
  if (item.title || item.ingredients?.length || item.steps?.length) return '正在生成'
  return '等待生成'
}

async function runRecipeGeneration(request, successMessage) {
  cancelRecipeStream()
  const requestId = generationRequestId.value + 1
  generationRequestId.value = requestId
  const controller = new AbortController()
  streamAbortController.value = controller

  if (detailViewOpen.value) {
    closeDetailView({ fromHistory: true })
  }
  lastSearch.value = request
  editingConditions.value = false
  detailSection.value = 'overview'
  resetRecommendationBatch()
  resetRecommendationFeedback()
  currentRecipePage.value = 0
  isFollowingLatest.value = true
  shoppingCheckOverrides.value = {}
  generating.value = true
  generationCompleted.value = false
  generationStage.value = 'preparing'
  streamFailed.value = false
  streamErrorMessage.value = ''

  try {
    await generateRecipeStream(request, {
      signal: controller.signal,
      onEvent: (event) => {
        if (requestId !== generationRequestId.value) {
          return
        }
        if (event.event === 'status') {
          generationStage.value = event.data?.stage || generationStage.value
          return
        }
        if (event.event === 'error') {
          throw new RecipeStreamError(event.data?.message || '菜谱生成失败，请稍后重试')
        }
        if (event.event === 'batch-start') {
          handleBatchStart(event.data)
          return
        }
        if (event.event === 'recipe-start') {
          handleRecipeStart(event.data)
          return
        }
        if (['overview', 'ingredients', 'steps', 'details'].includes(event.event)) {
          handleRecipeStreamEvent(event)
          queueRecipeScrollToLatest()
        }
        if (event.event === 'recipe-complete') {
          handleRecipeComplete(event.data)
          queueRecipeScrollToLatest()
        }
        if (event.event === 'complete') {
          handleBatchComplete(event.data)
        }
      }
    })

    if (requestId !== generationRequestId.value) {
      return false
    }
    if (!recommendationBatch.value
      || recommendationReadyCount.value < (recommendationBatch.value.total || recommendationRecipes.value.length)) {
      throw new RecipeStreamError('AI 返回的菜谱内容不完整，请点击重试')
    }
    generationStage.value = recommendationBatch.value.mode === 'MEAL_COMBO'
      ? 'matching-videos'
      : 'complete'
    await prioritizeRecommendationsByBilibili(requestId)
    generationCompleted.value = true
    generationStage.value = 'complete'
    await loadRecommendationFeedback(recipe.value?.searchLogId)
    recentSearchLoaded.value = false
    await loadShoppingChecks()
    await loadPantryReadiness()
    if (recipe.value?.pantryIncompatible) {
      ElMessage.warning('当前库存食材不可参考，本次已按原输入食材生成')
    } else if (recipe.value?.pantryFallback) {
      ElMessage.warning(lastSearch.value?.useAiIngredientRecommendation
        ? '库存读取失败，本次仍由 AI 自主推荐食材'
        : '库存读取失败，本次已按输入食材生成')
    }
    ElMessage.success(successMessage)
    return true
  } catch (error) {
    if (requestId !== generationRequestId.value || controller.signal.aborted || error?.name === 'AbortError') {
      return false
    }
    streamFailed.value = true
    generationStage.value = 'error'
    streamErrorMessage.value = getErrorMessage(error)
    if (!generationCompleted.value) {
      resetRecommendationBatch()
    }
    ElMessage.error(streamErrorMessage.value)
    return false
  } finally {
    if (requestId === generationRequestId.value) {
      generating.value = false
      if (streamAbortController.value === controller) {
        streamAbortController.value = null
      }
    }
  }
}

function cancelRecipeStream() {
  generationRequestId.value += 1
  streamAbortController.value?.abort()
  streamAbortController.value = null
  generating.value = false
}

function retryStreamGeneration() {
  if (!lastSearch.value || generating.value) {
    return
  }
  void runRecipeGeneration(lastSearch.value, '菜谱推荐已生成')
}

function handleIngredientsKeydown(event) {
  if (!shouldSubmitIngredientsKey(event, {
    generating: generating.value,
    recognizing: recognizing.value
  })) {
    return
  }
  event.preventDefault()
  void runSearch()
}

function resetSearch() {
  cancelRecipeStream()
  if (detailViewOpen.value) {
    closeDetailView({ fromHistory: true })
  }
  ingredients.value = ''
  mealType.value = 'any'
  goalManuallySelected.value = false
  goal.value = auth.isUser ? dietPreference.value.defaultGoal : 'balanced'
  searchMode.value = 'text'
  cameraCaptureVisible.value = false
  cookingModeVisible.value = false
  finishedDishReviewVisible.value = false
  clearSelectedImage()
  lastSearch.value = null
  editingConditions.value = false
  recipe.value = null
  recommendationRecipes.value = []
  recommendationBatch.value = null
  activeRecipeId.value = null
  generationCompleted.value = false
  generationStage.value = 'idle'
  streamFailed.value = false
  streamErrorMessage.value = ''
  savedRecipeId.value = null
  savedRecipeIds.value = {}
  resetRecommendationFeedback()
  currentRecipePage.value = 0
  isFollowingLatest.value = true
  shoppingCheckOverrides.value = {}
  window.sessionStorage.removeItem(PENDING_RECIPE_KEY)
}

function editSearchConditions() {
  editingConditions.value = true
  window.setTimeout(() => {
    document.querySelector('.search-panel textarea')?.focus()
  }, 0)
}

async function saveCurrentRecipe() {
  if (!recipeComplete.value || savingRecipe.value || savedRecipeId.value) {
    return
  }

  if (!auth.isUser) {
    window.sessionStorage.setItem(PENDING_RECIPE_KEY, JSON.stringify({
      recipe: recipe.value,
      recommendationRecipes: recommendationRecipes.value,
      activeRecipeId: activeRecipeId.value,
      lastSearch: lastSearch.value
    }))
    ElMessage.warning('请先登录，再保存到我的菜谱')
    router.push({ name: 'login', query: { redirect: '/' } })
    return
  }

  if (!recipe.value.searchLogId) {
    ElMessage.error('当前菜谱缺少搜索记录，无法保存')
    return
  }

  savingRecipe.value = true
  try {
    const response = await saveRecipe({
      searchLogId: recipe.value.searchLogId,
      title: recipe.value.title,
      summary: recipe.value.summary,
      effects: recipe.value.effects,
      ingredients: recipe.value.ingredients,
      steps: recipe.value.steps,
      tips: recipe.value.tips,
      videoKeywords: recipe.value.videoKeywords,
      missingIngredients: recipe.value.missingIngredients,
      explanation: recipe.value.explanation,
      nutritionEstimate: recipe.value.nutritionEstimate,
      provider: recipe.value.provider,
      model: recipe.value.model
    })
    savedRecipeId.value = response.data.data?.id || null
    if (activeRecipeId.value && savedRecipeId.value) {
      savedRecipeIds.value = {
        ...savedRecipeIds.value,
        [activeRecipeId.value]: savedRecipeId.value
      }
    }
    window.sessionStorage.removeItem(PENDING_RECIPE_KEY)
    ElMessage.success('菜谱已保存到我的菜谱')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    savingRecipe.value = false
  }
}

async function regenerateCurrentRecipe(preference) {
  if (!recipe.value || !lastSearch.value || generating.value) {
    return
  }

  if (requiresDietPreferenceLoad(auth.isUser, preferenceLoaded.value)) {
    const loaded = await loadDietPreference()
    if (!loaded) {
      ElMessage.warning('饮食偏好加载失败，请重试后再生成')
      return
    }
  }

  const currentRecipe = recipe.value
  const currentRecommendationRecipes = recommendationRecipes.value
  const currentRecommendationBatch = recommendationBatch.value
  const currentActiveRecipeId = activeRecipeId.value
  const currentSavedRecipeIds = savedRecipeIds.value
  const currentSavedRecipeId = savedRecipeId.value
  const currentSearch = lastSearch.value
  const currentGenerationCompleted = generationCompleted.value
  const sourceIngredients = parseIngredientNames(currentSearch.ingredients || ingredients.value).join(', ')
  if (!sourceIngredients) {
    ElMessage.warning('当前没有可用的原始食材，请先修改条件并输入食材')
    return
  }
  const request = {
    ingredients: sourceIngredients,
    mealType: currentSearch.mealType,
    goal: currentSearch.goal,
    searchMode: currentSearch.searchMode,
    regenerationPreference: preference,
    previousTitle: currentRecipe.title,
    dietPreference: buildRecipeDietPreference(dietPreference.value),
    usePantry: currentSearch.usePantry,
    useHealthNutrition: currentSearch.useHealthNutrition,
    useAiIngredientRecommendation: false
  }

  editingConditions.value = false
  if (detailViewOpen.value) {
    closeDetailView({ fromHistory: true })
  }
  detailSection.value = 'overview'
  regenerating.value = true
  try {
    const succeeded = await runRecipeGeneration(request, '新版本菜谱已生成')
    if (succeeded) {
      window.sessionStorage.removeItem(PENDING_RECIPE_KEY)
      return
    }
    recipe.value = currentRecipe
    recommendationRecipes.value = currentRecommendationRecipes
    recommendationBatch.value = currentRecommendationBatch
    activeRecipeId.value = currentActiveRecipeId
    savedRecipeIds.value = currentSavedRecipeIds
    lastSearch.value = currentSearch
    savedRecipeId.value = currentSavedRecipeId
    generationCompleted.value = currentGenerationCompleted
    generationStage.value = currentGenerationCompleted ? 'complete' : 'idle'
    streamFailed.value = false
    streamErrorMessage.value = ''
  } finally {
    regenerating.value = false
  }
}

function restorePendingRecipe() {
  const pending = window.sessionStorage.getItem(PENDING_RECIPE_KEY)
  if (!pending) {
    return
  }

  try {
    const draft = JSON.parse(pending)
    if (draft?.recipe && draft?.lastSearch) {
      recommendationRecipes.value = Array.isArray(draft.recommendationRecipes) && draft.recommendationRecipes.length
        ? draft.recommendationRecipes
        : [{ id: 'restored-recipe-1', index: 0, label: '已恢复', ...draft.recipe }]
      activeRecipeId.value = draft.activeRecipeId || recommendationRecipes.value[0]?.id || null
      recipe.value = recommendationRecipes.value.find((item) => item.id === activeRecipeId.value)
        || recommendationRecipes.value[0]
        || draft.recipe
      recommendationBatch.value = recommendationRecipes.value.length > 1
        ? { mode: 'STYLE_VARIANTS', total: recommendationRecipes.value.length }
        : null
      savedRecipeIds.value = {}
      savedRecipeId.value = null
      lastSearch.value = draft.lastSearch
      editingConditions.value = false
      detailSection.value = 'overview'
      ingredients.value = draft.lastSearch.ingredients || ''
      mealType.value = draft.lastSearch.mealType || 'any'
      goal.value = draft.lastSearch.goal || 'balanced'
      goalManuallySelected.value = true
      searchMode.value = draft.lastSearch.searchMode || 'text'
      currentRecipePage.value = 0
      generationCompleted.value = true
      generationStage.value = 'complete'
      streamFailed.value = false
      streamErrorMessage.value = ''
      loadRecommendationFeedback(recipe.value?.searchLogId)
      loadShoppingChecks()
      loadPantryReadiness()
      ElMessage.info('已恢复未保存的菜谱，请点击保存')
    }
  } catch {
    window.sessionStorage.removeItem(PENDING_RECIPE_KEY)
  }
}

async function loadDietPreference() {
  if (!auth.isUser) {
    return true
  }
  if (preferenceLoading.value) {
    return false
  }

  const token = auth.token
  preferenceLoading.value = true
  try {
    const response = await getDietPreference()
    if (!auth.isUser || auth.token !== token) {
      return false
    }
    dietPreference.value = normalizeDietPreference(response.data.data)
    goal.value = resolveGoalWithPreference(
      goal.value,
      dietPreference.value.defaultGoal,
      goalManuallySelected.value
    )
    preferenceLoaded.value = true
    return true
  } catch {
    if (auth.isUser && auth.token === token) {
      ElMessage.warning('饮食偏好加载失败，请稍后重试')
    }
    return false
  } finally {
    if (auth.token === token) {
      preferenceLoading.value = false
    }
  }
}

async function loadNutritionTarget() {
  if (!auth.isUser || nutritionTargetLoading.value) {
    return
  }

  const token = auth.token
  nutritionTargetLoading.value = true
  try {
    const response = await getNutritionTarget()
    if (!auth.isUser || auth.token !== token) {
      return
    }
    nutritionTarget.value = normalizeNutritionTarget(response.data.data)
  } catch {
    if (auth.isUser && auth.token === token) {
      nutritionTarget.value = emptyNutritionTarget()
      ElMessage.warning('每日营养目标加载失败，菜谱仍可正常使用')
    }
  } finally {
    if (auth.token === token) {
      nutritionTargetLoading.value = false
    }
  }
}

function restoreReferenceChoices() {
  usePantry.value = auth.isUser && readStoredBoolean(pantryReferenceStorageKey.value)
  useHealthNutrition.value = auth.isUser && readStoredBoolean(healthNutritionReferenceStorageKey.value)
}

function readStoredBoolean(key) {
  try {
    return window.localStorage.getItem(key) === 'true'
  } catch {
    return false
  }
}

function storeBoolean(key, value) {
  try {
    window.localStorage.setItem(key, String(Boolean(value)))
  } catch {
    // Storage may be unavailable in private browsing; the current choice still applies.
  }
}

function handlePantryReferenceChanged(enabled) {
  storeBoolean(pantryReferenceStorageKey.value, enabled)
  if (enabled) {
    void loadPantryItems()
    return
  }
  clearPantryState()
}

function handleHealthNutritionReferenceChanged(enabled) {
  storeBoolean(healthNutritionReferenceStorageKey.value, enabled)
}

function handleRecipeScroll(event) {
  const target = event.currentTarget
  const nearLatest = target.scrollHeight - target.scrollTop - target.clientHeight < 48
  isFollowingLatest.value = nearLatest
}

function getRecipeScrollTarget() {
  const content = recipeScrollContainer.value
  if (!content) return null
  const contentStyle = window.getComputedStyle(content)
  const contentCanScroll = ['auto', 'scroll'].includes(contentStyle.overflowY)
    && content.scrollHeight > content.clientHeight
  if (contentCanScroll) return content
  return content.closest('.scene-feature-host--chef') || content
}

function handleEmbeddedRecipeScroll() {
  const target = getRecipeScrollTarget()
  if (target) handleRecipeScroll({ currentTarget: target })
}

function syncRecipeScrollParent(content) {
  removeRecipeScrollParent()
  if (!props.embedded || !content) return
  recipeScrollParent = content.closest('.scene-feature-host--chef')
  recipeScrollParent?.addEventListener('scroll', handleEmbeddedRecipeScroll, { passive: true })
}

function removeRecipeScrollParent() {
  recipeScrollParent?.removeEventListener('scroll', handleEmbeddedRecipeScroll)
  recipeScrollParent = null
}

function queueRecipeScrollToLatest() {
  if (!isFollowingLatest.value || detailViewOpen.value) return
  window.requestAnimationFrame(() => {
    const target = getRecipeScrollTarget()
    if (target && isFollowingLatest.value) {
      target.scrollTop = target.scrollHeight
    }
  })
}

function scrollRecipeToLatest() {
  isFollowingLatest.value = true
  const target = getRecipeScrollTarget()
  if (target) {
    target.scrollTo({ top: target.scrollHeight, behavior: 'smooth' })
  }
}

async function persistDietPreference(value) {
  if (!auth.isUser || preferenceSaving.value) {
    return
  }

  const token = auth.token
  preferenceSaving.value = true
  try {
    const response = await saveDietPreference(normalizeDietPreference(value))
    if (!auth.isUser || auth.token !== token) {
      return
    }
    dietPreference.value = normalizeDietPreference(response.data.data)
    preferenceLoaded.value = true
    goal.value = resolveGoalWithPreference(
      goal.value,
      dietPreference.value.defaultGoal,
      goalManuallySelected.value
    )
    preferenceDialogVisible.value = false
    ElMessage.success('饮食偏好已保存')
  } catch (error) {
    if (auth.isUser && auth.token === token) {
      ElMessage.error(getErrorMessage(error))
    }
  } finally {
    if (auth.token === token) {
      preferenceSaving.value = false
    }
  }
}

async function openRecentSearches() {
  if (!auth.isUser) {
    return
  }

  recentSearchVisible.value = true
  if (recentSearchLoaded.value || recentSearchLoading.value) {
    return
  }

  const token = auth.token
  recentSearchLoading.value = true
  try {
    const response = await getRecentSearches()
    if (!auth.isUser || auth.token !== token) {
      return
    }
    recentSearches.value = (response.data.data || []).slice(0, 5)
    recentSearchLoaded.value = true
  } catch {
    if (auth.isUser && auth.token === token) {
      recentSearchVisible.value = false
      ElMessage({
        type: 'warning',
        message: '最近搜索加载失败，可继续手动输入',
        duration: 2200
      })
    }
  } finally {
    if (auth.token === token) {
      recentSearchLoading.value = false
    }
  }
}

function applyRecentSearch(item) {
  const form = toSearchForm(item)
  ingredients.value = form.ingredients
  mealType.value = form.mealType
  goal.value = form.goal
  goalManuallySelected.value = true
  recentSearchVisible.value = false
}

function clearPersonalizationState() {
  resetRecommendationFeedback()
  dietPreference.value = normalizeDietPreference()
  preferenceDialogVisible.value = false
  preferenceLoaded.value = false
  preferenceLoading.value = false
  preferenceSaving.value = false
  nutritionTarget.value = emptyNutritionTarget()
  nutritionTargetLoading.value = false
  recentSearches.value = []
  recentSearchLoading.value = false
  recentSearchLoaded.value = false
  recentSearchVisible.value = false
  if (!goalManuallySelected.value) {
    goal.value = 'balanced'
  }
}

function resetRecommendationFeedback() {
  feedbackReaction.value = null
  feedbackCooked.value = false
  feedbackLoading.value = false
}

async function loadRecommendationFeedback(searchLogId) {
  if (!auth.isUser || !searchLogId) {
    resetRecommendationFeedback()
    return
  }
  const token = auth.token
  try {
    const response = await getRecommendationFeedback(searchLogId)
    if (auth.token !== token || recipe.value?.searchLogId !== searchLogId) {
      return
    }
    const feedback = normalizeRecommendationFeedback(response.data.data)
    feedbackReaction.value = feedback.reaction
    feedbackCooked.value = feedback.cooked
  } catch (error) {
    if (auth.token === token && recipe.value?.searchLogId === searchLogId) {
      resetRecommendationFeedback()
      if (error?.response?.status !== 404) {
        ElMessage.warning(getFeedbackErrorMessage(error))
      }
    }
  }
}

async function toggleRecommendationReaction(reaction) {
  if (!recipeComplete.value) {
    return
  }
  const searchLogId = recipe.value?.searchLogId
  if (!searchLogId || feedbackLoading.value) {
    return
  }
  if (!auth.isUser) {
    window.sessionStorage.setItem(PENDING_RECIPE_KEY, JSON.stringify({
      recipe: recipe.value,
      lastSearch: lastSearch.value
    }))
    ElMessage.warning('登录后可保存推荐偏好')
    router.push({ name: 'login', query: { redirect: '/' } })
    return
  }

  const previousReaction = feedbackReaction.value
  feedbackLoading.value = true
  try {
    const nextReaction = nextRecommendationReaction(previousReaction, reaction)
    const response = nextReaction === null
      ? await clearRecommendationReaction(searchLogId)
      : await setRecommendationReaction(searchLogId, reaction)
    const feedback = normalizeRecommendationFeedback(response.data.data)
    feedbackReaction.value = feedback.reaction
    feedbackCooked.value = feedback.cooked
    ElMessage.success(feedbackReaction.value ? '推荐偏好已记录' : '推荐偏好已取消')
  } catch (error) {
    feedbackReaction.value = previousReaction
    ElMessage.error(getFeedbackErrorMessage(error))
  } finally {
    feedbackLoading.value = false
  }
}

function getFeedbackErrorMessage(error) {
  const status = error?.response?.status
  if (status === 401) {
    return '登录状态已失效，请重新登录后保存推荐偏好'
  }
  if (status === 403) {
    return '当前账号没有操作该推荐记录的权限'
  }
  if (status === 404) {
    return '推荐记录不存在或已过期'
  }
  return error?.response?.data?.message || '推荐反馈保存失败，请稍后重试'
}

function clearPantryState() {
  pantryItems.value = []
  pantryLoading.value = false
  pantryExpirySummary.value = emptyPantryExpirySummary()
  pantryReadiness.value = emptyPantryReadiness()
  pantryReadinessLoading.value = false
  pantryReadinessError.value = false
  pantryReadinessErrorText.value = ''
  pantryReadinessRequestId.value += 1
  pantryReadinessBulkSaving.value = false
  shoppingCheckOverrides.value = {}
  shoppingCheckSavingKey.value = ''
}

function emptyPantryExpirySummary() {
  return {
    asOf: null,
    warningDays: 7,
    expiredItems: [],
    expiringSoonItems: []
  }
}

function emptyPantryReadiness() {
  return {
    itemCount: 0,
    readyCount: 0,
    shortageCount: 0,
    expiringSoonCount: 0,
    readinessPercent: 0,
    items: []
  }
}

async function loadPantryItems() {
  if (!auth.isUser || !usePantry.value || pantryLoading.value) {
    return
  }

  const token = auth.token
  pantryLoading.value = true
  try {
    const [pantryResult, expiryResult] = await Promise.allSettled([
      getPantryItems(),
      getPantryExpiryAlerts()
    ])
    if (pantryResult.status === 'rejected') {
      throw pantryResult.reason
    }
    if (!auth.isUser || auth.token !== token || !usePantry.value) {
      return
    }
    pantryItems.value = pantryResult.value.data.data || []
    pantryExpirySummary.value = expiryResult.status === 'fulfilled'
      ? expiryResult.value.data.data || emptyPantryExpirySummary()
      : emptyPantryExpirySummary()
    await loadShoppingChecks()
    await loadPantryReadiness()
  } catch (error) {
    if (auth.isUser && auth.token === token) {
      pantryItems.value = []
      pantryExpirySummary.value = emptyPantryExpirySummary()
      ElMessage.warning('食材库存加载失败，本次将不读取库存')
    }
  } finally {
    if (auth.token === token) {
      pantryLoading.value = false
    }
  }
}

function recipeReadinessIngredients() {
  return (Array.isArray(recipe.value?.ingredients) ? recipe.value.ingredients : [])
    .map((ingredient) => {
      if (typeof ingredient === 'string') {
        return { name: ingredient.trim(), amount: '' }
      }
      return {
        name: typeof ingredient?.name === 'string' ? ingredient.name.trim() : '',
        amount: typeof ingredient?.amount === 'string' ? ingredient.amount.trim() : ''
      }
    })
    .filter((ingredient) => ingredient.name)
}

async function loadPantryReadiness() {
  const currentRecipe = recipe.value
  const requestIngredients = recipeReadinessIngredients()
  if (!usePantry.value || !currentRecipe || !requestIngredients.length || !auth.isUser) {
    pantryReadinessRequestId.value += 1
    pantryReadiness.value = emptyPantryReadiness()
    pantryReadinessLoading.value = false
    pantryReadinessError.value = false
    pantryReadinessErrorText.value = ''
    return
  }

  const requestId = pantryReadinessRequestId.value + 1
  pantryReadinessRequestId.value = requestId
  const token = auth.token
  pantryReadinessLoading.value = true
  pantryReadinessError.value = false
  pantryReadinessErrorText.value = ''
  try {
    const response = await getPantryReadiness({ ingredients: requestIngredients })
    if (requestId !== pantryReadinessRequestId.value || !auth.isUser || auth.token !== token || recipe.value !== currentRecipe) {
      return
    }
    pantryReadiness.value = response.data.data || emptyPantryReadiness()
  } catch (error) {
    if (requestId === pantryReadinessRequestId.value && auth.isUser && auth.token === token && recipe.value === currentRecipe) {
      pantryReadiness.value = emptyPantryReadiness()
      pantryReadinessError.value = true
      pantryReadinessErrorText.value = getPantryReadinessErrorMessage(error)
    }
  } finally {
    if (requestId === pantryReadinessRequestId.value) {
      pantryReadinessLoading.value = false
    }
  }
}

function retryPantryReadiness() {
  loadPantryReadiness()
}

function getReadinessItem(ingredientName) {
  const key = shoppingChecklistKey(ingredientName)
  if (!key) return null
  return pantryReadinessItems.value.find((item) => shoppingChecklistKey(item?.ingredientName) === key) || null
}

function readinessBadge(item) {
  if (!item) return ''
  if (item.shortage) return item.status === 'EXPIRED_ONLY' ? '仅有过期库存' : '需补充'
  if (item.expiringSoon) return '临期'
  return ''
}

async function addMissingToShoppingList() {
  if (pantryReadinessBulkSaving.value || pantryReadinessLoading.value) return
  if (!auth.isUser) {
    ElMessage.warning('登录后才能加入采购清单')
    return
  }
  const searchLogId = recipe.value?.searchLogId
  if (!searchLogId) {
    ElMessage.warning('当前菜谱还没有可关联的采购记录')
    return
  }
  const missingItems = pantryReadinessMissingItems.value.filter((item) => (
    ['MISSING', 'PARTIAL', 'EXPIRED_ONLY'].includes(item.status)
  ))
  const newItems = missingItems.filter((item) => (
    !Object.prototype.hasOwnProperty.call(shoppingCheckOverrides.value, shoppingChecklistKey(item.ingredientName))
  ))
  if (!newItems.length) {
    ElMessage.info('缺少食材已在采购清单中')
    return
  }

  const previous = { ...shoppingCheckOverrides.value }
  pantryReadinessBulkSaving.value = true
  try {
    await Promise.all(newItems.map((item) => saveShoppingItemCheck({
      searchLogId,
      ingredientName: item.ingredientName,
      status: 'PENDING'
    })))
    await loadShoppingChecks()
    ElMessage.success(`已将 ${newItems.length} 项食材加入采购清单`)
  } catch {
    shoppingCheckOverrides.value = previous
    ElMessage.error('加入采购清单失败，请重试')
  } finally {
    pantryReadinessBulkSaving.value = false
  }
}

function viewPreparedIngredients(event) {
  if (!recipe.value) return
  openDetail('ingredients', event)
}

async function loadShoppingChecks() {
  const searchLogId = recipe.value?.searchLogId
  if (!auth.isUser || !searchLogId) {
    shoppingCheckOverrides.value = {}
    return
  }

  shoppingCheckOverrides.value = {}
  const token = auth.token
  try {
    const response = await getShoppingItemChecks(searchLogId)
    if (!auth.isUser || auth.token !== token || recipe.value?.searchLogId !== searchLogId) {
      return
    }
    shoppingCheckOverrides.value = (response.data.data || []).reduce((overrides, item) => {
      const key = shoppingChecklistKey(item.ingredientName)
      if (key) {
        overrides[key] = normalizeShoppingStatus(item.status, item.checked)
      }
      return overrides
    }, {})
  } catch (error) {
    if (auth.isUser && auth.token === token && error?.response?.status !== 403 && error?.response?.status !== 404) {
      ElMessage.warning('采购清单状态加载失败，可继续手动更新状态')
    }
  }
}

async function toggleShoppingItem({ item, status }) {
  const key = shoppingChecklistKey(item?.name)
  if (!key || !status || shoppingCheckSavingKey.value) {
    return
  }

  const previousExists = Object.prototype.hasOwnProperty.call(shoppingCheckOverrides.value, key)
  const previousValue = shoppingCheckOverrides.value[key]
  shoppingCheckOverrides.value = {
    ...shoppingCheckOverrides.value,
    [key]: status
  }

  const searchLogId = recipe.value?.searchLogId
  if (!auth.isUser || !searchLogId) {
    return
  }

  const token = auth.token
  shoppingCheckSavingKey.value = key
  try {
    const response = await saveShoppingItemCheck({
      searchLogId,
      ingredientName: item.name,
      status
    })
    if (auth.isUser && auth.token === token && recipe.value?.searchLogId === searchLogId) {
      shoppingCheckOverrides.value = {
        ...shoppingCheckOverrides.value,
        [key]: normalizeShoppingStatus(response.data.data?.status, status)
      }
    }
  } catch (error) {
    if (auth.isUser && auth.token === token) {
      const restored = { ...shoppingCheckOverrides.value }
      if (previousExists) {
        restored[key] = previousValue
      } else {
        delete restored[key]
      }
      shoppingCheckOverrides.value = restored
      ElMessage.error('采购清单状态保存失败，请重试')
    }
  } finally {
    if (shoppingCheckSavingKey.value === key) {
      shoppingCheckSavingKey.value = ''
    }
  }
}

function handleStockIn(item) {
  if (!savedRecipeId.value || stockInKey.value) {
    ElMessage.info('请先保存菜谱，再将采购食材加入库存')
    return
  }
  const key = shoppingChecklistKey(item?.name)
  if (!key) return
  stockInItem.value = item
  stockInDialogVisible.value = true
}

async function handleStockInConfirm(payload) {
  const item = stockInItem.value
  if (!savedRecipeId.value || stockInKey.value) return
  const key = shoppingChecklistKey(item?.name)
  if (!key) return
  stockInKey.value = key
  try {
    await stockInPantry({ sourceType: 'RECIPE', sourceId: savedRecipeId.value, idempotencyKey: createClientKey(), ingredientName: item.name, quantity: payload.quantity, unit: payload.unit, category: payload.category, expireDate: payload.expireDate })
    shoppingCheckOverrides.value = { ...shoppingCheckOverrides.value, [key]: 'READY' }
    stockInDialogVisible.value = false
    await loadPantryItems()
    ElMessage.success(`${item.name} 已加入库存`)
  } catch (error) { ElMessage.error(error?.response?.data?.message || '入库失败，请稍后重试') }
  finally { stockInKey.value = '' }
}

function createClientKey() { return globalThis.crypto?.randomUUID?.() || `stock-in-${Date.now()}-${Math.random().toString(16).slice(2)}` }

function openImagePicker() {
  imageInput.value?.click()
}

function openCameraCapture() {
  if (!recognizing.value) {
    cameraCaptureVisible.value = true
  }
}

function handleImageSelected(event) {
  const file = event.target.files?.[0]
  if (!file) {
    return
  }

  if (!selectImageFile(file)) {
    event.target.value = ''
  }
}

function selectImageFile(file) {
  const validationMessage = validateIngredientImageFile(file)
  if (validationMessage) {
    ElMessage.warning(validationMessage)
    return false
  }

  selectedImageFile.value = file
  recognizedIngredients.value = []
  recognitionDescription.value = ''
  revokeImagePreview()
  selectedImagePreview.value = URL.createObjectURL(file)
  return true
}

async function handleCameraCaptured(file) {
  cameraCaptureVisible.value = false
  if (!selectImageFile(file)) {
    return
  }

  await recognizeSelectedImage()
}

async function recognizeUploadedImage() {
  await recognizeSelectedImage()
}

async function recognizeSelectedImage() {
  if (!selectedImageFile.value) {
    ElMessage.warning('请先选择食材图片')
    return
  }

  if (recognizing.value) {
    return
  }

  recognizing.value = true
  try {
    const response = await recognizeIngredients(selectedImageFile.value)
    const result = response.data.data
    recognizedIngredients.value = result?.ingredients || []
    recognitionDescription.value = result?.description || ''
    if (!recognizedIngredients.value.length) {
      ElMessage.warning('未识别到明确食材，请更换图片后重试')
      return
    }
    ingredients.value = mergeIngredientNames(ingredients.value, recognizedIngredients.value)
    ElMessage.success('食材识别已完成')
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    recognizing.value = false
  }
}

function buildRecipeShoppingList(recipeData, ownedIngredients) {
  if (!recipeData) {
    return []
  }

  return buildShoppingList(recipeData.ingredients, parseIngredientNames(ownedIngredients)).map((item) => ({
    ...item,
    purchaseLinks: item.purchaseLinks || buildPurchaseLinks(item.name)
  }))
}

function findMissingIngredient(missingIngredients, ingredientName) {
  const target = parseIngredientNames([ingredientName])[0]?.toLocaleLowerCase()
  if (!target) {
    return null
  }

  return (Array.isArray(missingIngredients) ? missingIngredients : []).find((item) => {
    const sourceName = typeof item === 'string' ? item : item?.name
    const normalized = parseIngredientNames([sourceName])[0]?.toLocaleLowerCase()
    return normalized === target
  }) || null
}

function formatSubstitutes(substitutes) {
  if (!Array.isArray(substitutes)) {
    return typeof substitutes === 'string' ? substitutes : ''
  }
  return substitutes
    .map((item) => typeof item === 'string' ? item : item?.name)
    .filter(Boolean)
    .join('、')
}

async function preparePlatformSearch(ingredientName) {
  try {
    if (await copyIngredientName(ingredientName, navigator.clipboard)) {
      ElMessage.info(`已复制“${ingredientName}”，请在平台中粘贴搜索`)
    }
  } catch {
    ElMessage.info(`请在平台中搜索“${ingredientName}”`)
  }
}

function openCookingMode() {
  if (!recipeComplete.value) {
    ElMessage.warning('当前菜谱暂无可执行的烹饪步骤')
    return
  }
  cookingModeVisible.value = true
}

function openFinishedDishReview() {
  if (!recipeComplete.value) {
    ElMessage.warning('当前菜谱信息不完整，暂时无法评价')
    return
  }
  finishedDishReviewVisible.value = true
}

function clearSelectedImage() {
  selectedImageFile.value = null
  recognizedIngredients.value = []
  recognitionDescription.value = ''
  revokeImagePreview()
  if (imageInput.value) {
    imageInput.value.value = ''
  }
}

function revokeImagePreview() {
  if (selectedImagePreview.value) {
    URL.revokeObjectURL(selectedImagePreview.value)
    selectedImagePreview.value = ''
  }
}

function previousRecipePage() {
  if (recipePages.value.length <= 1) {
    return
  }
  currentRecipePage.value = (activeRecipePageIndex.value - 1 + recipePages.value.length) % recipePages.value.length
}

function nextRecipePage() {
  if (recipePages.value.length <= 1) {
    return
  }
  currentRecipePage.value = (activeRecipePageIndex.value + 1) % recipePages.value.length
}

function getErrorMessage(error) {
  const message = error?.response?.data?.message || error?.message
  const messages = {
    '千问 API Key 未配置，请设置 DASHSCOPE_API_KEY': '千问 API Key 未配置，请先设置 DASHSCOPE_API_KEY',
    '千问服务调用失败，请稍后重试': '千问服务调用失败，请稍后重试',
    '千问流式服务调用失败，请稍后重试': 'AI 服务连接失败，请稍后重试',
    '千问流式响应中断，请点击重试': '菜谱生成连接中断，请点击重试',
    '千问流式响应格式无效，请点击重试': '菜谱生成返回格式异常，请点击重试',
    'AI 返回的菜谱内容不完整，请点击重试': '菜谱内容不完整，请点击重试',
    'AI 菜谱生成暂时失败，请检查网络后重试': 'AI 菜谱生成暂时失败，请检查网络后重试',
    'AI 生成超时，请点击重试': 'AI 生成超时，请检查网络后重试',
    'Failed to fetch': '菜谱生成连接失败，请检查网络后重试',
    'Load failed': '菜谱生成连接失败，请检查网络后重试',
    '千问服务未返回菜谱内容': '千问服务未返回菜谱内容',
    '千问返回内容不是有效菜谱 JSON': '千问返回内容格式异常',
    '千问视觉服务调用失败，请稍后重试': '千问视觉服务调用失败，请稍后重试',
    '千问视觉服务未返回识别内容': '千问视觉服务未返回识别内容',
    '千问视觉返回内容不是有效食材 JSON': '千问视觉返回内容格式异常',
    '请上传食材图片': '请先上传食材图片',
    '图片大小不能超过 5MB': '图片大小不能超过 5MB',
    '仅支持 JPG、PNG、WebP 图片': '仅支持 JPG、PNG、WebP 图片',
    'Invalid request parameters': '请求参数不合法',
    'Network Error': '网络连接失败，请检查后端服务'
  }
  if (message?.toLowerCase().includes('timeout')) {
    return '请求超时，请稍后重试'
  }
  return messages[message] || message || '菜谱生成失败'
}
</script>

<style scoped>
.home-page {
  height: calc(100vh - 58px);
  overflow: hidden;
  overflow-x: hidden;
  padding: clamp(10px, 1.4vw, 18px);
  color: var(--app-text);
}

.command-shell {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
  gap: 10px;
  width: min(1360px, 100%);
  height: 100%;
  margin: 0 auto;
}

.workspace-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 14px;
}

.eyebrow {
  margin: 0 0 4px;
  color: var(--app-text-muted);
  font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0;
}

h1,
h2,
h3 {
  margin: 0;
  color: var(--app-text);
}

h1 {
  font-size: clamp(24px, 2.8vw, 38px);
  line-height: 1.02;
}

h2 {
  font-size: clamp(19px, 1.6vw, 25px);
  line-height: 1.15;
}

h3 {
  font-size: 16px;
}

.signal-strip {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
}

.signal-strip span,
.status-pill,
.system-tag,
.video-keywords span,
.video-keywords a {
  display: inline-flex;
  align-items: center;
  min-height: 26px;
  padding: 0 8px;
  border: 1px solid var(--app-line-strong);
  border-radius: 999px;
  color: var(--app-text);
  background: var(--app-surface);
  font-size: 11px;
  font-weight: 800;
}

.pantry-expiry-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 42px;
  padding: 8px 12px;
  border: 1px solid var(--app-accent);
  border-radius: 8px;
  color: var(--app-text);
  background: var(--app-accent-soft);
}

.pantry-expiry-banner > svg {
  flex: 0 0 auto;
}

.pantry-expiry-banner > div {
  display: grid;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.pantry-expiry-banner strong {
  font-size: 13px;
}

.pantry-expiry-banner span {
  color: var(--app-text-muted);
  font-size: 12px;
}

.pantry-expiry-link {
  flex: 0 0 auto;
  padding: 0;
  border: 0;
  color: var(--app-text);
  background: transparent;
  font: inherit;
  font-size: 12px;
  font-weight: 800;
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
}

.command-grid {
  display: grid;
  grid-template-columns: minmax(310px, 0.78fr) minmax(0, 1.22fr);
  gap: 12px;
  min-height: 0;
}

.result-priority-grid {
  grid-template-columns: minmax(0, 1fr);
  grid-template-rows: auto minmax(0, 1fr);
}

.query-summary-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  min-height: 48px;
  padding: 7px 12px;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-surface);
  box-shadow: var(--app-panel-shadow);
}

.query-summary-copy {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.query-summary-copy .eyebrow {
  flex: 0 0 auto;
  margin: 0;
}

.query-summary-copy > strong {
  overflow: hidden;
  color: var(--app-text);
  font-size: clamp(14px, 1.2vw, 18px);
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.query-summary-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  flex: 0 0 auto;
}

.query-summary-meta span {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 0 8px;
  border: 1px solid var(--app-line);
  border-radius: 999px;
  color: var(--app-text-muted);
  background: var(--app-surface-soft);
  font-size: 12px;
  font-weight: 800;
}

.query-summary-actions {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.query-summary-actions :deep(.el-button) {
  min-height: 40px;
  margin-left: 0;
}

.query-summary-actions :deep(.el-button span) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.search-panel,
.result-panel {
  position: relative;
  min-height: 0;
  overflow: hidden;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background:
    linear-gradient(90deg, var(--app-grid-line-strong) 1px, transparent 1px),
    linear-gradient(var(--app-grid-line-soft) 1px, transparent 1px),
    var(--app-surface);
  background-size: 28px 28px;
  box-shadow:
    var(--app-panel-shadow),
    inset 0 1px 0 var(--app-grid-line-strong);
}

.search-panel {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 10px;
  padding: clamp(12px, 1.4vw, 18px);
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--app-text);
}

.panel-title > svg {
  flex: 0 0 auto;
}

.panel-title div {
  display: grid;
  gap: 2px;
}

.panel-title span,
.field-label,
.window-head span {
  color: var(--app-text-muted);
  font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace;
  font-size: 12px;
  font-weight: 800;
}

.panel-title strong {
  font-size: 16px;
}

.search-form {
  display: grid;
  align-content: start;
  gap: 9px;
  min-height: 0;
}

.search-form :deep(.el-form-item) {
  margin-bottom: 0;
}

.search-form :deep(.el-select) {
  width: 100%;
}

.search-form :deep(.el-textarea__inner) {
  min-height: 86px !important;
}

.filters {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.goal-label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
}

.goal-label-row :deep(.el-button) {
  height: auto;
  min-height: 22px;
  padding: 0;
}

.goal-label-row :deep(.el-button span) {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.mode-panel {
  display: grid;
  gap: 8px;
}

.mode-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 7px;
}

.mode-card {
  display: grid;
  gap: 4px;
  min-width: 0;
  min-height: 82px;
  padding: 9px;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  color: var(--app-text-muted);
  background: var(--app-surface);
  text-align: left;
  cursor: pointer;
  transition:
    border-color 180ms ease,
    background-color 180ms ease,
    color 180ms ease,
    transform 180ms ease;
}

.mode-card:hover,
.mode-card:focus-visible,
.mode-card.active {
  border-color: var(--app-accent);
  color: var(--app-text);
  background: var(--app-surface-soft);
  outline: none;
}

.mode-card.active {
  transform: translateY(-1px);
}

.mode-card:disabled {
  border-style: dashed;
  color: var(--app-text-faint);
  background: var(--app-surface-strong);
  cursor: not-allowed;
  opacity: 0.75;
}

.mode-card:disabled:hover,
.mode-card:disabled:focus-visible {
  border-color: var(--app-line);
  color: var(--app-text-faint);
  background: var(--app-surface-strong);
  transform: none;
}

.mode-card strong {
  overflow: hidden;
  color: inherit;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mode-card span {
  color: var(--app-text-faint);
  font-size: 11px;
  line-height: 1.35;
}

.image-upload-panel {
  display: grid;
  gap: 8px;
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

.upload-dropzone {
  display: grid;
  place-items: center;
  min-height: 90px;
  padding: 0;
  overflow: hidden;
  border: 1px dashed var(--app-line-strong);
  border-radius: 8px;
  color: var(--app-text-muted);
  background: var(--app-surface);
  cursor: pointer;
  transition:
    border-color 180ms ease,
    background-color 180ms ease;
}

.upload-dropzone:hover,
.upload-dropzone:focus-visible {
  border-color: var(--app-accent);
  background: var(--app-surface-soft);
  outline: none;
}

.upload-dropzone img {
  width: 100%;
  height: 96px;
  object-fit: cover;
}

.upload-empty {
  display: grid;
  place-items: center;
  gap: 4px;
  padding: 10px;
  text-align: center;
}

.upload-empty strong {
  color: var(--app-text);
  font-size: 13px;
}

.upload-empty em {
  color: var(--app-text-faint);
  font-size: 11px;
  font-style: normal;
}

.image-actions {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 8px;
}

.image-actions :deep(.el-button) {
  min-height: 36px;
  margin-left: 0;
}

.image-actions :deep(.el-button span) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.recognized-result {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.recognized-result span {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 0 8px;
  border: 1px solid var(--app-line-strong);
  border-radius: 999px;
  color: var(--app-text);
  background: var(--app-surface-strong);
  font-size: 11px;
  font-weight: 800;
}

.recognition-summary {
  margin: 0;
  color: var(--app-text-muted);
  font-size: 11px;
  line-height: 1.5;
}

.camera-capture-panel {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px 12px;
  align-items: center;
  padding: 12px;
  border: 1px solid var(--app-line-strong);
  border-radius: 8px;
  background: var(--app-surface-soft);
}

.camera-capture-copy {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
}

.camera-capture-icon {
  display: inline-grid;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid var(--app-line-strong);
  border-radius: 7px;
  color: var(--app-accent);
  background: var(--app-surface);
}

.camera-capture-copy strong {
  display: block;
  color: var(--app-text);
  font-size: 13px;
}

.camera-capture-copy p {
  margin: 3px 0 0;
  color: var(--app-text-muted);
  font-size: 11px;
  line-height: 1.45;
}

.camera-capture-panel :deep(.el-button) {
  min-height: 36px;
  margin-left: 0;
}

.camera-capture-panel :deep(.el-button span) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.camera-recognized-result,
.camera-recognition-summary {
  grid-column: 1 / -1;
}

.search-actions {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  margin-top: 2px;
}

.search-actions :deep(.el-button) {
  min-height: 38px;
  margin-left: 0;
}

.search-actions :deep(.el-button span) {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.result-panel {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 9px;
  padding: clamp(12px, 1.4vw, 18px);
}

.pantry-readiness-card {
  display: grid;
  gap: 12px;
  min-width: 0;
  padding: 14px 16px;
  border: 1px solid var(--app-line);
  border-radius: 10px;
  background: linear-gradient(135deg, var(--app-surface), var(--app-surface-soft));
}

.pantry-readiness-head,
.pantry-readiness-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.pantry-readiness-head h3 {
  font-size: 17px;
  line-height: 1.25;
}

.pantry-readiness-status {
  flex: 0 0 auto;
  padding: 5px 9px;
  border: 1px solid var(--app-line-strong);
  border-radius: 999px;
  color: var(--app-text-soft);
  background: var(--app-surface);
  font-size: 12px;
  font-weight: 700;
}

.pantry-readiness-metrics {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 8px;
  min-width: 0;
}

.pantry-readiness-metric {
  display: grid;
  align-content: start;
  gap: 4px;
  min-width: 0;
  min-height: 59px;
  padding: 9px 10px;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-surface);
}

.pantry-readiness-metric > span {
  overflow-wrap: anywhere;
  color: var(--app-text-muted);
  font-size: 11px;
  font-weight: 700;
}

.pantry-readiness-metric > strong {
  overflow-wrap: anywhere;
  color: var(--app-text);
  font-size: 16px;
  line-height: 1.25;
}

.pantry-readiness-metric-primary > strong {
  color: var(--app-accent);
}

.pantry-readiness-progress {
  height: 4px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--app-line);
}

.pantry-readiness-progress i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--app-accent);
  transition: width 240ms ease;
}

.pantry-readiness-missing {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.pantry-readiness-missing-label {
  color: var(--app-text-muted);
  font-size: 12px;
  font-weight: 800;
}

.pantry-readiness-tag,
.pantry-readiness-overflow {
  overflow-wrap: anywhere;
  padding: 4px 8px;
  border: 1px solid var(--app-line-strong);
  border-radius: 999px;
  color: var(--app-text-soft);
  background: var(--app-surface);
  font-size: 12px;
}

.pantry-readiness-overflow {
  color: var(--app-text-muted);
}

.pantry-readiness-ready-copy,
.pantry-readiness-disclaimer {
  color: var(--app-text-muted);
  font-size: 12px;
}

.pantry-readiness-actions {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.pantry-readiness-actions :deep(.el-button) {
  min-height: 40px;
  margin-left: 0;
}

.pantry-readiness-error,
.pantry-readiness-disclaimer {
  margin: 0;
}

.pantry-readiness-error {
  color: var(--app-danger, #b54747);
  font-size: 12px;
}

.pantry-readiness-error button {
  padding: 0;
  border: 0;
  color: inherit;
  background: transparent;
  font: inherit;
  font-weight: 800;
  text-decoration: underline;
  cursor: pointer;
}

.pantry-readiness-skeleton {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 8px;
}

.pantry-readiness-skeleton-block {
  display: block;
  height: 59px;
  border-radius: 8px;
  background: linear-gradient(90deg, var(--app-surface-soft), var(--app-line), var(--app-surface-soft));
  background-size: 200% 100%;
  animation: pantry-readiness-shimmer 1.4s ease-in-out infinite;
}

@keyframes pantry-readiness-shimmer {
  from { background-position: 200% 0; }
  to { background-position: -200% 0; }
}

.ingredient-name-cell {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.readiness-ingredient-badge {
  padding: 2px 6px;
  border: 1px solid rgba(181, 71, 71, 0.35);
  border-radius: 999px;
  color: var(--app-danger, #b54747);
  background: rgba(181, 71, 71, 0.08);
  font-size: 10px;
  font-weight: 800;
  line-height: 1.2;
}

.readiness-ingredient-badge.soon {
  border-color: rgba(180, 118, 32, 0.35);
  color: #9a6818;
  background: rgba(231, 177, 58, 0.12);
}

.home-page.is-result-expanded:not(.is-detail-view) .result-content {
  display: block;
  min-height: 0;
  overflow: visible;
}

.home-page.is-result-expanded:not(.is-detail-view) .result-panel {
  display: block;
  min-height: 0;
  overflow: visible;
}

.home-page.is-result-expanded:not(.is-detail-view) .recipe-detail {
  display: block;
  min-height: 0;
  overflow: visible;
}

.home-page.is-detail-view .command-grid {
  grid-template-columns: minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
}

.home-page.is-detail-view .result-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.home-page.is-detail-view .result-content {
  display: block;
  flex: 1 1 0;
  min-height: 0;
  overflow: hidden;
}

.home-page.is-detail-view .brief-grid,
.home-page.is-detail-view .recipe-detail :deep(.recommendation-feedback),
.home-page.is-detail-view .recipe-detail > :deep(.nutrition-card) {
  display: none;
}

.home-page.is-detail-view .recipe-detail {
  display: block;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.home-page.is-detail-view .recipe-sections {
  display: block;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.home-page.is-detail-view .recipe-section {
  height: 100%;
  min-height: 0;
  overflow: auto;
}

.recipe-detail-view-header {
  display: grid;
  grid-template-columns: minmax(145px, auto) minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  min-height: 54px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--app-line);
}

.detail-back-button {
  display: inline-flex;
  align-items: center;
  justify-content: flex-start;
  gap: 7px;
  min-height: 44px;
  padding: 0 10px;
  border: 1px solid var(--app-line-strong);
  border-radius: 7px;
  color: var(--app-text);
  background: var(--app-surface);
  font: inherit;
  font-size: 13px;
  font-weight: 800;
  cursor: pointer;
}

.detail-back-button:hover,
.detail-back-button:focus-visible {
  border-color: var(--app-accent);
  background: var(--app-accent-soft);
  outline: none;
}

.detail-view-title {
  min-width: 0;
}

.detail-view-title h2 {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-view-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.detail-close-button {
  position: absolute;
  top: 50%;
  right: 0;
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  transform: translateY(-50%);
  padding: 0;
  border: 1px solid var(--app-line-strong);
  border-radius: 7px;
  color: var(--app-text);
  background: var(--app-surface);
  cursor: pointer;
  transition: color 140ms ease, border-color 140ms ease, background-color 140ms ease;
}

.detail-close-button:hover,
.detail-close-button:focus-visible {
  border-color: var(--app-accent);
  color: var(--app-text);
  background: var(--app-accent-soft);
  outline: 2px solid color-mix(in srgb, var(--app-accent) 55%, transparent);
  outline-offset: 2px;
}

.recipe-entry-nav {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 9px;
  min-width: 0;
  margin-top: 2px;
}

.recipe-entry-button {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 9px;
  min-width: 0;
  min-height: 78px;
  padding: 11px 12px;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  color: var(--app-text);
  background: var(--app-surface);
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition:
    border-color 180ms ease,
    background-color 180ms ease,
    transform 180ms ease;
}

.recipe-entry-button:hover,
.recipe-entry-button:focus-visible {
  border-color: var(--app-accent);
  background: var(--app-surface-soft);
  outline: none;
  transform: translateY(-1px);
}

.recipe-entry-button:disabled {
  color: var(--app-text-faint);
  background: var(--app-surface-strong);
  cursor: not-allowed;
  opacity: 0.72;
}

.recipe-entry-button:disabled:hover,
.recipe-entry-button:disabled:focus-visible {
  border-color: var(--app-line);
  background: var(--app-surface-strong);
  transform: none;
}

.recipe-entry-index {
  color: var(--app-accent);
  font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace;
  font-size: 12px;
  font-weight: 900;
}

.recipe-entry-copy {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.recipe-entry-copy strong {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recipe-entry-copy small {
  overflow: hidden;
  color: var(--app-text-muted);
  font-size: 11px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recipe-detail-bottom-nav {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 7px;
  min-width: 0;
  padding-top: 8px;
  border-top: 1px solid var(--app-line);
  background: var(--app-surface);
}

.recipe-detail-bottom-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-width: 0;
  min-height: 44px;
  padding: 0 10px;
  border: 1px solid var(--app-line);
  border-radius: 7px;
  color: var(--app-text-muted);
  background: var(--app-surface-soft);
  font: inherit;
  font-size: 13px;
  font-weight: 800;
  cursor: pointer;
  transition:
    border-color 180ms ease,
    background-color 180ms ease,
    color 180ms ease;
}

.recipe-detail-bottom-link span {
  color: var(--app-text-faint);
  font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace;
  font-size: 11px;
}

.recipe-detail-bottom-link:hover,
.recipe-detail-bottom-link:focus-visible,
.recipe-detail-bottom-link.active {
  border-color: var(--app-accent);
  color: var(--app-text);
  background: var(--app-accent-soft);
  outline: none;
}

.recipe-detail-bottom-link[aria-current="page"] {
  box-shadow: inset 0 -2px 0 var(--app-accent);
}

.recipe-detail-bottom-link:disabled {
  color: var(--app-text-faint);
  background: var(--app-surface-strong);
  cursor: not-allowed;
  opacity: 0.7;
}

.home-page.is-result-expanded {
  height: calc(100vh - 58px);
  min-height: 0;
  overflow: hidden;
  overflow-x: hidden;
  display: flex;
  flex-direction: column;
}

.home-page.is-result-expanded .command-shell {
  height: 100%;
  min-height: 0;
  flex: 1 1 auto;
}

.home-page.is-result-expanded .result-panel {
  min-height: 0;
  overflow: hidden;
  display: grid;
}

.home-page.is-result-expanded .result-content {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 8px;
  overflow: hidden;
}

.home-page.is-result-expanded .recipe-detail {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
  gap: 8px;
  overflow: hidden;
  min-height: 0;
}

.home-page.is-result-expanded .brief-grid {
  display: none;
}

.home-page.is-result-expanded .workspace-heading {
  gap: 8px;
}

.home-page.is-result-expanded h1 {
  font-size: clamp(22px, 2.2vw, 31px);
}

.home-page.is-result-expanded .pantry-expiry-banner {
  min-height: 34px;
  padding: 5px 10px;
}

/* Recipe results keep their natural height so the nutrition and readiness cards
   remain in normal document flow at every desktop viewport height. */
.home-page.is-result-expanded:not(.is-detail-view) {
  display: block;
  height: auto;
  min-height: calc(100vh - 58px);
  overflow: visible;
}

.home-page.is-result-expanded:not(.is-detail-view) .command-shell {
  display: block;
  height: auto;
  min-height: 0;
}

.home-page.is-result-expanded:not(.is-detail-view) .command-grid {
  display: block;
  min-height: 0;
}

.home-page.is-result-expanded:not(.is-detail-view) .result-content {
  margin-top: 9px;
}

.home-page.is-result-expanded:not(.is-detail-view) .recipe-detail > * + * {
  margin-top: 8px;
}

.home-page.is-result-expanded:not(.is-detail-view) .pantry-readiness-card,
.home-page.is-result-expanded:not(.is-detail-view) .recipe-entry-nav {
  margin-top: 14px;
}

.recipe-sections {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 8px;
  min-height: 0;
  margin-top: 0;
  overflow: hidden;
}

.recipe-section-nav {
  display: flex;
  flex-wrap: nowrap;
  gap: 6px;
  min-height: 48px;
  padding: 4px;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-surface-strong);
}

.reference-controls {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}

.reference-control {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  min-height: 58px;
  padding: 9px 11px;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-surface-strong);
}

.reference-control > div {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.reference-control strong {
  color: var(--app-text);
  font-size: 13px;
}

.reference-control span {
  color: var(--app-text-muted);
  font-size: 11px;
  line-height: 1.45;
}

.recipe-section-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  flex: 1 1 0;
  min-width: 0;
  min-height: 38px;
  padding: 0 12px;
  border: 1px solid transparent;
  border-radius: 6px;
  color: var(--app-text-muted);
  background: transparent;
  font: inherit;
  font-size: 13px;
  font-weight: 800;
  cursor: pointer;
  transition:
    border-color 180ms ease,
    background-color 180ms ease,
    color 180ms ease,
    transform 180ms ease;
}

.recipe-section-link span {
  color: var(--app-text-faint);
  font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace;
  font-size: 11px;
}

.recipe-section-link:hover,
.recipe-section-link:focus-visible,
.recipe-section-link.active {
  border-color: var(--app-accent);
  color: var(--app-text);
  background: var(--app-accent-soft);
  outline: none;
}

.recipe-section-link:active {
  transform: translateY(1px);
}

.recipe-section {
  min-height: 0;
  overflow: auto;
  display: grid;
  align-content: start;
  gap: 12px;
  padding: clamp(14px, 2vw, 22px);
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-surface);
}

.home-page.is-result-expanded .recipe-section {
  min-height: 0;
}

.section-heading {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 40px;
  padding-right: 52px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--app-line);
}

.section-heading > div {
  display: flex;
  align-items: baseline;
  gap: 9px;
}

.section-index {
  color: var(--app-accent);
  font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace;
  font-size: 12px;
  font-weight: 900;
}

.overview-card,
.tips-card,
.detail-card {
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--app-line);
  border-radius: 7px;
  background: var(--app-surface-soft);
}

.overview-card {
  display: grid;
  gap: 10px;
}

.overview-card p {
  margin: 0;
  color: var(--app-text-soft);
  line-height: 1.7;
}

.section-card-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  grid-template-rows: minmax(0, auto) auto;
  gap: 16px;
  align-items: stretch;
}

.detail-card-wide {
  grid-column: auto;
}

#recipe-section-ingredients .ingredients-detail-card {
  grid-column: 1;
  grid-row: 1;
}

#recipe-section-ingredients .analysis-detail-card {
  grid-column: 1 / -1;
  grid-row: 2;
}

#recipe-section-ingredients .shopping-detail-card {
  grid-column: 2;
  grid-row: 1;
}

#recipe-section-ingredients .ingredients-detail-card,
#recipe-section-ingredients .shopping-detail-card {
  height: 100%;
  min-width: 0;
  align-self: stretch;
}

#recipe-section-ingredients .ingredients-detail-card .ingredients-table,
#recipe-section-ingredients .shopping-detail-card :deep(.shopping-table) {
  width: 100%;
  max-width: 100%;
}

#recipe-section-ingredients .ingredients-detail-card :deep(.el-table .cell),
#recipe-section-ingredients .shopping-detail-card :deep(.el-table .cell) {
  min-width: 0;
  overflow-wrap: anywhere;
  white-space: normal;
}

#recipe-section-ingredients .ingredients-detail-card :deep(.el-table__body-wrapper),
#recipe-section-ingredients .ingredients-detail-card :deep(.el-table__inner-wrapper) {
  min-width: 0;
  max-width: 100%;
  overflow-x: hidden;
}

#recipe-section-ingredients .shopping-detail-card :deep(.shopping-checklist),
#recipe-section-ingredients .shopping-detail-card :deep(.shopping-table),
#recipe-section-ingredients .shopping-detail-card :deep(.el-table__inner-wrapper),
#recipe-section-ingredients .shopping-detail-card :deep(.el-table__body-wrapper) {
  min-width: 0;
  max-width: 100%;
}

#recipe-section-ingredients .shopping-detail-card :deep(.el-table__body-wrapper) {
  overflow-x: hidden;
}

#recipe-section-ingredients .shopping-detail-card :deep(.el-table .cell),
#recipe-section-ingredients .shopping-detail-card :deep(.purchase-links),
#recipe-section-ingredients .shopping-detail-card :deep(.purchase-links a) {
  min-width: 0;
  max-width: 100%;
  overflow-wrap: anywhere;
  white-space: normal;
}

#recipe-section-ingredients .shopping-detail-card :deep(.purchase-links) {
  display: grid;
  gap: 6px;
}

#recipe-section-ingredients .shopping-detail-card :deep(.purchase-links a) {
  width: fit-content;
  max-width: 100%;
}

#recipe-section-more .tips-card {
  grid-column: 1;
}

#recipe-section-more {
  grid-template-columns: minmax(0, 0.8fr) minmax(0, 1.2fr);
}

#recipe-section-more .section-heading {
  grid-column: 1 / -1;
}

#recipe-section-more .video-keywords {
  grid-column: 2;
  align-content: start;
}

#recipe-section-more .el-empty {
  grid-column: 1 / -1;
}

.detail-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.detail-card-head h4 {
  margin: 0;
  color: var(--app-text);
  font-size: 14px;
}

.detail-card-head span {
  color: var(--app-text-muted);
  font-size: 12px;
  font-weight: 700;
}

.ingredient-mobile-list,
.analysis-mobile-list {
  display: none;
}

.analysis-mobile-card {
  display: grid;
  gap: 8px;
  padding: 11px 0;
  border-bottom: 1px solid var(--app-line);
}

.analysis-mobile-card:last-child {
  padding-bottom: 0;
  border-bottom: 0;
}

.ingredient-mobile-row,
.analysis-mobile-heading,
.analysis-mobile-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.ingredient-mobile-row {
  min-height: 40px;
  padding: 0 10px;
  border-bottom: 1px solid var(--app-line);
}

.ingredient-mobile-row:last-child {
  border-bottom: 0;
}

.ingredient-mobile-row strong,
.analysis-mobile-heading strong {
  color: var(--app-text);
}

.ingredient-mobile-row span,
.analysis-mobile-heading span,
.analysis-mobile-meta span:last-child,
.analysis-mobile-card p {
  color: var(--app-text-muted);
  font-size: 12px;
}

.analysis-mobile-meta {
  align-items: flex-start;
}

.analysis-mobile-meta span:last-child {
  flex: 1;
  line-height: 1.45;
  text-align: right;
}

.analysis-mobile-card p {
  margin: 0;
  line-height: 1.5;
}

.full-step-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.full-step-item {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  gap: 12px;
  align-items: start;
  padding: 14px;
  border: 1px solid var(--app-line);
  border-radius: 7px;
  background: var(--app-surface-soft);
}

.full-step-index {
  display: inline-grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 50%;
  color: var(--app-accent-text);
  background: var(--app-accent);
  font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace;
  font-size: 13px;
  font-weight: 900;
}

.full-step-copy {
  min-width: 0;
}

.full-step-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.full-step-head h4 {
  margin: 0;
  color: var(--app-text);
  font-size: 15px;
}

.full-step-head span {
  flex: 0 0 auto;
  color: var(--app-text-muted);
  font-size: 12px;
  font-weight: 800;
}

.full-step-copy p {
  margin: 6px 0 0;
  color: var(--app-text-soft);
  line-height: 1.7;
}

.full-step-copy .full-step-note {
  color: var(--app-text-muted);
  font-size: 12px;
}

.tips-card {
  background: var(--app-surface);
}

.tips-card .tip-list {
  padding-left: 18px;
}

.result-header {
  display: flex;
  align-items: start;
  justify-content: space-between;
  gap: 12px;
}

.recipe-generation-loading {
  display: grid;
  min-height: 132px;
  place-items: center;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: color-mix(in srgb, var(--app-surface) 88%, var(--app-accent-soft));
  overflow: hidden;
}

.recipe-generation-loading-stage {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  min-width: min(100%, 340px);
  padding: 8px 18px;
}

.recipe-generation-loading-art {
  display: grid;
  width: 94px;
  height: 108px;
  place-items: end center;
  flex: 0 0 auto;
}

.recipe-generation-loading-art img {
  z-index: 1;
  display: block;
  width: 100%;
  height: auto;
  object-fit: contain;
  mix-blend-mode: multiply;
}

.recipe-generation-loading-copy {
  display: grid;
  gap: 5px;
  color: var(--app-text-muted);
}

.recipe-generation-loading-copy strong {
  color: var(--app-text);
  font-size: 17px;
}

.recipe-generation-loading-copy span {
  color: var(--app-text-soft);
  font-size: 12px;
}

.result-summary-line {
  max-width: 720px;
  margin: 7px 0 0;
  color: var(--app-text-soft);
  line-height: 1.55;
}

.result-header-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.result-context-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.context-tag {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 0 8px;
  border: 1px solid var(--app-line-strong);
  border-radius: 999px;
  color: var(--app-text-muted);
  background: var(--app-surface-strong);
  font-size: 11px;
  font-weight: 800;
}

.context-tag-stock {
  border-color: color-mix(in srgb, var(--el-color-success) 40%, var(--app-line));
  color: var(--el-color-success);
}

.context-tag-warning {
  border-color: color-mix(in srgb, var(--el-color-warning) 42%, var(--app-line));
  color: #8d631b;
}

.ingredient-input-hint {
  margin: 5px 0 0;
  color: var(--app-text-faint);
  font-size: 11px;
  font-weight: 700;
  line-height: 1.4;
}

.recipe-skeleton-line {
  display: block;
  width: 100%;
  height: 14px;
  border-radius: 999px;
  background: linear-gradient(90deg, var(--app-surface-soft), var(--app-line), var(--app-surface-soft));
  background-size: 200% 100%;
  animation: recipe-skeleton-shimmer 1.6s ease-in-out infinite;
}

.recipe-skeleton-line-wide {
  width: min(88%, 520px);
  margin-top: 7px;
}

.recipe-skeleton-line-short {
  width: 62%;
}

.stream-status {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 9px;
  padding: 8px 10px;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  color: var(--app-text-muted);
  background: var(--app-surface);
  font-size: 11px;
  line-height: 1.45;
}

.stream-status strong,
.stream-status span {
  display: block;
}

.stream-status strong {
  color: var(--app-text);
  font-size: 12px;
}

.stream-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--app-accent);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--app-accent) 16%, transparent);
}

.context-fallback-alert {
  padding: 8px 10px;
  border: 1px solid color-mix(in srgb, var(--el-color-warning) 42%, var(--app-line));
  border-radius: 8px;
  color: #8d631b;
  background: color-mix(in srgb, var(--el-color-warning) 10%, var(--app-surface));
  font-size: 12px;
  line-height: 1.5;
}

.latest-content-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 36px;
  padding: 4px 8px 4px 10px;
  border: 1px solid var(--app-line-strong);
  border-radius: 7px;
  color: var(--app-text-muted);
  background: var(--app-surface-strong);
  font-size: 11px;
}

.latest-content-bar button {
  min-height: 30px;
  padding: 0 9px;
  border: 1px solid var(--app-accent);
  border-radius: 5px;
  color: var(--app-accent-text);
  background: var(--app-accent);
  font: inherit;
  font-size: 11px;
  font-weight: 800;
  cursor: pointer;
}

.latest-content-bar button:focus-visible,
.latest-content-bar button:hover {
  background: var(--app-accent-hover);
  outline: none;
}

.recommendation-selector {
  display: grid;
  gap: 8px;
  padding: 10px;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-surface-strong);
}

.recommendation-selector-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-width: 0;
}

.recommendation-selector-heading > div {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.recommendation-selector-heading strong {
  color: var(--app-text);
  font-size: 13px;
}

.recommendation-selector-heading > span {
  flex: 0 0 auto;
  color: var(--app-text-muted);
  font-size: 12px;
  font-weight: 800;
}

.recommendation-selector-track {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  grid-auto-flow: row;
  gap: 8px;
  min-width: 0;
  overflow: hidden;
}

.recommendation-card {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  min-width: 0;
  min-height: 60px;
  padding: 9px 10px;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  color: var(--app-text-muted);
  background: var(--app-surface);
  font: inherit;
  text-align: left;
  cursor: pointer;
  scroll-snap-align: start;
  transition: border-color 160ms ease, background-color 160ms ease, color 160ms ease;
}

.recommendation-card:hover,
.recommendation-card:focus-visible,
.recommendation-card.active {
  border-color: var(--app-accent);
  color: var(--app-text);
  background: var(--app-accent-soft);
  outline: none;
}

.recommendation-card-index {
  color: var(--app-accent);
  font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace;
  font-size: 12px;
  font-weight: 900;
}

.recommendation-card-copy {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.recommendation-card-copy strong,
.recommendation-card-copy small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recommendation-card-copy strong {
  color: inherit;
  font-size: 13px;
}

.recommendation-card-copy small {
  color: var(--app-text-muted);
  font-size: 11px;
}

.recommendation-card-state {
  color: var(--app-text-faint);
  font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace;
  font-size: 16px;
  font-weight: 900;
}

.recommendation-card.ready .recommendation-card-state {
  color: var(--app-success, #1b9a72);
}

@container scene-window-content (max-width: 920px) {
  .recommendation-selector-track {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@container scene-window-content (max-width: 620px) {
  .recommendation-selector-track {
    grid-template-columns: repeat(1, minmax(0, 1fr));
  }
}

@media (max-width: 960px) {
  .recommendation-selector-track {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .recommendation-selector-track {
    grid-template-columns: repeat(1, minmax(0, 1fr));
  }
}

.stream-progress-panel {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.stream-progress-card {
  min-width: 0;
  padding: 10px;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-surface);
}

.stream-progress-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.stream-progress-card-head h3 {
  margin: 0;
  font-size: 13px;
}

.stream-progress-card-head span {
  color: var(--app-text-faint);
  font-size: 11px;
}

.stream-preview-list {
  display: grid;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.stream-preview-list li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  min-width: 0;
  color: var(--app-text-soft);
  font-size: 12px;
}

.stream-preview-list strong,
.stream-preview-list span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stream-preview-list strong {
  color: var(--app-text);
}

.stream-preview-list span {
  color: var(--app-text-muted);
}

.stream-preview-steps li {
  grid-template-columns: auto minmax(0, 1fr);
}

.stream-preview-skeleton {
  display: grid;
  gap: 10px;
}

@keyframes recipe-skeleton-shimmer {
  from { background-position: 100% 0; }
  to { background-position: -100% 0; }
}

@media (prefers-reduced-motion: reduce) {
  .recipe-skeleton-line {
    animation: none;
  }
}

@media (max-width: 520px) {
  .recipe-generation-loading-stage {
    gap: 8px;
    min-width: 0;
    padding: 8px 12px;
  }

  .recipe-generation-loading-art {
    width: 78px;
    height: 92px;
  }

  .recipe-generation-loading-copy strong {
    font-size: 15px;
  }

  .stream-progress-panel {
    grid-template-columns: 1fr;
  }
}

.home-page.is-result-expanded .recipe-detail :deep(.nutrition-card) {
  display: grid;
  grid-template-columns: minmax(130px, 0.34fr) minmax(0, 1fr) minmax(170px, 0.46fr);
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
}

.home-page.is-result-expanded .recipe-detail :deep(.nutrition-card-heading) {
  align-items: center;
}

.home-page.is-result-expanded .recipe-detail :deep(.nutrition-card-heading h3) {
  font-size: 14px;
}

.home-page.is-result-expanded .recipe-detail :deep(.nutrition-metrics) {
  gap: 6px;
}

.home-page.is-result-expanded .recipe-detail :deep(.nutrition-metric) {
  gap: 2px;
  padding: 6px 8px;
}

.home-page.is-result-expanded .recipe-detail :deep(.nutrition-metric strong) {
  font-size: 16px;
}

.home-page.is-result-expanded .recipe-detail :deep(.nutrition-disclosure) {
  margin: 0;
  font-size: 11px;
}

.result-header-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

.save-recipe-button,
.regenerate-recipe-button,
.start-cooking-button,
.finished-dish-review-button {
  white-space: nowrap;
}

.save-recipe-button {
  --el-button-text-color: #000000;
  --el-button-hover-text-color: #000000;
  --el-button-active-text-color: #000000;
  color: #000000 !important;
}

.regenerate-recipe-button :deep(span),
.save-recipe-button :deep(span),
.start-cooking-button :deep(span),
.finished-dish-review-button :deep(span) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.empty-stage {
  display: grid;
  place-items: center;
  align-content: center;
  gap: 10px;
  min-height: 0;
  border: 1px dashed var(--app-line-strong);
  border-radius: 8px;
  color: var(--app-text-muted);
  text-align: center;
}

.empty-stage strong {
  color: var(--app-text);
  font-size: 18px;
}

.empty-stage span {
  width: min(420px, 92%);
  line-height: 1.7;
}

.result-content {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 9px;
  min-height: 0;
  overscroll-behavior: contain;
}

.home-page.is-result-expanded:not(.is-detail-view) .result-content {
  max-height: min(760px, calc(100vh - 318px));
  overflow-y: auto;
  scrollbar-width: thin;
}

.home-page.is-result-expanded:not(.is-detail-view) .result-content:focus-visible {
  outline: 2px solid var(--app-accent);
  outline-offset: -2px;
}

.brief-grid {
  display: grid;
  grid-template-columns: 1.5fr 0.7fr 0.8fr;
  gap: 8px;
  margin: 0;
}

.brief-grid div {
  min-width: 0;
  padding: 9px;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-surface);
}

.brief-grid dt {
  margin-bottom: 4px;
  color: var(--app-text-faint);
  font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace;
  font-size: 12px;
  font-weight: 800;
}

.brief-grid dd {
  margin: 0;
  overflow: hidden;
  color: var(--app-text);
  font-weight: 800;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recipe-detail,
.next-steps {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 9px;
  min-height: 0;
}

.recipe-summary-block {
  display: grid;
  gap: 8px;
  padding: 9px;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-surface-strong);
}

.recipe-summary-block p {
  display: -webkit-box;
  max-height: 44px;
  margin: 0;
  overflow: hidden;
  color: var(--app-text-soft);
  line-height: 1.55;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.recipe-pages {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 8px;
  min-height: 0;
}

.page-toolbar {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr) 34px;
  align-items: center;
  gap: 8px;
}

.page-arrow {
  display: inline-grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border: 1px solid var(--app-line-strong);
  border-radius: 6px;
  color: var(--app-text);
  background: var(--app-surface);
  cursor: pointer;
  transition:
    border-color 180ms ease,
    background-color 180ms ease,
    color 180ms ease;
}

.page-arrow:hover:not(:disabled),
.page-arrow:focus-visible {
  border-color: var(--app-accent);
  background: var(--app-surface-soft);
  outline: none;
}

.page-arrow:disabled {
  cursor: not-allowed;
  opacity: 0.42;
}

.page-tabs {
  display: flex;
  gap: 6px;
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: thin;
}

.page-tab {
  flex: 0 0 auto;
  min-width: 82px;
  min-height: 34px;
  padding: 0 8px;
  border: 1px solid var(--app-line);
  border-radius: 6px;
  color: var(--app-text-muted);
  background: var(--app-surface);
  font-size: 12px;
  font-weight: 800;
  cursor: pointer;
  transition:
    border-color 180ms ease,
    background-color 180ms ease,
    color 180ms ease;
}

.page-tab:hover,
.page-tab:focus-visible,
.page-tab.active {
  border-color: var(--app-accent);
  color: var(--app-text);
  background: var(--app-surface-soft);
  outline: none;
}

.recipe-page-window,
.next-steps {
  min-height: 0;
  padding: 10px;
  overflow: auto;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-surface);
}

.window-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}

.recipe-page-window :deep(.el-table),
.next-steps :deep(.el-table) {
  overflow: hidden;
  border: 1px solid var(--app-line);
  border-radius: 8px;
}

.step-list,
.tip-list {
  display: grid;
  gap: 8px;
  margin: 0;
  padding-left: 22px;
}

.step-list li,
.tip-list li {
  color: var(--app-text-soft);
  line-height: 1.52;
}

.step-list strong {
  color: var(--app-text);
}

.step-list span {
  margin-left: 8px;
  color: var(--app-text-faint);
  font-size: 13px;
  font-weight: 800;
}

.step-list p {
  margin: 4px 0 0;
}

.video-keywords {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.video-keywords span,
.video-keywords a {
  gap: 6px;
  border-radius: 6px;
  color: var(--app-text);
  text-decoration: none;
}

.ingredient-state {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 0 7px;
  border: 1px solid var(--app-line-strong);
  border-radius: 4px;
  font-size: 11px;
  font-weight: 900;
}

.ingredient-state.owned {
  color: var(--el-color-success);
  background: var(--app-surface-soft);
}

.ingredient-state.missing {
  color: var(--el-color-warning);
  background: var(--app-surface-soft);
}

.purchase-links {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.purchase-links a {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 26px;
  padding: 0 7px;
  border: 1px solid var(--app-line-strong);
  border-radius: 4px;
  color: var(--app-text);
  background: var(--app-surface-soft);
  font-size: 12px;
  font-weight: 800;
}

.explanation-grid {
  display: grid;
  gap: 8px;
}

.explanation-item {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 9px;
  padding: 10px;
  border: 1px solid var(--app-line);
  border-radius: 6px;
  background: var(--app-surface-soft);
}

.explanation-item h4,
.explanation-item p {
  margin: 0;
}

.explanation-item h4 {
  color: var(--app-text);
  font-size: 13px;
}

.explanation-item p {
  margin-top: 4px;
  color: var(--app-text-soft);
  font-size: 13px;
  line-height: 1.55;
}

@media (max-width: 980px) {
  .home-page {
    height: auto;
    min-height: calc(100vh - 58px);
    overflow: visible;
  }

  .command-grid {
    grid-template-columns: 1fr;
  }

  .result-panel {
    min-height: 620px;
  }

  .home-page.is-result-expanded .result-panel {
    min-height: 0;
  }

  .pantry-readiness-metrics,
  .pantry-readiness-skeleton {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .home-page.is-result-expanded {
    height: auto;
    min-height: calc(100vh - 58px);
    overflow: visible;
    display: block;
  }

  .home-page.is-result-expanded .command-shell {
    height: auto;
    min-height: 0;
  }

  .home-page.is-result-expanded .result-panel,
  .home-page.is-result-expanded .result-content,
  .home-page.is-result-expanded .recipe-detail {
    display: block;
    overflow: visible;
  }

  .home-page.is-result-expanded .recipe-sections {
    overflow: visible;
  }

  .home-page.is-detail-view {
    height: auto;
    min-height: calc(100vh - 58px);
    overflow: visible;
  }

  .home-page.is-detail-view .command-grid {
    grid-template-rows: minmax(0, 1fr);
  }

  .home-page.is-detail-view .result-panel,
  .home-page.is-detail-view .result-content,
  .home-page.is-detail-view .recipe-detail {
    display: grid;
    min-height: 0;
    overflow: hidden;
  }

  .home-page.is-detail-view .result-panel {
    display: flex;
    flex-direction: column;
  }

  .home-page.is-detail-view .result-content {
    display: block;
    flex: 1 1 0;
    overflow: hidden;
  }

  .home-page.is-detail-view .recipe-detail {
    display: block;
    height: 100%;
  }

  .home-page.is-detail-view .recipe-sections,
  .home-page.is-detail-view .recipe-section {
    height: 100%;
    overflow: auto;
  }

  .recipe-detail-view-header {
    grid-template-columns: auto minmax(0, 1fr) auto;
  }

  .section-card-grid {
    grid-template-columns: 1fr;
  }

  #recipe-section-ingredients .ingredients-detail-card,
  #recipe-section-ingredients .analysis-detail-card,
  #recipe-section-ingredients .shopping-detail-card {
    grid-column: 1;
    grid-row: auto;
  }

  #recipe-section-more {
    grid-template-columns: 1fr;
  }

  #recipe-section-more .section-heading,
  #recipe-section-more .tips-card,
  #recipe-section-more .video-keywords,
  #recipe-section-more .el-empty {
    grid-column: 1;
  }
}

@media (max-width: 720px) {
  .home-page {
    min-height: calc(100vh - 121px);
    padding: 16px;
  }

  .workspace-heading {
    align-items: start;
    flex-direction: column;
  }

  .result-header {
    flex-direction: column;
  }

  .result-header-actions {
    width: 100%;
    justify-content: flex-start;
  }

  .signal-strip {
    justify-content: flex-start;
  }

  .pantry-expiry-banner {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .pantry-expiry-link {
    margin-left: 27px;
  }

  .mode-grid,
  .filters,
  .brief-grid,
  .page-tabs {
    grid-template-columns: 1fr;
  }

  .search-actions {
    grid-template-columns: 1fr;
  }

  .camera-capture-panel {
    grid-template-columns: 1fr;
  }

  .camera-capture-panel :deep(.el-button) {
    width: 100%;
  }

  .query-summary-bar {
    align-items: stretch;
    flex-direction: column;
  }

  .query-summary-copy > strong {
    white-space: normal;
  }

  .query-summary-actions {
    justify-content: stretch;
  }

  .query-summary-actions :deep(.el-button) {
    flex: 1 1 0;
  }

  .pantry-readiness-head,
  .pantry-readiness-footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .pantry-readiness-status {
    align-self: flex-start;
  }

  .pantry-readiness-metrics,
  .pantry-readiness-skeleton {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .pantry-readiness-actions {
    width: 100%;
    justify-content: stretch;
  }

  .pantry-readiness-actions :deep(.el-button) {
    flex: 1 1 0;
  }

  .recipe-entry-nav,
  .recipe-detail-bottom-nav {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .recipe-entry-button {
    min-height: 72px;
    padding: 10px;
  }

  .recipe-detail-view-header {
    grid-template-columns: auto minmax(0, 1fr);
    align-items: start;
  }

  .detail-view-actions {
    grid-column: 2;
    justify-content: flex-start;
  }

  .detail-view-title h2 {
    white-space: normal;
  }

  .home-page.is-detail-view .result-panel {
    min-height: calc(100vh - 121px);
  }

  .recipe-detail-bottom-nav {
    position: sticky;
    bottom: 0;
    z-index: 2;
    padding-bottom: max(8px, env(safe-area-inset-bottom));
  }

  .recipe-section-nav {
    top: 10px;
  }

  .recipe-section-link {
    flex: 1 1 calc(50% - 6px);
    min-width: 0;
  }

  .section-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .full-step-item {
    grid-template-columns: 30px minmax(0, 1fr);
    gap: 10px;
    padding: 12px;
  }

  .full-step-index {
    width: 30px;
    height: 30px;
  }

  .full-step-head {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
  }

  .full-step-list {
    grid-template-columns: 1fr;
  }

  .home-page.is-result-expanded .recipe-detail :deep(.nutrition-card) {
    display: grid;
    grid-template-columns: 1fr;
  }

  .ingredients-table,
  .analysis-table {
    display: none;
  }

  .ingredient-mobile-list,
  .analysis-mobile-list {
    display: grid;
  }
}

@media (prefers-reduced-motion: reduce) {
  .pantry-readiness-progress i,
  .pantry-readiness-skeleton-block {
    animation: none;
    transition: none;
  }

  .recipe-section-link,
  .recipe-entry-button,
  .recipe-detail-bottom-link,
  .mode-card,
  .page-arrow,
  .page-tab {
    transition: none;
  }
}

/* The kitchen scene owns the scroll viewport when this page is embedded. */
.home-page.is-embedded,
.home-page.is-embedded.is-result-expanded,
.home-page.is-embedded.is-result-expanded:not(.is-detail-view),
.home-page.is-embedded.is-detail-view {
  height: auto;
  min-height: 100%;
  overflow: visible;
}

.home-page.is-embedded .command-shell,
.home-page.is-embedded.is-result-expanded .command-shell,
.home-page.is-embedded.is-result-expanded:not(.is-detail-view) .command-shell {
  height: auto;
  min-height: 0;
}

.home-page.is-embedded .command-grid,
.home-page.is-embedded.is-result-expanded:not(.is-detail-view) .command-grid {
  display: block;
  min-height: 0;
}

.home-page.is-embedded .result-panel,
.home-page.is-embedded.is-result-expanded .result-panel,
.home-page.is-embedded.is-result-expanded:not(.is-detail-view) .result-panel {
  display: block;
  min-height: 0;
  overflow: visible;
}

.home-page.is-embedded .result-content,
.home-page.is-embedded.is-result-expanded:not(.is-detail-view) .result-content {
  display: block;
  max-height: none;
  min-height: 0;
  overflow: visible;
  overscroll-behavior: auto;
}

.home-page.is-embedded .recipe-detail,
.home-page.is-embedded.is-detail-view .recipe-detail,
.home-page.is-embedded .recipe-sections,
.home-page.is-embedded.is-detail-view .recipe-sections,
.home-page.is-embedded .recipe-section,
.home-page.is-embedded.is-detail-view .recipe-section {
  display: block;
  height: auto;
  min-height: 0;
  overflow: visible;
}
</style>
