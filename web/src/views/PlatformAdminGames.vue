<script setup lang="ts">
import { onMounted, ref } from 'vue'
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
  if (!window.confirm(`确定将[${row.displayName}]删除么?`)) return
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
      <header>
        <div><h1>游戏设置</h1><p>对应 BY220 admin.php - 游戏设置</p></div>
        <button type="button" @click="openCreate">添加</button>
        <button type="button" :disabled="loading" @click="load">刷新</button>
      </header>
      <p v-if="error" class="alert">{{ error }}</p>
      <p v-if="feedback" class="feedback">{{ feedback }}</p>
      <div v-if="loading" class="empty">正在加载...</div>
      <div v-else class="table-wrap">
        <table>
          <thead><tr><th>彩种名称</th><th>彩种键名</th><th>开奖球号</th><th>采集链接</th><th>状态</th><th>排序</th><th>算法</th><th>玩法前缀</th><th>是否切换</th><th>显示特</th><th>特模型</th><th>显示键盘</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>{{ row.displayName }}</td><td>{{ row.gameCode }}</td><td>{{ row.ballIndexes }}</td>
              <td class="url">{{ row.drawSourceUrl || '--' }}</td>
              <td :class="row.status === 'ACTIVE' ? 'ok' : 'off'">{{ row.status === 'ACTIVE' ? '正常' : '关闭' }}</td>
              <td>{{ row.sortOrder }}</td><td>{{ algorithmText(row.algorithm) }}</td><td>{{ row.playPrefix || '--' }}</td>
              <td>{{ row.switchEnabled ? '是' : '否' }}</td><td>{{ row.specialEnabled ? '显示' : '隐藏' }}</td>
              <td>{{ row.specialModel === 'MODEL_TWO' ? '模型二' : '模型一' }}</td><td>{{ row.keyboardEnabled ? '显示' : '隐藏' }}</td>
              <td><div class="actions"><button type="button" @click="openEdit(row)">编辑</button><button type="button" class="danger" :disabled="row.id === 1" @click="remove(row)">删除</button></div></td>
            </tr>
            <tr v-if="rows.length===0"><td colspan="13">暂无数据</td></tr>
          </tbody>
        </table>
      </div>
      <p class="hint">A-D 盘赔率已真实保存到彩种定义；当前玩家下注、期号和结算引擎仍只消费主彩种 AU8，新彩种上线能力需后续引擎专项，不能把后台定义当作已接入玩法。</p>
    </section>

    <div v-if="formOpen" class="modal-mask" @click.self="formOpen = false">
      <form class="modal" @submit.prevent="save">
        <header><h2>{{ editingId === null ? '添加彩种' : '编辑彩种' }}</h2><button type="button" @click="formOpen = false">×</button></header>
        <div class="form-grid">
          <label>彩种名称<input v-model="form.displayName" required maxlength="128" /></label>
          <label>彩种键名<input v-model="form.gameCode" required maxlength="32" :disabled="editingId !== null" /></label>
          <label class="wide">开奖球号<input v-model="form.ballIndexes" required placeholder="1,2,3,4,5,6,7,8" /></label>
          <label class="wide">采集链接<input v-model="form.drawSourceUrl" maxlength="512" placeholder="可留空，当前不主动请求" /></label>
          <label>排序<input v-model.number="form.sortOrder" type="number" min="0" max="100000" /></label>
          <label>算法<select v-model="form.algorithm"><option value="SUM">相加</option><option value="CONCAT">相拼</option></select></label>
          <label>玩法前缀<input v-model="form.playPrefix" maxlength="32" /></label>
          <label>特模型<select v-model="form.specialModel"><option value="MODEL_ONE">模型一</option><option value="MODEL_TWO">模型二</option></select></label>
          <label>状态<select v-model="form.status"><option value="ACTIVE">开通</option><option value="DISABLED">关闭</option></select></label>
          <div class="odds-grid wide">
            <label>A盘特码赔率<input v-model.number="form.oddsAte" type="number" min="0" step="0.01" /></label>
            <label>A盘大小单双<input v-model.number="form.oddsAdx" type="number" min="0" step="0.01" /></label>
            <label>B盘特码赔率<input v-model.number="form.oddsBte" type="number" min="0" step="0.01" /></label>
            <label>B盘大小单双<input v-model.number="form.oddsBdx" type="number" min="0" step="0.01" /></label>
            <label>C盘特码赔率<input v-model.number="form.oddsCte" type="number" min="0" step="0.01" /></label>
            <label>C盘大小单双<input v-model.number="form.oddsCdx" type="number" min="0" step="0.01" /></label>
            <label>D盘特码赔率<input v-model.number="form.oddsDte" type="number" min="0" step="0.01" /></label>
            <label>D盘大小单双<input v-model.number="form.oddsDdx" type="number" min="0" step="0.01" /></label>
          </div>
          <label class="switch-row"><span>是否切换</span><input v-model="form.switchEnabled" type="checkbox" /></label>
          <label class="switch-row"><span>显示特</span><input v-model="form.specialEnabled" type="checkbox" /></label>
          <label class="switch-row"><span>显示键盘</span><input v-model="form.keyboardEnabled" type="checkbox" /></label>
        </div>
        <footer><button type="button" @click="formOpen = false">取消</button><button type="submit" :disabled="saving">确认</button></footer>
      </form>
    </div>
  </main>
</template>

<style scoped>
.platform-admin-page{min-height:100vh;background:#eaf4fb;color:#1d1d1d}.content{padding:14px}.content header{display:flex;align-items:center;gap:8px}.content header div{margin-right:auto}.content h1{margin:0;font-size:24px}.content p{margin:4px 0 12px;color:#5f7280}.content button{padding:7px 12px;border:0;background:#1f7fb1;color:#fff;cursor:pointer}.table-wrap{overflow:auto;background:#fff}.table-wrap table{width:100%;min-width:1300px}table{border-collapse:collapse;font-size:13px}th{padding:9px;background:#40b2db;color:#fff}td{padding:8px;border-bottom:1px solid #d9e3e8;text-align:center}.url{max-width:240px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.ok{color:#177245}.off{color:#b42318}.actions{display:flex;gap:6px;justify-content:center}.actions button.danger{background:#d65a4a}.actions button:disabled{background:#98a1a8;cursor:not-allowed}.alert{padding:10px;background:#ffe1de;color:#a51e13}.feedback{padding:10px;background:#dff5e4;color:#176b3a}.hint{padding:10px;background:#fff9dd;color:#75621a}.empty{padding:30px;background:#fff;text-align:center}.modal-mask{position:fixed;inset:0;display:grid;place-items:center;background:rgba(0,0,0,.35);z-index:20}.modal{width:min(980px,calc(100vw - 24px));max-height:92vh;overflow:auto;background:#fff}.modal header,.modal footer{display:flex;justify-content:space-between;align-items:center;padding:12px 16px;background:#eef6fb}.modal h2{margin:0;font-size:18px}.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;padding:16px}.form-grid label{display:grid;gap:5px}.form-grid input,.form-grid select{padding:7px;border:1px solid #a8c8dc;font:inherit}.wide{grid-column:1/-1}.odds-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;padding:10px;background:#f4f9fc}.switch-row{display:flex;align-items:center;justify-content:space-between}.switch-row input{width:20px;height:20px}.modal footer{justify-content:flex-end;gap:8px}.modal footer button{background:#1f7fb1;color:#fff}.modal footer button:first-child{background:#fff;color:#1d1d1d;border:1px solid #98a1a8}@media(max-width:760px){.form-grid,.odds-grid{grid-template-columns:1fr}.wide{grid-column:auto}}
</style>
