<script setup lang="ts">
defineProps<{ sessionExpired?: boolean }>()
</script>

<template>
  <main class="auth-page player-link-page">
    <section class="auth-panel" aria-labelledby="player-link-expired-title">
      <p class="auth-eyebrow">奥巴AI</p>
      <h1 id="player-link-expired-title">{{ sessionExpired ? '玩家会话已失效' : '玩家链接已失效' }}</h1>
      <p class="auth-message">{{ sessionExpired ? '当前玩家会话无法恢复，请联系管理员重新获取玩家链接。' : '该链接已过期、被拉黑或已被刷新，请联系管理员获取新的玩家链接。' }}</p>
    </section>
  </main>
</template>
