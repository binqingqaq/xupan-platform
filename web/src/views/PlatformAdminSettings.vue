<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, apiErrorMessage } from '../api'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import type { PlatformSettings, PlatformSettingsInput } from '../types/platformAdmin'

const form = ref<PlatformSettingsInput>({
  siteTitle: '',
  announcement: '',
  domainLinks: '',
  chatWarning: '',
  information: '',
  headerEnabled: true,
  statusBarEnabled: true,
  keyboardMode: false,
  version: 0,
})
const loading = ref(false)
const saving = ref(false)
const dangerSaving = ref(false)
const error = ref('')
const feedback = ref('')
const currentUser = ref('超级管理员')
const clearTime = ref('')

function applySettings(settings: PlatformSettings) {
  form.value = {
    siteTitle: settings.siteTitle,
    announcement: settings.announcement || '',
    domainLinks: settings.domainLinks || '',
    chatWarning: settings.chatWarning || '',
    information: settings.information || '',
    headerEnabled: settings.headerEnabled,
    statusBarEnabled: settings.statusBarEnabled,
    keyboardMode: settings.keyboardMode,
    version: settings.version,
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    applySettings(await api.getPlatformSettings())
  } catch (cause) {
    error.value = apiErrorMessage(cause, '设置加载失败')
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  error.value = ''
  feedback.value = ''
  try {
    applySettings(await api.updatePlatformSettings(form.value))
    feedback.value = '保存成功！'
  } catch (cause) {
    error.value = apiErrorMessage(cause, '设置保存失败')
  } finally {
    saving.value = false
  }
}

async function deleteAllAccounts() {
  const confirm = window.prompt('此操作会删除除超级管理员和主机器人外的全部账号。请输入 DELETE_ALL_ACCOUNTS 确认：')
  if (confirm !== 'DELETE_ALL_ACCOUNTS') {
    if (confirm !== null) feedback.value = '确认文本不正确，已取消'
    return
  }
  dangerSaving.value = true
  error.value = ''
  feedback.value = ''
  try {
    const preview = await api.previewDeleteAllAccounts(confirm)
    const message = `将删除子账号 ${preview.counts.admins} 个、机器 ${preview.counts.robots} 个、玩家/托 ${preview.counts.players} 个，是否继续？`
    if (!window.confirm(message)) return
    const result = await api.deleteAllAccounts(confirm)
    feedback.value = result.message
    await load()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '全量账号删除失败')
  } finally {
    dangerSaving.value = false
  }
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
      <header><div><h1>设置</h1><p>对应 BY220 admin.php - 设置</p></div></header>
      <p v-if="error" class="alert">{{ error }}</p>
      <p v-if="feedback" class="feedback">{{ feedback }}</p>
      <div v-if="loading" class="empty">正在加载...</div>
      <form v-else class="settings-form" @submit.prevent="save">
        <section class="danger-zone">
          <h2>数据操作</h2>
          <div class="inline-row">
            <label>时间<input v-model="clearTime" type="date" /></label>
            <button type="button" class="danger" disabled title="需要先完成备份、预检和删除专项">清空数据</button>
          </div>
          <div class="inline-row">
            <strong>危险操作</strong>
            <button type="button" class="danger" :disabled="dangerSaving" @click="deleteAllAccounts">{{ dangerSaving ? '处理中...' : '删除全量账号' }}</button>
            <span>仅保留当前超级管理员和主机器人账号；按 BY220 先预检，再输入确认文本执行软删除。</span>
          </div>
        </section>

        <label>群聊标题<input v-model="form.siteTitle" required maxlength="128" /></label>
        <label>公告<textarea v-model="form.announcement" rows="4" maxlength="4000" /></label>
        <label>域名链接<textarea v-model="form.domainLinks" rows="4" maxlength="4000" placeholder="每行一个链接" /></label>
        <label>群聊警告<textarea v-model="form.chatWarning" rows="4" maxlength="2000" /></label>

        <div class="switch-grid">
          <label class="switch-row"><span>头部导航</span><input v-model="form.headerEnabled" type="checkbox" /><strong>{{ form.headerEnabled ? '显示' : '隐藏' }}</strong></label>
          <label class="switch-row"><span>游戏状态栏</span><input v-model="form.statusBarEnabled" type="checkbox" /><strong>{{ form.statusBarEnabled ? '显示' : '隐藏' }}</strong></label>
          <label class="switch-row"><span>键盘模式</span><input v-model="form.keyboardMode" type="checkbox" /><strong>{{ form.keyboardMode ? '双键' : '单键' }}</strong></label>
        </div>

        <label>信息<textarea v-model="form.information" rows="6" maxlength="4000" /></label>
        <p class="hint">公告、域名链接、群聊警告和信息按 BY220 后台真实保存；当前前台模板未发现这些字段的消费位置，因此不伪造显示效果。</p>
        <footer><button type="submit" :disabled="saving">{{ saving ? '保存中...' : '保存' }}</button></footer>
      </form>
    </section>
  </main>
</template>

<style scoped>
.platform-admin-page{min-height:100vh;background:#eaf4fb;color:#1d1d1d}.content{padding:14px}.content h1{margin:0;font-size:24px}.content p{margin:4px 0 12px;color:#5f7280}.settings-form{display:grid;gap:14px;max-width:900px}.settings-form>label{display:grid;gap:6px;padding:12px;background:#fff}.settings-form input,.settings-form textarea{padding:8px;border:1px solid #a8c8dc;font:inherit}.danger-zone{display:grid;gap:10px;padding:14px;background:#fff3f1;border:1px solid #f0b4aa}.danger-zone h2{margin:0;font-size:18px;color:#b3261e}.inline-row{display:flex;flex-wrap:wrap;align-items:end;gap:10px}.inline-row label{display:grid;gap:5px}.inline-row span{color:#8b4b43}.danger{padding:8px 12px;border:1px solid #d65a4a;background:#fff;color:#b3261e}.danger:disabled,.settings-form footer button:disabled{color:#98a1a8;cursor:not-allowed}.switch-grid{display:grid;gap:10px}.switch-row{display:flex;align-items:center;justify-content:space-between;padding:12px;background:#fff}.switch-row input{width:20px;height:20px}.hint{padding:10px;background:#fff9dd;color:#75621a}.alert{padding:10px;background:#ffe1de}.feedback{padding:10px;background:#dff5e4;color:#166b2d}.empty{padding:30px;background:#fff;text-align:center}.settings-form footer{display:flex;justify-content:flex-end}.settings-form footer button{padding:9px 18px;border:1px solid #7da9c1;background:#fff;cursor:pointer}
</style>
