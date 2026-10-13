<template>
  <main class="saved-page">
    <header class="saved-heading">
      <div>
        <p class="eyebrow">个人菜谱库</p>
        <h1>我的菜谱</h1>
        <p>同名菜谱只保存一次，再次保存时展示已有菜谱。</p>
      </div>
      <div class="saved-heading-actions">
        <el-button class="print-button" plain type="button" @click="printRecipe">
          <Printer :size="16" aria-hidden="true" />
          <span>打印菜谱</span>
        </el-button>
        <RouterLink class="back-link" to="/">
        <ArrowLeft :size="16" aria-hidden="true" />
        <span>返回工作台</span>
        </RouterLink>
      </div>
    </header>

    <div class="history-layout">
      <section class="history-panel" aria-label="已保存菜谱列表">
        <div class="panel-heading">
          <div>
            <span class="panel-kicker">已保存菜谱</span>
            <h2>已保存</h2>
          </div>
          <div class="panel-heading-actions">
            <span class="count-badge">{{ totalRecipes }}</span>
            <el-button class="manage-button" plain type="button" @click="batchMode = !batchMode">
              {{ batchMode ? '退出批量' : '批量管理' }}
            </el-button>
            <el-button class="manage-button" plain type="button" @click="openShareManager">分享管理</el-button>
          </div>
        </div>

        <div class="collection-list" aria-label="收藏夹">
          <button
            v-for="collection in collections"
            :key="collection.id"
            class="collection-filter"
            :class="{ active: selectedCollectionId === collection.id }"
            type="button"
            @click="selectCollection(collection.id)"
          >
            <span>{{ collection.name }}</span>
            <span>{{ collection.recipeCount }}</span>
          </button>
          <div class="collection-actions">
            <el-button link type="primary" @click="openCollectionDialog()">新建收藏夹</el-button>
            <el-button link :disabled="!selectedCustomCollection" @click="openCollectionDialog(selectedCustomCollection)">重命名</el-button>
            <el-button link type="danger" :disabled="!selectedCustomCollection" @click="confirmDeleteCollection(selectedCustomCollection)">删除</el-button>
          </div>
        </div>

        <form class="recipe-filters" role="search" @submit.prevent="applyFilters">
          <div class="filter-search-row">
            <el-input
              v-model="keywordDraft"
              aria-label="按菜名搜索"
              clearable
              placeholder="搜索菜名"
              @clear="applyFilters"
            >
              <template #prefix>
                <Search :size="15" aria-hidden="true" />
              </template>
            </el-input>
            <el-tooltip content="搜索菜谱" placement="top">
              <button class="search-button" type="submit" aria-label="搜索菜谱">
                <Search :size="16" aria-hidden="true" />
              </button>
            </el-tooltip>
          </div>
          <div class="filter-select-row">
            <el-select v-model="mealType" aria-label="筛选餐次" placeholder="不限餐次" @change="applyFilters">
              <el-option label="不限餐次" value="" />
              <el-option label="早餐" value="breakfast" />
              <el-option label="午餐" value="lunch" />
              <el-option label="晚餐" value="dinner" />
            </el-select>
            <el-select v-model="goal" aria-label="筛选目标" placeholder="不限目标" @change="applyFilters">
              <el-option label="不限目标" value="" />
              <el-option label="营养均衡" value="balanced" />
              <el-option label="高蛋白" value="protein" />
              <el-option label="低热量" value="light" />
              <el-option label="快速烹饪" value="quick" />
              <el-option label="减脂" value="fat_loss" />
              <el-option label="增肌" value="muscle_gain" />
              <el-option label="控糖" value="low_sugar" />
            </el-select>
          </div>
          <div class="filter-select-row">
            <el-select v-model="tagFilter" aria-label="按标签筛选" placeholder="全部标签" clearable @change="applyFilters">
              <el-option v-for="tag in tags" :key="tag.id" :label="tag.name" :value="tag.name" />
            </el-select>
            <el-select v-model="sortOrder" aria-label="排序方式" @change="applyFilters">
              <el-option label="最近保存" value="desc" />
              <el-option label="最早保存" value="asc" />
            </el-select>
          </div>
        </form>

        <div v-if="batchMode" class="bulk-toolbar">
          <label class="select-all-control">
            <input type="checkbox" :checked="allVisibleSelected" @change="toggleSelectAll" />
            <span>全选当前页（{{ selectedRecipeIds.length }}）</span>
          </label>
          <div class="bulk-actions">
            <el-button size="small" :disabled="!selectedRecipeIds.length" @click="openBatchMove">移动收藏夹</el-button>
            <el-button size="small" :disabled="!selectedRecipeIds.length" @click="openBatchTagDialog('add')">添加标签</el-button>
            <el-button size="small" :disabled="!selectedRecipeIds.length" @click="openBatchTagDialog('remove')">移除标签</el-button>
            <el-button size="small" type="danger" plain :disabled="!selectedRecipeIds.length" @click="confirmBatchDelete">批量删除</el-button>
          </div>
        </div>

        <el-skeleton v-if="loading" :rows="5" animated />
        <el-empty v-else-if="!recipes.length" description="还没有保存菜谱" />
        <div v-else class="history-items" :class="{ 'batch-active': batchMode }">
          <div
            v-for="item in recipes"
            :key="item.groupKey"
            class="history-item-row"
            :class="{ active: selectedGroupKey === item.groupKey }"
          >
            <input
              v-if="batchMode"
              class="recipe-checkbox"
              type="checkbox"
              :checked="selectedRecipeIds.includes(item.groupKey)"
              :aria-label="`选择菜谱 ${item.title}`"
              @click.stop
              @change="toggleRecipeSelection(item.groupKey)"
            />
            <span class="recipe-cover" aria-hidden="true">
              <img v-if="recipeCoverUrl(item)" :src="recipeCoverUrl(item)" alt="" />
              <ChefHat v-else :size="21" />
            </span>
            <button class="history-item" type="button" @click="openGroup(item)">
              <span class="history-item-title">{{ item.title }}</span>
              <span class="history-item-ingredients">{{ item.searchIngredients || '未记录食材' }}</span>
              <span v-if="item.tags?.length" class="history-item-tags">
                <span v-for="tag in item.tags" :key="tag" class="recipe-tag">#{{ tag }}</span>
              </span>
              <span class="history-item-meta">
                <span>{{ mealLabel(item.mealType) }}</span>
                <span>{{ formatDate(item.savedAt) }}</span>
              </span>
            </button>
            <el-tooltip content="删除菜谱" placement="top">
              <button
                class="delete-button"
                type="button"
                :aria-label="`删除菜谱：${item.title}`"
                :disabled="deletingId !== null"
                @click="confirmDeleteRecipe(item)"
              >
                <Trash2 :size="16" aria-hidden="true" />
              </button>
            </el-tooltip>
          </div>
        </div>
        <el-pagination
          v-if="totalRecipes > pageSize"
          class="recipe-pagination"
          background
          layout="prev, pager, next"
          :current-page="currentPage"
          :page-size="pageSize"
          :total="totalRecipes"
          @current-change="handlePageChange"
        />
      </section>

      <section class="detail-panel" aria-label="菜谱详情">
        <el-skeleton v-if="detailLoading" :rows="9" animated />
        <el-empty v-else-if="!selected" description="选择一个菜谱查看详情" />
        <template v-else>
          <header class="detail-heading">
            <div>
              <p class="eyebrow">已保存菜谱</p>
              <h2>{{ selected.recipe?.title || '菜谱详情' }}</h2>
              <p class="saved-time">保存于 {{ formatDate(selected.savedAt) }}</p>
            </div>
            <div class="detail-heading-actions">
              <el-button
                v-if="selected.recipe"
                class="finished-dish-review-button"
                plain
                @click="openFinishedDishReview"
              >
                <Sparkles :size="16" aria-hidden="true" />
                <span>评价成品</span>
              </el-button>
              <el-button
                v-if="selected.recipe?.steps?.length"
                class="start-cooking-button"
                type="primary"
                @click="openCookingMode"
              >
                <Play :size="16" aria-hidden="true" />
                <span>开始烹饪</span>
              </el-button>
              <el-button v-if="selected.recipe" plain size="small" @click="openShareDialog(selected.id)">
                <Share2 :size="15" aria-hidden="true" />
                <span>分享</span>
              </el-button>
              <el-button v-if="selected.recipe" plain size="small" @click="openTagEditor">
                <Tags :size="15" aria-hidden="true" />
                <span>标签</span>
              </el-button>
              <el-button v-if="selected.recipe" plain size="small" @click="openMoveSelected">
                <FolderInput :size="15" aria-hidden="true" />
                <span>移动</span>
              </el-button>
              <span class="detail-id">#{{ selected.id }}</span>
            </div>
          </header>

          <div v-if="selected.recipe" class="recipe-detail">
            <div class="detail-collection-meta">
              <span>收藏夹：{{ selectedListItem?.collectionName || defaultCollectionName }}</span>
              <span v-if="selectedListItem?.tags?.length">标签：{{ selectedListItem.tags.join('、') }}</span>
            </div>
            <p class="summary">{{ selected.recipe.summary || '暂无菜谱简介' }}</p>
            <div v-if="selected.recipe.effects?.length" class="tag-row" aria-label="菜谱功效">
              <span v-for="effect in selected.recipe.effects" :key="effect" class="system-tag">
                {{ effect }}
              </span>
            </div>

            <RecommendationFeedbackButtons
              :reaction="feedbackReaction"
              :cooked="feedbackCooked"
              :loading="feedbackLoading"
              :disabled="!selected.searchLogId"
              @toggle-reaction="toggleRecommendationReaction"
            />

            <NutritionEstimateCard
              :nutrition="selected.recipe.nutritionEstimate"
              :nutrition-target="nutritionTarget"
            />

            <div class="recipe-pages">
              <div class="page-tabs" role="tablist" aria-label="菜谱详情分页">
                <button
                  v-for="page in recipePages"
                  :key="page.key"
                  class="page-tab"
                  :class="{ active: activePage === page.key }"
                  type="button"
                  role="tab"
                  :aria-selected="activePage === page.key"
                  @click="activePage = page.key"
                >
                  {{ page.label }}
                </button>
              </div>

              <div class="recipe-window">
                <el-table v-if="activePage === 'ingredients'" :data="selected.recipe.ingredients" size="large">
                  <el-table-column prop="name" label="食材" min-width="150" />
                  <el-table-column prop="amount" label="用量" min-width="120" />
                </el-table>

                <el-table v-else-if="activePage === 'analysis'" :data="ingredientAnalysisRows" size="large">
                  <el-table-column prop="name" label="食材" min-width="105" />
                  <el-table-column prop="amount" label="用量" min-width="90" />
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
                  v-else-if="activePage === 'shopping'"
                  :items="shoppingList"
                  :overrides="shoppingCheckOverrides"
                  :saving-key="shoppingCheckSavingKey"
                  source-type="RECIPE"
                  :source-id="selected?.id"
                  :stock-in-key="stockInKey"
                  @status-change="toggleShoppingItem"
                  @purchase-search="preparePlatformSearch"
                  @stock-in="handleStockIn"
                />

                <div v-else-if="activePage === 'explanation'" class="explanation-grid">
                  <article v-for="item in explanationItems" :key="item.key" class="explanation-item">
                    <component :is="item.icon" :size="18" aria-hidden="true" />
                    <div>
                      <h3>{{ item.label }}</h3>
                      <p>{{ item.content }}</p>
                    </div>
                  </article>
                </div>

                <ol v-else-if="activePage === 'steps'" class="step-list">
                  <li v-for="step in selected.recipe.steps" :key="step.order || step.title">
                    <div class="step-title-row">
                      <strong>{{ step.title }}</strong>
                      <span v-if="step.durationMinutes">约 {{ step.durationMinutes }} 分钟</span>
                    </div>
                    <p>{{ step.description }}</p>
                  </li>
                </ol>

                <ul v-else-if="activePage === 'tips'" class="tip-list">
                  <li v-for="tip in selected.recipe.tips" :key="tip">{{ tip }}</li>
                </ul>

                <CookingVideoSearch
                  v-else
                  :recipe-title="selected.recipe.title"
                  :keywords="videoKeywords"
                  :recipe-ready="Boolean(selected.recipe?.title)"
                />
              </div>
            </div>

            <section class="print-recipe" aria-label="打印菜谱">
              <header class="print-recipe-heading">
                <p class="eyebrow">菜谱打印</p>
                <h2>{{ selected.recipe.title || '菜谱详情' }}</h2>
              </header>

              <div class="print-recipe-sections">
                <section class="print-recipe-section">
                  <div class="print-section-heading">
                    <span class="print-section-number">01</span>
                    <h3>所需食材</h3>
                  </div>
                  <ul v-if="selected.recipe.ingredients?.length" class="print-ingredient-list">
                    <li v-for="item in selected.recipe.ingredients" :key="`${item.name}-${item.amount}`">
                      <span>{{ item.name || '未命名食材' }}</span>
                      <strong>{{ item.amount || '适量' }}</strong>
                    </li>
                  </ul>
                  <p v-else class="print-empty">暂无所需食材</p>
                </section>

                <section class="print-recipe-section">
                  <div class="print-section-heading">
                    <span class="print-section-number">02</span>
                    <h3>烹饪步骤</h3>
                  </div>
                  <ol v-if="selected.recipe.steps?.length" class="print-step-list">
                    <li v-for="(step, index) in selected.recipe.steps" :key="step.order || index">
                      <div class="print-step-index">{{ step.order || index + 1 }}</div>
                      <div>
                        <strong>{{ step.title || '烹饪步骤' }}</strong>
                        <span v-if="step.durationMinutes" class="print-step-duration">约 {{ step.durationMinutes }} 分钟</span>
                        <p>{{ step.description || '暂无步骤说明' }}</p>
                      </div>
                    </li>
                  </ol>
                  <p v-else class="print-empty">暂无烹饪步骤</p>
                </section>

                <section class="print-recipe-section">
                  <div class="print-section-heading">
                    <span class="print-section-number">03</span>
                    <h3>烹饪建议</h3>
                  </div>
                  <ul v-if="selected.recipe.tips?.length" class="print-tip-list">
                    <li v-for="tip in selected.recipe.tips" :key="tip">{{ tip || '暂无烹饪建议' }}</li>
                  </ul>
                  <p v-else class="print-empty">暂无烹饪建议</p>
                </section>
              </div>
            </section>
          </div>
        </template>
      </section>
    </div>
  </main>
  <CookingModeDialog
    v-if="selected?.recipe"
    v-model="cookingModeVisible"
    :recipe="selected.recipe"
    :storage-key="cookingStorageKey"
    :recipe-id="selected.id"
    :search-log-id="selected.searchLogId"
  />
  <StockInDialog
    v-model="stockInDialogVisible"
    :item="stockInItem"
    :loading="Boolean(stockInKey)"
    @confirm="handleStockInConfirm"
  />
  <FinishedDishReviewDialog
    v-if="selected?.recipe"
    v-model="finishedDishReviewVisible"
    :recipe="selected.recipe"
    :recipe-id="selected.id"
  />

  <el-dialog v-model="collectionDialogVisible" :title="collectionDialogMode === 'create' ? '新建收藏夹' : '重命名收藏夹'" width="420px">
    <el-input v-model="collectionDraft" maxlength="64" show-word-limit placeholder="请输入收藏夹名称" @keyup.enter="submitCollection" />
    <template #footer>
      <el-button @click="collectionDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="collectionSaving" @click="submitCollection">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="batchMoveDialogVisible" title="移动到收藏夹" width="420px">
    <el-select v-model="batchMoveCollectionId" class="dialog-control" placeholder="选择收藏夹">
      <el-option v-for="collection in collections" :key="collection.id" :label="collection.name" :value="collection.id" />
    </el-select>
    <template #footer>
      <el-button @click="batchMoveDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="batchSaving" @click="submitBatchMove">移动</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="tagEditorVisible" title="编辑菜谱标签" width="460px">
    <el-input v-model="tagEditorDraft" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="多个标签用逗号或空格分隔，最多 10 个" />
    <template #footer>
      <el-button @click="tagEditorVisible = false">取消</el-button>
      <el-button type="primary" :loading="tagSaving" @click="submitTagEditor">保存标签</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="batchTagDialogVisible" :title="batchTagMode === 'add' ? '批量添加标签' : '批量移除标签'" width="460px">
    <el-input v-model="batchTagDraft" type="textarea" :rows="3" maxlength="200" placeholder="多个标签用逗号、空格或 # 分隔" />
    <template #footer>
      <el-button @click="batchTagDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="batchSaving" @click="submitBatchTags">确认</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="shareDialogVisible" title="创建分享链接" width="430px">
    <el-radio-group v-model="shareValidity">
      <el-radio-button label="1">1 天</el-radio-button>
      <el-radio-button label="7">7 天</el-radio-button>
      <el-radio-button label="30">30 天</el-radio-button>
      <el-radio-button label="PERMANENT">永久</el-radio-button>
    </el-radio-group>
    <template #footer>
      <el-button @click="shareDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="shareSaving" @click="submitShare">创建并复制链接</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="shareManagerVisible" title="我的分享链接" width="680px">
    <el-skeleton v-if="sharesLoading" :rows="3" animated />
    <el-empty v-else-if="!shares.length" description="暂无分享记录" />
    <div v-else class="share-list">
      <div v-for="share in shares" :key="share.id" class="share-item">
        <div>
          <strong>{{ share.recipeTitle || '已删除菜谱' }}</strong>
          <p>{{ share.shareUrl }} · {{ shareStatusLabel(share.status) }}</p>
        </div>
        <div class="share-actions">
          <el-button size="small" @click="copyShare(share)">复制</el-button>
          <el-button v-if="share.status === 'ACTIVE'" size="small" type="danger" plain @click="disableShare(share)">失效</el-button>
        </div>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, markRaw, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft,
  ChefHat,
  Flame,
  FolderInput,
  HeartPulse,
  Network,
  Play,
  Printer,
  Search,
  Share2,
  Sparkles,
  Tags,
  Trash2
} from 'lucide-vue-next'
import {
  clearRecommendationReaction,
  getRecommendationFeedback,
  getSavedRecipe,
  setRecommendationReaction
} from '../api/recipes'
import {
  batchDeleteRecipeGroups as batchDeleteSavedRecipes,
  batchMoveRecipeGroups as batchMoveSavedRecipes,
  batchTagRecipeGroups as batchTagSavedRecipes,
  deleteRecipeGroup,
  createRecipeCollection,
  createRecipeShare,
  deleteRecipeCollection,
  disableRecipeShare,
  getSavedRecipeGroups as getEnhancedSavedRecipes,
  getRecipeGroupCollections as getRecipeCollections,
  getRecipeShares,
  getRecipeTags,
  moveSavedRecipe,
  renameRecipeCollection,
  replaceSavedRecipeTags
} from '../api/savedRecipes'
import { getPantryItems, stockInPantry } from '../api/pantry'
import { getShoppingItemChecks, saveShoppingItemCheck } from '../api/shoppingChecks'
import { getNutritionTarget } from '../api/nutritionTargets'
import CookingModeDialog from '../components/CookingModeDialog.vue'
import CookingVideoSearch from '../components/CookingVideoSearch.vue'
import FinishedDishReviewDialog from '../components/FinishedDishReviewDialog.vue'
import ShoppingChecklistTable from '../components/ShoppingChecklistTable.vue'
import StockInDialog from '../components/StockInDialog.vue'
import NutritionEstimateCard from '../components/NutritionEstimateCard.vue'
import RecommendationFeedbackButtons from '../components/RecommendationFeedbackButtons.vue'
import { useAuthStore } from '../stores/auth'
import {
  buildPurchaseLinks,
  buildShoppingList,
  copyIngredientName,
  filterVideoKeywords,
  normalizeShoppingStatus,
  parseIngredientNames,
  shoppingChecklistKey
} from '../utils/recipeEnhancements'
import { getIngredientImage } from '../utils/ingredientImages'
import { emptyNutritionTarget, normalizeNutritionTarget } from '../utils/nutritionTarget'
import {
  nextRecommendationReaction,
  normalizeRecommendationFeedback
} from '../utils/recommendationFeedback'

const recipes = ref([])
const totalRecipes = ref(0)
const collections = ref([])
const tags = ref([])
const selectedCollectionId = ref(null)
const tagFilter = ref('')
const sortOrder = ref('desc')
const currentPage = ref(1)
const pageSize = 20
const batchMode = ref(false)
const selectedRecipeIds = ref([])
const collectionDialogVisible = ref(false)
const collectionDialogMode = ref('create')
const collectionDraft = ref('')
const collectionEditing = ref(null)
const collectionSaving = ref(false)
const batchMoveDialogVisible = ref(false)
const batchMoveCollectionId = ref(null)
const batchTagDialogVisible = ref(false)
const batchTagMode = ref('add')
const batchTagDraft = ref('')
const tagEditorVisible = ref(false)
const tagEditorDraft = ref('')
const tagSaving = ref(false)
const batchSaving = ref(false)
const shareDialogVisible = ref(false)
const shareValidity = ref('7')
const shareRecipeId = ref(null)
const shareSaving = ref(false)
const shareManagerVisible = ref(false)
const shares = ref([])
const sharesLoading = ref(false)
const selected = ref(null)
const selectedGroupKey = ref(null)
const moveCurrentVersionId = ref(null)
const loading = ref(false)
const detailLoading = ref(false)
const deletingId = ref(null)
const activePage = ref('ingredients')
const cookingModeVisible = ref(false)
const finishedDishReviewVisible = ref(false)
const keywordDraft = ref('')
const keyword = ref('')
const mealType = ref('')
const goal = ref('')
const pantryItems = ref([])
const shoppingCheckOverrides = ref({})
const shoppingCheckSavingKey = ref('')
const stockInKey = ref('')
const stockInDialogVisible = ref(false)
const stockInItem = ref(null)
const feedbackReaction = ref(null)
const feedbackCooked = ref(false)
const feedbackLoading = ref(false)
const nutritionTarget = ref(emptyNutritionTarget())
const nutritionTargetLoading = ref(false)
const auth = useAuthStore()
let listRequestId = 0
let detailRequestId = 0

const selectedListItem = computed(() => recipes.value.find((item) => item.groupKey === selectedGroupKey.value) || null)
const defaultCollection = computed(() => collections.value.find((item) => item.defaultCollection) || null)
const defaultCollectionName = computed(() => defaultCollection.value?.name || '默认收藏夹')
const selectedCustomCollection = computed(() => {
  const collection = collections.value.find((item) => item.id === selectedCollectionId.value)
  return collection && !collection.defaultCollection ? collection : null
})
const allVisibleSelected = computed(() => (
  recipes.value.length > 0 && recipes.value.every((item) => selectedRecipeIds.value.includes(item.groupKey))
))

const ownedIngredients = computed(() => [
  selected.value?.searchIngredients || '',
  ...pantryItems.value.map((item) => item.ingredientName)
])
const shoppingList = computed(() => buildRecipeShoppingList(
  selected.value?.recipe,
  ownedIngredients.value
))
const ingredientAnalysisRows = computed(() => shoppingList.value.map((item) => {
  const missing = findMissingIngredient(selected.value?.recipe?.missingIngredients, item.name)
  return {
    ...item,
    substitutesText: item.alreadyOwned ? '' : formatSubstitutes(missing?.substitutes),
    reason: item.alreadyOwned ? '可直接使用现有食材' : missing?.reason || ''
  }
}))
const explanationItems = computed(() => {
  const explanation = selected.value?.recipe?.explanation || {}
  return [
    { key: 'pairingLogic', label: '搭配逻辑', content: explanation.pairingLogic, icon: markRaw(Network) },
    { key: 'nutrition', label: '营养说明', content: explanation.nutrition, icon: markRaw(HeartPulse) },
    { key: 'cookingPrinciple', label: '烹饪原理', content: explanation.cookingPrinciple, icon: markRaw(Flame) }
  ].filter((item) => item.content)
})
const videoKeywords = computed(() => filterVideoKeywords(selected.value?.recipe?.videoKeywords))
const cookingStorageKey = computed(() => `ai_smart_recipe:cooking:saved:${selected.value?.id || 'draft'}`)

const recipePages = computed(() => {
  const recipe = selected.value?.recipe
  if (!recipe) {
    return []
  }

  return [
    recipe.ingredients?.length ? { key: 'ingredients', label: '所需食材' } : null,
    ingredientAnalysisRows.value.length ? { key: 'analysis', label: '食材分析' } : null,
    shoppingList.value.length ? { key: 'shopping', label: '采购清单' } : null,
    explanationItems.value.length ? { key: 'explanation', label: 'AI 解释' } : null,
    recipe.steps?.length ? { key: 'steps', label: '烹饪步骤' } : null,
    recipe.tips?.length ? { key: 'tips', label: '烹饪建议' } : null,
    { key: 'videos', label: '相关烹饪视频' }
  ].filter(Boolean)
})

onMounted(async () => {
  loadPantryItems()
  loadNutritionTarget()
  await loadCollections()
  await loadTags()
  await loadRecipes()
})

async function loadNutritionTarget() {
  if (nutritionTargetLoading.value) {
    return
  }
  nutritionTargetLoading.value = true
  try {
    const response = await getNutritionTarget()
    nutritionTarget.value = normalizeNutritionTarget(response.data.data)
  } catch {
    nutritionTarget.value = emptyNutritionTarget()
    ElMessage.warning('每日营养目标加载失败，菜谱仍可正常使用')
  } finally {
    nutritionTargetLoading.value = false
  }
}

async function loadRecipes(preferredId = null) {
  const requestId = ++listRequestId
  ++detailRequestId
  loading.value = true
  try {
    const response = await getEnhancedSavedRecipes({
      collectionId: selectedCollectionId.value,
      keyword: keyword.value,
      mealType: mealType.value,
      goal: goal.value,
      tag: tagFilter.value,
      sort: sortOrder.value === 'asc' ? 'savedAtAsc' : 'savedAtDesc',
      page: currentPage.value,
      size: pageSize
    })
    if (requestId !== listRequestId) {
      return
    }

    const payload = response.data.data || {}
    const nextRecipes = payload.items || []
    recipes.value = nextRecipes
    totalRecipes.value = payload.total || 0
    if (!nextRecipes.length) {
      detailRequestId += 1
      detailLoading.value = false
      selected.value = null
      selectedGroupKey.value = null
      resetRecommendationFeedback()
      return
    }

    const nextGroup = nextRecipes.find((item) => item.groupKey === selectedGroupKey.value) || nextRecipes[0]
    selected.value = null
    await openGroup(nextGroup)
  } catch (error) {
    if (requestId === listRequestId) {
      ElMessage.error(getErrorMessage(error))
    }
  } finally {
    if (requestId === listRequestId) {
      loading.value = false
    }
  }
}

function applyFilters() {
  keyword.value = keywordDraft.value.trim()
  currentPage.value = 1
  selectedRecipeIds.value = []
  loadRecipes()
}

async function loadCollections() {
  try {
    const response = await getRecipeCollections()
    collections.value = response.data.data || []
    if (!selectedCollectionId.value && collections.value.length) {
      selectedCollectionId.value = collections.value.find((item) => item.defaultCollection)?.id || collections.value[0].id
    }
  } catch (error) {
    ElMessage.warning(getErrorMessage(error))
  }
}

async function loadTags() {
  try {
    const response = await getRecipeTags()
    tags.value = response.data.data || []
  } catch (error) {
    tags.value = []
    ElMessage.warning(getErrorMessage(error))
  }
}

function selectCollection(collectionId) {
  selectedCollectionId.value = collectionId
  currentPage.value = 1
  selectedRecipeIds.value = []
  loadRecipes()
}

function handlePageChange(page) {
  currentPage.value = page
  selectedRecipeIds.value = []
  loadRecipes()
}

function recipeCoverUrl(item) {
  const ingredient = item?.coverIngredient || parseIngredientNames([item?.searchIngredients])[0]
  return getIngredientImage(ingredient)
}

function toggleRecipeSelection(recipeId) {
  selectedRecipeIds.value = selectedRecipeIds.value.includes(recipeId)
    ? selectedRecipeIds.value.filter((id) => id !== recipeId)
    : [...selectedRecipeIds.value, recipeId]
}

function toggleSelectAll(event) {
  selectedRecipeIds.value = event.target.checked
    ? [...new Set([...selectedRecipeIds.value, ...recipes.value.map((item) => item.groupKey)])]
    : selectedRecipeIds.value.filter((id) => !recipes.value.some((item) => item.groupKey === id))
}

function openCollectionDialog(collection = null) {
  collectionDialogMode.value = collection ? 'rename' : 'create'
  collectionEditing.value = collection
  collectionDraft.value = collection?.name || ''
  collectionDialogVisible.value = true
}

async function submitCollection() {
  const name = collectionDraft.value.trim()
  if (!name || collectionSaving.value) return
  collectionSaving.value = true
  try {
    if (collectionEditing.value) {
      await renameRecipeCollection(collectionEditing.value.id, name)
    } else {
      const response = await createRecipeCollection(name)
      selectedCollectionId.value = response.data.data?.id || selectedCollectionId.value
    }
    collectionDialogVisible.value = false
    await loadCollections()
    await loadRecipes()
    ElMessage.success(collectionEditing.value ? '收藏夹已重命名' : '收藏夹已创建')
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || '收藏夹操作失败')
  } finally {
    collectionSaving.value = false
  }
}

async function confirmDeleteCollection(collection) {
  if (!collection || collection.defaultCollection) return
  try {
    await ElMessageBox.confirm('删除收藏夹后，菜谱会移动到默认收藏夹。确定继续吗？', '删除收藏夹', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await deleteRecipeCollection(collection.id, true)
    selectedCollectionId.value = defaultCollection.value?.id || null
    await loadCollections()
    await loadRecipes()
    ElMessage.success('收藏夹已删除')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error?.response?.data?.message || '收藏夹删除失败')
    }
  }
}

function openMoveSelected() {
  if (!selected.value?.id) return
  openBatchMove()
  moveCurrentVersionId.value = selected.value.id
}

function openBatchMove() {
  moveCurrentVersionId.value = null
  batchMoveCollectionId.value = selectedCollectionId.value || defaultCollection.value?.id || null
  batchMoveDialogVisible.value = true
}

async function submitBatchMove() {
  if ((!selectedRecipeIds.value.length && !moveCurrentVersionId.value) || !batchMoveCollectionId.value || batchSaving.value) return
  batchSaving.value = true
  try {
    if (moveCurrentVersionId.value) {
      await moveSavedRecipe(moveCurrentVersionId.value, batchMoveCollectionId.value)
      ElMessage.success('菜谱已移动')
    } else {
      const response = await batchMoveSavedRecipes(selectedRecipeIds.value, batchMoveCollectionId.value)
      await showBatchResult(response.data.data)
    }
    batchMoveDialogVisible.value = false
    selectedRecipeIds.value = []
    await Promise.all([loadCollections(), loadRecipes()])
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || '移动菜谱失败')
  } finally {
    batchSaving.value = false
  }
}

function openBatchTagDialog(mode) {
  batchTagMode.value = mode
  batchTagDraft.value = ''
  batchTagDialogVisible.value = true
}

function splitTags(value) {
  return [...new Set(value.split(/[\s,，、#]+/).map((tag) => tag.trim()).filter(Boolean))]
}

async function submitBatchTags() {
  if (!selectedRecipeIds.value.length || batchSaving.value) return
  const tagNames = splitTags(batchTagDraft.value)
  if (!tagNames.length) return
  batchSaving.value = true
  try {
    const response = await batchTagSavedRecipes(
      selectedRecipeIds.value,
      batchTagMode.value === 'add' ? tagNames : [],
      batchTagMode.value === 'remove' ? tagNames : []
    )
    await showBatchResult(response.data.data)
    batchTagDialogVisible.value = false
    selectedRecipeIds.value = []
    await Promise.all([loadTags(), loadRecipes(selected.value?.id)])
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || '标签操作失败')
  } finally {
    batchSaving.value = false
  }
}

async function showBatchResult(result) {
  const failures = result?.failures || []
  const suffix = failures.length ? `，${failures.length} 条未处理` : ''
  ElMessage.success(`成功处理 ${result?.successCount || 0} 条${suffix}`)
}

async function confirmBatchDelete() {
  if (!selectedRecipeIds.value.length) return
  try {
    await ElMessageBox.confirm(`将删除选中的 ${selectedRecipeIds.value.length} 道菜谱，且无法恢复。确定继续吗？`, '批量删除菜谱', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    const response = await batchDeleteSavedRecipes(selectedRecipeIds.value)
    await showBatchResult(response.data.data)
    selectedRecipeIds.value = []
    await Promise.all([loadCollections(), loadRecipes()])
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error?.response?.data?.message || '批量删除失败')
    }
  }
}

function openTagEditor() {
  if (!selected.value?.id) return
  tagEditorDraft.value = selectedListItem.value?.tags?.join(', ') || ''
  tagEditorVisible.value = true
}

async function submitTagEditor() {
  if (!selected.value?.id || tagSaving.value) return
  tagSaving.value = true
  try {
    await replaceSavedRecipeTags(selected.value.id, splitTags(tagEditorDraft.value))
    tagEditorVisible.value = false
    await Promise.all([loadTags(), loadRecipes(selected.value.id)])
    ElMessage.success('菜谱标签已更新')
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || '标签保存失败')
  } finally {
    tagSaving.value = false
  }
}

function printRecipe() {
  if (!selected.value?.recipe) {
    ElMessage.info('请先选择一道菜谱')
    return
  }
  window.print()
}

function openShareDialog(recipeId) {
  shareRecipeId.value = recipeId
  shareValidity.value = '7'
  shareDialogVisible.value = true
}

async function submitShare() {
  if (!shareRecipeId.value || shareSaving.value) return
  shareSaving.value = true
  try {
    const response = await createRecipeShare(shareRecipeId.value, shareValidity.value)
    const share = response.data.data
    await copyShare(share)
    shareDialogVisible.value = false
    ElMessage.success('分享链接已创建并复制')
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || '分享链接创建失败')
  } finally {
    shareSaving.value = false
  }
}

async function openShareManager() {
  shareManagerVisible.value = true
  sharesLoading.value = true
  try {
    const response = await getRecipeShares()
    shares.value = response.data.data || []
  } catch (error) {
    shares.value = []
    ElMessage.error(error?.response?.data?.message || '分享记录加载失败')
  } finally {
    sharesLoading.value = false
  }
}

async function copyShare(share) {
  const url = share?.shareUrl?.startsWith('http')
    ? share.shareUrl
    : `${window.location.origin}${share?.shareUrl || ''}`
  try {
    await navigator.clipboard.writeText(url)
    ElMessage.success('链接已复制')
  } catch {
    ElMessage.warning(url)
  }
}

async function disableShare(share) {
  try {
    await ElMessageBox.confirm('链接失效后将无法继续访问，确定让它失效吗？', '分享链接管理', {
      confirmButtonText: '确认失效',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await disableRecipeShare(share.id)
    await openShareManager()
    ElMessage.success('分享链接已失效')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error?.response?.data?.message || '分享链接操作失败')
    }
  }
}

function shareStatusLabel(status) {
  return {
    ACTIVE: '有效',
    EXPIRED: '已过期',
    DISABLED: '已失效',
    INVALID: '菜谱已删除'
  }[status] || '未知状态'
}

async function openGroup(item) {
  ++detailRequestId
  selectedGroupKey.value = item.groupKey
  selected.value = null
  await openRecipe(item.id)
}

async function openRecipe(id) {
  const requestId = ++detailRequestId
  detailLoading.value = true
  try {
    const response = await getSavedRecipe(id)
    if (requestId !== detailRequestId) {
      return
    }
    selected.value = response.data.data
    resetRecommendationFeedback()
    activePage.value = recipePages.value[0]?.key || 'ingredients'
    await loadRecommendationFeedback(selected.value?.searchLogId)
    await loadShoppingChecks()
  } catch (error) {
    if (requestId === detailRequestId) {
      ElMessage.error(getErrorMessage(error))
    }
  } finally {
    if (requestId === detailRequestId) {
      detailLoading.value = false
    }
  }
}

async function confirmDeleteRecipe(item) {
  if (deletingId.value !== null) {
    return
  }

  try {
    await ElMessageBox.confirm(
      `删除“${item.title}”后无法恢复，确定继续吗？`,
      '删除菜谱',
      {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
  } catch {
    return
  }

  const selectedId = selected.value?.id
  deletingId.value = item.id
  try {
    const response = await deleteRecipeGroup(item.groupKey)
    if (response.data.data?.failureCount) throw new Error(response.data.data.failures?.[0]?.reason || '删除失败')
    ElMessage.success('菜谱已删除')
    await loadCollections()
    await loadRecipes(selectedId === item.id ? null : selectedId)
  } catch (error) {
    ElMessage.error(getDeleteErrorMessage(error))
  } finally {
    deletingId.value = null
  }
}

function buildRecipeShoppingList(recipe, ownedIngredients) {
  if (!recipe) {
    return []
  }

  return buildShoppingList(recipe.ingredients, parseIngredientNames(ownedIngredients)).map((item) => ({
    ...item,
    purchaseLinks: item.purchaseLinks || buildPurchaseLinks(item.name)
  }))
}

async function loadPantryItems() {
  try {
    const response = await getPantryItems()
    pantryItems.value = response.data.data || []
  } catch (error) {
    pantryItems.value = []
    ElMessage.warning(getErrorMessage(error))
  }
}

async function loadShoppingChecks() {
  const searchLogId = selected.value?.searchLogId
  if (!searchLogId) {
    shoppingCheckOverrides.value = {}
    return
  }

  shoppingCheckOverrides.value = {}
  try {
    const response = await getShoppingItemChecks(searchLogId)
    if (selected.value?.searchLogId !== searchLogId) {
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
    if (error?.response?.status !== 403 && error?.response?.status !== 404) {
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

  const searchLogId = selected.value?.searchLogId
  if (!searchLogId) {
    return
  }

  shoppingCheckSavingKey.value = key

  try {
    const response = await saveShoppingItemCheck({
      searchLogId,
      ingredientName: item.name,
      status
    })
    if (selected.value?.searchLogId === searchLogId) {
      shoppingCheckOverrides.value = {
        ...shoppingCheckOverrides.value,
        [key]: normalizeShoppingStatus(response.data.data?.status, status)
      }
    }
  } catch (error) {
    const restored = { ...shoppingCheckOverrides.value }
    if (previousExists) {
      restored[key] = previousValue
    } else {
      delete restored[key]
    }
    shoppingCheckOverrides.value = restored
    ElMessage.error('采购清单状态保存失败，请重试')
  } finally {
    if (shoppingCheckSavingKey.value === key) {
      shoppingCheckSavingKey.value = ''
    }
  }
}

function handleStockIn(item) {
  const key = shoppingChecklistKey(item?.name)
  if (!key || stockInKey.value || !selected.value?.id) return
  stockInItem.value = item
  stockInDialogVisible.value = true
}

async function handleStockInConfirm(payload) {
  const item = stockInItem.value
  const key = shoppingChecklistKey(item?.name)
  if (!key || stockInKey.value || !selected.value?.id) return
  stockInKey.value = key
  try {
    await stockInPantry({
      sourceType: 'RECIPE',
      sourceId: selected.value.id,
      idempotencyKey: createClientKey(),
      ingredientName: item.name,
      quantity: payload.quantity,
      unit: payload.unit,
      category: payload.category,
      expireDate: payload.expireDate
    })
    shoppingCheckOverrides.value = { ...shoppingCheckOverrides.value, [key]: 'READY' }
    stockInDialogVisible.value = false
    ElMessage.success(`${item.name} 已加入库存`)
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || '入库失败，请稍后重试')
  } finally {
    stockInKey.value = ''
  }
}

function createClientKey() {
  return globalThis.crypto?.randomUUID?.() || `stock-in-${Date.now()}-${Math.random().toString(16).slice(2)}`
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

function mealLabel(value) {
  return {
    any: '不限餐次',
    breakfast: '早餐',
    lunch: '午餐',
    dinner: '晚餐'
  }[value] || '未指定餐次'
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

function formatDate(value) {
  if (!value) {
    return '刚刚保存'
  }
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(new Date(value))
}

function openCookingMode() {
  if (!selected.value?.recipe?.steps?.length) {
    ElMessage.warning('当前菜谱暂无可执行的烹饪步骤')
    return
  }
  cookingModeVisible.value = true
}

function openFinishedDishReview() {
  if (!selected.value?.recipe?.title) {
    ElMessage.warning('当前菜谱信息不完整，暂时无法评价')
    return
  }
  finishedDishReviewVisible.value = true
}

function resetRecommendationFeedback() {
  feedbackReaction.value = null
  feedbackCooked.value = false
  feedbackLoading.value = false
}

async function loadRecommendationFeedback(searchLogId) {
  if (!searchLogId) {
    resetRecommendationFeedback()
    return
  }
  const token = auth.token
  try {
    const response = await getRecommendationFeedback(searchLogId)
    if (auth.token !== token || selected.value?.searchLogId !== searchLogId) {
      return
    }
    const feedback = normalizeRecommendationFeedback(response.data.data)
    feedbackReaction.value = feedback.reaction
    feedbackCooked.value = feedback.cooked
  } catch (error) {
    if (auth.token === token && selected.value?.searchLogId === searchLogId) {
      resetRecommendationFeedback()
      if (error?.response?.status !== 404) {
        ElMessage.warning(getFeedbackErrorMessage(error))
      }
    }
  }
}

async function toggleRecommendationReaction(reaction) {
  const searchLogId = selected.value?.searchLogId
  if (!searchLogId || feedbackLoading.value) {
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

function getErrorMessage(error) {
  const status = error?.response?.status
  if (status === 401) {
    return '请先登录后查看我的菜谱'
  }
  if (status === 403) {
    return '当前账号没有访问权限'
  }
  return error?.response?.data?.message || error?.message || '菜谱加载失败，请稍后重试'
}

function getDeleteErrorMessage(error) {
  const status = error?.response?.status
  if (status === 401) {
    return '登录状态已失效，请重新登录后删除'
  }
  if (status === 403) {
    return '当前账号没有删除权限'
  }
  if (status === 404) {
    return '菜谱不存在或已被删除'
  }
  return '删除失败，请稍后重试'
}
</script>

<style scoped>
.saved-page {
  min-height: calc(100vh - 58px);
  padding: clamp(20px, 3vw, 36px);
  color: var(--app-text);
}

.saved-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  max-width: 1440px;
  margin: 0 auto 20px;
}

.eyebrow,
.panel-kicker {
  margin: 0;
  color: var(--app-text-muted);
  font-size: 11px;
  font-weight: 900;
  letter-spacing: 0.12em;
}

.saved-heading h1,
.detail-heading h2,
.panel-heading h2 {
  margin: 7px 0 0;
  letter-spacing: 0;
}

.saved-heading p:not(.eyebrow),
.saved-time {
  margin: 8px 0 0;
  color: var(--app-text-muted);
}

.back-link {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 36px;
  padding: 0 11px;
  border: 1px solid var(--app-line-strong);
  border-radius: 6px;
  color: var(--app-text);
  background: var(--app-surface);
  font-size: 13px;
  font-weight: 800;
}

.history-layout {
  display: grid;
  grid-template-columns: minmax(250px, 330px) minmax(0, 1fr);
  gap: 18px;
  max-width: 1440px;
  min-height: 560px;
  margin: 0 auto;
}

.history-panel,
.detail-panel {
  min-width: 0;
  border: 1px solid var(--app-line);
  background: var(--app-surface);
  box-shadow: var(--app-panel-shadow);
}

.history-panel {
  padding: 16px;
}

.recipe-filters {
  display: grid;
  gap: 8px;
  margin-top: 14px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--app-line);
}

.filter-search-row,
.filter-select-row {
  display: grid;
  gap: 7px;
}

.filter-search-row {
  grid-template-columns: minmax(0, 1fr) 36px;
}

.filter-select-row {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.recipe-filters :deep(.el-select) {
  width: 100%;
}

.search-button,
.delete-button {
  display: inline-grid;
  place-items: center;
  border: 1px solid var(--app-line-strong);
  border-radius: 6px;
  color: var(--app-text);
  background: var(--app-surface);
  cursor: pointer;
  transition: border-color 180ms ease, color 180ms ease, background-color 180ms ease;
}

.search-button {
  width: 36px;
  height: 32px;
}

.search-button:hover,
.search-button:focus-visible,
.delete-button:hover,
.delete-button:focus-visible {
  border-color: var(--app-accent);
  color: var(--app-accent);
  background: var(--app-surface-soft);
  outline: none;
}

.panel-heading,
.detail-heading,
.step-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.detail-heading-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.start-cooking-button :deep(span),
.finished-dish-review-button :deep(span) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.count-badge,
.detail-id {
  display: inline-grid;
  min-width: 28px;
  min-height: 28px;
  place-items: center;
  border: 1px solid var(--app-line-strong);
  border-radius: 6px;
  color: var(--app-text);
  font-size: 12px;
  font-weight: 900;
}

.history-items {
  display: grid;
  gap: 8px;
  margin-top: 14px;
}

.history-item-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 34px;
  gap: 4px;
  width: 100%;
  border: 1px solid var(--app-line);
  border-radius: 6px;
  color: var(--app-text);
  background: var(--app-surface);
  transition: border-color 180ms ease, background-color 180ms ease;
}

.history-item-row:hover,
.history-item-row.active {
  border-color: var(--app-accent);
  background: var(--app-surface-soft);
}

.history-item {
  display: grid;
  gap: 6px;
  min-width: 0;
  padding: 11px 5px 11px 11px;
  border: 0;
  color: inherit;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.delete-button {
  align-self: center;
  width: 30px;
  height: 30px;
  border-color: transparent;
  color: var(--app-text-muted);
  background: transparent;
}

.delete-button:disabled {
  cursor: wait;
  opacity: 0.45;
}

.history-item-title {
  overflow: hidden;
  font-size: 14px;
  font-weight: 900;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-item-ingredients {
  overflow: hidden;
  color: var(--app-text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-item-meta {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  color: var(--app-text-faint);
  font-size: 11px;
}

.detail-panel {
  padding: 22px;
}

.detail-heading {
  padding-bottom: 15px;
  border-bottom: 1px solid var(--app-line);
}

.saved-time {
  font-size: 12px;
}

.recipe-detail {
  padding-top: 17px;
}

.summary {
  margin: 0;
  color: var(--app-text-soft);
  line-height: 1.7;
}

.tag-row,
.video-keywords {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 13px;
}

.system-tag,
.video-keywords span,
.video-keywords a {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 8px;
  border: 1px solid var(--app-line-strong);
  border-radius: 4px;
  color: var(--app-text);
  text-decoration: none;
  color: var(--app-text-soft);
  background: var(--app-surface-soft);
  font-size: 12px;
}

.recipe-pages {
  margin-top: 20px;
}

.page-tabs {
  display: flex;
  gap: 4px;
  overflow-x: auto;
  border-bottom: 1px solid var(--app-line);
}

.page-tab {
  min-height: 37px;
  padding: 0 12px;
  border: 0;
  border-bottom: 2px solid transparent;
  color: var(--app-text-muted);
  background: transparent;
  font: inherit;
  font-size: 13px;
  font-weight: 800;
  white-space: nowrap;
  cursor: pointer;
}

.page-tab.active {
  border-bottom-color: var(--app-accent);
  color: var(--app-text);
}

.recipe-window {
  min-height: 220px;
  padding-top: 14px;
}

.print-recipe {
  display: none;
}

.print-recipe-heading {
  padding-bottom: 18px;
  border-bottom: 1px solid var(--app-line);
}

.print-recipe-heading h2 {
  margin: 6px 0 0;
  font-size: 28px;
}

.print-recipe-sections {
  display: grid;
  gap: 24px;
  margin-top: 24px;
}

.print-recipe-section {
  break-inside: avoid;
}

.print-section-heading {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 9px;
  border-bottom: 1px solid var(--app-line);
}

.print-section-heading h3 {
  margin: 0;
  font-size: 18px;
}

.print-section-number {
  color: var(--app-accent);
  font-size: 13px;
  font-weight: 900;
}

.print-ingredient-list,
.print-step-list,
.print-tip-list {
  margin: 14px 0 0;
  padding: 0;
}

.print-ingredient-list,
.print-tip-list {
  display: grid;
  gap: 7px;
  list-style: none;
}

.print-ingredient-list li {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 8px 0;
  border-bottom: 1px dashed var(--app-line-strong);
  color: var(--app-text-soft);
}

.print-ingredient-list strong {
  color: var(--app-text);
  font-weight: 800;
}

.print-step-list {
  display: grid;
  gap: 14px;
  list-style: none;
}

.print-step-list li {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr);
  gap: 10px;
  break-inside: avoid;
}

.print-step-index {
  display: grid;
  width: 26px;
  height: 26px;
  place-items: center;
  border: 1px solid var(--app-line-strong);
  border-radius: 50%;
  color: var(--app-text);
  font-size: 12px;
  font-weight: 900;
}

.print-step-duration {
  margin-left: 8px;
  color: var(--app-text-muted);
  font-size: 12px;
}

.print-step-list p {
  margin: 4px 0 0;
  color: var(--app-text-soft);
  line-height: 1.7;
}

.print-tip-list {
  padding-left: 20px;
  color: var(--app-text-soft);
  line-height: 1.7;
}

.print-empty {
  margin: 14px 0 0;
  color: var(--app-text-muted);
}

.step-list,
.tip-list {
  display: grid;
  gap: 12px;
  margin: 0;
  padding-left: 22px;
}

.step-list li,
.tip-list li {
  color: var(--app-text-soft);
  line-height: 1.7;
}

.step-title-row span {
  color: var(--app-text-muted);
  font-size: 12px;
}

.step-list p {
  margin: 3px 0 0;
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
  gap: 10px;
}

.explanation-item {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--app-line);
  border-radius: 6px;
  background: var(--app-surface-soft);
}

.explanation-item h3,
.explanation-item p {
  margin: 0;
}

.explanation-item h3 {
  color: var(--app-text);
  font-size: 14px;
}

.explanation-item p {
  margin-top: 5px;
  color: var(--app-text-soft);
  line-height: 1.65;
}

.saved-heading-actions,
.panel-heading-actions,
.collection-actions,
.bulk-actions,
.share-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
}

.saved-heading-actions {
  justify-content: flex-end;
}

.panel-heading-actions {
  justify-content: flex-end;
}

.manage-button,
.print-button {
  min-height: 32px;
}

.collection-list {
  display: grid;
  gap: 5px;
  margin-top: 15px;
  padding-bottom: 13px;
  border-bottom: 1px solid var(--app-line);
}

.collection-filter {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 34px;
  padding: 0 9px;
  border: 1px solid transparent;
  border-radius: 5px;
  color: var(--app-text-soft);
  background: transparent;
  font: inherit;
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}

.collection-filter:hover,
.collection-filter.active {
  border-color: var(--app-accent);
  color: var(--app-text);
  background: var(--app-surface-soft);
}

.collection-filter span:last-child {
  color: var(--app-text-faint);
  font-size: 11px;
}

.collection-actions {
  padding: 2px 3px 0;
}

.collection-actions :deep(.el-button) {
  padding: 0;
  font-size: 12px;
}

.bulk-toolbar {
  display: grid;
  gap: 8px;
  margin-top: 12px;
  padding: 10px;
  border: 1px solid var(--app-line);
  border-radius: 6px;
  background: var(--app-surface-soft);
}

.select-all-control {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--app-text-soft);
  font-size: 12px;
  cursor: pointer;
}

.recipe-checkbox {
  width: 16px;
  height: 16px;
  margin: 0 2px 0 10px;
  accent-color: var(--app-accent);
  cursor: pointer;
}

.history-item-row {
  grid-template-columns: 38px minmax(0, 1fr) 34px;
  align-items: center;
}

.history-item-row:has(.recipe-checkbox) {
  grid-template-columns: auto 38px minmax(0, 1fr) 34px;
}

.recipe-cover {
  display: inline-grid;
  width: 38px;
  height: 38px;
  place-items: center;
  overflow: hidden;
  border: 1px solid var(--app-line-strong);
  border-radius: 6px;
  color: var(--app-text-muted);
  background: var(--app-surface-soft);
}

.recipe-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.history-item-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.recipe-tag {
  display: inline-flex;
  align-items: center;
  min-height: 18px;
  padding: 0 5px;
  border: 1px solid color-mix(in srgb, var(--app-accent) 35%, var(--app-line));
  border-radius: 3px;
  color: var(--app-accent);
  background: var(--app-surface-soft);
  font-size: 10px;
}

.history-items.batch-active .delete-button {
  display: none;
}

.recipe-pagination {
  justify-content: center;
  margin-top: 14px;
}

.detail-collection-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 7px 16px;
  margin-bottom: 10px;
  color: var(--app-text-muted);
  font-size: 12px;
}

.detail-heading-actions :deep(.el-button) {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.dialog-control {
  width: 100%;
}

.share-list {
  display: grid;
  gap: 9px;
}

.share-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 11px;
  border: 1px solid var(--app-line);
  border-radius: 6px;
  background: var(--app-surface-soft);
}

.share-item strong,
.share-item p {
  display: block;
  max-width: 100%;
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.share-item p {
  margin-top: 4px;
  color: var(--app-text-muted);
  font-size: 12px;
}

@media print {
  .saved-page {
    padding: 0;
  }

  .saved-heading,
  .history-panel,
  .detail-heading,
  .recipe-detail > :not(.print-recipe),
  .detail-heading-actions,
  .page-tabs,
  :deep(.app-header),
  :deep(.app-sidebar) {
    display: none !important;
  }

  .history-layout,
  .detail-panel,
  .recipe-detail {
    display: block;
    max-width: none;
    min-height: 0;
    padding: 0;
    border: 0;
    box-shadow: none;
  }

  .recipe-window {
    display: none;
  }

  .print-recipe {
    display: block;
  }
}

@media (max-width: 900px) {
  .history-layout {
    grid-template-columns: 1fr;
  }

  .history-panel {
    min-height: auto;
  }
}

@media (max-width: 560px) {
  .saved-heading {
    flex-direction: column;
  }

  .saved-page {
    padding: 18px 14px;
  }

  .detail-panel {
    padding: 16px;
  }

  .detail-heading {
    align-items: flex-start;
  }

  .detail-heading-actions {
    flex-wrap: wrap;
  }
}
</style>
