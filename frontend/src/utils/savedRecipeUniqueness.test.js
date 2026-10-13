import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'

const saved = fs.readFileSync(new URL('../views/SavedRecipesView.vue', import.meta.url), 'utf8')
const home = fs.readFileSync(new URL('../views/HomeView.vue', import.meta.url), 'utf8')
const agent = fs.readFileSync(new URL('../components/KitchenAgentWidget.vue', import.meta.url), 'utf8')

test('菜谱室只展示同名菜谱的一个入口，不提供版本切换', () => {
  assert.doesNotMatch(saved, /recipeVersions|version-picker|version-count|saved-recipe-version|getRecipeGroupVersions/)
  assert.match(saved, /同名菜谱只保存一次/)
  assert.match(saved, /await openRecipe\(item\.id\)/)
})

test('重复保存展示已有内容并更新推荐卡片，提示重复而非新保存成功', () => {
  assert.match(home, /response\.data\.data\?\.alreadySaved/)
  assert.match(home, /replaceRecommendationRecipe\(activeRecipeId\.value, savedDisplay\)/)
  assert.match(home, /ElMessage\.info\('该菜谱已经保存过，仅为您展示卡片信息'\)/)
  assert.match(agent, /已保存\|已经保存\|保存成功/)
})
