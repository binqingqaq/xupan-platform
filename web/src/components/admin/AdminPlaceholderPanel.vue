<script setup lang="ts">
withDefaults(defineProps<{
  title: string
  description?: string
  status?: string
}>(), {
  description: '该区域将在后续模块中接入',
  status: '后续接入',
})
</script>

<template>
  <section class="admin-placeholder-panel" aria-disabled="true">
    <div class="admin-placeholder-heading">
      <h2>{{ title }}</h2>
      <span>{{ status }}</span>
    </div>
    <div class="admin-placeholder-content">
      <strong>暂未接入</strong>
      <p>{{ description }}</p>
    </div>
  </section>
</template>

<style scoped>
.admin-placeholder-panel { min-width: 0; border: 1px dashed #b9d8ec; background: #fbfdfe; color: #7890a0; }
.admin-placeholder-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 52px; border-bottom: 1px solid #deebf3; padding: 0 16px; }
.admin-placeholder-heading h2 { margin: 0; color: #55758a; font-size: 14px; }
.admin-placeholder-heading span { color: #91a6b4; font-size: 12px; white-space: nowrap; }
.admin-placeholder-content { display: grid; min-height: 150px; place-content: center; gap: 6px; padding: 18px; text-align: center; }
.admin-placeholder-content strong { color: #7897aa; font-size: 14px; font-weight: 700; }
.admin-placeholder-content p { margin: 0; color: #9aadb9; font-size: 12px; }
</style>
