<script setup lang="ts">
withDefaults(defineProps<{
  points?: string
  status?: string
  createdAt?: string
  lastLoginAt?: string
}>(), {
  points: '--',
  status: '--',
  createdAt: '--',
  lastLoginAt: '--',
})
</script>

<template>
  <div class="player-summary-backup-grid">
    <div><span>当前积分</span><strong class="points">{{ points }}</strong></div>
    <div><span>状态</span><strong>{{ status }}</strong></div>
    <div><span>创建时间</span><strong>{{ createdAt }}</strong></div>
    <div><span>最后登录</span><strong>{{ lastLoginAt }}</strong></div>
  </div>
</template>

<style scoped>
.player-summary-backup-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  border: 1px solid #8eb7e6;
  background: #8eb7e6;
}

.player-summary-backup-grid > div {
  min-width: 0;
  min-height: 52px;
  background: #fff;
  padding: 7px 9px;
}

.player-summary-backup-grid span {
  display: block;
  margin-bottom: 4px;
  color: #687e91;
  font-size: 10px;
}

.player-summary-backup-grid strong {
  display: block;
  overflow-wrap: anywhere;
  color: #315774;
  font-size: 12px;
}

.player-summary-backup-grid .points {
  color: #145b8e;
  font-size: 15px;
  font-variant-numeric: tabular-nums;
}

@media (max-width: 760px) {
  .player-summary-backup-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
