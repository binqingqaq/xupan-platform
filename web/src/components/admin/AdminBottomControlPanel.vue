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
  gap: 10px 18px;
  min-height: 62px;
  margin-top: 14px;
  border: 1px solid #6ba0d5;
  background: #fff;
  color: #23506f;
  padding: 7px 12px;
  font-size: 12px;
}

.bottom-control-group {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.bottom-control-label {
  color: #23506f;
  font-weight: 800;
  white-space: nowrap;
}

.bottom-control-spaced {
  margin-left: 14px;
}

.bottom-control-input {
  width: 62px;
  height: 28px;
  border: 1px solid #2e8bd0;
  background: #f2faff;
  color: #1d6da8;
  padding: 0 6px;
  font: inherit;
  font-weight: 700;
  text-align: center;
}

.bottom-control-input-narrow {
  width: 48px;
}

.bottom-control-unit {
  color: #23506f;
  font-weight: 800;
  white-space: nowrap;
}

.bottom-control-switches {
  display: grid;
  gap: 4px;
  margin-left: auto;
}

.bottom-switch-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.bottom-switch-option {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: #1e79bb;
  white-space: nowrap;
  cursor: pointer;
}

.bottom-switch-option input {
  width: 14px;
  height: 14px;
  margin: 0;
  accent-color: #208fd4;
}

.bottom-control-raise {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  height: 24px;
  margin-left: 4px;
  border: 1px solid #8faec1;
  background: #f4f8fb;
  color: #35647f;
  padding: 0 7px;
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
    padding: 8px;
  }

  .bottom-control-spaced {
    margin-left: 0;
  }

  .bottom-control-switches {
    margin-left: 0;
  }
}
</style>
