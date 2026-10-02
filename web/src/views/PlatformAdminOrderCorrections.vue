<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, apiErrorMessage } from '../api'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import type { OrderCorrectionDetail, OrderCorrectionItem } from '../types/platformAdmin'

const rows = ref<OrderCorrectionItem[]>([])
const page = ref(1)
const pageSize = 30
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const feedback = ref('')
const currentUser = ref('超级管理员')
const editorOpen = ref(false)
const detail = ref<OrderCorrectionDetail | null>(null)
const command = ref('')
const ballNumber = ref(1)
const idempotencyKey = ref('')
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

function createIdempotencyKey() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return `order-correction-${crypto.randomUUID()}`
  return `order-correction-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

async function load(nextPage = page.value) {
  if (nextPage < 1 || nextPage > totalPages.value) return
  loading.value = true
  error.value = ''
  try {
    const result = await api.listOrderCorrections({ page: nextPage, pageSize })
    rows.value = result.items
    page.value = result.page
    total.value = result.total
  } catch (cause) {
    error.value = apiErrorMessage(cause, '订单改单加载失败')
  } finally {
    loading.value = false
  }
}

async function openEditor(row: OrderCorrectionItem) {
  error.value = ''
  feedback.value = ''
  try {
    detail.value = await api.getOrderCorrection(row.id)
    command.value = detail.value.command
    ballNumber.value = detail.value.ballNumber || 1
    idempotencyKey.value = createIdempotencyKey()
    editorOpen.value = true
  } catch (cause) {
    error.value = apiErrorMessage(cause, '订单详情加载失败')
  }
}

async function submit() {
  if (!detail.value || saving.value) return
  saving.value = true
  error.value = ''
  feedback.value = ''
  try {
    const result = await api.correctOrder(detail.value.id, {
      command: command.value,
      ballNumber: ballNumber.value,
      idempotencyKey: idempotencyKey.value,
    })
    const delta = result.stakeDelta > 0 ? `扣除 ${result.stakeDelta.toFixed(2)}` : result.stakeDelta < 0 ? `返还 ${Math.abs(result.stakeDelta).toFixed(2)}` : '积分不变'
    feedback.value = `修改成功，${delta}${result.walletBalance === null ? '' : `，当前余额 ${result.walletBalance.toFixed(2)}`}`
    editorOpen.value = false
    await load(page.value)
  } catch (cause) {
    error.value = apiErrorMessage(cause, '订单改单失败')
  } finally {
    saving.value = false
  }
}

function time(value: string) {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

onMounted(async () => {
  currentUser.value = (await api.me()).displayName || '超级管理员'
  await load(1)
})
</script>

<template>
  <main class="platform-admin-page">
    <PlatformAdminNav :display-name="currentUser" />
    <section class="content">
      <header>
        <div>
          <h1>订单改单</h1>
          <p>对应 BY220 admin.php - 订单改单</p>
        </div>
        <button type="button" :disabled="loading" @click="load(page)">刷新</button>
      </header>
      <p v-if="error" class="alert">{{ error }}</p>
      <p v-if="feedback" class="feedback">{{ feedback }}</p>
      <div v-if="loading" class="empty">正在加载...</div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>ID</th>
              <th>所属机器人</th>
              <th>昵称</th>
              <th>期号</th>
              <th>时间</th>
              <th>金额</th>
              <th>投注内容</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>{{ row.id }}</td>
              <td>{{ row.machineName || '--' }}</td>
              <td>{{ row.playerName || '--' }}</td>
              <td>{{ row.issueNumber }}</td>
              <td>{{ time(row.createdAt) }}</td>
              <td>{{ row.stake.toFixed(2) }}</td>
              <td class="command">{{ row.command }}</td>
              <td><button type="button" @click="openEditor(row)">编辑</button></td>
            </tr>
            <tr v-if="rows.length === 0"><td colspan="8">暂无数据</td></tr>
          </tbody>
        </table>
      </div>
      <footer v-if="total > pageSize">
        <button type="button" :disabled="page <= 1 || loading" @click="load(page - 1)">上一页</button>
        <span>第 {{ page }} / {{ totalPages }} 页，共 {{ total }} 条</span>
        <button type="button" :disabled="page >= totalPages || loading" @click="load(page + 1)">下一页</button>
      </footer>
    </section>

    <div v-if="editorOpen && detail" class="modal-mask" @click.self="editorOpen = false">
      <form class="modal" @submit.prevent="submit">
        <header>
          <h2>编辑订单</h2>
          <button type="button" @click="editorOpen = false">×</button>
        </header>
        <div class="form-grid">
          <label>订单ID<input :value="detail.id" readonly /></label>
          <label>期号<input :value="detail.issueNumber" readonly /></label>
          <label>所属机器人<input :value="detail.machineName" readonly /></label>
          <label>昵称<input :value="detail.playerName" readonly /></label>
          <label class="wide">指令<input v-model="command" required /></label>
        </div>
        <fieldset>
          <legend>球</legend>
          <label v-for="number in 8" :key="number" class="ball-choice">
            <input v-model.number="ballNumber" type="radio" :value="number" />
            <span>{{ number }}</span>
          </label>
          <small>支持第1至8球；开奖后按订单所选球位结算。</small>
        </fieldset>
        <footer>
          <button type="button" @click="editorOpen = false">取消</button>
          <button type="submit" :disabled="saving">{{ saving ? '保存中...' : '确认' }}</button>
        </footer>
      </form>
    </div>
  </main>
</template>

<style scoped>
.platform-admin-page{min-height:100vh;background:#eaf4fb;color:#1d1d1d}.content{padding:14px}.content header{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}.content h1{margin:0;font-size:24px}.content p{margin:4px 0 12px;color:#5f7280}.content button,.modal button{padding:7px 12px;border:1px solid #7da9c1;background:#fff;cursor:pointer}.table-wrap{overflow:auto;background:#fff}.table-wrap table{width:100%;min-width:1000px}table{border-collapse:collapse;background:#fff;font-size:13px}th{padding:9px;background:#40b2db;color:#fff}td{padding:8px;border-bottom:1px solid #d9e3e8;text-align:center}.command{color:#2458c6;font-weight:600}.alert{padding:10px;background:#ffe1de}.feedback{padding:10px;background:#dff5e4;color:#166b2d}.empty{padding:30px;background:#fff;text-align:center}.content footer{display:flex;align-items:center;justify-content:flex-end;gap:12px;padding:12px 0}.modal-mask{position:fixed;inset:0;display:grid;place-items:center;background:rgba(0,0,0,.35);z-index:20}.modal{width:min(520px,calc(100vw - 24px));background:#fff}.modal header,.modal footer{display:flex;align-items:center;justify-content:space-between;padding:12px 16px;background:#eef6fb}.modal h2{margin:0;font-size:18px}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:10px;padding:16px}.form-grid label{display:grid;gap:5px}.form-grid input{padding:7px;border:1px solid #a8c8dc}.wide{grid-column:1/-1}.modal fieldset{margin:0 16px 16px;padding:10px;border:1px solid #c5d9e6}.ball-choice{display:inline-flex;gap:4px;margin:5px}.modal small{display:block;margin-top:6px;color:#667985}.modal footer{justify-content:flex-end}
</style>

