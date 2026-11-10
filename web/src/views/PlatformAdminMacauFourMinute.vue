<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, apiErrorMessage } from '../api'
import { adminNotice } from '../services/adminNotice'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import PlatformAdminMobileRecordList from '../components/platform-admin/PlatformAdminMobileRecordList.vue'
import { usePlatformAdminMobile } from '../composables/usePlatformAdminMobile'
import type { MacauFourMinuteDraw } from '../types/platformAdmin'

const rows = ref<MacauFourMinuteDraw[]>([])
const page = ref(1)
const pageSize = 20
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const currentUser = ref('超级管理员')
const canManagePreset = ref(false)
const isMobileViewport = usePlatformAdminMobile()
const editOpen = ref(false)
const editingIssue = ref('')
const presetInputs = ref<string[]>(Array(7).fill(''))
const inputError = ref('')

const mobileFields = [
  { key: 'lottery', label: '彩票名称', value: () => '澳门四分彩' },
  { key: 'issueNumber', label: '期号', value: (row: MacauFourMinuteDraw) => row.issueNumber },
  { key: 'balls', label: '开奖球号', value: (row: MacauFourMinuteDraw) => row.numbers.map(formatBall).join('  ') },
  { key: 'drawTime', label: '开奖时间', value: drawTime },
  { key: 'status', label: '当前状态', value: (row: MacauFourMinuteDraw) => statusLabel(row.status) },
]

async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const result = await api.listMacauFourMinuteDraws(page.value, pageSize)
    rows.value = result.items
    page.value = result.page
    total.value = result.total
  } catch (cause) {
    error.value = apiErrorMessage(cause, '澳门四分彩记录加载失败')
  } finally {
    loading.value = false
  }
}

function changePage(nextPage: number) {
  page.value = nextPage
  void load()
}

function openEdit(row: MacauFourMinuteDraw) {
  if (!canManagePreset.value || row.status !== 'PENDING') return
  editingIssue.value = row.issueNumber
  presetInputs.value = Array.from({ length: 7 }, (_, index) => {
    const value = row.numbers[index]
    return value === null || value === undefined ? '' : String(value).padStart(2, '0')
  })
  inputError.value = ''
  editOpen.value = true
}

function updatePresetInput(index: number, value: string | number) {
  presetInputs.value[index] = String(value).replace(/\D/g, '').slice(0, 2)
  inputError.value = ''
}

function parsePresets(): (number | null)[] | null {
  const values = presetInputs.value.map(value => {
    if (!value.trim()) return null
    if (!/^\d{1,2}$/.test(value)) return Number.NaN
    const number = Number(value)
    return number >= 1 && number <= 40 ? number : Number.NaN
  })
  const invalidIndex = values.findIndex(value => value !== null && !Number.isInteger(value))
  if (invalidIndex >= 0) {
    inputError.value = `第 ${invalidIndex + 1} 球请输入 1 至 40 的整数`
    return null
  }
  const selected = values.filter((value): value is number => value !== null)
  if (new Set(selected).size !== selected.length) {
    inputError.value = '同一期预设号码不能重复'
    return null
  }
  return values
}

async function savePreset() {
  if (!canManagePreset.value || saving.value || !editingIssue.value) return
  const numbers = parsePresets()
  if (!numbers) return
  saving.value = true
  try {
    await api.saveMacauFourMinutePreset(editingIssue.value, numbers)
    adminNotice.success('预设号码已保存')
    editOpen.value = false
    await load()
  } catch (cause) {
    adminNotice.error(apiErrorMessage(cause, '预设号码保存失败'))
  } finally {
    saving.value = false
  }
}

function formatBall(value: number | null | undefined) {
  return value === null || value === undefined ? '-' : String(value).padStart(2, '0')
}

function statusLabel(status: MacauFourMinuteDraw['status']) {
  return status === 'DRAWN' ? '已开奖' : '待开奖'
}

function rowKey(row: MacauFourMinuteDraw) {
  return row.issueNumber
}

function drawTime(row: MacauFourMinuteDraw) {
  const value = row.status === 'DRAWN' ? row.drawnAt : row.scheduledAt
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
    hourCycle: 'h23',
  }).format(date)
}

onMounted(async () => {
  try {
    const me = await api.me()
    currentUser.value = me.displayName || '超级管理员'
    canManagePreset.value = me.permissions.includes('MACAU_FOUR_MINUTE_PRESET_MANAGE')
  } catch (cause) {
    error.value = apiErrorMessage(cause, '权限读取失败')
  }
  await load()
})
</script>

<template>
  <main class="platform-admin-page macau-four-minute-page">
    <PlatformAdminNav :display-name="currentUser" />
    <section class="content">
      <header class="page-heading">
        <h1>澳门四分彩</h1>
        <el-button :disabled="loading" :loading="loading" @click="load">刷新</el-button>
      </header>

      <el-alert v-if="error" type="error" :title="error" :closable="false" />

      <div v-if="!isMobileViewport" class="desktop-table-wrap">
        <el-table v-loading="loading" :data="rows" row-key="issueNumber" stripe border empty-text="暂无开奖记录">
          <el-table-column label="彩票名称" min-width="130"><template #default>澳门四分彩</template></el-table-column>
          <el-table-column prop="issueNumber" label="期号" min-width="110" />
          <el-table-column label="开奖球号" min-width="340">
            <template #default="{ row }">
              <div class="draw-balls">
                <span v-for="(number, index) in row.numbers" :key="index" class="draw-ball">{{ formatBall(number) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="开奖时间" min-width="190"><template #default="{ row }">{{ drawTime(row as MacauFourMinuteDraw) }}</template></el-table-column>
          <el-table-column label="当前状态" min-width="110">
            <template #default="{ row }"><el-tag :type="row.status === 'DRAWN' ? 'success' : 'warning'">{{ statusLabel(row.status as MacauFourMinuteDraw['status']) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 'PENDING'" link type="primary" :disabled="!canManagePreset" @click="openEdit(row as MacauFourMinuteDraw)">编辑</el-button>
              <span v-else class="empty-action">-</span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <PlatformAdminMobileRecordList
        v-else
        :rows="rows"
        :fields="mobileFields"
        :row-key="rowKey"
        :loading="loading"
        empty-text="暂无开奖记录"
      >
        <template #heading="{ row }"><strong>第 {{ row.issueNumber }} 期</strong></template>
        <template #actions="{ row }"><el-button v-if="row.status === 'PENDING'" type="primary" plain :disabled="!canManagePreset" @click="openEdit(row)">编辑</el-button></template>
      </PlatformAdminMobileRecordList>

      <div class="history-pagination">
        <el-pagination
          background
          :current-page="page"
          :page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next"
          :disabled="loading"
          @current-change="changePage"
        />
      </div>
    </section>

    <el-dialog v-model="editOpen" :title="`编辑第 ${editingIssue} 期预设号码`" width="min(620px, 94vw)" modal-class="platform-admin-dialog-overlay" :close-on-click-modal="false" destroy-on-close>
      <div class="preset-grid">
        <label v-for="(value, index) in presetInputs" :key="index" class="preset-field">
          <span>第 {{ index + 1 }} 球</span>
          <el-input
            :model-value="value"
            inputmode="numeric"
            maxlength="2"
            placeholder="随机"
            :disabled="saving"
            @update:model-value="updatePresetInput(index, $event)"
          />
        </label>
      </div>
      <p class="preset-hint">留空表示该球位开奖时随机；七个预设号码不能重复。</p>
      <el-alert v-if="inputError" class="preset-error" type="error" :title="inputError" :closable="false" />
      <template #footer>
        <el-button :disabled="saving" @click="editOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="savePreset">确认</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.platform-admin-page { min-height: 100vh; background: #f4f6f9; color: #303133; }
.content { display: grid; gap: 16px; padding: 18px; }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.page-heading h1 { margin: 0; font-size: 24px; line-height: 1.35; }
.desktop-table-wrap { min-width: 0; }
.draw-balls { display: flex; flex-wrap: wrap; gap: 6px; }
.draw-ball { display: inline-grid; width: 34px; height: 28px; place-items: center; border: 1px solid #cfe2e0; border-radius: 14px; background: #edf6f5; color: #276d68; font-variant-numeric: tabular-nums; }
.empty-action { color: #98a2b3; }
.history-pagination { display: flex; justify-content: flex-end; }
.preset-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.preset-field { display: grid; gap: 6px; color: #475467; font-size: 13px; }
.preset-field :deep(input) { text-align: center; font-variant-numeric: tabular-nums; }
.preset-hint { margin: 14px 0 0; color: #667085; font-size: 13px; }
.preset-error { margin-top: 12px; }
@media (max-width: 767px) {
  .content { min-width: 0; padding: 14px 12px; }
  .page-heading h1 { font-size: 21px; }
  .preset-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
  .history-pagination { justify-content: center; }
}
</style>
