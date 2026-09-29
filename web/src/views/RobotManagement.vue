<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { api, apiErrorMessage } from '../api'
import { toInstantQueryValue, validateRobotDrawComponents, validateRobotDraft as validateRobotDraftInput } from '../robotAdmin'
import type { CurrentUserView } from '../types'
import type {
  DispatchPage,
  DispatchSummary,
  RobotDrawComponentConfig,
  RobotDrawComponentList,
  RobotDrawComponentType,
  RobotDetail,
  RobotDispatchStatus,
  RobotEventType,
  RobotStatus,
  RobotSummary,
  TemplatePreview,
  TemplateSummary,
} from '../types/robot'

const eventTypes: RobotEventType[] = ['ISSUE_STARTED', 'BETTING_WARNING', 'BETTING_CLOSED', 'DRAW_RESULT']
const eventLabels: Record<RobotEventType, string> = {
  ISSUE_STARTED: '新期开始',
  BETTING_WARNING: '封盘提醒',
  BETTING_CLOSED: '已封盘',
  DRAW_RESULT: '开奖结果',
}
const dispatchStatuses: RobotDispatchStatus[] = ['PENDING', 'PROCESSING', 'FAILED', 'PUBLISHED', 'SKIPPED']
const robotStatusLabels: Record<RobotStatus, string> = { ENABLED: '已启用', DISABLED: '已停用' }
const dispatchStatusLabels: Record<RobotDispatchStatus, string> = {
  PENDING: '待处理',
  PROCESSING: '处理中',
  FAILED: '失败',
  PUBLISHED: '已发布',
  SKIPPED: '已跳过',
}
const drawComponentTypes: RobotDrawComponentType[] = ['DRAW_SUMMARY', 'DRAW_HISTORY', 'WINNER_LIST']
const drawComponentLabels: Record<RobotDrawComponentType, string> = {
  DRAW_SUMMARY: '开奖摘要',
  DRAW_HISTORY: '历史结果',
  WINNER_LIST: '获胜名单',
}

type RobotDraft = {
  robotCode: string
  displayName: string
  avatarKey: string
  weight: number
  delaySeconds: number
}

type DispatchFilters = {
  issueNumber: string
  eventType: RobotEventType | ''
  status: RobotDispatchStatus | ''
  from: string
  to: string
}

const router = useRouter()
const currentUser = ref<CurrentUserView | null>(null)
const robots = ref<RobotSummary[]>([])
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const selectedRobotId = ref<number | null>(null)
const selectedRobot = ref<RobotDetail | null>(null)
const loading = ref(false)
const detailLoading = ref(false)
const dispatchLoading = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const detailError = ref('')
const dispatchError = ref('')
const feedback = ref('')
const feedbackKind = ref<'success' | 'error'>('success')
const createOpen = ref(false)
const editingRobotId = ref<number | null>(null)
const robotDraft = ref<RobotDraft>(emptyRobotDraft())
const selectedEventType = ref<RobotEventType>('ISSUE_STARTED')
const templateDraft = ref('')
const templateSaving = ref(false)
const templatePreviewing = ref(false)
const templatePreview = ref<TemplatePreview | null>(null)
const templateError = ref('')
const drawComponents = ref<RobotDrawComponentConfig[]>([])
const drawComponentSaving = ref(false)
const drawComponentError = ref('')
const dispatches = ref<DispatchSummary[]>([])
const dispatchPage = ref(1)
const dispatchPageSize = ref(20)
const dispatchTotal = ref(0)
const retryingDispatchId = ref<number | null>(null)
const dispatchFilters = reactive<DispatchFilters>({
  issueNumber: '',
  eventType: '',
  status: '',
  from: '',
  to: '',
})
const avatarFile = ref<File | null>(null)
const avatarUploading = ref(false)

const canWrite = computed(() => currentUser.value?.permissions.includes('ROBOT_WRITE') === true)
const canTemplateWrite = computed(() => currentUser.value?.permissions.includes('ROBOT_TEMPLATE_WRITE') === true)
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
const dispatchPageCount = computed(() => Math.max(1, Math.ceil(dispatchTotal.value / dispatchPageSize.value)))
const editing = computed(() => editingRobotId.value !== null)
const selectedTemplate = computed<TemplateSummary | null>(() => {
  const candidates = (selectedRobot.value?.templates ?? [])
    .filter(template => template.eventType === selectedEventType.value)
    .sort((left, right) => {
      if (left.enabled !== right.enabled) return left.enabled ? -1 : 1
      return right.version - left.version
    })
  return candidates[0] ?? null
})
const draftErrors = computed(() => validateRobotDraft(robotDraft.value, editing.value))
const drawComponentErrors = computed(() => validateRobotDrawComponents(drawComponents.value))

function emptyRobotDraft(): RobotDraft {
  return { robotCode: '', displayName: '', avatarKey: 'robot-default', weight: 100, delaySeconds: 0 }
}

function validateRobotDraft(draft: RobotDraft, isEditing = false): string[] {
  const errors = validateRobotDraftInput(draft)
  return isEditing ? errors.filter(error => !error.startsWith('机器人编码')) : errors
}

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

function robotStatusClass(status: RobotStatus) {
  return status === 'ENABLED' ? 'robot-status-enabled' : 'robot-status-disabled'
}

function dispatchStatusClass(status: RobotDispatchStatus) {
  return `robot-dispatch-${status.toLowerCase()}`
}

function dispatchStatusLabel(status: RobotDispatchStatus) {
  return dispatchStatusLabels[status] ?? status
}

function closeRobotModal() {
  createOpen.value = false
  editingRobotId.value = null
  robotDraft.value = emptyRobotDraft()
  avatarFile.value = null
}

function selectAvatar(event: Event) {
  avatarFile.value = (event.target as HTMLInputElement).files?.[0] || null
}

function openCreate() {
  if (!canWrite.value) return
  editingRobotId.value = null
  robotDraft.value = emptyRobotDraft()
  createOpen.value = true
}

function openEdit() {
  if (!canWrite.value || !selectedRobot.value) return
  const robot = selectedRobot.value.robot
  editingRobotId.value = robot.id
  robotDraft.value = {
    robotCode: robot.robotCode,
    displayName: robot.displayName,
    avatarKey: robot.avatarKey,
    weight: robot.weight,
    delaySeconds: robot.delaySeconds,
  }
  createOpen.value = true
}

async function loadRobotDetail(robotId: number) {
  selectedRobotId.value = robotId
  detailLoading.value = true
  detailError.value = ''
  templateError.value = ''
  drawComponentError.value = ''
  templatePreview.value = null
  try {
    selectedRobot.value = await api.getAdminRobot(robotId)
    syncTemplateDraft()
    try {
      syncDrawComponentDraft(await api.getAdminRobotDrawComponents(robotId))
    } catch (error) {
      drawComponents.value = []
      drawComponentError.value = apiErrorMessage(error, '开奖组件配置加载失败')
    }
  } catch (error) {
    selectedRobot.value = null
    drawComponents.value = []
    detailError.value = apiErrorMessage(error, '机器人详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

function syncTemplateDraft() {
  templateDraft.value = selectedTemplate.value?.templateText ?? ''
  templatePreview.value = null
  templateError.value = selectedTemplate.value ? '' : '服务端未配置模板，页面不会写入默认模板。'
}

function syncDrawComponentDraft(list: RobotDrawComponentList) {
  drawComponents.value = drawComponentTypes.map((component, index) => {
    const existing = list.items.find(item => item.component === component)
    return { component, enabled: existing?.enabled ?? false, order: existing?.order ?? index + 1 }
  })
}

async function loadPage() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await api.getAdminRobots(page.value, pageSize.value)
    robots.value = result.items
    total.value = result.total
    const nextId = result.items.some(robot => robot.id === selectedRobotId.value)
      ? selectedRobotId.value
      : result.items[0]?.id ?? null
    selectedRobotId.value = nextId
    if (nextId !== null) await loadRobotDetail(nextId)
    else {
      selectedRobot.value = null
      templateDraft.value = ''
      templatePreview.value = null
      drawComponents.value = []
      drawComponentError.value = ''
    }
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '机器人列表加载失败')
  } finally {
    loading.value = false
  }
}

async function saveDrawComponents() {
  if (!selectedRobot.value || !canWrite.value || drawComponentSaving.value) return
  const errors = drawComponentErrors.value
  if (errors.length) {
    drawComponentError.value = errors[0]
    return
  }
  drawComponentSaving.value = true
  drawComponentError.value = ''
  try {
    const result = await api.updateAdminRobotDrawComponents(selectedRobot.value.robot.id, { components: drawComponents.value })
    syncDrawComponentDraft(result)
    showFeedback('开奖组件配置已保存')
  } catch (error) {
    drawComponentError.value = apiErrorMessage(error, '开奖组件配置保存失败')
  } finally {
    drawComponentSaving.value = false
  }
}

async function refresh() {
  await Promise.all([loadPage(), loadDispatches()])
}

function movePage(nextPage: number) {
  if (nextPage < 1 || nextPage > pageCount.value || nextPage === page.value || loading.value) return
  page.value = nextPage
  void loadPage()
}

async function submitRobot() {
  const errors = draftErrors.value
  if (errors.length) {
    showFeedback(errors[0], 'error')
    return
  }
  saving.value = true
  const wasEditing = editing.value
  try {
    const payload = {
      displayName: robotDraft.value.displayName.trim(),
      avatarKey: robotDraft.value.avatarKey.trim(),
      weight: robotDraft.value.weight,
      delaySeconds: robotDraft.value.delaySeconds,
    }
    let detail: RobotDetail
    if (editingRobotId.value === null) {
      detail = await api.createAdminRobot({ robotCode: robotDraft.value.robotCode.trim(), ...payload })
    } else {
      detail = await api.updateAdminRobot(editingRobotId.value, payload)
    }
    if (avatarFile.value) {
      avatarUploading.value = true
      await api.uploadAdminRobotAvatar(detail.robot.id, avatarFile.value)
      detail = await api.getAdminRobot(detail.robot.id)
    }
    closeRobotModal()
    selectedRobotId.value = detail.robot.id
    await loadPage()
    showFeedback(wasEditing ? '机器人配置已更新' : '机器人已创建')
  } catch (error) {
    showFeedback(apiErrorMessage(error, editing.value ? '机器人配置保存失败' : '机器人创建失败'), 'error')
  } finally {
    avatarUploading.value = false
    saving.value = false
  }
}

async function changeStatus(robot: RobotSummary) {
  if (!canWrite.value || saving.value) return
  const nextStatus: RobotStatus = robot.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  if (nextStatus === 'DISABLED' && !window.confirm(`确定停用机器人“${robot.displayName}”吗？`)) return
  saving.value = true
  try {
    const detail = await api.changeAdminRobotStatus(robot.id, nextStatus)
    selectedRobotId.value = robot.id
    selectedRobot.value = detail
    syncTemplateDraft()
    await loadPage()
    showFeedback(`机器人已${nextStatus === 'ENABLED' ? '启用' : '停用'}`)
  } catch (error) {
    showFeedback(apiErrorMessage(error, '机器人状态更新失败'), 'error')
  } finally {
    saving.value = false
  }
}

function selectEventType(eventType: RobotEventType) {
  selectedEventType.value = eventType
  syncTemplateDraft()
}

async function previewTemplate() {
  if (!selectedRobot.value || !selectedTemplate.value || !canTemplateWrite.value) return
  const text = templateDraft.value.trim()
  if (!text) {
    templateError.value = '模板正文不能为空。'
    return
  }
  if (text.length > 1000) {
    templateError.value = '模板正文不能超过 1000 个字符。'
    return
  }
  templatePreviewing.value = true
  templateError.value = ''
  try {
    templatePreview.value = await api.previewAdminRobotTemplate(selectedRobot.value.robot.id, selectedEventType.value, { templateText: text })
  } catch (error) {
    templateError.value = apiErrorMessage(error, '模板预览失败')
  } finally {
    templatePreviewing.value = false
  }
}

async function saveTemplate() {
  if (!selectedRobot.value || !selectedTemplate.value || !canTemplateWrite.value) return
  const text = templateDraft.value.trim()
  if (!text) {
    templateError.value = '模板正文不能为空。'
    return
  }
  if (text.length > 1000) {
    templateError.value = '模板正文不能超过 1000 个字符。'
    return
  }
  templateSaving.value = true
  templateError.value = ''
  try {
    const saved = await api.updateAdminRobotTemplate(selectedRobot.value.robot.id, selectedEventType.value, { templateText: text })
    selectedRobot.value = {
      ...selectedRobot.value,
      templates: selectedRobot.value.templates.map(template => template.eventType === saved.eventType ? saved : template),
    }
    syncTemplateDraft()
    showFeedback(`模板已保存，当前版本 v${saved.version}`)
  } catch (error) {
    templateError.value = apiErrorMessage(error, '模板保存失败')
  } finally {
    templateSaving.value = false
  }
}

function dispatchQuery() {
  return {
    issueNumber: dispatchFilters.issueNumber.trim() || undefined,
    eventType: dispatchFilters.eventType || undefined,
    status: dispatchFilters.status || undefined,
    from: toInstantQueryValue(dispatchFilters.from),
    to: toInstantQueryValue(dispatchFilters.to),
    page: dispatchPage.value,
    pageSize: dispatchPageSize.value,
  }
}

async function loadDispatches() {
  dispatchLoading.value = true
  dispatchError.value = ''
  try {
    const result: DispatchPage = await api.getAdminRobotDispatches(dispatchQuery())
    dispatches.value = result.items
    dispatchTotal.value = result.total
  } catch (error) {
    dispatchError.value = apiErrorMessage(error, '机器人投递记录加载失败')
  } finally {
    dispatchLoading.value = false
  }
}

function applyDispatchFilters() {
  dispatchPage.value = 1
  void loadDispatches()
}

function moveDispatchPage(nextPage: number) {
  if (nextPage < 1 || nextPage > dispatchPageCount.value || nextPage === dispatchPage.value || dispatchLoading.value) return
  dispatchPage.value = nextPage
  void loadDispatches()
}

async function retryDispatch(dispatch: DispatchSummary) {
  if (!canWrite.value || dispatch.status !== 'FAILED' || retryingDispatchId.value !== null) return
  retryingDispatchId.value = dispatch.id
  try {
    await api.retryAdminRobotDispatch(dispatch.id)
    await Promise.all([loadDispatches(), selectedRobotId.value === null ? Promise.resolve() : loadRobotDetail(selectedRobotId.value)])
    showFeedback(`投递 ${dispatch.id} 已重新提交`)
  } catch (error) {
    showFeedback(apiErrorMessage(error, '失败投递重试未完成'), 'error')
  } finally {
    retryingDispatchId.value = null
  }
}

async function logout() {
  try {
    await api.logout()
  } finally {
    await router.replace({ path: '/login', query: { reason: 'logged-out' } })
  }
}

onMounted(async () => {
  try {
    currentUser.value = await api.me()
    await refresh()
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '机器人管理页面加载失败')
  }
})
</script>

<template>
  <div class="admin-page robot-management-page">
    <header class="admin-header">
      <div>
        <p class="eyebrow">XUPAN / ROBOT OPERATIONS</p>
        <h1>机器人管理</h1>
      </div>
      <nav class="admin-header-actions" aria-label="后台导航">
        <RouterLink class="header-link" to="/console">运营控制台</RouterLink>
        <RouterLink class="header-link" to="/console/users">用户管理</RouterLink>
        <RouterLink class="header-link" to="/console/players">玩家工作台</RouterLink>
        <RouterLink class="header-link" to="/room">返回用户前台</RouterLink>
        <button class="header-link" type="button" @click="logout">退出登录</button>
      </nav>
    </header>

    <main class="admin-main">
      <section class="admin-summary robot-summary" aria-label="机器人管理概览">
        <div class="summary-item"><span>机器人总数</span><strong>{{ total }}</strong></div>
        <div class="summary-item"><span>当前页</span><strong>{{ page }} / {{ pageCount }}</strong></div>
        <div class="summary-item"><span>已启用</span><strong>{{ robots.filter(robot => robot.status === 'ENABLED').length }}</strong></div>
        <div class="summary-item"><span>当前失败投递</span><strong>{{ selectedRobot?.dispatchStatistics.failed ?? '--' }}</strong></div>
      </section>

      <section class="admin-section robot-list-section">
        <div class="section-title">
          <div><span class="eyebrow">ROBOT REGISTRY</span><h2>机器人列表</h2></div>
          <button type="button" class="primary-button" :disabled="!canWrite || saving" @click="openCreate">新增机器人</button>
        </div>
        <p v-if="!canWrite" class="permission-note">当前账号仅有查看权限，配置和重试操作已禁用。</p>
        <p v-if="errorMessage" class="inline-error" role="alert">{{ errorMessage }}</p>
        <div v-if="loading && !robots.length" class="user-empty-state">正在加载机器人列表...</div>
        <div v-else-if="!robots.length" class="user-empty-state">
          <strong>暂无机器人</strong>
          <span>服务端当前没有可管理的机器人配置。</span>
          <button v-if="canWrite" type="button" class="secondary-button" @click="openCreate">创建第一个机器人</button>
        </div>
        <div v-else class="user-table-wrap robot-table-wrap">
          <table class="user-table robot-table">
            <thead><tr><th>编码</th><th>展示名</th><th>头像标识</th><th>状态</th><th>权重</th><th>延时</th><th>更新时间</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="robot in robots" :key="robot.id" :class="{ 'is-selected': selectedRobotId === robot.id }">
                <td><button type="button" class="robot-row-trigger" @click="loadRobotDetail(robot.id)">{{ robot.robotCode }}</button></td>
                <td><strong>{{ robot.displayName }}</strong></td>
                <td>{{ robot.avatarKey }}</td>
                <td><span class="robot-status" :class="robotStatusClass(robot.status)">{{ robotStatusLabels[robot.status] }}</span></td>
                <td>{{ robot.weight }}</td>
                <td>{{ robot.delaySeconds }} 秒</td>
                <td>{{ dateTime(robot.updatedAt) }}</td>
                <td><div class="robot-row-actions"><button type="button" class="table-action" @click="loadRobotDetail(robot.id)">详情</button><button type="button" class="table-action" :disabled="!canWrite || saving" @click="changeStatus(robot)">{{ robot.status === 'ENABLED' ? '停用' : '启用' }}</button></div></td>
              </tr>
            </tbody>
          </table>
        </div>
        <footer v-if="total > 0" class="user-pagination">
          <span>共 {{ total }} 条</span>
          <div class="pagination-actions"><button type="button" class="secondary-button" :disabled="page <= 1 || loading" @click="movePage(page - 1)">上一页</button><span>第 {{ page }} / {{ pageCount }} 页</span><button type="button" class="secondary-button" :disabled="page >= pageCount || loading" @click="movePage(page + 1)">下一页</button></div>
        </footer>
      </section>

      <section class="robot-detail-layout">
        <section class="admin-section robot-detail-section">
          <div class="section-title"><div><span class="eyebrow">ROBOT DETAIL</span><h2>配置详情</h2></div><button v-if="selectedRobot" type="button" class="secondary-button" :disabled="!canWrite || saving" @click="openEdit">编辑配置</button></div>
          <div v-if="detailLoading" class="user-empty-state robot-detail-state">正在加载机器人详情...</div>
          <p v-else-if="detailError" class="inline-error" role="alert">{{ detailError }}</p>
          <div v-else-if="!selectedRobot" class="user-empty-state robot-detail-state">选择一个机器人查看详情。</div>
          <template v-else>
            <dl class="user-detail-grid robot-detail-grid"><div><dt>机器人编码</dt><dd>{{ selectedRobot.robot.robotCode }}</dd></div><div><dt>展示名</dt><dd>{{ selectedRobot.robot.displayName }}</dd></div><div><dt>头像标识</dt><dd>{{ selectedRobot.robot.avatarKey }}</dd></div><div><dt>状态</dt><dd><span class="robot-status" :class="robotStatusClass(selectedRobot.robot.status)">{{ robotStatusLabels[selectedRobot.robot.status] }}</span></dd></div><div><dt>权重</dt><dd>{{ selectedRobot.robot.weight }}</dd></div><div><dt>延时</dt><dd>{{ selectedRobot.robot.delaySeconds }} 秒</dd></div><div><dt>创建时间</dt><dd>{{ dateTime(selectedRobot.robot.createdAt) }}</dd></div><div><dt>更新时间</dt><dd>{{ dateTime(selectedRobot.robot.updatedAt) }}</dd></div></dl>
            <div class="robot-stat-grid" aria-label="投递统计"><div><span>待处理</span><strong>{{ selectedRobot.dispatchStatistics.pending }}</strong></div><div><span>处理中</span><strong>{{ selectedRobot.dispatchStatistics.processing }}</strong></div><div><span>失败</span><strong class="robot-stat-danger">{{ selectedRobot.dispatchStatistics.failed }}</strong></div><div><span>已发布</span><strong>{{ selectedRobot.dispatchStatistics.published }}</strong></div><div><span>已跳过</span><strong>{{ selectedRobot.dispatchStatistics.skipped }}</strong></div></div>
            <div class="detail-actions"><button type="button" class="secondary-button" :disabled="!canWrite || saving" @click="changeStatus(selectedRobot.robot)">{{ selectedRobot.robot.status === 'ENABLED' ? '停用机器人' : '启用机器人' }}</button><span class="action-helper">状态变更由服务端权限和调度器共同控制。</span></div>
          </template>
        </section>

        <section class="admin-section robot-template-section">
          <div class="section-title"><div><span class="eyebrow">MESSAGE TEMPLATES</span><h2>模板编辑与预览</h2></div><span>纯文本 · 最长 1000 字符</span></div>
          <div class="robot-event-tabs" role="tablist" aria-label="机器人事件类型"><button v-for="eventType in eventTypes" :key="eventType" type="button" role="tab" :aria-selected="selectedEventType === eventType" :class="{ active: selectedEventType === eventType }" @click="selectEventType(eventType)">{{ eventLabels[eventType] }}</button></div>
          <div v-if="!selectedRobot" class="user-empty-state robot-detail-state">选择机器人后编辑模板。</div>
          <template v-else>
            <div v-if="selectedTemplate" class="template-meta"><span>{{ selectedTemplate.templateCode }}</span><span>v{{ selectedTemplate.version }}</span><span>{{ selectedTemplate.enabled ? '已启用' : '已停用' }}</span><time>{{ dateTime(selectedTemplate.updatedAt) }}</time></div>
            <p v-if="templateError" class="inline-error" role="alert">{{ templateError }}</p>
            <p v-if="!selectedTemplate" class="modal-note">服务端未配置“{{ eventLabels[selectedEventType] }}”模板，当前不会写入默认文本。</p>
            <label class="robot-template-field" for="robot-template-text">模板正文<textarea id="robot-template-text" v-model="templateDraft" maxlength="1000" :disabled="!selectedTemplate || !canTemplateWrite || templateSaving || templatePreviewing" rows="7"></textarea></label>
            <div class="template-counter">{{ templateDraft.length }} / 1000</div>
            <div class="modal-actions"><button type="button" class="secondary-button" :disabled="!selectedTemplate || !canTemplateWrite || templateSaving || templatePreviewing" @click="previewTemplate">{{ templatePreviewing ? '预览中...' : '预览模板' }}</button><button type="button" class="primary-button" :disabled="!selectedTemplate || !canTemplateWrite || templateSaving || templatePreviewing" @click="saveTemplate">{{ templateSaving ? '保存中...' : '保存模板' }}</button></div>
            <div v-if="templatePreview" class="template-preview" aria-live="polite"><div><span>预览结果</span><strong>第 {{ templatePreview.issueNumber }} 期 · {{ eventLabels[templatePreview.eventType] }}</strong></div><p>{{ templatePreview.renderedText }}</p><small>示例事件：{{ templatePreview.eventMessage }} · 版本 v{{ templatePreview.version }}</small></div>
          </template>
        </section>
      </section>

      <section class="admin-section robot-draw-section">
        <div class="section-title">
          <div><span class="eyebrow">DRAW MESSAGE COMPONENTS</span><h2>开奖消息组件</h2></div>
          <span>控制机器人开奖消息的展示顺序</span>
        </div>
        <p v-if="!selectedRobot" class="user-empty-state robot-detail-state">选择机器人后配置开奖消息组件。</p>
        <template v-else>
          <p class="modal-note">三类组件都会提交，顺序使用 1-3；关闭仅表示该段不展示。</p>
          <p v-if="drawComponentError" class="inline-error" role="alert">{{ drawComponentError }}</p>
          <div class="robot-draw-config-list" aria-label="开奖消息组件配置">
            <div v-for="component in drawComponents" :key="component.component" class="robot-draw-config-row">
              <label class="robot-draw-toggle">
                <input v-model="component.enabled" type="checkbox" :disabled="!canWrite || drawComponentSaving" :aria-label="`启用${drawComponentLabels[component.component]}`" />
                <span>{{ drawComponentLabels[component.component] }}</span>
              </label>
              <label class="robot-draw-order">展示顺序
                <select v-model.number="component.order" :disabled="!canWrite || drawComponentSaving" :aria-label="`${drawComponentLabels[component.component]}展示顺序`">
                  <option v-for="order in [1, 2, 3]" :key="order" :value="order">第 {{ order }} 段</option>
                </select>
              </label>
            </div>
          </div>
          <p v-if="drawComponentErrors.length" class="inline-error" role="alert">{{ drawComponentErrors[0] }}</p>
          <div class="modal-actions"><button type="button" class="primary-button" :disabled="!canWrite || drawComponentSaving || drawComponentErrors.length > 0" @click="saveDrawComponents">{{ drawComponentSaving ? '保存中...' : '保存开奖组件配置' }}</button></div>
        </template>
      </section>

      <section class="admin-section robot-dispatch-section">
        <div class="section-title"><div><span class="eyebrow">DISPATCH MONITOR</span><h2>投递记录</h2></div><button type="button" class="secondary-button" :disabled="dispatchLoading" @click="loadDispatches">刷新记录</button></div>
        <div class="user-filter-bar robot-dispatch-filters"><label>期号<input v-model="dispatchFilters.issueNumber" type="search" maxlength="64" placeholder="支持中文期号" @keyup.enter="applyDispatchFilters" /></label><label>事件类型<select v-model="dispatchFilters.eventType"><option value="">全部事件</option><option v-for="eventType in eventTypes" :key="eventType" :value="eventType">{{ eventLabels[eventType] }}</option></select></label><label>状态<select v-model="dispatchFilters.status"><option value="">全部状态</option><option v-for="status in dispatchStatuses" :key="status" :value="status">{{ dispatchStatusLabel(status) }}</option></select></label><label>开始时间<input v-model="dispatchFilters.from" type="datetime-local" /></label><label>结束时间<input v-model="dispatchFilters.to" type="datetime-local" /></label><button type="button" class="secondary-button" :disabled="dispatchLoading" @click="applyDispatchFilters">查询</button></div>
        <p v-if="dispatchError" class="inline-error" role="alert">{{ dispatchError }}</p>
        <div v-if="dispatchLoading && !dispatches.length" class="user-empty-state">正在加载投递记录...</div>
        <div v-else-if="!dispatches.length" class="user-empty-state"><strong>暂无投递记录</strong><span>当前筛选条件没有匹配的机器人投递。</span></div>
        <div v-else class="user-table-wrap robot-table-wrap"><table class="user-table robot-dispatch-table"><thead><tr><th>投递 ID</th><th>事件 ID</th><th>期号</th><th>事件类型</th><th>状态</th><th>尝试次数</th><th>下次重试</th><th>消息 ID</th><th>错误摘要</th><th>发布时间</th><th>操作</th></tr></thead><tbody><tr v-for="dispatch in dispatches" :key="dispatch.id"><td>{{ dispatch.id }}</td><td>{{ dispatch.gameEventId }}</td><td>{{ dispatch.issueNumber }}</td><td>{{ eventLabels[dispatch.eventType] }}</td><td><span class="robot-dispatch-status" :class="dispatchStatusClass(dispatch.status)">{{ dispatchStatusLabel(dispatch.status) }}</span></td><td>{{ dispatch.attemptCount }}</td><td>{{ dateTime(dispatch.nextAttemptAt) }}</td><td>{{ dispatch.messageId ?? '--' }}</td><td class="robot-error-cell" :title="dispatch.lastError || undefined">{{ dispatch.lastError || '--' }}</td><td>{{ dateTime(dispatch.publishedAt) }}</td><td><button v-if="dispatch.status === 'FAILED'" type="button" class="table-action" :disabled="!canWrite || retryingDispatchId !== null" @click="retryDispatch(dispatch)">{{ retryingDispatchId === dispatch.id ? '重试中...' : '重试' }}</button><span v-else class="robot-muted-action">不可重试</span></td></tr></tbody></table></div>
        <footer v-if="dispatchTotal > 0" class="user-pagination"><span>共 {{ dispatchTotal }} 条</span><div class="pagination-actions"><button type="button" class="secondary-button" :disabled="dispatchPage <= 1 || dispatchLoading" @click="moveDispatchPage(dispatchPage - 1)">上一页</button><span>第 {{ dispatchPage }} / {{ dispatchPageCount }} 页</span><button type="button" class="secondary-button" :disabled="dispatchPage >= dispatchPageCount || dispatchLoading" @click="moveDispatchPage(dispatchPage + 1)">下一页</button></div></footer>
      </section>
    </main>

    <div v-if="createOpen" class="user-modal-layer" role="presentation" @click.self="closeRobotModal">
      <section class="user-modal robot-modal" role="dialog" aria-modal="true" aria-labelledby="robot-form-title">
        <header><div><span class="eyebrow">{{ editing ? 'EDIT ROBOT' : 'CREATE ROBOT' }}</span><h2 id="robot-form-title">{{ editing ? '编辑机器人' : '新增机器人' }}</h2></div><button type="button" class="modal-close" aria-label="关闭" @click="closeRobotModal">×</button></header>
        <p class="modal-note">机器人只负责生成聊天室消息，不会产生下注、钱包或用户身份数据。</p>
        <form class="user-form" @submit.prevent="submitRobot">
          <label>机器人编码<input v-model="robotDraft.robotCode" autocomplete="off" maxlength="32" :readonly="editing" required /><small v-if="editing">编码创建后不可修改。</small></label>
          <label>展示名<input v-model="robotDraft.displayName" maxlength="32" required /></label>
          <label>头像标识（兼容字段）<input v-model="robotDraft.avatarKey" maxlength="64" required /></label>
          <label>上传真实头像<input type="file" accept="image/jpeg,image/png,image/gif,image/webp" :disabled="saving || avatarUploading" @change="selectAvatar" /><small>支持 JPG、PNG、GIF、WebP，最大 5 MB；选择后随本次保存上传。</small></label>
          <div class="robot-form-grid"><label>权重<input v-model.number="robotDraft.weight" type="number" min="1" max="100" step="1" required /></label><label>延时（秒）<input v-model.number="robotDraft.delaySeconds" type="number" min="0" max="300" step="1" required /></label></div>
          <p v-if="draftErrors.length" class="inline-error" role="alert">{{ draftErrors.join('；') }}</p>
          <div class="modal-actions"><button type="button" class="secondary-button" :disabled="saving" @click="closeRobotModal">取消</button><button type="submit" class="primary-button" :disabled="saving || !canWrite">{{ saving ? '保存中...' : editing ? '保存配置' : '创建机器人' }}</button></div>
        </form>
      </section>
    </div>

    <p v-if="feedback" class="toast" :class="`toast-${feedbackKind}`" role="status" aria-live="polite">{{ feedback }}</p>
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
  display: grid;
  gap: 6px;
  width: 100%;
  max-width: none;
  margin: 0;
  padding: 5px 6px 10px;
}

.admin-page .admin-summary {
  gap: 1px;
  margin: 0;
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

.admin-page .section-title > span {
  color: var(--retro-muted);
  font-size: 10px;
}

.admin-page button,
.admin-page input,
.admin-page select,
.admin-page textarea {
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

.admin-page textarea {
  border: 1px solid var(--retro-line);
  background: #fff;
  color: var(--retro-ink);
  font-size: 11px;
}

.admin-page input:focus,
.admin-page select:focus,
.admin-page textarea:focus {
  border-color: #2d7bcd;
  box-shadow: 0 0 0 1px rgb(45 123 205 / 22%);
  outline: none;
}

.admin-page .permission-note {
  margin: -1px 0 5px;
  border: 1px solid #e2cf9d;
  background: #fff9e8;
  padding: 4px 6px;
  color: #806527;
  font-size: 10px;
}

.admin-page .user-table-wrap {
  max-width: 100%;
  overflow-x: auto;
  border: 1px solid #bfd8ef;
}

.admin-page .user-table {
  width: 100%;
  border-collapse: collapse;
  background: #fff;
  color: #29465f;
  font-size: 10px;
}

.admin-page .robot-table {
  min-width: 980px;
}

.admin-page .robot-dispatch-table {
  min-width: 1320px;
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

.admin-page .user-table td {
  white-space: nowrap;
}

.admin-page .user-table tbody tr:hover,
.admin-page .user-table tr.is-selected {
  background: #eaf6ff;
}

.admin-page .user-table tbody tr:last-child td {
  border-bottom: 0;
}

.admin-page .robot-row-trigger {
  min-height: 20px;
  border: 0;
  background: transparent;
  padding: 0;
  color: #17679f;
  font: inherit;
  font-weight: 700;
  text-align: left;
  text-decoration: underline;
  text-decoration-color: #a7cde8;
  text-underline-offset: 2px;
}

.admin-page .robot-row-actions {
  gap: 3px;
}

.admin-page .table-action {
  min-height: 20px;
  border-radius: 1px;
  padding: 1px 5px;
  font-size: 10px;
}

.admin-page .robot-status,
.admin-page .robot-dispatch-status {
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

.admin-page .robot-status-enabled,
.admin-page .robot-dispatch-published {
  border-color: #8bc8ad;
  background: #eaf8f1;
  color: #19704e;
}

.admin-page .robot-status-disabled,
.admin-page .robot-dispatch-skipped {
  border-color: #b8c4ce;
  background: #f1f4f6;
  color: #63717d;
}

.admin-page .robot-dispatch-processing {
  border-color: #dfc174;
  background: #fff7de;
  color: #826218;
}

.admin-page .robot-dispatch-failed {
  border-color: #dfa4a0;
  background: #fff0ee;
  color: #a83d38;
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

.admin-page .robot-detail-layout {
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 6px;
}

.admin-page .user-empty-state {
  min-height: 92px;
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

.admin-page .user-detail-grid {
  gap: 1px;
  border: 1px solid #bfd8ef;
  background: #bfd8ef;
}

.admin-page .user-detail-grid div {
  background: linear-gradient(#fff, #f2f8ff);
  padding: 4px 5px;
}

.admin-page .user-detail-grid dt {
  color: var(--retro-muted);
  font-size: 9px;
}

.admin-page .user-detail-grid dd {
  margin: 2px 0 0;
  color: #203f5b;
  font-size: 10px;
  white-space: normal;
}

.admin-page .robot-stat-grid {
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 1px;
  border: 1px solid #bfd8ef;
  background: #bfd8ef;
}

.admin-page .robot-stat-grid div {
  background: #f4f9ff;
  padding: 5px 3px;
}

.admin-page .robot-stat-grid span {
  font-size: 9px;
}

.admin-page .robot-stat-grid strong {
  margin-top: 2px;
  color: #23445b;
  font-size: 14px;
}

.admin-page .robot-stat-grid .robot-stat-danger {
  color: #b33e38;
}

.admin-page .detail-actions {
  align-items: center;
  border-top: 1px solid #bfd8ef;
  padding-top: 5px;
  margin-top: 6px;
}

.admin-page .action-helper {
  color: #6b8194;
  font-size: 9px;
  line-height: 1.35;
}

.admin-page .robot-event-tabs {
  gap: 3px;
  margin-bottom: 6px;
  padding-bottom: 0;
}

.admin-page .robot-event-tabs button {
  min-width: 0;
  min-height: 24px;
  border-radius: 1px;
  padding: 2px 7px;
  font-size: 10px;
}

.admin-page .robot-event-tabs button.active {
  border-color: #2d7bcd;
  background: linear-gradient(#eef8ff, #d9ecfc);
  color: #175d93;
}

.admin-page .template-meta {
  gap: 5px 10px;
  margin-bottom: 5px;
  color: #657d91;
  font-size: 9px;
}

.admin-page .template-meta span:nth-child(2) {
  color: #216b9d;
}

.admin-page .robot-template-field {
  gap: 2px;
  color: var(--retro-muted);
  font-size: 10px;
}

.admin-page .robot-template-field textarea {
  min-height: 112px;
  padding: 5px;
  font-size: 11px;
  line-height: 1.45;
}

.admin-page .template-counter {
  margin-top: 2px;
  font-size: 9px;
}

.admin-page .template-preview {
  margin-top: 6px;
  border: 1px solid #8ec7cc;
  background: #eef9f9;
  padding: 6px 7px;
}

.admin-page .template-preview > div {
  gap: 5px;
  color: #27716d;
  font-size: 9px;
}

.admin-page .template-preview p {
  margin: 5px 0;
  color: #21474d;
  font-size: 10px;
  line-height: 1.45;
}

.admin-page .template-preview small {
  font-size: 9px;
}

.admin-page .robot-draw-config-list {
  gap: 1px;
  border: 1px solid #bfd8ef;
  background: #bfd8ef;
}

.admin-page .robot-draw-config-row {
  min-height: 31px;
  gap: 8px;
  background: #f8fcff;
  padding: 3px 6px;
}

.admin-page .robot-draw-toggle {
  gap: 5px;
  min-height: 24px;
  color: #26465d;
  font-size: 10px;
}

.admin-page .robot-draw-toggle input {
  width: 14px;
  height: 14px;
  min-height: 14px;
}

.admin-page .robot-draw-order {
  gap: 4px;
  color: var(--retro-muted);
  font-size: 9px;
}

.admin-page .robot-draw-order select {
  min-width: 82px;
  min-height: 24px;
  height: 24px;
  padding: 0 5px;
}

.admin-page .user-filter-bar {
  display: grid;
  grid-template-columns: minmax(140px, 1fr) repeat(4, minmax(110px, 1fr)) auto;
  align-items: end;
  gap: 4px;
  margin-bottom: 6px;
  border: 1px solid #bfd8ef;
  background: linear-gradient(#f8fcff, #edf6ff);
  padding: 5px;
}

.admin-page .user-filter-bar label,
.admin-page .user-form label {
  gap: 2px;
  min-width: 0;
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

.admin-page .robot-error-cell {
  max-width: 210px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.admin-page .robot-muted-action {
  color: #8a99a6;
  font-size: 9px;
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

.admin-page .robot-modal {
  width: min(100%, 580px);
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

.admin-page .user-form small,
.admin-page .robot-modal small {
  color: #788b9c;
  font-size: 9px;
}

.admin-page .robot-form-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 5px;
}

.admin-page .modal-actions {
  margin-top: 6px;
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
