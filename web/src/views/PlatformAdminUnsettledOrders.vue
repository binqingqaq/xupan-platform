<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, apiErrorMessage } from '../api'
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
  if (!window.confirm(`确定将注单[${row.command}]删除么?`)) return
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
  if (value === 'REPORTED') return '已飞单'
  if (value === 'FAILED') return '失败'
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
      <header>
        <div>
          <h1>未结订单</h1>
          <p>对应 BY220 admin.php - 未结订单</p>
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
              <th>子帐号</th>
              <th>机器人</th>
              <th>玩家</th>
              <th>期号</th>
              <th>指令</th>
              <th>投注时间</th>
              <th>报网状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>{{ row.subAccount || '--' }}</td>
              <td>{{ row.machineName || '--' }}</td>
              <td>{{ player(row) || '--' }}</td>
              <td>{{ row.issueNumber }}</td>
              <td class="command">{{ row.command }}</td>
              <td>{{ time(row.createdAt) }}</td>
              <td :class="`report-${row.reportStatus.toLowerCase()}`">{{ reportStatus(row.reportStatus) }}</td>
              <td>
                <button class="danger" type="button" :disabled="busyId === row.id" @click="remove(row)">
                  {{ busyId === row.id ? '处理中...' : '删除' }}
                </button>
              </td>
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
  </main>
</template>

<style scoped>
.platform-admin-page{min-height:100vh;background:#eaf4fb;color:#1d1d1d}.content{padding:14px}.content header{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}.content h1{margin:0;font-size:24px}.content p{margin:4px 0 12px;color:#5f7280}.content header>button,.danger{padding:7px 12px;border:1px solid #7da9c1;background:#fff;cursor:pointer}.danger{border-color:#d65a4a;color:#b3261e}.danger:disabled,.content button:disabled{color:#98a1a8;cursor:not-allowed}.table-wrap{overflow:auto;background:#fff}.table-wrap table{width:100%;min-width:900px}table{border-collapse:collapse;background:#fff;font-size:13px}th{padding:9px;background:#40b2db;color:#fff}td{padding:8px;border-bottom:1px solid #d9e3e8;text-align:center}.command{color:#2458c6;font-weight:600}.report-reported{color:#16803a;font-weight:600}.report-failed{color:#d02b20;font-weight:600}.alert{padding:10px;background:#ffe1de}.feedback{padding:10px;background:#dff5e4;color:#166b2d}.empty{padding:30px;background:#fff;text-align:center}.content footer{display:flex;align-items:center;justify-content:flex-end;gap:12px;padding:12px 0}.content footer button{padding:6px 12px}
</style>
