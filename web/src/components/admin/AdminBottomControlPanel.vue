<script setup lang="ts">
import { ref } from 'vue'

const emit = defineEmits<{ raise: [] }>()

const closeReserveSeconds = ref(100)
const cancelSeconds = ref(10)
const rebate = ref(5)
const showRightCorner = ref(true)
const showCountdown = ref(true)
</script>

<template>
  <section class="admin-bottom-control" aria-label="封盘与显示控制区域">
    <div class="bottom-control-group">
      <label class="bottom-control-label" for="admin-close-reserve">预留封盘:</label>
      <input
        id="admin-close-reserve"
        v-model.number="closeReserveSeconds"
        class="bottom-control-input"
        type="number"
        min="0"
        step="1"
        inputmode="numeric"
      />
      <span class="bottom-control-unit">S</span>
      <label class="bottom-control-label bottom-control-spaced" for="admin-cancel-seconds">取消:</label>
      <input
        id="admin-cancel-seconds"
        v-model.number="cancelSeconds"
        class="bottom-control-input"
        type="number"
        min="0"
        step="1"
        inputmode="numeric"
      />
      <span class="bottom-control-unit">S</span>
      <label class="bottom-control-label bottom-control-spaced" for="admin-rebate">返水:</label>
      <input
        id="admin-rebate"
        v-model.number="rebate"
        class="bottom-control-input bottom-control-input-narrow"
        type="number"
        min="0"
        step="1"
        inputmode="numeric"
      />
    </div>

    <div class="bottom-control-switches">
      <div class="bottom-switch-row">
        <span class="bottom-control-label">显示右角:</span>
        <label class="bottom-switch-option">
          <input v-model="showRightCorner" type="radio" name="admin-show-right-corner" :value="true" />
          <span>开</span>
        </label>
        <label class="bottom-switch-option">
          <input v-model="showRightCorner" type="radio" name="admin-show-right-corner" :value="false" />
          <span>关</span>
        </label>
      </div>
      <div class="bottom-switch-row">
        <span class="bottom-control-label">倒计时:</span>
        <label class="bottom-switch-option">
          <input v-model="showCountdown" type="radio" name="admin-show-countdown" :value="true" />
          <span>开</span>
        </label>
        <label class="bottom-switch-option">
          <input v-model="showCountdown" type="radio" name="admin-show-countdown" :value="false" />
          <span>关</span>
        </label>
        <button class="bottom-control-raise" type="button" title="上移至线路域名上方" @click="emit('raise')">
          <i aria-hidden="true">↑</i>移上
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.admin-bottom-control {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 5px 14px;
  min-height: 36px;
  margin-top: 8px;
  overflow: hidden;
  border: 1px solid var(--ops-line-strong, #4b8ed3);
  border-radius: 2px;
  background: linear-gradient(#fff, var(--ops-panel-soft, #f3f8ff));
  color: var(--ops-text, #243b53);
  padding: 4px 8px;
  font-size: 12px;
  box-shadow: inset 0 0 0 1px rgb(255 255 255 / 78%);
}

.bottom-control-group {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}

.bottom-control-label {
  color: var(--ops-blue-deep, #15599d);
  font-weight: 800;
  white-space: nowrap;
}

.bottom-control-spaced {
  margin-left: 9px;
}

.bottom-control-input {
  width: 54px;
  height: 23px;
  border: 1px solid var(--ops-line, #8eb7e6);
  background: #fff;
  color: var(--ops-blue-deep, #15599d);
  padding: 0 4px;
  font: inherit;
  font-weight: 700;
  text-align: center;
}

.bottom-control-input-narrow {
  width: 42px;
}

.bottom-control-unit {
  color: var(--ops-blue-deep, #15599d);
  font-weight: 800;
  white-space: nowrap;
}

.bottom-control-switches {
  display: grid;
  gap: 2px;
  margin-left: auto;
}

.bottom-switch-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.bottom-switch-option {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: var(--ops-blue-deep, #15599d);
  white-space: nowrap;
  cursor: pointer;
}

.bottom-switch-option input {
  width: 13px;
  height: 13px;
  margin: 0;
  accent-color: var(--ops-blue, #2d7bcd);
}

.bottom-control-raise {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  height: 22px;
  margin-left: 2px;
  border: 1px solid var(--ops-line, #8eb7e6);
  background: linear-gradient(#fff, #e8f3ff);
  color: var(--ops-text, #243b53);
  padding: 0 6px;
  font: inherit;
  white-space: nowrap;
  cursor: pointer;
}

.bottom-control-raise i {
  font-style: normal;
}

button:focus-visible,
input:focus-visible {
  outline: 3px solid #9acff3;
  outline-offset: 2px;
}

@media (max-width: 760px) {
  .admin-bottom-control {
    align-items: flex-start;
  }

  .bottom-control-spaced {
    margin-left: 0;
  }

  .bottom-control-switches {
    margin-left: 0;
  }
}
</style>
