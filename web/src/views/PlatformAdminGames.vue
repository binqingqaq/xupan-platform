<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { api, apiErrorMessage } from '../api'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import type { GameSettings, GameSettingsInput } from '../types/platformAdmin'

const rows = ref<GameSettings[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const feedback = ref('')
const currentUser = ref('超级管理员')
const formOpen = ref(false)
const editingId = ref<number | null>(null)
const editingVersion = ref(0)

function blankForm(): GameSettingsInput {
  return {
    gameCode: '', displayName: '', ballIndexes: '1,2,3,4,5,6,7,8', drawSourceUrl: '',
    sortOrder: rows.value.length + 1, algorithm: 'SUM', playPrefix: '', switchEnabled: false,
    specialEnabled: true, specialModel: 'MODEL_ONE', keyboardEnabled: true, status: 'ACTIVE',
    oddsAte: 0, oddsAdx: 0, oddsBte: 0, oddsBdx: 0, oddsCte: 0, oddsCdx: 0, oddsDte: 0, oddsDdx: 0,
  }
}

const form = ref<GameSettingsInput>(blankForm())

async function load() {
  loading.value = true
  error.value = ''
  try {
    rows.value = await api.getGameSettings()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '游戏设置加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  editingVersion.value = 0
  form.value = blankForm()
  formOpen.value = true
}

function openEdit(row: GameSettings) {
  editingId.value = row.id
  editingVersion.value = row.version
  form.value = {
    gameCode: row.gameCode,
    displayName: row.displayName,
    ballIndexes: row.ballIndexes,
    drawSourceUrl: row.drawSourceUrl || '',
    sortOrder: row.sortOrder,
    algorithm: row.algorithm,
    playPrefix: row.playPrefix || '',
    switchEnabled: row.switchEnabled,
    specialEnabled: row.specialEnabled,
    specialModel: row.specialModel,
    keyboardEnabled: row.keyboardEnabled,
    status: row.status,
    oddsAte: row.oddsAte,
    oddsAdx: row.oddsAdx,
    oddsBte: row.oddsBte,
    oddsBdx: row.oddsBdx,
    oddsCte: row.oddsCte,
    oddsCdx: row.oddsCdx,
    oddsDte: row.oddsDte,
    oddsDdx: row.oddsDdx,
  }
  formOpen.value = true
}

async function save() {
  if (saving.value || !form.value.displayName.trim() || !form.value.gameCode.trim()) return
  saving.value = true
  error.value = ''
  feedback.value = ''
  try {
    if (editingId.value === null) {
      await api.createGameSettings(form.value)
      feedback.value = '成功'
    } else {
      await api.updateGameSettings(editingId.value, editingVersion.value, form.value)
      feedback.value = '成功'
    }
    formOpen.value = false
    await load()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row: GameSettings) {
  if (row.id === 1) return
  try {
    await ElMessageBox.confirm(`确定将[${row.displayName}]删除么?`, '确认删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', closeOnClickModal: false })
  } catch { return }
  error.value = ''
  feedback.value = ''
  try {
    await api.deleteGameSettings(row.id)
    feedback.value = '成功'
    await load()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '删除失败')
  }
}

function algorithmText(value: string) {
  return value === 'CONCAT' ? '相拼' : '相加'
}

onMounted(async () => {
  currentUser.value = (await api.me()).displayName || '超级管理员'
  await load()
})
</script>

<template>
  <main class="platform-admin-page">
    <PlatformAdminNav :display-name="currentUser" />
    <section class="content">
      <header class="page-heading"><h1>游戏设置</h1><div class="heading-actions"><el-button :disabled="loading" @click="load">刷新</el-button><el-button type="primary" @click="openCreate">添加彩种</el-button></div></header>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="page-alert" />
      <el-alert v-if="feedback" :title="feedback" type="success" show-icon :closable="false" class="page-alert" />
      <el-table scrollbar-always-on v-loading="loading" :data="rows" row-key="id" border stripe class="games-table">
        <el-table-column prop="displayName" label="彩种名称" min-width="130" />
        <el-table-column prop="gameCode" label="彩种键名" min-width="120" />
        <el-table-column prop="ballIndexes" label="开奖球号" min-width="170" />
        <el-table-column label="采集链接" min-width="220" show-overflow-tooltip><template #default="{ row }">{{ row.drawSourceUrl || '--' }}</template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status === 'ACTIVE' ? '正常' : '关闭' }}</el-tag></template></el-table-column>
        <el-table-column prop="sortOrder" label="排序" width="80" align="right" />
        <el-table-column label="算法" width="85"><template #default="{ row }">{{ algorithmText(row.algorithm) }}</template></el-table-column>
        <el-table-column label="玩法前缀" width="100"><template #default="{ row }">{{ row.playPrefix || '--' }}</template></el-table-column>
        <el-table-column label="是否切换" width="95"><template #default="{ row }">{{ row.switchEnabled ? '是' : '否' }}</template></el-table-column>
        <el-table-column label="显示特" width="85"><template #default="{ row }">{{ row.specialEnabled ? '显示' : '隐藏' }}</template></el-table-column>
        <el-table-column label="特模型" width="90"><template #default="{ row }">{{ row.specialModel === 'MODEL_TWO' ? '模型二' : '模型一' }}</template></el-table-column>
        <el-table-column label="显示键盘" width="95"><template #default="{ row }">{{ row.keyboardEnabled ? '显示' : '隐藏' }}</template></el-table-column>
        <el-table-column label="操作" fixed="right" width="120"><template #default="{ row }"><div class="actions"><el-button link type="primary" @click="openEdit(row as GameSettings)">编辑</el-button><el-button link type="danger" :disabled="row.id === 1" @click="remove(row as GameSettings)">删除</el-button></div></template></el-table-column>
        <template #empty><el-empty description="暂无游戏" /></template>
      </el-table>
      <el-alert title="A-D 盘赔率已真实保存到彩种定义；当前玩家下注、期号和结算引擎仍只消费主彩种 AU8，新彩种上线能力需后续引擎专项，不能把后台定义当作已接入玩法。" type="info" :closable="false" class="page-alert capability-note" />
    </section>

    <el-dialog v-model="formOpen" :title="editingId === null ? '添加彩种' : '编辑彩种'" width="min(980px, calc(100vw - 32px))" modal-class="platform-admin-dialog-overlay" :close-on-click-modal="false" destroy-on-close>
      <el-form class="form-grid" label-position="top" @submit.prevent="save">
        <el-form-item label="彩种名称" required><el-input v-model="form.displayName" required maxlength="128" /></el-form-item>
        <el-form-item label="彩种键名" required><el-input v-model="form.gameCode" required maxlength="32" :disabled="editingId !== null" /></el-form-item>
        <el-form-item label="开奖球号" required class="wide"><el-input v-model="form.ballIndexes" required placeholder="1,2,3,4,5,6,7,8" /></el-form-item>
        <el-form-item label="采集链接" class="wide"><el-input v-model="form.drawSourceUrl" maxlength="512" placeholder="可留空，当前不主动请求" /></el-form-item>
        <el-form-item label="排序"><el-input v-model.number="form.sortOrder" type="number" min="0" max="100000" /></el-form-item>
        <el-form-item label="算法"><el-select v-model="form.algorithm" class="full-width"><el-option label="相加" value="SUM" /><el-option label="相拼" value="CONCAT" /></el-select></el-form-item>
        <el-form-item label="玩法前缀"><el-input v-model="form.playPrefix" maxlength="32" /></el-form-item>
        <el-form-item label="特模型"><el-select v-model="form.specialModel" class="full-width"><el-option label="模型一" value="MODEL_ONE" /><el-option label="模型二" value="MODEL_TWO" /></el-select></el-form-item>
        <el-form-item label="状态"><el-select v-model="form.status" class="full-width"><el-option label="开通" value="ACTIVE" /><el-option label="关闭" value="DISABLED" /></el-select></el-form-item>
        <section class="odds-panel wide">
          <h2>赔率</h2>
          <div class="odds-grid">
            <el-form-item label="A盘特码赔率"><el-input v-model.number="form.oddsAte" type="number" min="0" step="0.01" /></el-form-item>
            <el-form-item label="A盘大小单双"><el-input v-model.number="form.oddsAdx" type="number" min="0" step="0.01" /></el-form-item>
            <el-form-item label="B盘特码赔率"><el-input v-model.number="form.oddsBte" type="number" min="0" step="0.01" /></el-form-item>
            <el-form-item label="B盘大小单双"><el-input v-model.number="form.oddsBdx" type="number" min="0" step="0.01" /></el-form-item>
            <el-form-item label="C盘特码赔率"><el-input v-model.number="form.oddsCte" type="number" min="0" step="0.01" /></el-form-item>
            <el-form-item label="C盘大小单双"><el-input v-model.number="form.oddsCdx" type="number" min="0" step="0.01" /></el-form-item>
            <el-form-item label="D盘特码赔率"><el-input v-model.number="form.oddsDte" type="number" min="0" step="0.01" /></el-form-item>
            <el-form-item label="D盘大小单双"><el-input v-model.number="form.oddsDdx" type="number" min="0" step="0.01" /></el-form-item>
          </div>
        </section>
        <div class="switch-grid wide">
          <div class="switch-row"><span>是否切换</span><el-switch v-model="form.switchEnabled" /></div>
          <div class="switch-row"><span>显示特</span><el-switch v-model="form.specialEnabled" /></div>
          <div class="switch-row"><span>显示键盘</span><el-switch v-model="form.keyboardEnabled" /></div>
        </div>
        <div class="dialog-footer"><el-button @click="formOpen = false">取消</el-button><el-button type="primary" native-type="submit" :loading="saving">确认</el-button></div>
      </el-form>
    </el-dialog>
  </main>
</template>

<style scoped>
.content { display: grid; gap: 14px; padding: 18px; }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.page-heading h1 { margin: 0; font-size: 22px; }
.heading-actions { display: flex; gap: 8px; }
.page-alert { margin-bottom: 2px; }
.games-table { width: 100%; }
.actions { display: flex; align-items: center; justify-content: center; gap: 4px; white-space: nowrap; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.form-grid :deep(.el-form-item) { min-width: 0; }
.form-grid :deep(.el-form-item.wide), .wide { grid-column: 1 / -1; }
.full-width { width: 100%; }
.odds-panel { padding: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 4px; }
.odds-panel h2 { margin: 0 0 12px; font-size: 15px; }
.odds-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 0 12px; }
.switch-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.switch-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 4px; }
.dialog-footer { grid-column: 1 / -1; display: flex; justify-content: flex-end; gap: 8px; padding-top: 10px; }
@media (max-width: 760px) { .content { padding: 12px; } .form-grid, .odds-grid, .switch-grid { grid-template-columns: 1fr; } .form-grid :deep(.el-form-item.wide), .wide { grid-column: auto; } }
</style>
