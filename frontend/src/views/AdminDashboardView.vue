<template>
  <main class="admin-page">
    <section class="admin-shell" aria-label="管理员功能入口">
      <AdminOperationsOverview v-if="activePanel === 'overview'" />

      <div v-else-if="activePanel === 'settings'" class="admin-grid">
        <section class="summary-grid" aria-label="系统概览">
          <article class="summary-card">
            <span class="card-label">当前账号</span>
            <strong>{{ auth.displayName || '管理员' }}</strong>
          </article>
          <article class="summary-card">
            <span class="card-label">角色</span>
            <strong>{{ roleLabel }}</strong>
          </article>
          <article class="summary-card">
            <span class="card-label">会话状态</span>
            <strong>{{ auth.isLoggedIn ? '已登录' : '未登录' }}</strong>
          </article>
        </section>

        <section class="config-panel" v-loading="configLoading" aria-labelledby="ai-config-title">
          <div class="panel-title">
            <PlugZap :size="20" aria-hidden="true" />
            <div>
              <span>模型接入</span>
              <h2 id="ai-config-title">AI 接入配置</h2>
            </div>
          </div>

          <el-form class="config-form" label-position="top" @submit.prevent="saveConfig">
            <div class="config-grid">
              <el-form-item label="服务商">
                <el-select
                  v-model="aiConfig.provider"
                  placeholder="请选择服务商"
                  @change="handleProviderChange"
                >
                  <el-option label="千问" value="qwen" />
                  <el-option label="DeepSeek" value="deepseek" />
                </el-select>
              </el-form-item>

              <el-form-item label="接口协议">
                <el-select
                  v-model="aiConfig.protocol"
                  placeholder="请选择接口协议"
                  @change="handleProtocolChange"
                >
                  <el-option label="OpenAI 兼容" value="openai" />
                  <el-option label="Anthropic 兼容" value="anthropic" />
                </el-select>
              </el-form-item>

              <el-form-item label="模型名称">
                <el-input v-model.trim="aiConfig.modelName" placeholder="例如：qwen-plus" />
              </el-form-item>

              <el-form-item label="接口地址（Base URL）">
                <el-input
                  v-model.trim="aiConfig.endpoint"
                  placeholder="会根据接口协议自动生成"
                />
                <p class="config-hint">已自动填入标准地址，也可以按服务商文档手动修改。</p>
              </el-form-item>

              <el-form-item label="API Key">
                <el-input
                  v-model.trim="aiConfig.apiKey"
                  type="password"
                  show-password
                  autocomplete="off"
                  placeholder="留空则保留已保存的 Key"
                />
                <p class="config-hint">密钥不会回显，留空保存时不会覆盖已有密钥。</p>
              </el-form-item>
            </div>

            <div class="config-footer">
              <div class="config-status">
                <span>Key 状态</span>
                <el-tag :type="apiKeyConfigured ? 'success' : 'warning'">
                  {{ apiKeyConfigured ? `已配置 ${apiKeyPreview}` : '未配置' }}
                </el-tag>
              </div>
              <div class="config-actions">
                <el-switch
                  v-model="aiConfig.enabled"
                  active-text="启用"
                  inactive-text="停用"
                  inline-prompt
                />
                <el-button :loading="testingConfig" @click="testConnection">
                  <PlugZap :size="16" aria-hidden="true" />
                  <span>测试连接</span>
                </el-button>
                <el-button type="primary" :loading="savingConfig" @click="saveConfig">
                  <Save :size="16" aria-hidden="true" />
                  <span>保存配置</span>
                </el-button>
              </div>
            </div>
            <p
              v-if="connectionMessage"
              class="connection-feedback"
              :class="`is-${connectionStatus}`"
              aria-live="polite"
            >
              {{ connectionMessage }}
            </p>
          </el-form>
        </section>

      </div>
      <AdminHotIngredientsPanel v-else-if="activePanel === 'hot-ingredients'" />
      <AdminErrorLogs v-else-if="activePanel === 'error-logs'" />
      <AdminUserManagement v-else-if="activePanel === 'user-management'" />
      <AdminFeatureSuggestions v-else-if="activePanel === 'feature-suggestions'" />
      <AdminAgentObservabilityPanel v-else-if="activePanel === 'agent-observability'" />
      <AdminAgentEvaluationPanel v-else-if="activePanel === 'agent-evaluation'" />
      <AdminOperationLogs v-else />
    </section>
  </main>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute } from 'vue-router'
import { PlugZap, Save } from 'lucide-vue-next'
import {
  getTextRecipeAiConfig,
  saveTextRecipeAiConfig,
  testTextRecipeAiConfig
} from '../api/adminAiConfig'
import AdminHotIngredientsPanel from '../components/AdminHotIngredientsPanel.vue'
import AdminErrorLogs from '../components/AdminErrorLogs.vue'
import AdminOperationLogs from '../components/AdminOperationLogs.vue'
import AdminOperationsOverview from '../components/AdminOperationsOverview.vue'
import AdminUserManagement from '../components/AdminUserManagement.vue'
import AdminFeatureSuggestions from '../components/AdminFeatureSuggestions.vue'
import AdminAgentObservabilityPanel from '../components/AdminAgentObservabilityPanel.vue'
import AdminAgentEvaluationPanel from '../components/AdminAgentEvaluationPanel.vue'
import { useAuthStore } from '../stores/auth'
import { resolveAdminPanel } from '../utils/hotIngredientNavigation'
import { defaultAiEndpoint, isAiPresetEndpoint } from '../utils/adminAiConfig'

const auth = useAuthStore()
const route = useRoute()
const roleLabel = computed(() => ({ ADMIN: '管理员', USER: '普通用户' })[auth.role] || auth.role)
const configLoading = ref(false)
const savingConfig = ref(false)
const testingConfig = ref(false)
const apiKeyConfigured = ref(false)
const apiKeyPreview = ref('')
const connectionStatus = ref('idle')
const connectionMessage = ref('')
const activePanel = computed(() => resolveAdminPanel(route.query.panel))
const aiConfig = ref({
  provider: 'qwen',
  protocol: 'openai',
  modelName: 'qwen-plus',
  endpoint: defaultAiEndpoint('qwen', 'openai'),
  apiKey: '',
  enabled: true
})

onMounted(() => {
  loadConfig()
})

async function loadConfig() {
  configLoading.value = true
  try {
    const response = await getTextRecipeAiConfig()
    applyConfigResponse(response.data.data)
  } catch (error) {
    ElMessage.error(errorMessage(error, 'AI 接入配置加载失败'))
  } finally {
    configLoading.value = false
  }
}

async function saveConfig() {
  if (!validateConfig()) {
    ElMessage.warning('请完整填写服务商、模型名称和接口地址')
    return
  }

  savingConfig.value = true
  try {
    const response = await saveTextRecipeAiConfig({
      provider: aiConfig.value.provider,
      protocol: aiConfig.value.protocol,
      modelName: aiConfig.value.modelName,
      endpoint: aiConfig.value.endpoint,
      apiKey: aiConfig.value.apiKey,
      enabled: aiConfig.value.enabled
    })
    applyConfigResponse(response.data.data)
    resetConnectionFeedback()
    ElMessage.success('AI 接入配置已保存')
  } catch (error) {
    ElMessage.error(errorMessage(error, 'AI 接入配置保存失败'))
  } finally {
    savingConfig.value = false
  }
}

async function testConnection() {
  if (!validateConfig()) {
    ElMessage.warning('请完整填写服务商、模型名称和接口地址')
    return
  }

  testingConfig.value = true
  connectionStatus.value = 'loading'
  connectionMessage.value = '正在测试连接，请稍候…'
  try {
    const response = await testTextRecipeAiConfig({
      provider: aiConfig.value.provider,
      protocol: aiConfig.value.protocol,
      modelName: aiConfig.value.modelName,
      endpoint: aiConfig.value.endpoint,
      apiKey: aiConfig.value.apiKey,
      enabled: aiConfig.value.enabled
    })
    const result = response.data.data
    connectionStatus.value = 'success'
    connectionMessage.value = `${result?.modelName || aiConfig.value.modelName} 连接成功，当前配置尚未保存。`
  } catch (error) {
    connectionStatus.value = 'error'
    connectionMessage.value = errorMessage(error, '连接测试失败，请检查协议、地址和 API Key')
  } finally {
    testingConfig.value = false
  }
}

function applyConfigResponse(config) {
  aiConfig.value = {
    provider: config?.provider || 'qwen',
    protocol: config?.protocol || 'openai',
    modelName: config?.modelName || 'qwen-plus',
    endpoint: config?.endpoint || defaultAiEndpoint(config?.provider || 'qwen', config?.protocol || 'openai'),
    apiKey: '',
    enabled: config?.enabled ?? true
  }
  apiKeyConfigured.value = Boolean(config?.apiKeyConfigured)
  apiKeyPreview.value = config?.apiKeyPreview || ''
}

function validateConfig() {
  if (!aiConfig.value.endpoint) {
    aiConfig.value.endpoint = defaultAiEndpoint(aiConfig.value.provider, aiConfig.value.protocol)
  }
  return Boolean(
    aiConfig.value.provider &&
      aiConfig.value.protocol &&
      aiConfig.value.modelName &&
      aiConfig.value.endpoint
  )
}

function handleProviderChange(provider) {
  if (!aiConfig.value.endpoint || isAiPresetEndpoint(aiConfig.value.endpoint)) {
    aiConfig.value.endpoint = defaultAiEndpoint(provider, aiConfig.value.protocol)
  }
  resetConnectionFeedback()
}

function handleProtocolChange(protocol) {
  if (!aiConfig.value.endpoint || isAiPresetEndpoint(aiConfig.value.endpoint)) {
    aiConfig.value.endpoint = defaultAiEndpoint(aiConfig.value.provider, protocol)
  }
  resetConnectionFeedback()
}

function resetConnectionFeedback() {
  connectionStatus.value = 'idle'
  connectionMessage.value = ''
}

function errorMessage(error, fallback) {
  const message = error?.response?.data?.message || error?.message
  const messages = {
    Unauthorized: '登录状态无效，请重新登录',
    Forbidden: '当前账号没有管理员权限',
    'Invalid request parameters': '请求参数不合法',
    'Network Error': '网络连接失败，请检查后端服务'
  }
  return messages[message] || message || fallback
}

</script>

<style scoped>
.admin-page {
  height: calc(100vh - 58px);
  overflow: hidden;
  padding: clamp(10px, 1.4vw, 18px);
  color: var(--app-text);
}

.admin-shell {
  display: grid;
  grid-template-rows: minmax(0, 1fr);
  width: min(1360px, 100%);
  height: 100%;
  margin: 0 auto;
}

h2 {
  margin: 0;
  color: var(--app-text);
}

h2 {
  font-size: 18px;
}

.admin-grid {
  display: grid;
  grid-template-columns: minmax(220px, 0.7fr) minmax(420px, 1.35fr);
  grid-template-rows: auto minmax(0, 1fr);
  gap: 12px;
  min-height: 0;
}

.summary-grid {
  display: grid;
  grid-row: 1 / span 2;
  gap: 9px;
  min-height: 0;
}

.summary-card,
.config-panel {
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background:
    linear-gradient(90deg, var(--app-grid-line-strong) 1px, transparent 1px),
    linear-gradient(var(--app-grid-line-soft) 1px, transparent 1px),
    var(--app-surface);
  background-size: 32px 32px;
  box-shadow:
    var(--app-panel-shadow),
    inset 0 1px 0 var(--app-grid-line-strong);
}

.summary-card {
  display: grid;
  align-content: space-between;
  min-height: 124px;
  padding: 14px;
}

.card-label,
.panel-title span,
.config-status span {
  color: var(--app-text-muted);
  font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace;
  font-size: 11px;
  font-weight: 800;
}

.summary-card strong {
  overflow-wrap: anywhere;
  color: var(--app-text);
  font-size: 21px;
}

.config-panel {
  min-height: 0;
  padding: 16px;
  overflow: auto;
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  color: var(--app-text);
}

.panel-title div {
  display: grid;
  gap: 4px;
}

.config-form {
  display: grid;
  gap: 10px;
}

.config-form :deep(.el-form-item) {
  margin-bottom: 0;
}

.config-form :deep(.el-form-item__content) {
  display: block;
}

.config-form :deep(.el-select) {
  width: 100%;
}

.config-grid {
  display: grid;
  grid-template-columns: minmax(160px, 0.72fr) minmax(220px, 1fr);
  gap: 10px;
}

.config-grid :deep(.el-form-item:nth-child(4)),
.config-grid :deep(.el-form-item:nth-child(5)) {
  grid-column: span 2;
}

.config-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 2px;
}

.config-status,
.config-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.config-actions :deep(.el-button span) {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.config-hint {
  margin: 5px 0 0;
  color: var(--app-text-muted);
  font-size: 12px;
  line-height: 1.45;
}

.connection-feedback {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
}

.connection-feedback.is-loading {
  color: var(--app-text-muted);
}

.connection-feedback.is-success {
  color: var(--app-success, #198754);
}

.connection-feedback.is-error {
  color: var(--app-danger, #c2410c);
}

@media (max-width: 980px) {
  .admin-page {
    height: auto;
    min-height: calc(100vh - 58px);
    overflow: visible;
  }

  .admin-grid {
    grid-template-columns: 1fr;
  }

  .summary-grid {
    grid-row: auto;
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .admin-page {
    min-height: calc(100vh - 121px);
    padding: 16px;
  }

  .summary-grid,
  .config-grid {
    grid-template-columns: 1fr;
  }

  .config-grid :deep(.el-form-item:nth-child(4)),
  .config-grid :deep(.el-form-item:nth-child(5)) {
    grid-column: auto;
  }

  .config-footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .config-actions {
    flex-wrap: wrap;
  }
}
</style>
