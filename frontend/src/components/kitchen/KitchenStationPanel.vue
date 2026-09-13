<template>
  <SceneWindow
    :model-value="modelValue"
    :title="windowTitle"
    :subtitle="windowSubtitle"
    :icon="windowIcon"
    :accent="windowAccent"
    :content-class="{ 'scene-window-content--workbench': ['chef', 'hot'].includes(activeFeatureId) }"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-if="activeFeature" class="scene-feature-host" :class="`scene-feature-host--${activeFeatureId}`">
      <div v-if="showFeatureBack" class="scene-feature-toolbar">
        <button type="button" class="scene-feature-back" @click="returnToStationMenu">
          <span aria-hidden="true">‹</span>
          返回{{ backLabel }}
        </button>
        <span>{{ activeFeature.description }}</span>
      </div>

      <DietPreferenceForm
        v-if="activeFeatureId === 'diet-preference' && !preferenceLoading"
        :preference="dietPreference"
        :saving="preferenceSaving"
        @cancel="returnToStationMenu"
        @save="persistDietPreference"
      />

      <div v-else-if="activeFeatureId === 'diet-preference'" class="scene-feature-loading" role="status">
        <span class="scene-feature-loading__pan" aria-hidden="true">◒</span>
        <strong>{{ kitchen.getCharacterName('chef-nutrition', '小衡') }}正在读取饮食偏好…</strong>
      </div>

      <Suspense v-else>
        <component
          :is="activeFeature.component"
           :key="activeFeatureId"
           v-bind="activeFeatureProps"
           @open-feature="openFeatureFromChild"
           v-on="activeFeatureListeners"
        />
        <template #fallback>
          <div class="scene-feature-loading" role="status">
            <span class="scene-feature-loading__pan" aria-hidden="true">◒</span>
            <strong>正在准备功能窗口…</strong>
          </div>
        </template>
      </Suspense>
    </div>

    <div v-else-if="station" class="station-brief">
      <div class="station-brief-icon" :style="{ '--station-accent': station.accent }" aria-hidden="true">
        <component :is="station.icon" :size="22" />
      </div>
      <div>
        <p class="station-kicker">{{ station.role }}</p>
        <h2>{{ station.title }}</h2>
        <p class="station-description">{{ station.description }}</p>
      </div>
      <div class="station-preview-grid">
        <div v-for="item in station.preview" :key="item.label" class="station-preview-card">
          <span>{{ item.value }}</span>
          <strong>{{ item.label }}</strong>
        </div>
      </div>
      <div class="station-entry-list" aria-label="功能入口">
        <button
          v-for="entry in station.entries"
          :key="entry.label"
          type="button"
          class="station-entry-card"
          :disabled="preferenceLoading && entry.action === 'diet-preference'"
          @click="openStationEntry(entry)"
        >
          <span class="station-entry-mark" aria-hidden="true">
            <component v-if="entry.icon" :is="entry.icon" :size="17" />
            <span v-else>→</span>
          </span>
          <span class="station-entry-copy">
            <strong>{{ entry.label }}</strong>
            <small>{{ entry.description }}</small>
          </span>
          <span class="station-entry-arrow" aria-hidden="true">›</span>
        </button>
      </div>
      <div class="station-brief-actions">
        <el-button plain @click="emit('update:modelValue', false)">留在厨房</el-button>
      </div>
    </div>
  </SceneWindow>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { getDietPreference, saveDietPreference } from '../../api/userPreferences'
import { useAuthStore } from '../../stores/auth'
import { useKitchenStore } from '../../stores/kitchen'
import { normalizeDietPreference } from '../../utils/personalization'
import {
  getFeatureIdForStation,
  getFeatureIdForRoute,
  getKitchenFeature,
  getKitchenStation,
  kitchenFeatureLocation,
  kitchenStationLocation,
  KITCHEN_FEATURES,
  isSameKitchenFeature,
  parseKitchenFeature
} from '../../utils/kitchenFeatures'
import DietPreferenceForm from '../DietPreferenceForm.vue'
import SceneWindow from './SceneWindow.vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  stationId: { type: String, default: '' },
  featureId: { type: String, default: '' }
})

const emit = defineEmits(['update:modelValue'])
const auth = useAuthStore()
const kitchen = useKitchenStore()
const route = useRoute()
const router = useRouter()
const dietPreference = ref(normalizeDietPreference())
const preferenceLoading = ref(false)
const preferenceSaving = ref(false)
const activeFeatureId = ref('')
const previousFeatureId = ref('')
const previousStationId = ref('')
const chefSearchPreset = ref(null)
const preferenceLoaded = ref(false)
let internalNavigation = false

const featureMap = KITCHEN_FEATURES

const station = computed(() => {
  return getKitchenStation(props.stationId)
})

const activeFeature = computed(() => featureMap[activeFeatureId.value] || null)
const activeFeatureProps = computed(() => {
  const componentProps = { ...(activeFeature.value?.embeddedProps || {}) }
  if (activeFeatureId.value === 'chef') componentProps.initialSearch = chefSearchPreset.value
  return componentProps
})
const featureHandoffHandlers = {
  recognition: openChefWithRecognizedIngredients,
  history: openChefWithSearch,
  hot: openChefWithIngredient
}
// v-on="..." expects the emitted event name; Vue adds the listener prop prefix itself.
const activeFeatureListeners = computed(() => {
  const feature = activeFeature.value
  const handler = featureHandoffHandlers[activeFeatureId.value]
  return feature?.handoffEvent && handler
    ? { [feature.handoffEvent]: handler }
    : {}
})
const showFeatureBack = computed(() => {
  return Boolean(
    previousFeatureId.value
    || previousStationId.value
    || (station.value?.entries?.length > 1 && activeFeature.value)
  )
})
const backLabel = computed(() => {
  if (previousFeatureId.value) return featureMap[previousFeatureId.value]?.title || '上一功能'
  if (previousStationId.value) return getKitchenStation(previousStationId.value)?.title || '功能入口'
  return station.value?.title || '功能入口'
})
const windowTitle = computed(() => activeFeature.value?.windowTitle || activeFeature.value?.title || station.value?.title || '厨房功能')
const windowSubtitle = computed(() => getDisplayRole(activeFeature.value || station.value))
const windowIcon = computed(() => activeFeature.value?.icon || station.value?.icon || null)
const windowAccent = computed(() => activeFeature.value?.accent || station.value?.accent || '#d6a43b')

function getDisplayRole(meta) {
  if (!meta) return ''
  const role = String(meta.role || '').split('·')[0].trim()
  if (!meta.actorId) return role
  return `${role} · ${kitchen.getCharacterName(meta.actorId)}`
}

watch(
  () => [props.modelValue, props.stationId, props.featureId],
  ([visible, stationId, featureId]) => {
    if (!visible) {
      activeFeatureId.value = ''
      previousFeatureId.value = ''
      previousStationId.value = ''
      internalNavigation = false
      return
    }

    const nextFeatureId = featureId || getFeatureIdForStation(stationId)
    if (nextFeatureId === activeFeatureId.value) {
      internalNavigation = false
      return
    }

    if (!internalNavigation) {
      previousFeatureId.value = ''
      previousStationId.value = ''
    }
    internalNavigation = false
    activeFeatureId.value = nextFeatureId
    if (stationId === 'chef') chefSearchPreset.value = null
    if (nextFeatureId === 'diet-preference') {
      void openDietPreference()
      return
    }
    if (nextFeatureId) ensureFeatureAccess(nextFeatureId)
  },
  { immediate: true }
)

async function openStationEntry(entry) {
  if (entry.action === 'diet-preference') {
    await openDietPreference()
    return
  }
  if (entry.feature) {
    openFeature(entry.feature)
    return
  }
  if (entry.route) openFeature(getFeatureIdForRoute(entry.route))
}

function openFeature(featureId, { rememberCurrent = false } = {}) {
  const feature = getKitchenFeature(featureId)
  if (!feature || !ensureFeatureAccess(feature.featureId)) return

  const routeFeatureId = parseKitchenFeature(route.query)
  if (isSameKitchenFeature(activeFeatureId.value, feature.featureId) && routeFeatureId === feature.featureId) return

  previousFeatureId.value = rememberCurrent ? activeFeatureId.value : ''
  previousStationId.value = props.stationId || ''
  activeFeatureId.value = feature.featureId
  internalNavigation = true
  router.push(panelFeatureLocation(feature.featureId))
}

function ensureFeatureAccess(featureId) {
  const feature = getKitchenFeature(featureId)
  if (!feature?.requiresUser || auth.isUser) return true
  emit('update:modelValue', false)
  router.push({ name: 'login', query: { redirect: router.resolve(panelFeatureLocation(featureId)).fullPath } })
  return false
}

function returnToStationMenu() {
  if (previousFeatureId.value) {
    const featureId = previousFeatureId.value
    previousFeatureId.value = ''
    internalNavigation = true
    activeFeatureId.value = featureId
    router.push(panelFeatureLocation(featureId))
    return
  }
  if (previousStationId.value) {
    const stationId = previousStationId.value
    previousStationId.value = ''
    internalNavigation = true
    activeFeatureId.value = ''
    router.push(kitchenStationLocation(stationId, route.query))
    return
  }
  if (station.value?.entries?.length) {
    activeFeatureId.value = ''
    router.push(kitchenStationLocation(station.value.id, route.query))
    return
  }
  emit('update:modelValue', false)
}

function openChefWithIngredient(ingredientName) {
  const name = String(ingredientName || '').trim()
  if (!name) return
  chefSearchPreset.value = { ingredients: name, mealType: 'any', goal: 'balanced' }
  openFeature('chef', { rememberCurrent: true })
}

function openChefWithRecognizedIngredients(ingredientName) {
  const name = String(ingredientName || '').trim()
  if (!name) return
  chefSearchPreset.value = {
    ingredients: name,
    mealType: 'any',
    goal: 'balanced',
    searchMode: 'image',
    source: 'ingredient-recognition'
  }
  openFeature('chef', { rememberCurrent: true })
}

function openChefWithSearch(search) {
  chefSearchPreset.value = {
    ingredients: String(search?.ingredients || '').trim(),
    mealType: search?.mealType || 'any',
    goal: search?.goal || 'balanced'
  }
  openFeature('chef', { rememberCurrent: true })
}

function openFeatureFromChild(target) {
  const featureId = target === '/' ? 'chef' : getKitchenFeature(target)?.featureId || getFeatureIdForRoute(target)
  if (featureId) {
    openFeature(featureId, { rememberCurrent: true })
    return
  }
  if (typeof target === 'string' && target.startsWith('/')) {
    emit('update:modelValue', false)
    router.push(target)
  }
}

async function openDietPreference() {
  if (!auth.isUser) {
    emit('update:modelValue', false)
    router.push({ name: 'login', query: { redirect: router.resolve(panelFeatureLocation('diet-preference')).fullPath } })
    return
  }

  activeFeatureId.value = 'diet-preference'
  if (parseKitchenFeature(route.query) !== 'diet-preference') {
    internalNavigation = true
    previousStationId.value = props.stationId || previousStationId.value
    await router.push(panelFeatureLocation('diet-preference'))
  }
  if (preferenceLoading.value || preferenceLoaded.value) return
  preferenceLoading.value = true
  try {
    const response = await getDietPreference()
    dietPreference.value = normalizeDietPreference(response.data.data)
    preferenceLoaded.value = true
  } catch (error) {
    handleAuthorizedError(error, '饮食偏好加载失败，请稍后重试')
  } finally {
    preferenceLoading.value = false
  }
}

async function persistDietPreference(value) {
  if (!auth.isUser || preferenceSaving.value) return
  preferenceSaving.value = true
  try {
    const response = await saveDietPreference(normalizeDietPreference(value))
    dietPreference.value = normalizeDietPreference(response.data.data)
    returnToStationMenu()
    ElMessage.success('饮食偏好已保存')
  } catch (error) {
    handleAuthorizedError(error, '饮食偏好保存失败，请稍后重试')
  } finally {
    preferenceSaving.value = false
  }
}

function panelFeatureLocation(featureId) {
  const location = kitchenFeatureLocation(featureId, route.query)
  if (props.stationId) location.query.station = props.stationId
  return location
}

function handleAuthorizedError(error, fallback) {
  if (error?.response?.status === 401) {
    emit('update:modelValue', false)
    router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  ElMessage.error(getErrorMessage(error, fallback))
}

function getErrorMessage(error, fallback) {
  return error?.response?.data?.message || fallback
}
</script>

<style scoped>
.scene-feature-host {
  min-height: 100%;
  color: var(--app-text);
  background:
    linear-gradient(rgba(112, 84, 54, 0.07) 1px, transparent 1px),
    linear-gradient(90deg, rgba(112, 84, 54, 0.07) 1px, transparent 1px),
    #f8f1e2;
  background-size: 24px 24px;
}

.scene-feature-host--chef,
.scene-feature-host--hot {
  height: 100%;
  min-height: 0;
  overflow: auto;
  overflow-x: hidden;
  overscroll-behavior: contain;
  scroll-padding-block: 12px;
  scrollbar-color: #9e7b50 #eadfc9;
  scrollbar-width: thin;
}

.scene-feature-toolbar {
  position: sticky;
  top: 0;
  z-index: 8;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  min-height: 40px;
  padding: 6px 12px;
  border-bottom: 1px solid #c8aa7b;
  color: #80664a;
  background: rgba(242, 229, 199, 0.96);
  font-size: 11px;
  font-weight: 700;
  backdrop-filter: blur(8px);
}

.scene-feature-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 40px;
  padding: 0 9px;
  border: 1px solid #9e7b50;
  border-radius: 3px;
  color: #3c2b20;
  background: #fffaf0;
  font: inherit;
  font-weight: 900;
  cursor: pointer;
}

.scene-feature-back:hover,
.scene-feature-back:focus-visible {
  border-color: var(--app-accent);
  background: #fff4d9;
  outline: 2px solid #4f8ca5;
  outline-offset: 2px;
}

.scene-feature-back span {
  font-size: 20px;
  line-height: 1;
}

.scene-feature-loading {
  display: grid;
  min-height: 420px;
  place-items: center;
  align-content: center;
  gap: 12px;
  color: #80664a;
}

.scene-feature-loading__pan {
  color: #d6a43b;
  font-size: 36px;
  animation: scene-pan-bounce 900ms steps(2, end) infinite;
}

.scene-feature-host :deep(.workspace-heading),
.scene-feature-host :deep(.health-profile-heading),
.scene-feature-host :deep(.nutrition-target-heading),
.scene-feature-host :deep(.weekly-menu-heading),
.scene-feature-host :deep(.account-heading),
.scene-feature-host :deep(.notifications-heading) {
  display: none;
}

.scene-feature-host :deep(.pantry-heading),
.scene-feature-host :deep(.saved-heading),
.scene-feature-host :deep(.hot-header) {
  justify-content: flex-end;
  gap: 8px;
  margin-bottom: 10px;
}

.scene-feature-host :deep(.pantry-heading > div:first-child),
.scene-feature-host :deep(.saved-heading > div:first-child),
.scene-feature-host :deep(.hot-header > div:first-child),
.scene-feature-host :deep(.back-link) {
  display: none;
}

.scene-feature-host :deep(.home-page) {
  height: auto;
  min-height: 0;
  padding: 12px;
  overflow: visible;
  overscroll-behavior: auto;
}

.scene-feature-host :deep(.command-shell) {
  height: auto;
  min-height: 100%;
}

.scene-feature-host :deep(.pantry-page),
.scene-feature-host :deep(.saved-page),
.scene-feature-host :deep(.health-profile-page),
.scene-feature-host :deep(.nutrition-target-page),
.scene-feature-host :deep(.weekly-menu-page),
.scene-feature-host :deep(.account-page),
.scene-feature-host :deep(.notifications-page) {
  min-height: 100%;
  padding: 12px 14px;
}

.scene-feature-host :deep(.hot-page) {
  height: auto;
  min-height: 100%;
  padding: 12px 14px;
  overflow: visible;
  overscroll-behavior: auto;
}

.scene-feature-host :deep(.hot-shell) {
  height: auto;
  min-height: 100%;
}

.scene-feature-host :deep(.pantry-panel),
.scene-feature-host :deep(.history-layout) {
  min-height: 0;
}

.scene-feature-host :deep(.history-layout) {
  grid-template-columns: minmax(230px, 280px) minmax(0, 1fr);
  gap: 12px;
}

.scene-feature-host :deep(.history-panel),
.scene-feature-host :deep(.detail-panel),
.scene-feature-host :deep(.weekly-menu-workspace),
.scene-feature-host :deep(.notification-list-panel),
.scene-feature-host :deep(.notification-preferences-panel),
.scene-feature-host :deep(.profile-panel),
.scene-feature-host :deep(.security-panel),
.scene-feature-host :deep(.danger-panel),
.scene-feature-host :deep(.identity-card) {
  box-shadow: 0 8px 22px rgba(69, 48, 34, 0.09);
}

.scene-feature-host :deep(.menu-grid) {
  min-width: 980px;
  grid-template-columns: repeat(7, minmax(120px, 1fr));
}

.scene-feature-host :deep(.weekly-menu-workspace) {
  overflow-x: auto;
}

.scene-feature-host :deep(.notifications-layout) {
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: 12px;
}

.scene-feature-host :deep(.notification-list-panel),
.scene-feature-host :deep(.notification-preferences-panel),
.scene-feature-host :deep(.profile-panel),
.scene-feature-host :deep(.security-panel),
.scene-feature-host :deep(.danger-panel) {
  padding: 16px 18px;
}

.scene-feature-host :deep(.account-grid) {
  grid-template-columns: 250px minmax(0, 1fr);
  gap: 12px;
}

.scene-feature-host :deep(.identity-card) {
  top: 12px;
  padding: 18px;
}

@container scene-window-content (max-width: 820px) {
  .scene-feature-host :deep(.history-layout),
  .scene-feature-host :deep(.notifications-layout),
  .scene-feature-host :deep(.account-grid) {
    grid-template-columns: minmax(0, 1fr);
  }

  .scene-feature-host :deep(.identity-card),
  .scene-feature-host :deep(.notification-preferences-panel) {
    position: static;
  }
}

@keyframes scene-pan-bounce {
  50% {
    transform: translateY(-5px) rotate(-5deg);
  }
}

.station-brief {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 16px 18px;
  padding: 34px;
  color: #3c2b20;
}

.station-brief-icon {
  display: grid;
  width: 58px;
  height: 58px;
  place-items: center;
  border: 3px solid var(--station-accent);
  color: var(--station-accent);
  background: #221c19;
  font-size: 28px;
  font-weight: 900;
  image-rendering: pixelated;
}

.station-kicker {
  margin: 3px 0 4px;
  color: #966f43;
  font-size: 12px;
  font-weight: 900;
  letter-spacing: 0.12em;
}

.station-brief h2 {
  margin: 0;
  font-size: 24px;
}

.station-description {
  grid-column: 1 / -1;
  margin: 0;
  color: #745b43;
  line-height: 1.7;
}

.station-preview-grid {
  display: grid;
  grid-column: 1 / -1;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.station-preview-card {
  display: grid;
  gap: 5px;
  min-height: 78px;
  padding: 13px;
  border: 1px solid #c8aa7b;
  background: #fffaf0;
}

.station-preview-card span {
  color: #a4763f;
  font-size: 20px;
  font-weight: 900;
}

.station-preview-card strong {
  color: #6a4f37;
  font-size: 12px;
}

.station-entry-list {
  display: grid;
  grid-column: 1 / -1;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.station-entry-card {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  min-height: 68px;
  padding: 10px 12px;
  border: 1px solid #c8aa7b;
  color: #3c2b20;
  background: #fffaf0;
  text-align: left;
  cursor: pointer;
  transition: border-color 160ms ease, background-color 160ms ease, transform 160ms ease;
}

.station-entry-card:hover,
.station-entry-card:focus-visible {
  border-color: var(--app-accent);
  background: #fff4d9;
  outline: none;
  transform: translateY(-1px);
}

.station-entry-card:disabled {
  cursor: wait;
  opacity: 0.68;
  transform: none;
}

.station-entry-mark {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid #9e7b50;
  color: #f4d37f;
  background: #2b211d;
  font-weight: 900;
}

.station-entry-copy {
  display: grid;
  gap: 4px;
}

.station-entry-copy strong {
  font-size: 13px;
}

.station-entry-copy small {
  color: #80664a;
  font-size: 11px;
  line-height: 1.45;
}

.station-entry-arrow {
  color: #9e7444;
  font-size: 24px;
  font-weight: 900;
}

.station-brief-actions {
  display: flex;
  grid-column: 1 / -1;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 4px;
}

@media (max-width: 620px) {
  .station-preview-grid,
  .station-entry-list {
    grid-template-columns: 1fr;
  }
}
</style>
