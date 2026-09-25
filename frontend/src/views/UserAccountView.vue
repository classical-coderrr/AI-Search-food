<template>
  <section class="account-page" aria-labelledby="account-title">
    <div class="account-heading">
      <div>
        <p class="eyebrow">我的身份档案</p>
        <h1 id="account-title">账号中心</h1>
        <p class="heading-copy">管理个人资料、登录安全和小厨灵记住的偏好。手机号仅用于登录和身份验证，不能在此修改。</p>
      </div>
      <RouterLink class="back-link" to="/">返回工作台 <span aria-hidden="true">↗</span></RouterLink>
    </div>

    <div v-if="loading" class="account-loading" role="status">正在读取账号资料…</div>

    <template v-else>
      <div class="account-grid">
        <article class="identity-card account-panel">
          <div class="identity-kicker"><span class="signal-dot" />账号状态</div>
          <div class="avatar-wrap">
            <img v-if="avatarSource" :src="avatarSource" alt="当前头像" class="avatar-image" />
            <div v-else class="avatar-fallback" aria-hidden="true">{{ avatarInitial }}</div>
            <button class="avatar-edit" type="button" aria-label="选择新头像" @click="openFilePicker">
              <Camera :size="17" aria-hidden="true" />
            </button>
          </div>
          <h2>{{ account.nickname || '未设置昵称' }}</h2>
          <p class="identity-phone">{{ account.phone || '—' }}</p>
          <span class="status-chip"><span class="status-pip" />{{ statusLabel }}</span>
          <div class="identity-rule" />
          <p class="identity-note">头像只对当前账号开放访问，上传后会自动替换旧头像。</p>
          <input
            ref="fileInput"
            class="visually-hidden"
            type="file"
            accept="image/jpeg,image/png,image/webp"
            @change="handleAvatarChange"
          />
          <div class="avatar-actions">
            <el-button class="soft-button" :loading="uploading" @click="openFilePicker">更换头像</el-button>
            <el-button v-if="account.avatarUrl" class="text-danger-button" :disabled="uploading" @click="removeAvatar">
              删除头像
            </el-button>
          </div>
          <p class="upload-hint">支持 JPG、PNG、WebP，单张不超过 2MB</p>
        </article>

        <div class="account-main-column">
          <article class="account-panel profile-panel">
            <div class="panel-heading">
              <div>
                <p class="panel-label">PROFILE / 01</p>
                <h2>基本资料</h2>
              </div>
              <span class="panel-mark">A—01</span>
            </div>
            <form class="profile-form" @submit.prevent="saveProfile">
              <label class="field-label" for="nickname">昵称</label>
              <div class="nickname-row">
                <el-input
                  id="nickname"
                  v-model="nickname"
                  class="account-input"
                  maxlength="64"
                  minlength="2"
                  show-word-limit
                  placeholder="给自己一个好记的名字"
                />
                <el-button class="save-button" type="primary" native-type="submit" :loading="saving">保存资料</el-button>
              </div>
              <p class="field-hint">2–64 个字符，保存后会同步更新顶部账号展示。</p>
            </form>

            <dl class="profile-facts">
              <div>
                <dt>绑定手机号</dt>
                <dd>{{ account.phone || '—' }} <span class="readonly-tag">仅展示</span></dd>
              </div>
              <div>
                <dt>注册时间</dt>
                <dd>{{ formatDate(account.registeredAt) }}</dd>
              </div>
              <div>
                <dt>最近登录</dt>
                <dd>{{ formatDate(account.lastLoginAt) }}</dd>
              </div>
            </dl>
          </article>

          <article class="account-panel security-panel">
            <div class="panel-heading">
              <div>
                <p class="panel-label">SECURITY / 02</p>
                <h2>登录安全</h2>
              </div>
              <ShieldCheck :size="22" aria-hidden="true" />
            </div>
            <div class="security-action">
              <div>
                <h3>退出全部设备</h3>
                <p>立即使其他浏览器和设备上的登录状态失效。当前页面也会退出。</p>
              </div>
              <el-button class="outline-button" :loading="loggingOutAll" @click="handleLogoutAll">退出全部设备</el-button>
            </div>
          </article>

          <article class="account-panel memory-panel" aria-labelledby="memory-heading">
            <div class="panel-heading">
              <div>
                <p class="panel-label">MEMORY / 03</p>
                <h2 id="memory-heading">记忆与个性化</h2>
              </div>
              <Brain :size="22" aria-hidden="true" />
            </div>

            <div class="memory-settings-row">
              <div class="memory-settings-copy">
                <h3>个性化记忆</h3>
                <p>开启后，小厨灵会从你的使用行为中逐步学习，并在相关任务中参考这些记忆。</p>
                <p class="memory-setting-state" role="status" aria-live="polite">
                  {{ memoryPersonalization.enabled
                    ? '已开启：新行为会继续用于学习，相关记忆可参与回答。'
                    : '已关闭：暂停新记忆学习和回答召回，已有记忆仍保留在下方。' }}
                </p>
              </div>
              <el-switch
                :model-value="memoryPersonalization.enabled"
                :loading="updatingPersonalization"
                :disabled="memoryLoading || Boolean(memoryError) || memoryMutating || clearingMemories || updatingPersonalization"
                active-text="开启"
                inactive-text="关闭"
                aria-label="个性化记忆开关"
                @change="handlePersonalizationToggle"
              />
            </div>

            <el-alert
              v-if="memoryError"
              class="memory-error"
              :title="memoryError"
              type="error"
              :closable="false"
              show-icon
            >
              <template #default>
                <el-button link type="primary" @click="loadMemoryManagement">重新加载记忆</el-button>
              </template>
            </el-alert>

            <div v-if="memoryLoading" class="memory-loading" role="status" aria-live="polite">
              正在读取记忆…
            </div>
            <template v-else-if="!memoryError">
              <div class="memory-list-heading">
                <div>
                  <h3>小厨灵记住的内容</h3>
                  <p>共 {{ memoryTotal }} 条。你可以随时调整或移除，不影响菜谱、收藏和冰箱数据。</p>
                </div>
                <el-button
                  class="outline-button memory-refresh"
                  :loading="memoryLoading"
                  :disabled="memoryMutating || clearingMemories || updatingPersonalization"
                  @click="loadMemoryManagement"
                >
                  刷新
                </el-button>
              </div>

              <p v-if="memoryTotal > memoryItems.length" class="memory-limit-note">
                当前展示 {{ memoryItems.length }} 条；记忆较多时，这里最多载入 500 条。
              </p>

              <div v-if="memoryItems.length" class="memory-list" role="list" aria-label="已保存的个性化记忆">
                <article v-for="item in visibleMemoryItems" :key="item.id" class="memory-item" role="listitem">
                  <div class="memory-item-main">
                    <div class="memory-item-tags">
                      <span class="memory-type-tag">{{ memoryTypeLabel(item.memoryType) }}</span>
                      <span class="memory-scope-tag">{{ memoryScopeLabel(item.scope) }}</span>
                      <span v-if="item.userModified" class="memory-user-tag">你已修改</span>
                    </div>
                    <h4>{{ item.entity || memoryTypeLabel(item.memoryType) }}</h4>
                    <p class="memory-preference-line">
                      {{ memoryPreferenceLabel(item.preference) }}<span v-if="item.entity"> · {{ memoryTypeLabel(item.memoryType) }}</span>
                    </p>
                    <dl class="memory-facts">
                      <div>
                        <dt>置信度</dt>
                        <dd>{{ formatPercent(item.confidence) }}</dd>
                      </div>
                      <div>
                        <dt>偏好强度</dt>
                        <dd>{{ formatPercent(item.strength) }}</dd>
                      </div>
                      <div>
                        <dt>相关证据</dt>
                        <dd>{{ item.evidenceCount ?? 0 }} 条</dd>
                      </div>
                      <div>
                        <dt>行为次数</dt>
                        <dd>{{ item.occurrenceCount ?? 0 }} 次</dd>
                      </div>
                      <div>
                        <dt>首次记录</dt>
                        <dd>{{ formatDate(item.firstSeenAt) }}</dd>
                      </div>
                      <div>
                        <dt>最近更新</dt>
                        <dd>{{ formatDate(item.lastSeenAt) }}</dd>
                      </div>
                      <div>
                        <dt>记忆来源</dt>
                        <dd>{{ memoryTemporalTypeLabel(item.temporalType) }} · {{ item.sourceCount ?? 0 }} 个来源</dd>
                      </div>
                    </dl>
                  </div>
                  <div class="memory-item-actions">
                    <el-button
                      v-if="item.editable"
                      class="outline-button memory-action-button"
                      :disabled="memoryMutating || clearingMemories"
                      @click="openMemoryEditor(item)"
                    >
                      <Pencil :size="15" aria-hidden="true" />编辑
                    </el-button>
                    <el-button
                      class="memory-delete-button"
                      :disabled="memoryMutating || clearingMemories"
                      :aria-label="`删除记忆：${item.entity || memoryTypeLabel(item.memoryType)}`"
                      @click="handleDeleteMemory(item)"
                    >
                      <Trash2 :size="15" aria-hidden="true" />删除
                    </el-button>
                  </div>
                </article>
              </div>

              <el-pagination
                v-if="memoryItems.length > memoryPageSize"
                v-model:current-page="memoryPage"
                class="memory-pagination"
                background
                layout="prev, pager, next"
                :page-size="memoryPageSize"
                :total="memoryItems.length"
                aria-label="记忆列表分页"
              />

              <div v-if="!memoryItems.length" class="memory-empty-state">
                <Sparkles :size="21" aria-hidden="true" />
                <div>
                  <h4>暂时还没有长期记忆</h4>
                  <p>随着你搜索、收藏、烹饪和评价菜谱，小厨灵会逐步总结有帮助的偏好。</p>
                </div>
              </div>
            </template>

            <div class="memory-clear-row">
              <div>
                <h3>清空全部记忆</h3>
                <p>删除已保存的记忆、行为事件和记忆画像；不会删除菜谱、收藏或冰箱数据。</p>
              </div>
              <el-button
                class="memory-clear-button"
                :loading="clearingMemories"
                :disabled="memoryLoading || Boolean(memoryError) || memoryMutating || updatingPersonalization || memoryEditorVisible || clearingMemories"
                @click="handleClearMemories"
              >
                <Trash2 :size="15" aria-hidden="true" />清空全部
              </el-button>
            </div>
          </article>

          <article class="account-panel danger-panel">
            <div class="panel-heading">
              <div>
                <p class="panel-label">IRREVERSIBLE / 04</p>
                <h2>注销账号</h2>
              </div>
              <AlertTriangle :size="22" aria-hidden="true" />
            </div>
            <p class="danger-copy">注销后将无法恢复当前账号数据。你的资料、密码和头像会被清除，历史记录中的用户关系会保留为匿名状态。</p>
            <div class="cancel-form">
              <label class="field-label" for="cancel-code">短信验证码</label>
              <div class="code-row">
                <el-input id="cancel-code" v-model="cancelCode" class="account-input" inputmode="numeric" maxlength="6" placeholder="输入 6 位验证码" />
                <el-button class="soft-button" :disabled="codeCountdown > 0 || requestingCode" :loading="requestingCode" @click="requestCancelCode">
                  {{ codeCountdown > 0 ? `${codeCountdown}s 后重发` : '获取验证码' }}
                </el-button>
              </div>
              <el-button class="danger-button" :loading="cancelling" :disabled="!cancelCode" @click="handleCancelAccount">
                注销当前账号
              </el-button>
            </div>
          </article>
        </div>
      </div>
    </template>

    <el-dialog
      v-model="memoryEditorVisible"
      class="memory-editor-dialog"
      title="修改这条记忆"
      width="min(500px, calc(100vw - 32px))"
      :close-on-click-modal="false"
      :show-close="!savingMemoryEdit"
      :close-on-press-escape="!savingMemoryEdit"
      @closed="editingMemory = null"
    >
      <form v-if="editingMemory" class="memory-editor-form" @submit.prevent="saveMemoryEdit">
        <p class="memory-editor-entity">{{ editingMemory.entity || memoryTypeLabel(editingMemory.memoryType) }}</p>
        <label class="field-label" for="memory-preference">你的偏好</label>
        <el-select
          id="memory-preference"
          v-model="memoryEditForm.preference"
          class="memory-editor-control"
          :disabled="savingMemoryEdit"
          aria-label="选择你的偏好"
        >
          <el-option
            v-for="option in memoryEditOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <p class="field-hint">这会更新小厨灵今后采用的偏好判断。</p>

        <label class="field-label memory-strength-label" for="memory-strength">偏好强度</label>
        <el-input-number
          id="memory-strength"
          v-model="memoryEditForm.strength"
          class="memory-editor-control"
          :min="0"
          :max="1"
          :step="0.05"
          :precision="2"
          controls-position="right"
          :disabled="savingMemoryEdit"
          aria-label="偏好强度，范围 0 到 1"
        />
        <p class="field-hint">数值越高，表示这条偏好在画像中的程度越强。</p>
      </form>
      <template #footer>
        <el-button class="outline-button" :disabled="savingMemoryEdit" @click="memoryEditorVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingMemoryEdit" @click="saveMemoryEdit">保存修改</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter, RouterLink } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { AlertTriangle, Brain, Camera, Pencil, ShieldCheck, Sparkles, Trash2 } from 'lucide-vue-next'
import { cancelMyAccount, deleteMyAvatar, getMyAccount, loadMyAvatar, logoutAllDevices, requestAccountCancellationCode, updateMyProfile, uploadMyAvatar } from '../api/userAccount'
import { clearManagedMemories, deleteManagedMemory, getMemoryManagement, updateManagedMemory, updateMemoryPersonalization } from '../api/memory'
import { useAuthStore } from '../stores/auth'
import { memoryPreferenceLabel, memoryPreferenceOptions, memoryScopeLabel, memoryTemporalTypeLabel, memoryTypeLabel, normalizeMemoryManagementResponse, toMemoryUpdatePayload } from '../utils/memoryManagement.js'

const router = useRouter()
const auth = useAuthStore()
const account = ref({})
const nickname = ref('')
const cancelCode = ref('')
const loading = ref(true)
const saving = ref(false)
const uploading = ref(false)
const requestingCode = ref(false)
const cancelling = ref(false)
const loggingOutAll = ref(false)
const codeCountdown = ref(0)
const fileInput = ref(null)
const avatarPreview = ref('')
const avatarImageUrl = ref('')
const memoryPersonalization = ref({ enabled: true, version: 0 })
const memoryItems = ref([])
const memoryTotal = ref(0)
const memoryLoading = ref(true)
const memoryError = ref('')
const updatingPersonalization = ref(false)
const memoryMutating = ref(false)
const clearingMemories = ref(false)
const memoryEditorVisible = ref(false)
const editingMemory = ref(null)
const memoryEditForm = ref({ preference: '', strength: 0.5 })
const savingMemoryEdit = ref(false)
const memoryPage = ref(1)
const memoryPageSize = 20
let countdownTimer = null

const avatarSource = computed(() => avatarPreview.value || avatarImageUrl.value)
const avatarInitial = computed(() => (account.value.nickname || '用户').trim().slice(0, 1).toUpperCase())
const statusLabel = computed(() => account.value.status === 'ACTIVE' ? '账号正常' : '账号不可用')
const memoryEditOptions = computed(() => memoryPreferenceOptions(editingMemory.value?.memoryType))
const visibleMemoryItems = computed(() => {
  const start = (memoryPage.value - 1) * memoryPageSize
  return memoryItems.value.slice(start, start + memoryPageSize)
})

onMounted(() => {
  void loadAccount()
  void loadMemoryManagement()
})
onBeforeUnmount(() => {
  revokeAvatarPreview()
  revokeAvatarImageUrl()
  clearCountdown()
})

async function loadAccount() {
  loading.value = true
  try {
    const response = await getMyAccount()
    account.value = response.data.data || {}
    nickname.value = account.value.nickname || ''
    await loadAvatarImage()
  } catch (error) {
    ElMessage.error(messageFrom(error, '账号资料读取失败，请稍后重试'))
  } finally {
    loading.value = false
  }
}

async function loadMemoryManagement() {
  memoryLoading.value = true
  memoryError.value = ''
  try {
    const response = await getMemoryManagement()
    const overview = normalizeMemoryManagementResponse(response.data.data)
    memoryPersonalization.value = overview.personalization
    memoryItems.value = overview.memories
    memoryTotal.value = overview.total
    memoryPage.value = Math.min(memoryPage.value, Math.max(1, Math.ceil(overview.memories.length / memoryPageSize)))
  } catch (error) {
    memoryError.value = messageFrom(error, '记忆读取失败，请检查网络后重试')
  } finally {
    memoryLoading.value = false
  }
}

async function handlePersonalizationToggle(enabled) {
  if (memoryMutating.value || clearingMemories.value || updatingPersonalization.value
      || enabled === memoryPersonalization.value.enabled) return
  if (!enabled) {
    try {
      await ElMessageBox.confirm(
        '关闭后会暂停新行为学习和回答中的记忆召回，但不会删除已有记忆。你仍可在本页查看、修改或清空它们。',
        '关闭个性化记忆',
        { type: 'warning', confirmButtonText: '确认关闭', cancelButtonText: '继续保留' }
      )
    } catch {
      return
    }
  }

  updatingPersonalization.value = true
  try {
    const response = await updateMemoryPersonalization({
      enabled,
      version: memoryPersonalization.value.version
    })
    memoryPersonalization.value = response.data.data || { ...memoryPersonalization.value, enabled }
    ElMessage.success(enabled ? '个性化记忆已开启' : '个性化记忆已关闭')
  } catch (error) {
    ElMessage.error(messageFrom(error, '设置更新失败，请刷新后重试'))
    if (error?.response?.status === 409) await loadMemoryManagement()
  } finally {
    updatingPersonalization.value = false
  }
}

function openMemoryEditor(item) {
  editingMemory.value = item
  memoryEditForm.value = {
    preference: item.preference,
    strength: Number(item.strength ?? 0.5)
  }
  memoryEditorVisible.value = true
}

async function saveMemoryEdit() {
  const item = editingMemory.value
  if (!item || savingMemoryEdit.value) return
  savingMemoryEdit.value = true
  memoryMutating.value = true
  try {
    await updateManagedMemory(item.id, toMemoryUpdatePayload(item, memoryEditForm.value))
    memoryEditorVisible.value = false
    ElMessage.success('记忆已更新')
    await loadMemoryManagement()
  } catch (error) {
    ElMessage.error(messageFrom(error, '记忆修改失败，请刷新后重试'))
    if (error?.response?.status === 409) {
      await loadMemoryManagement()
      const latest = memoryItems.value.find((candidate) => candidate.id === item.id)
      if (latest) {
        openMemoryEditor(latest)
        ElMessage.info('已载入最新记忆，请核对后再保存')
      } else {
        memoryEditorVisible.value = false
      }
    }
  } finally {
    savingMemoryEdit.value = false
    memoryMutating.value = false
  }
}

async function handleDeleteMemory(item) {
  if (memoryMutating.value || clearingMemories.value) return
  const name = item.entity || memoryTypeLabel(item.memoryType)
  try {
    await ElMessageBox.confirm(
      `确定删除“${name}”这条记忆吗？删除不会影响对应菜谱或收藏；后续的新行为仍可能形成新的相关记忆。`,
      '删除这条记忆',
      { type: 'warning', confirmButtonText: '删除记忆', cancelButtonText: '保留' }
    )
  } catch {
    return
  }

  memoryMutating.value = true
  try {
    await deleteManagedMemory(item.id, item.version)
    ElMessage.success('这条记忆已删除')
    await loadMemoryManagement()
  } catch (error) {
    ElMessage.error(messageFrom(error, '记忆删除失败，请刷新后重试'))
    if (error?.response?.status === 409) await loadMemoryManagement()
  } finally {
    memoryMutating.value = false
  }
}

async function handleClearMemories() {
  if (clearingMemories.value || memoryMutating.value || updatingPersonalization.value || memoryEditorVisible.value) return
  try {
    await ElMessageBox.confirm(
      '这会清除已保存的记忆、行为事件和记忆画像，且无法恢复；不会删除菜谱、收藏或冰箱数据。个性化开关保持原样，之后的新行为仍可能形成新记忆。',
      '清空全部记忆',
      { type: 'warning', confirmButtonText: '清空全部记忆', cancelButtonText: '取消' }
    )
  } catch {
    return
  }

  clearingMemories.value = true
  try {
    await clearManagedMemories()
    ElMessage.success('记忆与相关历史已清空')
    await loadMemoryManagement()
  } catch (error) {
    ElMessage.error(messageFrom(error, '清空失败，请稍后重试'))
  } finally {
    clearingMemories.value = false
  }
}

function formatPercent(value) {
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return '—'
  return `${Math.round(Math.min(1, Math.max(0, numeric)) * 100)}%`
}

async function saveProfile() {
  if (saving.value) return
  if (nickname.value.trim().length < 2) {
    ElMessage.warning('昵称至少需要 2 个字符')
    return
  }
  saving.value = true
  try {
    const response = await updateMyProfile(nickname.value.trim())
    account.value = response.data.data || account.value
    nickname.value = account.value.nickname || nickname.value.trim()
    auth.setDisplayName(nickname.value)
    ElMessage.success('资料已保存')
  } catch (error) {
    ElMessage.error(messageFrom(error, '资料保存失败，请稍后重试'))
  } finally {
    saving.value = false
  }
}

function openFilePicker() {
  fileInput.value?.click()
}

async function handleAvatarChange(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
    ElMessage.error('头像仅支持 JPG、PNG 或 WebP 图片')
    return
  }
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.error('头像图片不能超过 2MB')
    return
  }
  revokeAvatarPreview()
  avatarPreview.value = URL.createObjectURL(file)
  uploading.value = true
  try {
    const response = await uploadMyAvatar(file)
    account.value = response.data.data || account.value
    try {
      await loadAvatarImage()
    } catch {
      revokeAvatarImageUrl()
    }
    auth.refreshAvatar()
    revokeAvatarPreview()
    ElMessage.success('头像已更新')
  } catch (error) {
    revokeAvatarPreview()
    ElMessage.error(messageFrom(error, '头像上传失败，请稍后重试'))
  } finally {
    uploading.value = false
  }
}

async function removeAvatar() {
  if (uploading.value) return
  try {
    await ElMessageBox.confirm('确定删除当前头像吗？', '删除头像', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '保留' })
  } catch {
    return
  }
  uploading.value = true
  try {
    const response = await deleteMyAvatar()
    account.value = response.data.data || { ...account.value, avatarUrl: null }
    revokeAvatarImageUrl()
    revokeAvatarPreview()
    auth.refreshAvatar()
    ElMessage.success('头像已删除')
  } catch (error) {
    ElMessage.error(messageFrom(error, '头像删除失败，请稍后重试'))
  } finally {
    uploading.value = false
  }
}

async function handleLogoutAll() {
  if (loggingOutAll.value) return
  try {
    await ElMessageBox.confirm('这会让全部设备立即退出，包括当前页面。确定继续吗？', '退出全部设备', { type: 'warning', confirmButtonText: '确认退出', cancelButtonText: '暂不退出' })
  } catch {
    return
  }
  loggingOutAll.value = true
  try {
    await logoutAllDevices()
    auth.logout()
    await router.push({ name: 'login' })
  } catch (error) {
    ElMessage.error(messageFrom(error, '操作失败，请稍后重试'))
  } finally {
    loggingOutAll.value = false
  }
}

async function requestCancelCode() {
  if (requestingCode.value || codeCountdown.value > 0) return
  requestingCode.value = true
  try {
    const response = await requestAccountCancellationCode()
    const retryAfter = Number(response.data.data?.retryAfterSeconds || 60)
    codeCountdown.value = retryAfter
    clearCountdown()
    countdownTimer = window.setInterval(() => {
      codeCountdown.value -= 1
      if (codeCountdown.value <= 0) clearCountdown()
    }, 1000)
    ElMessage.success('验证码已发送，请查收短信')
  } catch (error) {
    ElMessage.error(messageFrom(error, '验证码发送失败，请稍后重试'))
  } finally {
    requestingCode.value = false
  }
}

async function handleCancelAccount() {
  if (cancelling.value) return
  try {
    await ElMessageBox.confirm('注销后无法恢复当前账号数据，确定要注销吗？', '最后确认', { type: 'warning', confirmButtonText: '确认注销', cancelButtonText: '我再想想' })
  } catch {
    return
  }
  cancelling.value = true
  try {
    await cancelMyAccount(cancelCode.value.trim(), true)
    auth.logout()
    await router.push({ name: 'login' })
  } catch (error) {
    ElMessage.error(messageFrom(error, '注销失败，请检查验证码后重试'))
  } finally {
    cancelling.value = false
  }
}

function formatDate(value) {
  if (!value) return '暂无记录'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '暂无记录'
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

function revokeAvatarPreview() {
  if (avatarPreview.value) URL.revokeObjectURL(avatarPreview.value)
  avatarPreview.value = ''
}

async function loadAvatarImage() {
  if (!account.value.avatarUrl) {
    revokeAvatarImageUrl()
    return
  }
  try {
    const response = await loadMyAvatar()
    revokeAvatarImageUrl()
    avatarImageUrl.value = URL.createObjectURL(response.data)
  } catch (error) {
    revokeAvatarImageUrl()
    if (error?.response?.status !== 404) throw error
  }
}

function revokeAvatarImageUrl() {
  if (avatarImageUrl.value) URL.revokeObjectURL(avatarImageUrl.value)
  avatarImageUrl.value = ''
}

function clearCountdown() {
  if (countdownTimer) window.clearInterval(countdownTimer)
  countdownTimer = null
}

function messageFrom(error, fallback) {
  return error?.response?.data?.message || fallback
}
</script>

<style scoped>
.account-page {
  width: min(1180px, 100%);
  margin: 0 auto;
  padding: 10px 0 64px;
}

.account-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 28px;
}

.eyebrow,
.panel-label {
  margin: 0 0 8px;
  color: var(--app-accent);
  font-size: 11px;
  font-weight: 900;
  letter-spacing: .18em;
}

h1,
h2,
h3,
p {
  margin-top: 0;
}

h1 {
  margin-bottom: 9px;
  color: var(--app-text);
  font-size: clamp(30px, 4vw, 48px);
  letter-spacing: -.06em;
  line-height: 1;
}

.heading-copy {
  max-width: 620px;
  margin-bottom: 0;
  color: var(--app-text-muted);
  font-size: 14px;
  line-height: 1.7;
}

.back-link {
  flex: 0 0 auto;
  color: var(--app-text-muted);
  font-size: 13px;
  font-weight: 800;
  text-decoration: none;
}

.back-link:hover { color: var(--app-accent); }

.account-loading {
  min-height: 240px;
  display: grid;
  place-items: center;
  border: 1px dashed var(--app-line-strong);
  color: var(--app-text-muted);
}

.account-grid {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  align-items: start;
  gap: 18px;
}

.account-main-column { display: grid; gap: 18px; }

.account-panel {
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-surface);
  box-shadow: var(--app-panel-shadow);
}

.identity-card {
  position: sticky;
  top: 22px;
  padding: 24px;
  overflow: hidden;
  background: linear-gradient(160deg, var(--app-surface-strong), var(--app-surface));
}

.identity-kicker {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--app-text-faint);
  font-size: 11px;
  font-weight: 900;
  letter-spacing: .12em;
  text-transform: uppercase;
}

.signal-dot,
.status-pip { width: 7px; height: 7px; border-radius: 50%; background: var(--app-accent); box-shadow: 0 0 0 4px var(--app-accent-soft); }

.avatar-wrap { position: relative; width: 112px; height: 112px; margin: 28px 0 20px; }
.avatar-image,
.avatar-fallback { width: 100%; height: 100%; border-radius: 50%; }
.avatar-image { display: block; object-fit: cover; }
.avatar-fallback { display: grid; place-items: center; color: var(--app-accent-text); background: var(--app-accent); font-size: 42px; font-weight: 900; }
.avatar-edit { position: absolute; right: 0; bottom: 0; display: grid; width: 34px; height: 34px; place-items: center; border: 3px solid var(--app-surface); border-radius: 50%; color: var(--app-accent-text); background: var(--app-text); cursor: pointer; }
.avatar-edit:hover { background: var(--app-accent); }
.identity-card h2 { margin-bottom: 5px; color: var(--app-text); font-size: 25px; letter-spacing: -.04em; }
.identity-phone { margin-bottom: 14px; color: var(--app-text-muted); font-size: 14px; }
.status-chip { display: inline-flex; align-items: center; gap: 8px; padding: 6px 10px; border: 1px solid var(--app-line); border-radius: 999px; color: var(--app-text-soft); font-size: 12px; font-weight: 800; }
.status-pip { width: 6px; height: 6px; box-shadow: none; }
.identity-rule { height: 1px; margin: 24px 0 16px; background: var(--app-line); }
.identity-note, .upload-hint { color: var(--app-text-faint); font-size: 12px; line-height: 1.6; }
.identity-note { margin-bottom: 20px; }
.avatar-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.upload-hint { margin: 10px 0 0; }

.profile-panel, .security-panel, .memory-panel, .danger-panel { padding: 25px 28px; }
.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 22px; }
.panel-heading h2 { margin-bottom: 0; color: var(--app-text); font-size: 23px; letter-spacing: -.04em; }
.panel-heading > svg { color: var(--app-accent); }
.panel-mark { color: var(--app-text-faint); font-family: monospace; font-size: 12px; }
.memory-settings-row { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 16px 18px; border: 1px solid var(--app-line); border-radius: 8px; background: var(--app-surface-strong); }
.memory-settings-copy { min-width: 0; max-width: 720px; }
.memory-settings-copy h3, .memory-list-heading h3, .memory-clear-row h3 { margin-bottom: 6px; color: var(--app-text-soft); font-size: 15px; }
.memory-settings-copy p, .memory-list-heading p, .memory-clear-row p { margin-bottom: 0; color: var(--app-text-muted); font-size: 13px; line-height: 1.65; }
.memory-settings-copy .memory-setting-state { margin-top: 7px; color: var(--app-text-soft); font-weight: 700; }
.memory-error { margin-top: 16px; }
.memory-error :deep(.el-alert__description) { margin-top: 4px; }
.memory-loading { min-height: 90px; display: grid; place-items: center; color: var(--app-text-muted); font-size: 14px; }
.memory-list-heading { display: flex; align-items: center; justify-content: space-between; gap: 18px; margin: 24px 0 14px; }
.memory-list-heading > div { min-width: 0; }
.memory-refresh { flex: 0 0 auto; }
.memory-limit-note { margin: 0 0 12px; color: var(--app-text-faint); font-size: 12px; line-height: 1.5; }
.memory-list { display: grid; gap: 12px; }
.memory-pagination { justify-content: center; margin-top: 16px; }
.memory-item { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; padding: 16px; border: 1px solid var(--app-line); border-radius: 8px; background: var(--app-surface); }
.memory-item-main { min-width: 0; flex: 1; }
.memory-item-tags { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; margin-bottom: 10px; }
.memory-type-tag, .memory-scope-tag, .memory-user-tag { display: inline-flex; align-items: center; min-height: 24px; padding: 2px 8px; border: 1px solid var(--app-line); border-radius: 999px; color: var(--app-text-muted); font-size: 11px; font-weight: 800; }
.memory-type-tag { color: var(--app-accent-text); border-color: color-mix(in srgb, var(--app-accent) 32%, var(--app-line)); background: var(--app-accent-soft); }
.memory-user-tag { color: var(--app-text-soft); border-color: var(--app-line-strong); }
.memory-item h4 { margin-bottom: 4px; color: var(--app-text); font-size: 17px; overflow-wrap: anywhere; }
.memory-preference-line { margin-bottom: 13px; color: var(--app-text-soft); font-size: 14px; line-height: 1.55; }
.memory-facts { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px 14px; margin: 0; }
.memory-facts div { min-width: 0; }
.memory-facts dt { margin-bottom: 4px; color: var(--app-text-faint); font-size: 11px; }
.memory-facts dd { margin: 0; color: var(--app-text-soft); font-size: 12px; font-weight: 700; line-height: 1.5; overflow-wrap: anywhere; }
.memory-item-actions { display: flex; flex: 0 0 auto; align-items: center; gap: 6px; }
.memory-action-button, .memory-delete-button, .memory-clear-button { min-height: 44px; font-weight: 800; }
.memory-action-button { display: inline-flex; align-items: center; gap: 6px; }
.memory-delete-button { color: #a94f42; border: 1px solid color-mix(in srgb, #b45c48 35%, var(--app-line)); background: transparent; }
.memory-delete-button:hover, .memory-clear-button:hover { color: #8f3e32; border-color: #b45c48; background: color-mix(in srgb, #b45c48 7%, var(--app-surface)); }
.memory-empty-state { display: flex; align-items: flex-start; gap: 12px; padding: 20px; border: 1px dashed var(--app-line-strong); border-radius: 8px; color: var(--app-accent); background: var(--app-surface-strong); }
.memory-empty-state svg { flex: 0 0 auto; margin-top: 1px; }
.memory-empty-state h4 { margin-bottom: 5px; color: var(--app-text); font-size: 15px; }
.memory-empty-state p { margin-bottom: 0; color: var(--app-text-muted); font-size: 13px; line-height: 1.65; }
.memory-clear-row { display: flex; align-items: center; justify-content: space-between; gap: 18px; margin-top: 22px; padding-top: 18px; border-top: 1px solid var(--app-line); }
.memory-clear-row > div { min-width: 0; }
.memory-clear-row p { max-width: 670px; }
.memory-clear-button { display: inline-flex; flex: 0 0 auto; align-items: center; gap: 7px; color: #a94f42; border-color: color-mix(in srgb, #b45c48 45%, var(--app-line)); background: transparent; }
.memory-clear-button:disabled, .memory-delete-button:disabled { cursor: not-allowed; }
.memory-editor-form { display: grid; gap: 10px; }
.memory-editor-entity { margin: 0 0 4px; padding: 12px; border-radius: 6px; color: var(--app-text); background: var(--app-surface-strong); font-weight: 800; overflow-wrap: anywhere; }
.memory-editor-form .field-label { margin: 0; }
.memory-strength-label { margin-top: 8px !important; }
.memory-editor-control { width: 100%; }
.memory-editor-form .field-hint { margin-top: -5px; }
.profile-form { padding-bottom: 22px; border-bottom: 1px solid var(--app-line); }
.field-label { display: block; margin-bottom: 8px; color: var(--app-text-soft); font-size: 13px; font-weight: 900; }
.nickname-row, .code-row { display: flex; align-items: center; gap: 10px; }
.account-input { flex: 1; }
.field-hint { margin: 8px 0 0; color: var(--app-text-faint); font-size: 12px; }
.profile-facts { display: grid; grid-template-columns: repeat(3, 1fr); gap: 18px; margin: 22px 0 0; }
.profile-facts div { min-width: 0; }
.profile-facts dt { margin-bottom: 7px; color: var(--app-text-faint); font-size: 12px; }
.profile-facts dd { margin: 0; color: var(--app-text-soft); font-size: 13px; font-weight: 800; line-height: 1.5; }
.readonly-tag { display: inline-block; margin-left: 5px; padding: 2px 5px; color: var(--app-text-faint); border: 1px solid var(--app-line); font-size: 10px; font-weight: 700; }
.security-action { display: flex; align-items: center; justify-content: space-between; gap: 24px; padding: 15px 0 2px; }
.security-action h3 { margin-bottom: 6px; color: var(--app-text-soft); font-size: 15px; }
.security-action p, .danger-copy { max-width: 650px; margin-bottom: 0; color: var(--app-text-muted); font-size: 13px; line-height: 1.7; }
.danger-panel { border-color: color-mix(in srgb, #c56b55 32%, var(--app-line)); }
.danger-panel .panel-heading > svg { color: #c56b55; }
.danger-copy { margin-bottom: 20px; }
.cancel-form { max-width: 600px; }
.danger-button { margin-top: 15px; color: #fff !important; border-color: #b45c48 !important; background: #b45c48 !important; }
.danger-button:hover { border-color: #914332 !important; background: #914332 !important; }
.soft-button, .outline-button, .save-button, .text-danger-button { min-height: 36px; font-weight: 800; }
.soft-button { color: var(--app-text-soft); border-color: var(--app-line-strong); background: var(--app-surface-strong); }
.soft-button:hover, .outline-button:hover { color: var(--app-accent); border-color: var(--app-accent); }
.outline-button { color: var(--app-text-soft); border-color: var(--app-line-strong); background: transparent; }
.save-button { flex: 0 0 auto; }
.text-danger-button { padding: 0 4px; color: #b45c48; border: 0; background: transparent; }
.visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }

button:focus-visible, a:focus-visible, input:focus-visible { outline: 3px solid color-mix(in srgb, var(--app-accent) 55%, transparent); outline-offset: 3px; }

@media (prefers-reduced-motion: reduce) { *, *::before, *::after { scroll-behavior: auto !important; transition-duration: .01ms !important; animation-duration: .01ms !important; } }

@media (max-width: 860px) {
  .account-page { padding-top: 0; }
  .account-grid { grid-template-columns: 1fr; }
  .identity-card { position: static; }
  .identity-card { display: grid; grid-template-columns: 112px minmax(0, 1fr); column-gap: 18px; align-items: center; }
  .identity-kicker, .avatar-wrap, .identity-card h2, .identity-phone, .status-chip, .identity-rule, .identity-note, .avatar-actions, .upload-hint { grid-column: 1 / -1; }
  .avatar-wrap { margin: 24px 0 0; }
  .identity-card h2 { margin-top: 0; }
  .memory-facts { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}

@media (max-width: 620px) {
  .account-heading { align-items: flex-start; flex-direction: column; gap: 14px; }
  .profile-panel, .security-panel, .memory-panel, .danger-panel, .identity-card { padding: 20px; }
  .nickname-row, .code-row, .security-action { align-items: stretch; flex-direction: column; }
  .profile-facts { grid-template-columns: 1fr; gap: 13px; }
  .save-button, .code-row .soft-button, .outline-button { width: 100%; }
  .memory-settings-row, .memory-list-heading, .memory-item, .memory-clear-row { align-items: stretch; flex-direction: column; }
  .memory-settings-row :deep(.el-switch) { align-self: flex-start; }
  .memory-list-heading { margin-top: 20px; }
  .memory-refresh, .memory-item-actions > *, .memory-clear-button { width: 100%; }
  .memory-item-actions { flex-direction: column; }
  .memory-facts { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
  .memory-settings-copy p, .memory-list-heading p, .memory-clear-row p, .memory-empty-state p { font-size: 14px; }
  .memory-preference-line { font-size: 15px; }
}
</style>
