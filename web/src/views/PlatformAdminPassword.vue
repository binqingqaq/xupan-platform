<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, apiErrorMessage } from '../api'
import { adminNotice } from '../services/adminNotice'
import PlatformAdminNav from '../components/platform-admin/PlatformAdminNav.vue'
import type { PlatformPasswordChangeInput } from '../types/platformAdmin'

const router = useRouter()
const currentUser = ref('超级管理员')
const currentUsername = ref('')
const canChangeUsername = ref(false)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
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
  try {
    await api.changePlatformPassword(form.value)
    adminNotice.success('修改成功, 请重新登录!')
    window.setTimeout(async () => {
      try {
        await api.logout()
      } finally {
        await router.replace('/platform-admin/login')
      }
    }, 1500)
  } catch (cause) {
    adminNotice.error(apiErrorMessage(cause, '修改失败'))
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
      <header class="page-heading"><h1>修改密码</h1></header>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="page-alert" />
      <el-skeleton v-if="loading" :rows="4" animated />
      <el-form v-else class="password-form" label-position="top" @submit.prevent="submit">
        <el-form-item label="旧密码"><el-input v-model="form.oldPassword" type="password" show-password placeholder="请输入旧密码" autocomplete="off" /></el-form-item>
        <el-form-item label="新密码"><el-input v-model="form.newPassword" type="password" show-password placeholder="请输入新密码" autocomplete="off" /></el-form-item>
        <el-form-item label="确认密码"><el-input v-model="form.confirmPassword" type="password" show-password placeholder="请再次输入新密码" autocomplete="off" /></el-form-item>
        <el-form-item v-if="canChangeUsername" label="登录账号"><el-input v-model="form.username" type="text" :placeholder="`如修改请输入，当前：${currentUsername}`" autocomplete="off" /></el-form-item>
        <div class="form-actions"><el-button type="primary" native-type="submit" :loading="saving">提交</el-button></div>
      </el-form>
    </section>
  </main>
</template>

<style scoped>
.content { display: grid; gap: 14px; padding: 18px; }
.page-heading h1 { margin: 0; font-size: 26px; }
.page-alert { margin-bottom: 2px; }
.password-form { display: grid; gap: 4px; max-width: 520px; }
.form-actions { display: flex; justify-content: flex-end; padding-top: 8px; }
@media (max-width: 700px) { .content { padding: 12px; } }
</style>
