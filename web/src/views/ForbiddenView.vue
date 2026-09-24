<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <main class="auth-page">
    <section class="auth-panel" aria-labelledby="forbidden-title">
      <p class="auth-eyebrow">AI模型房间管理</p>
      <h1 id="forbidden-title">无权访问</h1>
      <p class="auth-message">当前账号没有访问此页面的权限。</p>
      <button class="auth-submit" type="button" @click="router.replace('/room')">返回聊天室</button>
    </section>
  </main>
</template>
