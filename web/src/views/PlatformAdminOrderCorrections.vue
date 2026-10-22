<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, apiErrorMessage } from '../api'
import { ElMessage } from 'element-plus'
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
  if (command.value.length === 0) {
    ElMessage.warning('指令不能为空')
    return
  }
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
      <header class="page-heading"><h1>订单改单</h1><el-button :disabled="loading" @click="load(page)">刷新</el-button></header>
      <el-alert v-if="error" type="error" :title="error" :closable="false" />
      <el-alert v-if="feedback" type="success" :title="feedback" :closable="false" />
      <el-skeleton v-if="loading" :rows="6" animated />
      <el-table scrollbar-always-on v-else :data="rows" row-key="id" stripe border empty-text="暂无数据">
        <el-table-column prop="id" label="ID" width="90" />
        <el-table-column prop="machineName" label="所属机器人" min-width="140"><template #default="{ row }">{{ row.machineName || '--' }}</template></el-table-column>
        <el-table-column prop="playerName" label="昵称" min-width="130"><template #default="{ row }">{{ row.playerName || '--' }}</template></el-table-column>
        <el-table-column prop="issueNumber" label="期号" min-width="130" />
        <el-table-column label="时间" min-width="180"><template #default="{ row }">{{ time(row.createdAt) }}</template></el-table-column>
        <el-table-column label="金额" min-width="110" align="right"><template #default="{ row }">{{ row.stake.toFixed(2) }}</template></el-table-column>
        <el-table-column prop="command" label="投注内容" min-width="240" show-overflow-tooltip />
        <el-table-column label="操作" width="100" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openEditor(row as OrderCorrectionItem)">编辑</el-button></template></el-table-column>
      </el-table>
      <footer v-if="total > pageSize" class="table-footer">
        <span>第 {{ page }} / {{ totalPages }} 页，共 {{ total }} 条</span>
        <el-pagination :current-page="page" :page-size="pageSize" :total="total" layout="prev, pager, next" :disabled="loading" @current-change="load" />
      </footer>
    </section>

    <el-dialog v-if="detail" v-model="editorOpen" title="编辑订单" width="min(620px, 94vw)" modal-class="platform-admin-dialog-overlay" :close-on-click-modal="false">
      <el-form :model="detail" label-position="top" class="form-grid" @submit.prevent="submit">
        <el-form-item label="订单ID"><el-input :model-value="String(detail.id)" readonly /></el-form-item>
        <el-form-item label="期号"><el-input :model-value="detail.issueNumber" readonly /></el-form-item>
        <el-form-item label="所属机器人"><el-input :model-value="detail.machineName" readonly /></el-form-item>
        <el-form-item label="昵称"><el-input :model-value="detail.playerName" readonly /></el-form-item>
        <el-form-item label="指令" class="wide"><el-input v-model="command" required /></el-form-item>
        <el-form-item label="球" class="wide">
          <el-radio-group v-model="ballNumber">
            <el-radio-button v-for="number in 8" :key="number" :value="number">{{ number }}</el-radio-button>
          </el-radio-group>
          <small class="form-hint">支持第1至8球；开奖后按订单所选球位结算。</small>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">确认</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.platform-admin-page { min-height: 100vh; background: #f4f6f9; color: #303133; }
.content { display: grid; gap: 14px; padding: 18px; }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.page-heading h1 { margin: 0; font-size: 26px; }
.table-footer { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 16px; padding: 12px 0; color: #606266; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.wide { grid-column: 1 / -1; }
.form-hint { display: block; margin-top: 8px; color: #606266; }
@media (max-width: 640px) { .form-grid { grid-template-columns: 1fr; } .wide { grid-column: auto; } }
</style>

