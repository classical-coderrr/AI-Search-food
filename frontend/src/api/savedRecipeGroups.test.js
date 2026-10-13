import assert from 'node:assert/strict'
import test from 'node:test'
import { http } from './http.js'
import {
  getSavedRecipeGroups, getRecipeGroupCollections, getRecipeGroupVersions,
  batchMoveRecipeGroups, batchTagRecipeGroups, batchDeleteRecipeGroups,
  createRecipeShare, moveSavedRecipe
} from './savedRecipes.js'

test('分组查询保留筛选和版本选择，整组管理与单版本操作使用各自接口', async () => {
  const original = { get: http.get, post: http.post, put: http.put, delete: http.delete }
  const calls = []
  for (const method of Object.keys(original)) {
    http[method] = (...args) => { calls.push([method, ...args]); return Promise.resolve({}) }
  }
  const key = 'a'.repeat(64)
  const filters = { collectionId: 2, tag: '家常', page: 2, size: 20 }
  try {
    await getSavedRecipeGroups(filters)
    await getRecipeGroupCollections()
    await getRecipeGroupVersions(key, 17)
    await batchMoveRecipeGroups([key], 3)
    await batchTagRecipeGroups([key], ['家常'], [])
    await batchDeleteRecipeGroups([key])
    await createRecipeShare(17, '7')
    await moveSavedRecipe(17, 3)
  } finally {
    Object.assign(http, original)
  }
  assert.deepEqual(calls, [
    ['get', '/users/me/saved-recipe-groups', { params: filters }],
    ['get', '/users/me/saved-recipe-groups/collections'],
    ['get', `/users/me/saved-recipe-groups/${key}/versions`, { params: { preferredId: 17 } }],
    ['post', '/users/me/saved-recipe-groups/batch-move', { groupKeys: [key], collectionId: 3 }],
    ['post', '/users/me/saved-recipe-groups/batch-tags', { groupKeys: [key], addTags: ['家常'], removeTags: [] }],
    ['delete', '/users/me/saved-recipe-groups/batch', { data: { groupKeys: [key] } }],
    ['post', '/users/me/saved-recipes/17/shares', { validity: '7' }],
    ['put', '/users/me/saved-recipes/17/collection', { collectionId: 3 }]
  ])
})
