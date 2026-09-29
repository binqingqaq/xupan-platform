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
.admin-placeholder-panel {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--ops-line, #8eb7e6);
  border-radius: 2px;
  background: var(--ops-panel, #fff);
  color: var(--ops-muted, #6d8093);
  box-shadow: inset 0 0 0 1px rgb(255 255 255 / 72%);
}

.admin-placeholder-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 34px;
  border-bottom: 1px solid var(--ops-line, #8eb7e6);
  background: linear-gradient(#f4fbff, var(--ops-header, #dceeff));
  padding: 0 9px;
}

.admin-placeholder-heading h2 {
  margin: 0;
  color: var(--ops-blue-deep, #15599d);
  font-size: 13px;
}

.admin-placeholder-heading span {
  color: var(--ops-muted, #6d8093);
  font-size: 11px;
  white-space: nowrap;
}

.admin-placeholder-content {
  display: grid;
  min-height: 132px;
  place-content: center;
  gap: 5px;
  background:
    repeating-linear-gradient(0deg, transparent 0 23px, rgb(91 137 183 / 5%) 23px 24px),
    #fff;
  padding: 14px;
  text-align: center;
}

.admin-placeholder-content strong {
  color: var(--ops-blue-deep, #15599d);
  font-size: 13px;
  font-weight: 700;
}

.admin-placeholder-content p {
  margin: 0;
  color: var(--ops-muted, #6d8093);
  font-size: 12px;
}
</style>
