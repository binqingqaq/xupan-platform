<template>
  <section class="stashed-bot-actions" aria-label="托操作暂存">
    <div class="stashed-action-row">
      <strong>立即执行</strong>
      <button type="button" disabled>立即执行</button>
    </div>
    <p>原功能为强制执行当前期尚未执行的托动作，恢复时需要绑定选中的托玩家。</p>
    <div class="stashed-message-row">
      <label>手动发送测试消息<input type="text" disabled placeholder="例如：大家好，或 1番100" /></label>
      <button type="button" disabled>发送</button>
    </div>
    <p>原功能为管理员手动向选定托发送一条测试消息，恢复时需要绑定选中的托玩家。</p>
  </section>
</template>

<style scoped>
.stashed-bot-actions {
  display: grid;
  gap: 8px;
}

.stashed-action-row,
.stashed-message-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.stashed-action-row {
  justify-content: space-between;
  border: 1px solid #9fc3e2;
  background: #f4f9fe;
  padding: 7px 9px;
}

.stashed-action-row strong,
.stashed-bot-actions p {
  color: #527088;
  font-size: 11px;
}

.stashed-bot-actions p {
  margin: 0;
  line-height: 1.5;
}

.stashed-message-row label {
  display: grid;
  flex: 1 1 auto;
  gap: 4px;
  color: #527088;
  font-size: 11px;
}

.stashed-message-row input {
  min-width: 0;
  height: 27px;
  border: 1px solid #8eb7e6;
  border-radius: 2px;
  background: #f5f8fb;
  padding: 0 7px;
}

.stashed-action-row button,
.stashed-message-row button {
  min-height: 27px;
  border: 1px solid #8eb7e6;
  border-radius: 2px;
  background: #eef5fb;
  color: #7890a2;
  padding: 0 12px;
}
</style>
