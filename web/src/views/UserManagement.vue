<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useRouter } from 'vue-router'
import { api, apiErrorMessage } from '../api'
import type { AdminRoleOption, AdminUserDetail, AdminUserView } from '../types'

type UserStatus = '' | 'ACTIVE' | 'DISABLED' | 'LOCKED'
type StatusAction = 'ACTIVE' | 'DISABLED' | 'LOCKED'

const users = ref<AdminUserView[]>([])
const roles = ref<AdminRoleOption[]>([])
const selectedUser = ref<AdminUserDetail | null>(null)
const currentUserId = ref<number | null>(null)
const keyword = ref('')
const status = ref<UserStatus>('')
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const loading = ref(false)
const busy = ref(false)
const feedback = ref('')
const feedbackKind = ref<'success' | 'error'>('success')
const listError = ref('')
const detailError = ref('')
const router = useRouter()

const createOpen = ref(false)
const detailOpen = ref(false)
const passwordOpen = ref(false)
const rolesOpen = ref(false)
const confirmOpen = ref(false)
const confirmAction = ref<{ user: AdminUserView; status: StatusAction } | null>(null)

const newUsername = ref('')
const newDisplayName = ref('')
const newPassword = ref('')
const newPasswordConfirmation = ref('')
const resetPassword = ref('')
const resetPasswordConfirmation = ref('')
const selectedRoleCodes = ref<string[]>([])
const confirmAdminRole = ref(false)
const avatarFile = ref<File | null>(null)
const avatarUploading = ref(false)

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
const hasUsers = computed(() => users.value.length > 0)
const activeRoles = computed(() => roles.value.filter(role => role.status === 'ACTIVE'))
const selectedStatus = computed(() => selectedUser.value?.status || '')

function showFeedback(message: string, kind: 'success' | 'error' = 'success') {
  feedback.value = message
  feedbackKind.value = kind
  window.setTimeout(() => {
    if (feedback.value === message) feedback.value = ''
  }, 4000)
}

function dateTime(value?: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '--'
}

function statusLabel(value?: string) {
  return value === 'ACTIVE' ? '正常' : value === 'DISABLED' ? '已停用' : value === 'LOCKED' ? '已锁定' : value || '--'
}

function statusClass(value?: string) {
  return `user-status-${(value || 'unknown').toLowerCase()}`
}

async function loadPage() {
  loading.value = true
  listError.value = ''
  try {
    const result = await api.getAdminUsersPage({
      status: status.value,
      keyword: keyword.value,
      page: page.value,
      pageSize: pageSize.value,
    })
    users.value = result.items
    total.value = result.total
    page.value = result.page
    pageSize.value = result.pageSize
    if (selectedUser.value && !users.value.some(user => user.id === selectedUser.value?.id)) {
      closeDetail()
    }
  } catch (error) {
    listError.value = apiErrorMessage(error, '用户列表加载失败')
  } finally {
    loading.value = false
  }
}

async function loadReferenceData() {
  try {
    const [user, roleOptions] = await Promise.all([api.me(), api.getAdminRoles()])
    currentUserId.value = user.id
    roles.value = roleOptions
  } catch (error) {
    showFeedback(apiErrorMessage(error, '用户管理权限或角色信息加载失败'), 'error')
  }
}

async function refresh() {
  await Promise.all([loadPage(), loadReferenceData()])
}

async function logout() {
  try {
    await api.logout()
  } finally {
    await router.replace({ path: '/login', query: { reason: 'logged-out' } })
  }
}

function search() {
  page.value = 1
  void loadPage()
}

function changeStatusFilter() {
  page.value = 1
  void loadPage()
}

function movePage(nextPage: number) {
  if (nextPage < 1 || nextPage > pageCount.value || nextPage === page.value) return
  page.value = nextPage
  void loadPage()
}

async function openDetail(user: AdminUserView) {
  detailOpen.value = true
  detailError.value = ''
  selectedUser.value = null
  try {
    selectedUser.value = await api.getAdminUser(user.id)
  } catch (error) {
    detailError.value = apiErrorMessage(error, '用户详情加载失败')
  }
}

function closeDetail() {
  detailOpen.value = false
  closePasswordModal()
  rolesOpen.value = false
  selectedUser.value = null
  avatarFile.value = null
  detailError.value = ''
}

function selectAvatar(event: Event) {
  avatarFile.value = (event.target as HTMLInputElement).files?.[0] || null
}

async function uploadSelectedAvatar() {
  if (!selectedUser.value || !avatarFile.value) return
  avatarUploading.value = true
  try {
    await api.uploadAdminUserAvatar(selectedUser.value.id, avatarFile.value)
    selectedUser.value = await api.getAdminUser(selectedUser.value.id)
    avatarFile.value = null
    showFeedback('用户头像已更新')
  } catch (error) {
    showFeedback(apiErrorMessage(error, '用户头像上传失败'), 'error')
  } finally {
    avatarUploading.value = false
  }
}

function closeCreateModal() {
  createOpen.value = false
  clearCreateForm()
}

function closePasswordModal() {
  passwordOpen.value = false
  resetPassword.value = ''
  resetPasswordConfirmation.value = ''
}

function openStatusConfirmation(user: AdminUserView, nextStatus: StatusAction) {
  confirmAction.value = { user, status: nextStatus }
  confirmOpen.value = true
}

function closeConfirmation() {
  confirmOpen.value = false
  confirmAction.value = null
}

async function applyStatus() {
  if (!confirmAction.value) return
  const targetUserId = confirmAction.value.user.id
  busy.value = true
  try {
    await api.changeAdminUserStatus(targetUserId, { status: confirmAction.value.status })
    showFeedback(`用户已${confirmAction.value.status === 'ACTIVE' ? '启用' : confirmAction.value.status === 'LOCKED' ? '锁定' : '停用'}`)
    closeConfirmation()
    await refreshAfterMutation(targetUserId)
  } catch (error) {
    showFeedback(apiErrorMessage(error, '用户状态更新失败'), 'error')
  } finally {
    busy.value = false
  }
}

async function createUser() {
  if (!newUsername.value.trim() || !newDisplayName.value.trim() || !newPassword.value) {
    showFeedback('请完整填写用户名、展示名和密码', 'error')
    return
  }
  if (newPassword.value !== newPasswordConfirmation.value) {
    showFeedback('两次输入的密码不一致', 'error')
    return
  }
  busy.value = true
  try {
    await api.createAdminUser({
      username: newUsername.value.trim(),
      displayName: newDisplayName.value.trim(),
      rawPassword: newPassword.value,
    })
    clearCreateForm()
    createOpen.value = false
    page.value = 1
    await loadPage()
    showFeedback('普通用户已创建，初始余额为 0.00')
  } catch (error) {
    clearCreateForm()
    showFeedback(apiErrorMessage(error, '用户创建失败'), 'error')
  } finally {
    busy.value = false
  }
}

function clearCreateForm() {
  newUsername.value = ''
  newDisplayName.value = ''
  newPassword.value = ''
  newPasswordConfirmation.value = ''
}

function openPasswordForm() {
  resetPassword.value = ''
  resetPasswordConfirmation.value = ''
  passwordOpen.value = true
}

async function submitPasswordReset() {
  if (!selectedUser.value || !resetPassword.value) {
    showFeedback('请输入新密码', 'error')
    return
  }
  if (resetPassword.value !== resetPasswordConfirmation.value) {
    showFeedback('两次输入的密码不一致', 'error')
    return
  }
  busy.value = true
  try {
    await api.resetAdminUserPassword(selectedUser.value.id, { rawPassword: resetPassword.value })
    closePasswordModal()
    showFeedback('密码已重置，目标用户的旧会话已失效')
  } catch (error) {
    closePasswordModal()
    showFeedback(apiErrorMessage(error, '密码重置失败'), 'error')
  } finally {
    busy.value = false
  }
}

function openRolesForm() {
  selectedRoleCodes.value = [...(selectedUser.value?.roles || [])]
  confirmAdminRole.value = false
  rolesOpen.value = true
}

async function submitRoles() {
  if (!selectedUser.value || selectedRoleCodes.value.length === 0) {
    showFeedback('至少选择一个角色', 'error')
    return
  }
  if (selectedRoleCodes.value.includes('ADMIN') && !confirmAdminRole.value) {
    showFeedback('请明确确认授予管理员角色', 'error')
    return
  }
  busy.value = true
  try {
    selectedUser.value = await api.updateAdminUserRoles(selectedUser.value.id, { roleCodes: selectedRoleCodes.value })
    rolesOpen.value = false
    await loadPage()
    showFeedback('角色授权已保存，目标用户的旧会话已失效')
  } catch (error) {
    showFeedback(apiErrorMessage(error, '角色授权失败'), 'error')
  } finally {
    busy.value = false
  }
}

async function refreshAfterMutation(userId: number) {
  await loadPage()
  if (detailOpen.value) {
    try {
      selectedUser.value = await api.getAdminUser(userId)
    } catch (error) {
      detailError.value = apiErrorMessage(error, '用户详情刷新失败')
    }
  }
}

onMounted(() => {
  void refresh()
})

onBeforeUnmount(() => {
  clearCreateForm()
  closePasswordModal()
})
</script>

<template>
  <div class="admin-page user-management-page">
    <header class="admin-header">
      <div>
        <p class="eyebrow">XUPAN / USER MANAGEMENT</p>
        <h1>用户管理</h1>
      </div>
      <nav class="admin-header-actions" aria-label="后台导航">
        <RouterLink class="header-link" to="/console">运营控制台</RouterLink>
        <RouterLink class="header-link" to="/console/players">玩家工作台</RouterLink>
        <RouterLink class="header-link" to="/room">返回用户前台</RouterLink>
        <button class="header-link" type="button" @click="logout">退出登录</button>
      </nav>
    </header>

    <main class="admin-main">
      <section class="admin-summary user-management-summary" aria-label="用户管理概览">
        <div class="summary-item"><span>用户总数</span><strong>{{ total }}</strong></div>
        <div class="summary-item"><span>当前页</span><strong>{{ page }} / {{ pageCount }}</strong></div>
        <div class="summary-item"><span>列表状态</span><strong>{{ statusLabel(status) }}</strong></div>
        <div class="summary-item"><span>当前页条数</span><strong>{{ users.length }}</strong></div>
      </section>

      <section class="admin-section user-list-section">
        <div class="section-title">
          <div><span class="eyebrow">USERS</span><h2>成员账号</h2></div>
          <button type="button" class="primary-button" :disabled="busy" @click="createOpen = true">新增普通用户</button>
        </div>

        <div class="user-filter-bar">
          <label class="user-search-field">关键字<input v-model="keyword" type="search" maxlength="64" placeholder="用户名或展示名" @keyup.enter="search" /></label>
          <label class="user-status-field">状态<select v-model="status" @change="changeStatusFilter"><option value="">全部状态</option><option value="ACTIVE">正常</option><option value="DISABLED">已停用</option><option value="LOCKED">已锁定</option></select></label>
          <button type="button" class="secondary-button" :disabled="loading" @click="search">查询</button>
          <button type="button" class="secondary-button" :disabled="loading" @click="refresh">刷新</button>
        </div>

        <p v-if="listError" class="inline-error" role="alert">{{ listError }}</p>
        <div v-else-if="loading" class="user-empty-state">正在加载用户列表...</div>
        <div v-else-if="!hasUsers" class="user-empty-state">
          <strong>暂无用户</strong>
          <span>{{ keyword || status ? '没有符合当前筛选条件的用户' : '请先创建普通用户，再进行聊天室验收' }}</span>
          <button type="button" class="secondary-button" @click="createOpen = true">创建第一个用户</button>
        </div>
        <div v-else class="user-table-wrap">
          <table class="user-table">
            <thead><tr><th>ID</th><th>用户名</th><th>展示名</th><th>角色</th><th>状态</th><th>创建时间</th><th>最后登录</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="user in users" :key="user.id">
                <td>{{ user.id }}</td>
                <td><strong>{{ user.username }}</strong></td>
                <td>{{ user.displayName }}</td>
                <td><span class="role-list">{{ user.roles?.join(' / ') || '--' }}</span></td>
                <td><span class="user-status" :class="statusClass(user.status)">{{ statusLabel(user.status) }}</span></td>
                <td>{{ dateTime(user.createdAt) }}</td>
                <td>{{ dateTime(user.lastLoginAt) }}</td>
                <td><button type="button" class="table-action" @click="openDetail(user)">详情</button></td>
              </tr>
            </tbody>
          </table>
        </div>

        <footer v-if="total > 0" class="user-pagination">
          <span>共 {{ total }} 条</span>
          <div class="pagination-actions">
            <button type="button" class="secondary-button" :disabled="page <= 1 || loading" @click="movePage(page - 1)">上一页</button>
            <span>第 {{ page }} / {{ pageCount }} 页</span>
            <button type="button" class="secondary-button" :disabled="page >= pageCount || loading" @click="movePage(page + 1)">下一页</button>
          </div>
        </footer>
      </section>
    </main>

    <div v-if="createOpen" class="user-modal-layer" role="presentation" @click.self="closeCreateModal">
      <section class="user-modal" role="dialog" aria-modal="true" aria-labelledby="create-user-title">
        <header><div><span class="eyebrow">CREATE USER</span><h2 id="create-user-title">新增普通用户</h2></div><button type="button" class="modal-close" aria-label="关闭" @click="closeCreateModal">×</button></header>
        <p class="modal-note">新用户固定获得 USER 角色，余额从 0.00 开始，不在此处分配余额。</p>
        <form class="user-form" @submit.prevent="createUser">
          <label>用户名<input v-model="newUsername" autocomplete="off" maxlength="64" required /></label>
          <label>展示名<input v-model="newDisplayName" autocomplete="off" maxlength="64" required /></label>
          <label>初始密码<input v-model="newPassword" type="password" autocomplete="new-password" maxlength="128" required /></label>
          <label>确认密码<input v-model="newPasswordConfirmation" type="password" autocomplete="new-password" maxlength="128" required /></label>
          <div class="modal-actions"><button type="button" class="secondary-button" :disabled="busy" @click="closeCreateModal">取消</button><button type="submit" class="primary-button" :disabled="busy">创建用户</button></div>
        </form>
      </section>
    </div>

    <div v-if="detailOpen" class="user-modal-layer" role="presentation" @click.self="closeDetail">
      <section class="user-modal user-detail-modal" role="dialog" aria-modal="true" aria-labelledby="user-detail-title">
        <header><div><span class="eyebrow">USER DETAIL</span><h2 id="user-detail-title">用户详情</h2></div><button type="button" class="modal-close" aria-label="关闭" @click="closeDetail">×</button></header>
        <p v-if="detailError" class="inline-error" role="alert">{{ detailError }}</p>
        <div v-else-if="!selectedUser" class="user-empty-state">正在加载详情...</div>
        <template v-else>
          <div class="admin-avatar-editor"><div class="admin-avatar-preview"><img v-if="selectedUser.avatarKey" :src="api.avatarUrl(selectedUser.avatarKey)" alt="用户头像" /><span v-else>{{ selectedUser.displayName.slice(0, 1) }}</span></div><label>真实头像<input type="file" accept="image/jpeg,image/png,image/gif,image/webp" :disabled="avatarUploading" @change="selectAvatar" /></label><button type="button" class="secondary-button" :disabled="!avatarFile || avatarUploading" @click="uploadSelectedAvatar">{{ avatarUploading ? '上传中...' : '上传头像' }}</button></div>
          <dl class="user-detail-grid"><div><dt>ID</dt><dd>{{ selectedUser.id }}</dd></div><div><dt>用户名</dt><dd>{{ selectedUser.username }}</dd></div><div><dt>展示名</dt><dd>{{ selectedUser.displayName }}</dd></div><div><dt>状态</dt><dd><span class="user-status" :class="statusClass(selectedUser.status)">{{ statusLabel(selectedUser.status) }}</span></dd></div><div><dt>角色</dt><dd>{{ selectedUser.roles.join(' / ') || '--' }}</dd></div><div><dt>创建时间</dt><dd>{{ dateTime(selectedUser.createdAt) }}</dd></div><div><dt>最后登录</dt><dd>{{ dateTime(selectedUser.lastLoginAt) }}</dd></div><div><dt>余额</dt><dd>{{ selectedUser.wallet ? `¥${selectedUser.wallet.balance.toFixed(2)}` : '--' }}</dd></div></dl>
          <div class="detail-actions">
            <button v-if="selectedStatus !== 'ACTIVE'" type="button" class="secondary-button" :disabled="busy" @click="openStatusConfirmation(selectedUser, 'ACTIVE')">启用</button>
            <button v-if="selectedStatus === 'ACTIVE'" type="button" class="secondary-button warning-button" :disabled="busy || selectedUser.id === currentUserId" @click="openStatusConfirmation(selectedUser, 'DISABLED')">停用</button>
            <button v-if="selectedStatus !== 'LOCKED'" type="button" class="secondary-button warning-button" :disabled="busy || selectedUser.id === currentUserId" @click="openStatusConfirmation(selectedUser, 'LOCKED')">锁定</button>
            <button type="button" class="secondary-button" :disabled="busy" @click="openPasswordForm">重置密码</button>
            <button type="button" class="secondary-button" :disabled="busy" @click="openRolesForm">角色授权</button>
          </div>
          <p v-if="selectedUser.id === currentUserId" class="modal-note">当前登录管理员不能在此停用或锁定自己。</p>
        </template>
      </section>
    </div>

    <div v-if="passwordOpen && selectedUser" class="user-modal-layer nested-modal" role="presentation" @click.self="closePasswordModal">
      <section class="user-modal compact-modal" role="dialog" aria-modal="true" aria-labelledby="reset-password-title">
        <header><div><span class="eyebrow">RESET PASSWORD</span><h2 id="reset-password-title">重置密码</h2></div><button type="button" class="modal-close" aria-label="关闭" @click="closePasswordModal">×</button></header>
        <p class="modal-note">目标用户：{{ selectedUser.username }}。提交后该用户的旧会话会失效。</p>
        <form class="user-form" @submit.prevent="submitPasswordReset"><label>新密码<input v-model="resetPassword" type="password" autocomplete="new-password" maxlength="128" required /></label><label>确认密码<input v-model="resetPasswordConfirmation" type="password" autocomplete="new-password" maxlength="128" required /></label><div class="modal-actions"><button type="button" class="secondary-button" :disabled="busy" @click="closePasswordModal">取消</button><button type="submit" class="primary-button" :disabled="busy">确认重置</button></div></form>
      </section>
    </div>

    <div v-if="rolesOpen && selectedUser" class="user-modal-layer nested-modal" role="presentation" @click.self="rolesOpen = false">
      <section class="user-modal compact-modal" role="dialog" aria-modal="true" aria-labelledby="roles-title">
        <header><div><span class="eyebrow">ROLE AUTHORIZATION</span><h2 id="roles-title">角色授权</h2></div><button type="button" class="modal-close" aria-label="关闭" @click="rolesOpen = false">×</button></header>
        <div class="role-options"><label v-for="role in activeRoles" :key="role.code" class="role-option"><input v-model="selectedRoleCodes" type="checkbox" :value="role.code" /><span><strong>{{ role.name }}</strong><small>{{ role.code }}</small></span></label><p v-if="!activeRoles.length" class="user-empty-state">暂无可用角色</p></div>
        <label v-if="selectedRoleCodes.includes('ADMIN')" class="admin-role-confirm"><input v-model="confirmAdminRole" type="checkbox" />我确认授予该用户管理员权限</label>
        <div class="modal-actions"><button type="button" class="secondary-button" :disabled="busy" @click="rolesOpen = false">取消</button><button type="button" class="primary-button" :disabled="busy" @click="submitRoles">保存授权</button></div>
      </section>
    </div>

    <div v-if="confirmOpen && confirmAction" class="user-modal-layer nested-modal" role="presentation" @click.self="closeConfirmation">
      <section class="user-modal compact-modal" role="dialog" aria-modal="true" aria-labelledby="confirm-title">
        <header><div><span class="eyebrow">CONFIRM ACTION</span><h2 id="confirm-title">确认{{ confirmAction.status === 'ACTIVE' ? '启用' : confirmAction.status === 'LOCKED' ? '锁定' : '停用' }}</h2></div><button type="button" class="modal-close" aria-label="关闭" @click="closeConfirmation">×</button></header>
        <p class="modal-note">将对用户“{{ confirmAction.user.displayName }}（{{ confirmAction.user.username }}）”执行此操作。状态变更后会撤销其有效会话。</p>
        <div class="modal-actions"><button type="button" class="secondary-button" :disabled="busy" @click="closeConfirmation">取消</button><button type="button" class="primary-button warning-primary" :disabled="busy" @click="applyStatus">确认执行</button></div>
      </section>
    </div>

    <p v-if="feedback" class="toast" :class="`toast-${feedbackKind}`" role="status">{{ feedback }}</p>
  </div>
</template>

<style scoped>
.admin-page {
  --retro-line: #8eb7e6;
  --retro-line-strong: #4b8ed3;
  --retro-title: #dceeff;
  --retro-title-deep: #bfdffb;
  --retro-soft: #f1f7ff;
  --retro-ink: #1f3d5c;
  --retro-muted: #60788e;
  box-sizing: border-box;
  width: 1000px;
  min-width: 1000px;
  max-width: 1000px;
  overflow-x: hidden;
  color: var(--retro-ink);
  background:
    linear-gradient(180deg, rgb(255 255 255 / 78%), rgb(224 237 250 / 68%)),
    repeating-linear-gradient(0deg, transparent 0 23px, rgb(91 137 183 / 6%) 23px 24px);
  font-family: "Microsoft YaHei", "PingFang SC", "Segoe UI", sans-serif;
  font-size: 12px;
}

.admin-page *,
.admin-page *::before,
.admin-page *::after {
  box-sizing: border-box;
}

.admin-page .admin-header {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  min-height: 44px;
  padding: 4px 7px;
  border: 1px solid var(--retro-line-strong);
  border-top-width: 2px;
  background: linear-gradient(#f8fcff, var(--retro-title) 56%, var(--retro-title-deep));
  box-shadow: inset 0 0 0 1px rgb(255 255 255 / 72%), 0 1px 2px rgb(41 83 124 / 14%);
}

.admin-page .admin-header > div {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.admin-page .admin-header .eyebrow {
  order: 2;
  margin: 0;
  color: #2f6ea3;
  font-size: 8px;
  letter-spacing: .1em;
  white-space: nowrap;
}

.admin-page .admin-header h1 {
  margin: 0;
  color: #173b5e;
  font-size: 16px;
  line-height: 1.1;
}

.admin-page .admin-header-actions {
  flex-wrap: nowrap;
  justify-content: flex-end;
  gap: 3px;
}

.admin-page .header-link {
  min-height: 24px;
  border-radius: 1px;
  background: linear-gradient(#fff, #eaf4ff);
  padding: 0 7px;
  font-size: 11px;
  font-weight: 700;
  line-height: 22px;
  white-space: nowrap;
}

.admin-page .admin-main {
  width: 100%;
  max-width: none;
  margin: 0;
  padding: 5px 6px 10px;
}

.admin-page .admin-summary {
  gap: 1px;
  margin-bottom: 6px;
  border: 1px solid var(--retro-line);
  background: var(--retro-line);
  box-shadow: none;
}

.admin-page .summary-item {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 38px;
  background: linear-gradient(#fff, var(--retro-soft));
  padding: 4px 7px;
}

.admin-page .summary-item span {
  color: var(--retro-muted);
  font-size: 10px;
  white-space: nowrap;
}

.admin-page .summary-item strong {
  margin: 0 0 0 auto;
  color: #15486f;
  font-size: 15px;
  font-variant-numeric: tabular-nums;
  line-height: 1;
}

.admin-page .admin-section {
  min-width: 0;
  border: 1px solid var(--retro-line);
  background: #fff;
  box-shadow: none;
  padding: 6px;
}

.admin-page .section-title {
  align-items: center;
  min-height: 28px;
  margin: -6px -6px 6px;
  border-bottom: 1px solid var(--retro-line);
  background: linear-gradient(#eef8ff, var(--retro-title));
  padding: 3px 6px;
}

.admin-page .section-title h2 {
  margin: 0;
  color: #173d61;
  font-size: 13px;
  line-height: 1.2;
}

.admin-page .section-title .eyebrow {
  display: block;
  margin-bottom: 1px;
  color: #2d6fa6;
  font-size: 8px;
  letter-spacing: .08em;
}

.admin-page button,
.admin-page input,
.admin-page select {
  border-radius: 1px;
  font-family: inherit;
}

.admin-page button {
  min-height: 24px;
  border: 1px solid var(--retro-line);
  background: linear-gradient(#fff, #e8f3ff);
  padding: 2px 7px;
  color: var(--retro-ink);
  font-size: 11px;
  line-height: 1.2;
}

.admin-page button:hover:not(:disabled) {
  border-color: var(--retro-line-strong);
  background: linear-gradient(#f7fbff, #d8ecff);
}

.admin-page .primary-button {
  border-color: #2d7bcd;
  background: linear-gradient(#4d9ce0, #2471b9);
  color: #fff;
}

.admin-page .primary-button:hover:not(:disabled) {
  background: linear-gradient(#62a9e5, #1e66a9);
}

.admin-page .warning-primary {
  border-color: #b8682f;
  background: linear-gradient(#d58a4d, #b45f27);
  color: #fff;
}

.admin-page input,
.admin-page select {
  min-width: 0;
  min-height: 24px;
  height: 24px;
  border: 1px solid var(--retro-line);
  background: #fff;
  padding: 2px 5px;
  color: var(--retro-ink);
  font-size: 11px;
}

.admin-page input:focus,
.admin-page select:focus {
  border-color: #2d7bcd;
  box-shadow: 0 0 0 1px rgb(45 123 205 / 22%);
  outline: none;
}

.admin-page .user-filter-bar {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) 126px auto auto;
  align-items: end;
  gap: 5px;
  margin-bottom: 6px;
  border: 1px solid #bfd8ef;
  background: linear-gradient(#f8fcff, #edf6ff);
  padding: 5px;
}

.admin-page .user-filter-bar label,
.admin-page .user-form label {
  gap: 2px;
  color: var(--retro-muted);
  font-size: 10px;
}

.admin-page .user-filter-bar input,
.admin-page .user-filter-bar select,
.admin-page .user-form input {
  width: 100%;
  min-width: 0;
  height: 24px;
}

.admin-page .user-status-field select {
  min-width: 0;
}

.admin-page .user-table-wrap {
  max-width: 100%;
  overflow-x: auto;
  border: 1px solid #bfd8ef;
}

.admin-page .user-table {
  width: 100%;
  min-width: 900px;
  border-collapse: collapse;
  background: #fff;
  color: #29465f;
  font-size: 10px;
}

.admin-page .user-table th,
.admin-page .user-table td {
  border-right: 1px solid #dce9f5;
  border-bottom: 1px solid #dce9f5;
  padding: 4px 5px;
  text-align: left;
  vertical-align: middle;
}

.admin-page .user-table th {
  background: linear-gradient(#eef8ff, #d9ecfc);
  color: #28577d;
  font-size: 10px;
  font-weight: 700;
  white-space: nowrap;
}

.admin-page .user-table tbody tr:hover {
  background: #eef8ff;
}

.admin-page .user-table tbody tr:last-child td {
  border-bottom: 0;
}

.admin-page .role-list {
  color: #42657f;
}

.admin-page .user-status {
  display: inline-flex;
  align-items: center;
  min-height: 18px;
  border: 1px solid #9ec4e4;
  border-radius: 1px;
  background: #edf7ff;
  padding: 0 5px;
  color: #25618f;
  font-size: 9px;
  font-weight: 700;
  white-space: nowrap;
}

.admin-page .user-status-active {
  border-color: #8bc8ad;
  background: #eaf8f1;
  color: #19704e;
}

.admin-page .user-status-disabled {
  border-color: #b8c4ce;
  background: #f1f4f6;
  color: #63717d;
}

.admin-page .user-status-locked {
  border-color: #dda879;
  background: #fff1e6;
  color: #a64f1f;
}

.admin-page .user-empty-state {
  min-height: 110px;
  gap: 5px;
  color: #718698;
  font-size: 11px;
}

.admin-page .user-empty-state strong {
  color: #365b78;
  font-size: 13px;
}

.admin-page .inline-error {
  margin: 4px 0;
  border-radius: 1px;
  background: #fff5f5;
  padding: 5px 7px;
  font-size: 10px;
}

.admin-page .user-pagination {
  align-items: center;
  gap: 6px;
  margin-top: 5px;
  border-top: 1px solid #bfd8ef;
  padding-top: 4px;
  color: var(--retro-muted);
  font-size: 10px;
}

.admin-page .pagination-actions,
.admin-page .modal-actions,
.admin-page .detail-actions {
  gap: 4px;
}

.admin-page .table-action {
  min-height: 20px;
  border-radius: 1px;
  padding: 1px 5px;
  font-size: 10px;
}

.admin-page .user-modal-layer {
  background: rgb(22 49 73 / 48%);
  padding: 8px;
}

.admin-page .user-modal {
  width: min(100%, 540px);
  max-height: calc(100vh - 16px);
  border: 1px solid var(--retro-line-strong);
  background: #f7fbff;
  box-shadow: 0 3px 12px rgb(24 60 94 / 28%);
  padding: 6px;
}

.admin-page .user-detail-modal {
  width: min(100%, 720px);
}

.admin-page .compact-modal {
  width: min(100%, 440px);
}

.admin-page .user-modal > header {
  align-items: center;
  gap: 8px;
  min-height: 31px;
  margin: -6px -6px 6px;
  border-bottom: 1px solid var(--retro-line);
  background: linear-gradient(#eef8ff, var(--retro-title));
  padding: 4px 6px;
}

.admin-page .user-modal h2 {
  margin: 0;
  color: #173d61;
  font-size: 13px;
  line-height: 1.2;
}

.admin-page .user-modal header .eyebrow {
  display: block;
  margin-bottom: 1px;
  color: #2d6fa6;
  font-size: 8px;
  letter-spacing: .08em;
}

.admin-page .modal-close {
  width: 22px;
  height: 22px;
  min-height: 22px;
  border: 1px solid #9ec4e4;
  background: linear-gradient(#fff, #e4f1fc);
  color: #315b78;
  padding: 0;
  font-size: 16px;
  line-height: 18px;
}

.admin-page .modal-note {
  margin: 0 0 5px;
  color: #61788d;
  font-size: 10px;
  line-height: 1.45;
}

.admin-page .user-form {
  gap: 5px;
}

.admin-page .modal-actions,
.admin-page .detail-actions {
  margin-top: 6px;
}

.admin-page .detail-actions {
  border-top: 1px solid #bfd8ef;
  padding-top: 5px;
}

.admin-page .user-detail-grid {
  gap: 1px;
  border: 1px solid #bfd8ef;
  background: #bfd8ef;
}

.admin-page .user-detail-grid div {
  background: linear-gradient(#fff, #f2f8ff);
  padding: 5px 6px;
}

.admin-page .user-detail-grid dt {
  color: var(--retro-muted);
  font-size: 9px;
}

.admin-page .user-detail-grid dd {
  margin: 2px 0 0;
  color: #203f5b;
  font-size: 11px;
}

.admin-page .role-options {
  gap: 1px;
  border: 1px solid #bfd8ef;
  background: #bfd8ef;
}

.admin-page .role-option {
  border: 0;
  background: #fff;
  padding: 5px 7px;
}

.admin-page .role-option strong {
  font-size: 11px;
}

.admin-page .role-option small,
.admin-page .admin-role-confirm {
  font-size: 10px;
}

.admin-page .admin-role-confirm {
  margin-top: 5px;
}

.admin-page .admin-avatar-editor {
  align-items: center;
  gap: 6px;
  margin-bottom: 5px;
  border-top: 1px solid #bfd8ef;
  border-bottom: 1px solid #bfd8ef;
  padding: 5px 0;
}

.admin-page .admin-avatar-preview {
  width: 38px;
  height: 38px;
  flex-basis: 38px;
  border: 1px solid #8eb7e6;
  border-radius: 1px;
  background: #dceeff;
  font-size: 15px;
}

.admin-page .admin-avatar-editor label {
  font-size: 10px;
}

.admin-page .toast {
  top: 7px;
  right: 7px;
  width: min(330px, calc(100vw - 14px));
  border-radius: 1px;
  box-shadow: 0 2px 8px rgb(25 63 99 / 22%);
  padding: 7px 9px;
  font-size: 11px;
}

@media (max-width: 760px) {
  .admin-page .admin-header {
    grid-template-columns: 1fr;
  }

  .admin-page .admin-header-actions {
    flex-wrap: wrap;
    justify-content: flex-start;
  }
}
</style>
