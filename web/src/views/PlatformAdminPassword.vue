<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, apiErrorMessage } from '../api'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import type { PlatformPasswordChangeInput } from '../types/platformAdmin'

const router = useRouter()
const currentUser = ref('超级管理员')
const currentUsername = ref('')
const canChangeUsername = ref(false)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const feedback = ref('')
const form = ref<PlatformPasswordChangeInput>({
  username: '',
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [me, passwordForm] = await Promise.all([api.me(), api.getPlatformPasswordForm()])
    currentUser.value = me.displayName || me.username || '超级管理员'
    currentUsername.value = passwordForm.username
    canChangeUsername.value = passwordForm.canChangeUsername
  } catch (cause) {
    error.value = apiErrorMessage(cause, '修改密码信息加载失败')
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (saving.value) return
  saving.value = true
  error.value = ''
  feedback.value = ''
  try {
    await api.changePlatformPassword(form.value)
    feedback.value = '修改成功, 请重新登录!'
    window.setTimeout(async () => {
      try {
        await api.logout()
      } finally {
        await router.replace('/platform-admin/login')
      }
    }, 1500)
  } catch (cause) {
    error.value = apiErrorMessage(cause, '修改失败')
  } finally {
    saving.value = false
  }
}

onMounted(() => { document.title = '修改密码'; void load() })
</script>

<template>
  <main class="platform-admin-page">
    <PlatformAdminNav :display-name="currentUser" />
    <section class="content">
      <header><div><h1>修改密码</h1><p>对应 BY220 admin.php - 修改密码</p></div></header>
      <p v-if="error" class="alert">{{ error }}</p>
      <p v-if="feedback" class="feedback">{{ feedback }}</p>
      <div v-if="loading" class="empty">正在加载...</div>
      <form v-else class="password-form" @submit.prevent="submit">
        <label>旧密码<input v-model="form.oldPassword" type="password" placeholder="请输入旧密码" autocomplete="off" /></label>
        <label>新密码<input v-model="form.newPassword" type="password" placeholder="请输入新密码" autocomplete="off" /></label>
        <label>确认密码<input v-model="form.confirmPassword" type="password" placeholder="请再次输入新密码" autocomplete="off" /></label>
        <label v-if="canChangeUsername">登录账号<input v-model="form.username" type="text" :placeholder="`如修改请输入，当前：${currentUsername}`" autocomplete="off" /></label>
        <div class="actions"><button type="submit" :disabled="saving">{{ saving ? '提交中...' : '提交' }}</button></div>
      </form>
    </section>
  </main>
</template>

<style scoped>
.platform-admin-page{min-height:100vh;background:#eaf4fb;color:#1d1d1d}.content{padding:14px}.content h1{margin:0;font-size:24px}.content p{margin:4px 0 12px;color:#5f7280}.password-form{display:grid;gap:12px;max-width:720px;padding:18px;border:1px solid #d6e4ec;background:#fff}.password-form label{display:grid;grid-template-columns:110px 1fr;align-items:center;gap:12px}.password-form input{padding:9px;border:1px solid #a8c8dc;font:inherit}.actions{padding-left:122px}.actions button{padding:8px 18px;border:1px solid #7da9c1;background:#fff;cursor:pointer}.actions button:disabled{color:#98a1a8;cursor:not-allowed}.alert{padding:10px;background:#ffe1de}.feedback{padding:10px;background:#dff5e4;color:#166b2d}.empty{padding:30px;background:#fff;text-align:center}@media(max-width:700px){.password-form label{grid-template-columns:1fr}.actions{padding-left:0}}
</style>
