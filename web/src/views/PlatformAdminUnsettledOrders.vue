<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, apiErrorMessage } from '../api'
import { ElMessageBox } from 'element-plus'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import type { UnsettledOrderItem } from '../types/platformAdmin'

const rows = ref<UnsettledOrderItem[]>([])
const page = ref(1)
const pageSize = 30
const total = ref(0)
const loading = ref(false)
const busyId = ref<number | null>(null)
const error = ref('')
const feedback = ref('')
const currentUser = ref('超级管理员')
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

async function load(nextPage = page.value) {
  if (nextPage < 1 || nextPage > totalPages.value) return
  loading.value = true
  error.value = ''
  try {
    const result = await api.listUnsettledOrders({ page: nextPage, pageSize })
    rows.value = result.items
    page.value = result.page
    total.value = result.total
  } catch (cause) {
    error.value = apiErrorMessage(cause, '未结订单加载失败')
  } finally {
    loading.value = false
  }
}

async function remove(row: UnsettledOrderItem) {
  try {
    await ElMessageBox.confirm(`确定将注单[${row.command}]删除么?`, '删除确认', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', closeOnClickModal: false })
  } catch { return }
  busyId.value = row.id
  error.value = ''
  feedback.value = ''
  try {
    const result = await api.cancelUnsettledOrder(row.id)
    feedback.value = `成功！已删除注单，返还 ${result.refundedAmount.toFixed(2)}`
    await load(page.value)
    if (rows.value.length === 0 && page.value > 1) await load(page.value - 1)
  } catch (cause) {
    error.value = apiErrorMessage(cause, '删除未结订单失败')
  } finally {
    busyId.value = null
  }
}

function player(row: UnsettledOrderItem) {
  return [row.memberCode, row.playerName].filter(Boolean).join(' ')
}

function time(value: string) {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function reportStatus(value: UnsettledOrderItem['reportStatus']) {
  if (value === 'NOT_SENT') return '未发送'
  if (value === 'ACCEPTED' || value === 'SETTLED') return '已飞单'
  if (value === 'REJECTED') return '失败'
  if (value === 'TIMEOUT') return '超时'
  return '未报'
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
      <header class="page-heading"><h1>未结订单</h1><el-button :disabled="loading" @click="load(page)">刷新</el-button></header>
      <el-alert v-if="error" type="error" :title="error" :closable="false" />
      <el-alert v-if="feedback" type="success" :title="feedback" :closable="false" />
      <el-skeleton v-if="loading" :rows="6" animated />
      <el-table scrollbar-always-on v-else :data="rows" row-key="id" stripe border empty-text="暂无数据">
        <el-table-column prop="subAccount" label="子帐号" min-width="110"><template #default="{ row }">{{ row.subAccount || '--' }}</template></el-table-column>
        <el-table-column prop="machineName" label="机器人" min-width="120"><template #default="{ row }">{{ row.machineName || '--' }}</template></el-table-column>
        <el-table-column label="玩家" min-width="150"><template #default="{ row }">{{ player(row as UnsettledOrderItem) || '--' }}</template></el-table-column>
        <el-table-column prop="issueNumber" label="期号" min-width="130" />
        <el-table-column prop="command" label="指令" min-width="240" show-overflow-tooltip class-name="command" />
        <el-table-column label="投注时间" min-width="180"><template #default="{ row }">{{ time(row.createdAt) }}</template></el-table-column>
        <el-table-column label="报网状态" min-width="120"><template #default="{ row }"><el-tag :type="row.reportStatus === 'ACCEPTED' || row.reportStatus === 'SETTLED' ? 'success' : row.reportStatus === 'REJECTED' ? 'danger' : row.reportStatus === 'TIMEOUT' ? 'warning' : 'info'">{{ reportStatus(row.reportStatus) }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="110" fixed="right"><template #default="{ row }"><el-button type="danger" link :disabled="busyId === row.id" :loading="busyId === row.id" @click="remove(row as UnsettledOrderItem)">删除</el-button></template></el-table-column>
      </el-table>
      <footer v-if="total > pageSize" class="table-footer">
        <span>第 {{ page }} / {{ totalPages }} 页，共 {{ total }} 条</span>
        <el-pagination :current-page="page" :page-size="pageSize" :total="total" layout="prev, pager, next" :disabled="loading" @current-change="load" />
      </footer>
    </section>
  </main>
</template>

<style scoped>
.platform-admin-page { min-height: 100vh; background: #f4f6f9; color: #303133; }
.content { display: grid; gap: 14px; padding: 18px; }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.page-heading h1 { margin: 0; font-size: 26px; }
.table-footer { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 16px; padding: 12px 0; color: #606266; }
.command { color: #2458c6; font-weight: 600; }
</style>
