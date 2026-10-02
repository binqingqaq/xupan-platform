<script setup lang="ts">
/**
 * 报网控制面板（BY220 `robot.php` 报网栏，`x_robot.flyers_*`）。
 *
 * 用户 2026-10-02 确认按 A 方案做完整闭环：报网状态真实影响订单与玩家余额。
 * 合规边界（强制）：本项目**不接入任何真实外部上游**。"登录"是本地模拟（后端只置在线状态与登录时间，
 * 不发起任何网络请求）；密码只在保存时上传，界面永不回显（只显示是否已设置）。
 *
 * 层级：机器账号固定操作自己那台；管理员/子账号必须先选择机器。未选机器时本面板只读并提示。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { api, apiErrorMessage } from '../../api'
import {
  REPORT_ROUTE_OPTIONS,
  REPORT_TYPE_OPTIONS,
  type PlayerDeskScope,
  type ReportConfigUpdateRequest,
  type ReportConfigView,
} from '../../types'

const props = defineProps<{
  /** 当前身份范围；未就绪时为 null，此时不允许任何写操作。 */
  scope?: PlayerDeskScope | null
  /** 管理员选中的机器；机器账号忽略该值（后端强制自己那台）。 */
  machineId?: number | null
}>()

const emit = defineEmits<{ (event: 'changed', config: ReportConfigView): void }>()

const config = ref<ReportConfigView | null>(null)
const loading = ref(false)
const saving = ref(false)
const loggingIn = ref(false)
const notice = ref('')
const noticeKind = ref<'ok' | 'error'>('ok')

/** "设置账号"弹窗。密码单独输入，保存后即清空，永不回显。 */
const accountDialogOpen = ref(false)
const accountDraft = reactive({
  reportUsername: '',
  reportPassword: '',
  reportUrl: '',
  reportNetworkCode: '',
  reportRouteCode: 'A' as string,
})

/** 机器账号固定自己那台；管理员/子账号必须显式选择机器，否则不允许写。 */
const effectiveMachineId = computed<number | null>(() => {
  if (props.scope?.mode === 'MACHINE') return props.scope.machineId ?? null
  return props.machineId ?? null
})

const machineReady = computed(() => effectiveMachineId.value !== null)
const readOnlyHint = computed(() => {
  if (!props.scope) return '身份范围尚未就绪，报网配置暂不可编辑'
  if (!machineReady.value) return '请先在上方选择机器：报网配置属于每台机器自己的设置'
  return ''
})

const reportTypeLabel = computed(() => {
  const value = config.value?.reportType
  if (!value) return '未设置'
  return REPORT_TYPE_OPTIONS.find(option => option.value === value)?.label ?? value
})

const onlineText = computed(() => (config.value?.reportOnline ? '在线' : '离线'))
const loginButtonDisabled = computed(() => !machineReady.value || loggingIn.value || saving.value)

function setNotice(message: string, kind: 'ok' | 'error' = 'ok') {
  notice.value = message
  noticeKind.value = kind
}

/**
 * 读取机器报网配置与状态统计。
 *
 * 上报订单列表与重报入口**不在这里**：BY220 把它们做在下注榜的注单状态标记上
 * （`admin_list.html:1975-1992`），因此由 `BetBoardPanel` 承担；本面板只保留报网控制行
 * （类型、账号、登录/退出、开关、超时/未报/失败计数、刷新），与 BY220 报网栏一致。
 */
async function load() {
  if (!machineReady.value) {
    config.value = null
    return
  }
  loading.value = true
  try {
    config.value = await api.getReportConfig(effectiveMachineId.value)
    emit('changed', config.value)
  } catch (cause) {
    setNotice(apiErrorMessage(cause, '报网配置加载失败，请刷新重试'), 'error')
  } finally {
    loading.value = false
  }
}

/** 保存报网类型/开关等基础配置（不动账号密码）。 */
async function saveBase(payload: Partial<ReportConfigUpdateRequest>, okText: string) {
  if (!machineReady.value) {
    setNotice(readOnlyHint.value, 'error')
    return
  }
  saving.value = true
  try {
    config.value = await api.updateReportConfig({
      machineId: effectiveMachineId.value ?? undefined,
      ...payload,
    })
    emit('changed', config.value)
    setNotice(okText)
  } catch (cause) {
    setNotice(apiErrorMessage(cause, '报网配置保存失败'), 'error')
  } finally {
    saving.value = false
  }
}

function onReportTypeChange(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  void saveBase({ reportType: value }, `报网类型已保存为 ${reportTypeLabelOf(value)}`)
}

function reportTypeLabelOf(value: string): string {
  return REPORT_TYPE_OPTIONS.find(option => option.value === value)?.label ?? value
}

function onToggleAuto() {
  const next = !config.value?.reportAuto
  void saveBase({ reportAuto: next }, next ? '已开启自动上报网盘' : '已关闭自动上报网盘')
}

function onToggleWithdraw() {
  const next = !config.value?.reportWithdraw
  void saveBase(
    { reportWithdraw: next },
    next ? '已开启失败自动退单（上报失败将取消订单并退还玩家积分）' : '已关闭失败自动退单',
  )
}

function openAccountDialog() {
  if (!machineReady.value) {
    setNotice(readOnlyHint.value, 'error')
    return
  }
  accountDraft.reportUsername = config.value?.reportUsername ?? ''
  accountDraft.reportPassword = ''
  accountDraft.reportUrl = config.value?.reportUrl ?? ''
  accountDraft.reportNetworkCode = config.value?.reportNetworkCode ?? ''
  accountDraft.reportRouteCode = config.value?.reportRouteCode ?? 'A'
  accountDialogOpen.value = true
}

function closeAccountDialog() {
  accountDialogOpen.value = false
  accountDraft.reportPassword = ''
}

async function saveAccount() {
  if (!machineReady.value) {
    setNotice(readOnlyHint.value, 'error')
    return
  }
  saving.value = true
  try {
    config.value = await api.updateReportConfig({
      machineId: effectiveMachineId.value ?? undefined,
      reportUsername: accountDraft.reportUsername.trim(),
      // 密码为空表示不修改；非空才覆盖（后端只存密文）
      reportPassword: accountDraft.reportPassword.trim() || null,
      reportUrl: accountDraft.reportUrl.trim(),
      reportNetworkCode: accountDraft.reportNetworkCode.trim(),
      reportRouteCode: accountDraft.reportRouteCode,
    })
    emit('changed', config.value)
    setNotice('报网账号已保存（密码只存密文，界面不回显）')
    closeAccountDialog()
  } catch (cause) {
    setNotice(apiErrorMessage(cause, '报网账号保存失败'), 'error')
  } finally {
    saving.value = false
  }
}

async function login() {
  if (!machineReady.value) {
    setNotice(readOnlyHint.value, 'error')
    return
  }
  loggingIn.value = true
  try {
    config.value = await api.loginReport(effectiveMachineId.value)
    emit('changed', config.value)
    setNotice('报网登录成功（本地模拟，未连接任何外部平台）')
  } catch (cause) {
    setNotice(apiErrorMessage(cause, '报网登录失败'), 'error')
  } finally {
    loggingIn.value = false
  }
}

async function logout() {
  if (!machineReady.value) {
    setNotice(readOnlyHint.value, 'error')
    return
  }
  saving.value = true
  try {
    config.value = await api.logoutReport(effectiveMachineId.value)
    emit('changed', config.value)
    setNotice('已退出报网（在线状态置为离线）')
  } catch (cause) {
    setNotice(apiErrorMessage(cause, '退出报网失败'), 'error')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void load()
})

watch(
  () => [props.scope?.mode, props.scope?.machineId, props.machineId],
  () => {
    void load()
  },
)

defineExpose({ load })
</script>

<template>
  <section class="report-control-panel" aria-label="报网控制">
    <div class="report-control-row report-control-main-row">
      <strong class="report-control-label">报网类型:</strong>
      <select
        class="report-type-select"
        :value="config?.reportType ?? ''"
        :disabled="!machineReady || saving"
        aria-label="报网类型"
        @change="onReportTypeChange"
      >
        <option value="">未设置</option>
        <option v-for="option in REPORT_TYPE_OPTIONS" :key="option.value" :value="option.value">
          {{ option.label }}
        </option>
      </select>
      <button type="button" class="report-control-button report-control-account" :disabled="!machineReady || saving" @click="openAccountDialog">
        设置账号
      </button>
      <button type="button" class="report-control-button report-control-logout" :disabled="loginButtonDisabled || !config?.reportOnline" @click="logout">
        退出
      </button>
      <button type="button" class="report-control-button report-control-login" :disabled="loginButtonDisabled" @click="login">
        {{ loggingIn ? '登录中' : '登录' }}
      </button>
      <!-- BY220 本地环境登录时同样把 money/betting 写 0（rbt/Index.php:801-803），真值只由真实上游返回；
           本项目不接真实上游，因此这两项固定为本地模拟值 0，界面如实标注，不伪装成上游实时数据。 -->
      <span class="report-control-metric" title="本地模拟：本项目不接真实上游，该值不会由上游返回">押注: <b>{{ config?.reportStake ?? 0 }}</b></span>
      <span class="report-control-metric" title="本地模拟：本项目不接真实上游，该值不会由上游返回">网盘余额: <b>{{ config?.reportBalance ?? 0 }}</b>（本地模拟）</span>
      <span class="report-control-metric">账号: <b>{{ config?.reportUsername || '未设置' }}</b></span>
      <span class="report-control-metric">密码: <b>{{ config?.hasPassword ? '已设置' : '未设置' }}</b></span>
    </div>

    <div class="report-control-row report-control-status-row">
      <strong class="report-control-label">报网状态:</strong>
      <span class="report-control-state" :class="config?.reportOnline ? 'is-online' : 'is-offline'">
        {{ onlineText }}
      </span>
      <label class="report-control-check" :class="{ 'is-disabled': !machineReady }">
        <input type="checkbox" :checked="config?.reportWithdraw ?? false" :disabled="!machineReady || saving" @change="onToggleWithdraw" />
        失败自动退单
      </label>
      <label class="report-control-check" :class="{ 'is-disabled': !machineReady }">
        <input type="checkbox" :checked="config?.reportAuto ?? false" :disabled="!machineReady || saving" @change="onToggleAuto" />
        自动上报网盘
      </label>
      <span class="report-control-metric">超时订单: <b>{{ config?.timeoutOrderCount ?? 0 }}</b></span>
      <span class="report-control-metric">未报: <b>{{ config?.unreportedCount ?? 0 }}</b></span>
      <span class="report-control-metric">失败: <b>{{ config?.failedOrderCount ?? 0 }}</b></span>
      <span class="report-control-metric">盘口: <b>{{ config?.reportRouteCode || '未设置' }}</b></span>
      <span class="report-control-metric">网盘: <b>{{ config?.reportNetworkCode || '未设置' }}</b></span>
      <button type="button" class="report-control-button report-control-refresh" :disabled="!machineReady || loading" @click="load">
        {{ loading ? '刷新中' : '刷新' }}
      </button>
    </div>

    <p v-if="readOnlyHint" class="report-control-hint">{{ readOnlyHint }}</p>
    <p v-else-if="notice" class="report-control-notice" :class="noticeKind === 'error' ? 'is-error' : 'is-ok'" role="status">
      {{ notice }}
    </p>

    <!-- BY220 报网栏到此结束（robot.php 的报网控制区）。
         上报订单列表与重报不在这里：BY220 把它们做在下注榜的注单状态标记上
         （application/rbt/view/index/admin_list.html:1975-1992），由 BetBoardPanel 承担。 -->

    <div v-if="accountDialogOpen" class="report-account-mask" role="dialog" aria-label="设置报网账号">
      <div class="report-account-dialog">
        <div class="report-account-head">
          <strong>设置报网账号</strong>
          <button type="button" class="report-control-button" @click="closeAccountDialog">关闭</button>
        </div>
        <label class="report-account-field">
          <span>飞单账号</span>
          <input v-model="accountDraft.reportUsername" type="text" maxlength="128" placeholder="上游飞单账号" />
        </label>
        <label class="report-account-field">
          <span>飞单密码</span>
          <input
            v-model="accountDraft.reportPassword"
            type="password"
            maxlength="128"
            :placeholder="config?.hasPassword ? '留空表示不修改' : '上游飞单密码'"
          />
        </label>
        <label class="report-account-field">
          <span>上游地址</span>
          <input v-model="accountDraft.reportUrl" type="text" maxlength="512" placeholder="仅保存，不发起请求" />
        </label>
        <label class="report-account-field">
          <span>网盘编码</span>
          <input v-model="accountDraft.reportNetworkCode" type="text" maxlength="64" placeholder="如 K28" />
        </label>
        <label class="report-account-field">
          <span>盘口线路</span>
          <select v-model="accountDraft.reportRouteCode">
            <option v-for="route in REPORT_ROUTE_OPTIONS" :key="route" :value="route">{{ route }}</option>
          </select>
        </label>
        <p class="report-account-note">
          密码只保存密文、界面不回显；本项目不连接任何外部平台，登录为本地模拟。
        </p>
        <div class="report-account-actions">
          <button type="button" class="report-control-button report-control-login" :disabled="saving" @click="saveAccount">
            {{ saving ? '保存中' : '保存' }}
          </button>
          <button type="button" class="report-control-button" @click="closeAccountDialog">取消</button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.report-control-panel {
  margin: 8px 0;
  overflow: hidden;
  border: 1px solid var(--ops-line-strong, #4b8ed3);
  border-radius: 2px;
  background: linear-gradient(#fff, var(--ops-panel-soft, #f3f8ff));
  color: var(--ops-text, #243b53);
  font-size: 12px;
  box-shadow: inset 0 0 0 1px rgb(255 255 255 / 78%);
}

.report-control-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 5px;
  min-height: 30px;
  padding: 3px 8px;
}

.report-control-row + .report-control-row {
  border-top: 1px solid var(--ops-line, #8eb7e6);
}

.report-control-label {
  color: var(--ops-blue-deep, #15599d);
  font-size: 12px;
  font-weight: 800;
  white-space: nowrap;
}

.report-type-select {
  height: 23px;
  min-width: 74px;
  border: 1px solid var(--ops-line, #8eb7e6);
  background: #fff;
  color: var(--ops-blue-deep, #15599d);
  font-weight: 700;
  font-size: 12px;
}

.report-type-select:disabled {
  background: #eef2f6;
  color: #93a4b3;
}

.report-control-button {
  display: inline-flex;
  height: 23px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--ops-line, #8eb7e6);
  background: linear-gradient(#fff, #e8f3ff);
  color: var(--ops-text, #243b53);
  padding: 0 8px;
  white-space: nowrap;
  font-size: 12px;
  cursor: pointer;
}

.report-control-button:disabled {
  border-color: #c8d4de;
  background: #f2f5f8;
  color: #9aa9b6;
  cursor: not-allowed;
}

.report-control-account {
  min-width: 58px;
}

.report-control-logout {
  min-width: 40px;
  border-color: #d89aa0;
  background: linear-gradient(#ffd9dd, #f3a9b1);
  color: #8e2932;
}

.report-control-login {
  min-width: 40px;
  border-color: #5c9f55;
  background: linear-gradient(#8bd476, #5fb44e);
  color: #fff;
}

.report-control-refresh {
  min-width: 46px;
  border-color: var(--ops-blue, #2d7bcd);
  background: linear-gradient(#5aa8e7, var(--ops-blue, #2d7bcd));
  color: #fff;
  font-weight: 700;
}

.report-control-metric {
  color: var(--ops-text, #243b53);
  white-space: nowrap;
}

.report-control-metric b {
  color: var(--ops-blue-deep, #15599d);
  font-weight: 800;
}

.report-control-state {
  display: inline-flex;
  align-items: center;
  padding: 0 6px;
  height: 20px;
  border: 1px solid #8faec1;
  background: #fff;
  font-weight: 700;
}

.report-control-state.is-online {
  border-color: #5c9f55;
  color: #2f7a2a;
}

.report-control-state.is-offline {
  border-color: #d48b8b;
  color: #d63333;
}

.report-control-check {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--ops-blue-deep, #15599d);
  white-space: nowrap;
  cursor: pointer;
}

.report-control-check.is-disabled {
  color: #9aa9b6;
  cursor: not-allowed;
}

.report-control-check input {
  width: 12px;
  height: 12px;
  margin: 0;
}

.report-control-hint,
.report-control-notice {
  margin: 0;
  padding: 3px 8px;
  border-top: 1px solid var(--ops-line, #8eb7e6);
  font-size: 12px;
}

.report-control-hint {
  color: #8a6d1f;
  background: #fff8e1;
}

.report-control-notice.is-ok {
  color: #2f7a2a;
  background: #eefaf0;
}

.report-control-notice.is-error {
  color: #b02a2a;
  background: #fdeeee;
}

.report-account-mask {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgb(20 35 50 / 45%);
}

.report-account-dialog {
  width: 360px;
  border: 1px solid var(--ops-line-strong, #4b8ed3);
  background: #fff;
  padding: 10px 12px;
  box-shadow: 0 6px 20px rgb(20 35 50 / 30%);
}

.report-account-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  color: var(--ops-blue-deep, #15599d);
}

.report-account-field {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.report-account-field span {
  width: 66px;
  color: var(--ops-blue-deep, #15599d);
  font-weight: 700;
}

.report-account-field input,
.report-account-field select {
  flex: 1;
  height: 24px;
  border: 1px solid var(--ops-line, #8eb7e6);
  padding: 0 6px;
  font-size: 12px;
}

.report-account-note {
  margin: 6px 0;
  color: #8a6d1f;
  font-size: 11px;
  line-height: 1.5;
}

.report-account-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
}
</style>
