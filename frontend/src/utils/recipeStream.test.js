import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile, stat } from 'node:fs/promises'
import {
  applyRecipeStreamEvent,
  createRecipeBatchDraft,
  createRecipeDraft,
  isRecipeReady,
  isRecipeResultPriority,
  parseRecipeStreamBlock,
  shouldSubmitIngredientsKey
} from './recipeStream.js'

const homeViewSource = await readFile(new URL('../views/HomeView.vue', import.meta.url), 'utf8')

test('parses structured SSE events and ignores heartbeat comments', () => {
  assert.deepEqual(parseRecipeStreamBlock(': heartbeat\n\n'), null)
  assert.deepEqual(
    parseRecipeStreamBlock('event: overview\ndata: {"title":"番茄炒蛋"}'),
    { event: 'overview', data: { title: '番茄炒蛋' } }
  )
  assert.deepEqual(parseRecipeStreamBlock('data: [DONE]'), { event: 'done', data: null })
})

test('merges progressive fields without dropping previous modules', () => {
  let recipe = createRecipeDraft()
  recipe = applyRecipeStreamEvent(recipe, {
    event: 'overview',
    data: { title: '番茄炒蛋', summary: '家常菜' }
  })
  recipe = applyRecipeStreamEvent(recipe, {
    event: 'details',
    data: { explanation: { nutrition: '均衡搭配' }, nutritionEstimate: null }
  })

  assert.equal(recipe.title, '番茄炒蛋')
  assert.equal(recipe.summary, '家常菜')
  assert.equal(recipe.explanation.nutrition, '均衡搭配')
})

test('creates three independent recipe drafts and keeps stream metadata out of recipe fields', () => {
  const drafts = createRecipeBatchDraft(3)
  assert.equal(drafts.length, 3)
  assert.notEqual(drafts[0].id, drafts[1].id)

  const recipe = applyRecipeStreamEvent(drafts[0], {
    event: 'overview',
    data: {
      recipeId: drafts[0].id,
      index: 0,
      title: '番茄炒蛋',
      summary: '家常下饭'
    }
  })
  assert.equal(recipe.title, '番茄炒蛋')
  assert.equal(recipe.recipeId, undefined)
  assert.equal(recipe.index, 0)
})

test('keeps the result-priority mode until the user edits conditions', () => {
  assert.equal(isRecipeResultPriority({ ingredients: '番茄' }, false), true)
  assert.equal(isRecipeResultPriority({ ingredients: '番茄' }, true), false)
  assert.equal(isRecipeResultPriority(null, false), false)
})

test('recognizes incomplete and complete progressive recipes', () => {
  let recipe = createRecipeDraft()
  recipe = applyRecipeStreamEvent(recipe, {
    event: 'overview',
    data: { title: '番茄炒蛋', summary: '家常快手菜' }
  })
  assert.equal(isRecipeReady(recipe), false)

  recipe = applyRecipeStreamEvent(recipe, {
    event: 'ingredients',
    data: { ingredients: [{ name: '番茄', amount: '2 个' }] }
  })
  recipe = applyRecipeStreamEvent(recipe, {
    event: 'steps',
    data: { steps: [{ title: '翻炒', description: '炒至熟透' }] }
  })
  recipe = applyRecipeStreamEvent(recipe, {
    event: 'complete',
    data: { searchLogId: 12 }
  })
  assert.equal(isRecipeReady(recipe), true)
  assert.equal(recipe.searchLogId, 12)
})

test('submits Enter but keeps Shift+Enter and IME composition untouched', () => {
  assert.equal(shouldSubmitIngredientsKey({ key: 'Enter' }), true)
  assert.equal(shouldSubmitIngredientsKey({ key: 'Enter', shiftKey: true }), false)
  assert.equal(shouldSubmitIngredientsKey({ key: 'Enter', isComposing: true }), false)
  assert.equal(shouldSubmitIngredientsKey({ key: 'Enter', keyCode: 229 }), false)
  assert.equal(shouldSubmitIngredientsKey({ key: 'Enter' }, { generating: true }), false)
  assert.equal(shouldSubmitIngredientsKey({ key: 'a' }), false)
})

test('keeps streaming feedback local instead of masking the result container', () => {
  assert.doesNotMatch(
    homeViewSource,
    /<section[^>]*class="result-panel"[^>]*\bv-loading(?:=|\s|>)/
  )
  assert.doesNotMatch(homeViewSource, /\.result-panel[^\{]*\{[^\}]*\b(?:opacity|filter)\s*:/s)
  assert.doesNotMatch(homeViewSource, /\.result-panel[^\{]*(?:::before|::after)/)
  assert.match(homeViewSource, /class="stream-status"/)
  assert.match(homeViewSource, /class="stream-progress-panel"/)
  assert.match(homeViewSource, /@media \(prefers-reduced-motion: reduce\)/)
})

test('shows a cooking character and sequential loading bubbles on the loading surface', async () => {
  const cookingCharacter = await stat(new URL('../../public/images/chef-cooking.png', import.meta.url))

  assert.ok(cookingCharacter.size > 0)
  assert.match(homeViewSource, /v-if="!detailViewOpen && generating" class="recipe-generation-loading"/)
  assert.match(homeViewSource, /src="\/images\/chef-cooking\.png"/)
  assert.match(homeViewSource, /v-else-if="!detailViewOpen" class="result-header"/)
  assert.match(homeViewSource, /\.recipe-generation-loading-art img \{[\s\S]*mix-blend-mode: multiply;/)
  assert.match(homeViewSource, /class="recipe-generation-loading-dots" aria-hidden="true"/)
  assert.equal((homeViewSource.match(/class="recipe-generation-loading-bubble/g) || []).length, 4)
  assert.equal((homeViewSource.match(/--bubble-size: 14px/g) || []).length, 4)
  assert.match(homeViewSource, /@keyframes recipe-generation-bubble-shrink/)
  assert.match(homeViewSource, /--bubble-delay: 160ms/)
  assert.match(homeViewSource, /background: #f4bd3f/)
  assert.match(homeViewSource, /\.recipe-generation-loading-bubble \{[\s\S]*animation: none;/)
  assert.doesNotMatch(homeViewSource, /@keyframes chef-cooking-bob/)
  assert.doesNotMatch(homeViewSource, /@keyframes cooking-fire-pulse/)
  assert.doesNotMatch(homeViewSource, /\.recipe-generation-loading-art::after/)
})

test('recipe detail pages expose a top-right close control', () => {
  assert.equal((homeViewSource.match(/class="detail-close-button"/g) || []).length, 4)
  assert.match(homeViewSource, /aria-label="关闭菜谱详情"/)
  assert.match(homeViewSource, /class="detail-close-button"[\s\S]*@click="closeDetailView"/)
  assert.doesNotMatch(homeViewSource, /推荐摘要与智能说明/)
  assert.match(homeViewSource, /\.section-heading\s*\{[\s\S]*position:\s*relative;/)
  assert.match(homeViewSource, /\.detail-close-button\s*\{[\s\S]*position:\s*absolute;/)
})

test('detail view stacks the fallback alert before its content without overlap', () => {
  const fallbackIndex = homeViewSource.indexOf('class="context-fallback-alert"')
  const detailHeaderIndex = homeViewSource.indexOf('class="recipe-detail-view-header"')

  assert.ok(fallbackIndex >= 0 && fallbackIndex < detailHeaderIndex)
  assert.match(
    homeViewSource,
    /\.home-page\.is-detail-view \.result-panel \{[\s\S]*display: flex;[\s\S]*flex-direction: column;/
  )
  assert.match(
    homeViewSource,
    /\.home-page\.is-detail-view \.result-content \{[\s\S]*flex: 1 1 0;/
  )
})

test('prioritizes multi-ingredient recommendations with Bilibili matches', () => {
  assert.match(homeViewSource, /import \{ searchCookingVideos \} from '\.\.\/api\/videos'/)
  assert.match(homeViewSource, /'matching-videos': '正在匹配 B 站参考'/)
  assert.match(homeViewSource, /prioritizeRecommendationsByBilibili\(requestId\)/)
})

test('shows a distinct retry stage when the model repeats a recipe', () => {
  assert.match(homeViewSource, /retrying: '正在更换重复做法'/)
})

test('uses the backend batch total for dynamically sized recipe batches', () => {
  assert.match(homeViewSource, /:aria-label="`\$\{recommendationRecipes\.length\}道推荐菜谱`"/)
  assert.match(homeViewSource, /recommendationReadyCount\.value >= \(recommendationBatch\.value\?\.total \|\| recommendationRecipes\.value\.length\)/)
  assert.match(homeViewSource, /Number\.isInteger\(parsedTotal\) && parsedTotal >= 3 \? parsedTotal : 3/)
  assert.match(homeViewSource, /recommendationRecipes\.value = createRecipeBatchDraft\(3\)/)
})
