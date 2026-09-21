<template>
  <section class="admin-users-workspace" aria-labelledby="admin-users-title">
    <header class="admin-users-toolbar">
      <div>
        <p>账号状态与安全控制</p>
        <h2 id="admin-users-title">用户管理</h2>
        <span>仅管理普通用户，手机号默认脱敏；禁用会立即使现有登录失效。</span>
      </div>
      <el-button
        circle
        :loading="loading"
        aria-label="刷新用户列表"
        title="刷新用户列表"
        @click="loadUsers"
      >
        <RefreshCw :size="16" aria-hidden="true" />
      </el-button>
    </header>

    <div class="admin-users-summary" aria-live="polite">
      <span>用户总数</span>
      <strong>{{ total }}</strong>
      <i />
      <span>当前页 {{ users.length }} 人</span>
    </div>

    <form class="admin-users-filters" @submit.prevent="applyFilters">
      <el-input
        v-model.trim="keyword"
        clearable
        aria-label="搜索用户"
        placeholder="搜索手机号或昵称"
      >
        <template #prefix>
          <Search :size="16" aria-hidden="true" />
        </template>
      </el-input>
      <el-select v-model="enabledFilter" aria-label="筛选账号状态" @change="applyFilters">
        <el-option
          v-for="option in enabledOptions"
          :key="option.label"
          :label="option.label"
          :value="option.value"
        />
      </el-select>
      <el-select v-model="lockedFilter" aria-label="筛选密码锁定状态" @change="applyFilters">
        <el-option
          v-for="option in lockedOptions"
          :key="option.label"
          :label="option.label"
          :value="option.value"
        />
      </el-select>
      <el-button type="primary" native-type="submit" :loading="loading">查询</el-button>
    </form>

    <div v-if="errorMessage" class="admin-users-error" role="alert">
      <el-alert type="error" :title="errorMessage" :closable="false" show-icon />
      <el-button link type="primary" @click="loadUsers">重试</el-button>
    </div>

    <div class="admin-users-table">
      <el-table
        v-loading="loading"
        :data="users"
        row-key="id"
        height="100%"
        empty-text="暂无符合条件的用户"
      >
        <el-table-column prop="id" label="用户 ID" width="92" />
        <el-table-column label="用户" min-width="220">
          <template #default="scope">
            <div class="admin-user-identity">
              <span class="admin-user-avatar" aria-hidden="true">
                <img v-if="avatarSources[scope.row.id]" :src="avatarSources[scope.row.id]" alt="" />
                <UserCircle v-else :size="22" stroke-width="2.1" />
              </span>
              <strong>{{ scope.row.nickname }}</strong>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="phone" label="手机号" min-width="132" />
        <el-table-column label="账号状态" width="112" align="center">
          <template #default="scope">
            <el-tag :type="adminUserStatusType(scope.row.enabled)" effect="light">
              {{ adminUserStatusLabel(scope.row.enabled) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="密码锁定" width="112" align="center">
          <template #default="scope">
            <el-tag :type="passwordLockType(scope.row.passwordLocked)" effect="light">
              {{ passwordLockLabel(scope.row.passwordLocked) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="注册时间" min-width="168">
          <template #default="scope">{{ formatAdminUserTime(scope.row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="最后登录" min-width="168">
          <template #default="scope">{{ formatLastLogin(scope.row.lastLoginAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="160">
          <template #default="scope">
            <div class="admin-user-actions">
              <el-button
                v-if="scope.row.enabled"
                link
                type="danger"
                :loading="isBusy(statusOperationKey(scope.row.id, false))"
                :aria-label="`禁用用户 ${scope.row.nickname}`"
                @click="disableUser(scope.row)"
              >
                禁用
              </el-button>
              <el-button
                v-else
                link
                type="success"
                :loading="isBusy(statusOperationKey(scope.row.id, true))"
                :aria-label="`启用用户 ${scope.row.nickname}`"
                @click="enableUser(scope.row)"
              >
                启用
              </el-button>
              <el-button
                v-if="scope.row.passwordLocked"
                link
                type="warning"
                :loading="isBusy(lockOperationKey(scope.row.id))"
                :aria-label="`解除 ${scope.row.nickname} 的密码锁定`"
                @click="unlockUser(scope.row)"
              >
                解锁
              </el-button>
              <span v-else class="admin-user-action-muted">无需解锁</span>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <footer class="admin-users-footer">
      <span>共 {{ total }} 条记录</span>
      <el-pagination
        v-if="total"
        v-model:current-page="page"
        :page-size="PAGE_SIZE"
        :total="total"
        background
        layout="prev, pager, next"
        @current-change="changePage"
      />
    </footer>
  </section>
</template>

<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { RefreshCw, Search, UserCircle } from 'lucide-vue-next'
import { clearAdminUserPasswordLock, getAdminUsers, loadAdminUserAvatar, updateAdminUserStatus } from '../api/adminUsers'
import {
  ADMIN_USER_ENABLED_OPTIONS,
  ADMIN_USER_LOCKED_OPTIONS,
  ADMIN_USER_PAGE_SIZE,
  adminUserStatusLabel,
  adminUserStatusType,
  buildAdminUserParams,
  formatAdminUserTime,
  normalizeAdminUser,
  normalizeAdminUserPage,
  passwordLockLabel,
  passwordLockType
} from '../utils/adminUsers'

const PAGE_SIZE = ADMIN_USER_PAGE_SIZE
const enabledOptions = ADMIN_USER_ENABLED_OPTIONS
const lockedOptions = ADMIN_USER_LOCKED_OPTIONS
const users = ref([])
const total = ref(0)
const page = ref(1)
const keyword = ref('')
const enabledFilter = ref('')
const lockedFilter = ref('')
const loading = ref(false)
const errorMessage = ref('')
const avatarSources = reactive({})
const pendingOperations = reactive(new Set())
let usersRequestId = 0
let avatarRequestId = 0

onMounted(loadUsers)
onBeforeUnmount(releaseAvatarSources)

async function loadUsers() {
  const requestId = ++usersRequestId
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await getAdminUsers(buildAdminUserParams({
      keyword: keyword.value,
      enabled: enabledFilter.value,
      locked: lockedFilter.value,
      limit: PAGE_SIZE,
      offset: (page.value - 1) * PAGE_SIZE
    }))
    if (requestId !== usersRequestId) return
    const result = normalizeAdminUserPage(response.data.data)
    users.value = result.items
    total.value = result.total
    releaseAvatarSources()
    await loadAvatars(result.items)
  } catch (error) {
    if (requestId === usersRequestId) {
      errorMessage.value = resolveErrorMessage(error)
    }
  } finally {
    if (requestId === usersRequestId) loading.value = false
  }
}

async function loadAvatars(rows) {
  const requestId = ++avatarRequestId
  await Promise.all(rows.filter((row) => row.avatarUrl).map(async (row) => {
    try {
      const response = await loadAdminUserAvatar(row.id)
      if (requestId !== avatarRequestId) return
      avatarSources[row.id] = URL.createObjectURL(response.data)
    } catch {
      // The neutral default avatar is a valid fallback when an image is missing.
    }
  }))
}

function releaseAvatarSources() {
  avatarRequestId += 1
  Object.values(avatarSources).forEach((source) => URL.revokeObjectURL(source))
  Object.keys(avatarSources).forEach((id) => delete avatarSources[id])
}

function applyFilters() {
  page.value = 1
  void loadUsers()
}

function changePage(nextPage) {
  page.value = nextPage
  void loadUsers()
}

async function disableUser(row) {
  const reason = await requestReason({
    title: '禁用用户',
    message: `禁用后，${row.nickname} 将无法登录，现有会话也会立即失效。请输入原因。`,
    required: true,
    confirmButtonText: '确认禁用'
  })
  if (reason === null) return
  await executeStatusChange(row, false, reason)
}

async function enableUser(row) {
  const reason = await requestReason({
    title: '启用用户',
    message: `确认恢复 ${row.nickname} 的登录权限吗？旧登录会话不会恢复。原因可选。`,
    required: false,
    confirmButtonText: '确认启用'
  })
  if (reason === null) return
  await executeStatusChange(row, true, reason)
}

async function unlockUser(row) {
  try {
    await ElMessageBox.confirm(
      `确认清除 ${row.nickname} 的密码登录锁定吗？这不会修改密码或启用账号。`,
      '清除密码锁定',
      { type: 'warning', confirmButtonText: '确认解锁', cancelButtonText: '暂不处理' }
    )
  } catch {
    return
  }

  const key = lockOperationKey(row.id)
  if (isBusy(key)) return
  pendingOperations.add(key)
  try {
    const response = await clearAdminUserPasswordLock(row.id)
    replaceUser(response.data.data)
    ElMessage.success('密码锁定已清除')
    await loadUsers()
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, '密码锁定清除失败，请稍后重试'))
  } finally {
    pendingOperations.delete(key)
  }
}

async function executeStatusChange(row, enabled, reason) {
  const key = statusOperationKey(row.id, enabled)
  if (isBusy(key)) return
  pendingOperations.add(key)
  try {
    const response = await updateAdminUserStatus(row.id, enabled, reason)
    replaceUser(response.data.data)
    ElMessage.success(enabled ? '用户已启用' : '用户已禁用')
    await loadUsers()
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, enabled ? '用户启用失败，请稍后重试' : '用户禁用失败，请稍后重试'))
  } finally {
    pendingOperations.delete(key)
  }
}

async function requestReason({ title, message, required, confirmButtonText }) {
  try {
    const result = await ElMessageBox.prompt(message, title, {
      inputType: 'textarea',
      inputPlaceholder: required ? '请输入操作原因' : '可选，填写操作原因',
      inputValidator: (value) => validateReason(value, required),
      inputErrorMessage: required ? '请填写操作原因（255 字以内）' : '操作原因不能超过 255 个字符',
      confirmButtonText,
      cancelButtonText: '取消'
    })
    return result.value.trim()
  } catch {
    return null
  }
}

function validateReason(value, required) {
  const normalized = String(value || '').trim()
  if (required && !normalized) return '请输入操作原因'
  if (Array.from(normalized).length > 255) return '操作原因不能超过 255 个字符'
  return true
}

function replaceUser(value) {
  const nextUser = normalizeAdminUser(value)
  const index = users.value.findIndex((user) => user.id === nextUser.id)
  if (index >= 0) users.value[index] = nextUser
}

function statusOperationKey(id, enabled) {
  return `status:${id}:${enabled ? 'enable' : 'disable'}`
}

function lockOperationKey(id) {
  return `unlock:${id}`
}

function isBusy(key) {
  return pendingOperations.has(key)
}

function formatLastLogin(value) {
  return value ? formatAdminUserTime(value) : '暂无登录记录'
}

function resolveErrorMessage(error, fallback = '用户列表加载失败，请稍后重试') {
  const message = error?.response?.data?.message || error?.message
  const messages = {
    Unauthorized: '登录状态已失效，请重新登录',
    Forbidden: '当前账号没有管理员权限',
    '普通用户不存在': '用户不存在或已注销',
    '禁用原因不能为空': '请填写禁用原因',
    'Invalid request parameters': '请求参数不合法',
    'Network Error': '网络连接失败，请检查后端服务'
  }
  return messages[message] || message || fallback
}
</script>

<style scoped>
.admin-users-workspace {
  display: grid;
  grid-template-rows: auto auto auto auto minmax(0, 1fr) auto;
  gap: 12px;
  min-height: 0;
  padding: 16px;
  overflow: hidden;
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

.admin-users-toolbar,
.admin-users-footer,
.admin-users-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.admin-users-toolbar p {
  margin: 0 0 4px;
  color: var(--app-text-muted);
  font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace;
  font-size: 11px;
  font-weight: 800;
}

.admin-users-toolbar h2 {
  margin: 0;
  color: var(--app-text);
  font-size: 18px;
}

.admin-users-toolbar span,
.admin-users-footer,
.admin-users-summary {
  color: var(--app-text-muted);
  font-size: 12px;
}

.admin-users-summary {
  justify-content: flex-start;
  min-height: 32px;
  padding: 0 2px;
}

.admin-users-summary strong {
  color: var(--app-text);
  font-size: 20px;
}

.admin-users-summary i {
  width: 1px;
  height: 18px;
  margin: 0 2px;
  background: var(--app-line-strong);
}

.admin-users-filters {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) 150px 170px auto;
  gap: 10px;
}

.admin-users-error {
  display: flex;
  align-items: center;
  gap: 8px;
}

.admin-users-error .el-alert {
  flex: 1;
}

.admin-users-table {
  min-height: 0;
  overflow: auto;
}

.admin-users-table :deep(.el-table) {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: color-mix(in srgb, var(--app-surface-soft) 82%, transparent);
  --el-table-row-hover-bg-color: color-mix(in srgb, var(--app-accent) 8%, transparent);
  min-width: 1040px;
  height: 100%;
}

.admin-users-table :deep(.el-table__inner-wrapper::before) {
  display: none;
}

.admin-user-identity,
.admin-user-actions {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}

.admin-user-identity strong {
  overflow: hidden;
  color: var(--app-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.admin-user-avatar {
  display: inline-grid;
  width: 38px;
  height: 38px;
  flex: 0 0 38px;
  place-items: center;
  overflow: hidden;
  border: 1px solid var(--app-line-strong);
  border-radius: 50%;
  color: #ffffff;
  background: #a8b0ba;
}

.admin-user-avatar img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.admin-user-action-muted {
  color: var(--app-text-faint);
  font-size: 12px;
}

.admin-users-footer :deep(.el-pagination) {
  margin-left: auto;
}

@media (max-width: 720px) {
  .admin-users-workspace {
    min-height: 620px;
    padding: 12px;
  }

  .admin-users-toolbar {
    align-items: flex-start;
  }

  .admin-users-toolbar span {
    display: block;
    max-width: 280px;
    line-height: 1.5;
  }

  .admin-users-filters {
    grid-template-columns: 1fr;
  }

  .admin-users-filters :deep(.el-button) {
    width: 100%;
  }

  .admin-users-footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .admin-users-footer :deep(.el-pagination) {
    margin-left: 0;
  }
}
</style>
