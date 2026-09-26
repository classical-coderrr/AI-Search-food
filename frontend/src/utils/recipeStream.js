export function createRecipeDraft() {
  return {
    title: '',
    summary: '',
    effects: [],
    ingredients: [],
    missingIngredients: [],
    steps: [],
    tips: [],
    videoKeywords: [],
    explanation: {
      pairingLogic: '',
      nutrition: '',
      cookingPrinciple: ''
    },
    nutritionEstimate: null,
    provider: '',
    model: '',
    searchLogId: null,
    pantryReferenced: false,
    pantryFallback: false,
    pantryIncompatible: false,
    healthNutritionReferenced: false
  }
}

export function createRecipeBatchDraft(total = 3) {
  return Array.from({ length: total }, (_, index) => ({
    id: `pending-recipe-${index + 1}`,
    index,
    label: '',
    ...createRecipeDraft()
  }))
}

export function isRecipeResultPriority(lastSearch, editingConditions) {
  return Boolean(lastSearch && !editingConditions)
}

export function isRecipeReady(recipe) {
  const hasText = (value) => typeof value === 'string' && value.trim().length > 0
  const hasIngredient = Array.isArray(recipe?.ingredients)
    && recipe.ingredients.some((item) => hasText(typeof item === 'string' ? item : item?.name))
  const hasStep = Array.isArray(recipe?.steps)
    && recipe.steps.some((item) => hasText(item?.title) || hasText(item?.description))
  return Boolean(hasText(recipe?.title) && hasText(recipe?.summary) && hasIngredient && hasStep)
}

export function validateRecipeBatchCompletion(expectedTotal, payload) {
  const total = Number(expectedTotal)
  const recipes = payload?.recipes
  if (!Number.isInteger(total)
    || total < 1
    || Number(payload?.total) !== total
    || !Array.isArray(recipes)
    || recipes.length !== total
    || recipes.some((recipe) => !isRecipeReady(recipe))) {
    throw new RecipeStreamError('AI 返回的菜谱数量或内容不完整，请点击重试', 502)
  }
  return recipes
}

export function shouldSubmitIngredientsKey(event, { generating = false, recognizing = false } = {}) {
  if (!event || event.isComposing || event.keyCode === 229) {
    return false
  }
  return event.key === 'Enter' && !event.shiftKey && !generating && !recognizing
}

export function applyRecipeStreamEvent(recipe, event) {
  const current = recipe || createRecipeDraft()
  const data = event?.data
  if (!data || typeof data !== 'object' || Array.isArray(data)) {
    return current
  }

  const recipeData = { ...data }
  delete recipeData.recipeId
  delete recipeData.index
  if (event.event === 'details' && recipeData.explanation && typeof recipeData.explanation === 'object') {
    return {
      ...current,
      ...recipeData,
      explanation: {
        ...current.explanation,
        ...recipeData.explanation
      }
    }
  }
  return {
    ...current,
    ...recipeData
  }
}

export async function consumeRecipeStream(response, { onEvent, signal } = {}) {
  if (!response?.ok) {
    const payload = await readErrorPayload(response)
    throw createRecipeStreamError(response?.status, payload?.message || payload?.error)
  }
  if (!response.body?.getReader) {
    throw createRecipeStreamError(502, '当前浏览器不支持菜谱流式响应，请稍后重试')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const events = []
  try {
    while (true) {
      const { done, value } = await reader.read()
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
      const blocks = buffer.split(/\r?\n\r?\n/)
      buffer = blocks.pop() || ''
      for (const block of blocks) {
        const event = parseRecipeStreamBlock(block)
        if (!event) continue
        events.push(event)
        onEvent?.(event)
      }
      if (done) break
    }

    const finalEvent = parseRecipeStreamBlock(buffer)
    if (finalEvent) {
      events.push(finalEvent)
      onEvent?.(finalEvent)
    }
    return events
  } catch (error) {
    if (signal?.aborted || error?.name === 'AbortError') {
      throw error
    }
    if (error instanceof RecipeStreamError) {
      throw error
    }
    throw createRecipeStreamError(502, '菜谱生成连接中断，请点击重试')
  } finally {
    reader.cancel().catch(() => {})
  }
}

export function parseRecipeStreamBlock(block) {
  if (!block || !block.trim()) {
    return null
  }

  let eventName = 'message'
  const dataLines = []
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith(':')) continue
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim() || eventName
    } else if (line.startsWith('data:')) {
      const data = line.slice(5)
      dataLines.push(data.startsWith(' ') ? data.slice(1) : data)
    }
  }
  if (!dataLines.length) {
    return null
  }

  const rawData = dataLines.join('\n').trim()
  if (rawData === '[DONE]') {
    return { event: 'done', data: null }
  }
  let data
  try {
    data = JSON.parse(rawData)
  } catch (error) {
    throw createRecipeStreamError(502, '菜谱生成返回格式异常，请点击重试', error)
  }
  return { event: eventName, data }
}

export class RecipeStreamError extends Error {
  constructor(message, status = 0, cause) {
    super(message, { cause })
    this.name = 'RecipeStreamError'
    this.status = status
  }
}

function createRecipeStreamError(status, message, cause) {
  const stableMessage = status === 401
    ? '登录状态已失效，请重新登录后再生成'
    : status === 403
      ? '当前账号没有生成菜谱的权限'
      : status === 408 || status === 504
        ? 'AI 生成超时，请检查网络后重试'
        : status === 429
          ? 'AI 服务当前较忙，请稍后重试'
          : message || '菜谱生成失败，请稍后重试'
  return new RecipeStreamError(stableMessage, status, cause)
}

async function readErrorPayload(response) {
  try {
    const text = await response.text()
    return text ? JSON.parse(text) : {}
  } catch {
    return {}
  }
}
