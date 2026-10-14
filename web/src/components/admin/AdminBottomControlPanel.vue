<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  MACHINE_CONFIG_UNAVAILABLE_HINT,
  MACHINE_ONLY_FIELD_HINT,
  createMachineConfigConsoleStore,
  createMachineControlDraft,
  validateMachineControlDraft,
  type MachineConfigConsoleHandle,
  type MachineControlDraft,
} from '../../machineConfigConsole'
import type { PlayerDeskScope } from '../../types'

/**
 * 底部控制区（BY220 `robot.php`：预留封盘 / 取消 / 返水）。
 *
 * 这三项都是**机器自己的值**（`agent.close_seconds/cancel_seconds/rebate_rate`），
 * 因此只读写在机器上下文下的机器配置；"全部机器"或身份范围未就绪时禁用并给出提示，
 * 不再用前端假值伪装成已生效。显示类开关（显示右角/倒计时）等阶段 4 建字段后再接入。
 */
const props = defineProps<{
  /** `/console` 顶部状态栏与底部控制区共用同一个机器配置句柄。 */
  machineConsole?: MachineConfigConsoleHandle
  scope?: PlayerDeskScope | null
  machineId?: number | null
}>()

const emit = defineEmits<{ raise: [] }>()

const ownMachineConsole = props.machineConsole === undefined
  ? createMachineConfigConsoleStore(() => props.scope ?? null, () => props.machineId ?? null)
  : null
const machineConsole: MachineConfigConsoleHandle | null = props.machineConsole ?? ownMachineConsole

const draft = ref<MachineControlDraft>({ closeSeconds: '', cancelSeconds: '', rebateRate: '' })
const notice = ref('')
const error = ref('')
const saving = ref(false)

const showRightCorner = ref(true)
const showCountdown = ref(true)

const level = computed(() => machineConsole?.level ?? 'UNAVAILABLE')
const machineConfig = computed(() => machineConsole?.config ?? null)
const machineOnly = computed(() => level.value !== 'MACHINE' || machineConfig.value === null)
const disabled = computed(() => machineOnly.value || saving.value || Boolean(machineConsole?.saving))
const hintText = computed(() => {
  if (level.value === 'GLOBAL') return MACHINE_ONLY_FIELD_HINT
  if (level.value === 'UNAVAILABLE') return MACHINE_CONFIG_UNAVAILABLE_HINT
  return ''
})

watch(machineConfig, next => {
  if (next) draft.value = createMachineControlDraft(next)
}, { immediate: true })

function setNotice(value: string) {
  notice.value = value
  error.value = ''
}

function setError(value: string) {
  error.value = value
  notice.value = ''
}

async function commit() {
  const handle = machineConsole
  const current = machineConfig.value
  if (!handle || !current || disabled.value) return
  const parsed = validateMachineControlDraft(draft.value)
  if (!parsed.ok) {
    setError(parsed.message)
    draft.value = createMachineControlDraft(current)
    return
  }
  const unchanged = parsed.patch.closeSeconds === current.closeSeconds
    && parsed.patch.cancelSeconds === current.cancelSeconds
    && parsed.patch.rebateRate === current.rebateRate
  if (unchanged) return
  saving.value = true
  const saved = await handle.save(parsed.patch)
  saving.value = false
  if (saved) {
    setNotice('已自动保存')
    return
  }
  // 保存失败时不显示成功，并回退到服务端当前值。
  setError(handle.error || '机器配置保存失败，请稍后重试')
  draft.value = createMachineControlDraft(machineConfig.value ?? current)
}
</script>

<template>
  <section class="admin-bottom-control" aria-label="封盘与显示控制区域">
    <div class="bottom-control-group">
      <label class="bottom-control-label" for="admin-close-reserve">预留封盘:</label>
      <input
        id="admin-close-reserve"
        v-model="draft.closeSeconds"
        class="bottom-control-input"
        type="text"
        inputmode="numeric"
        autocomplete="off"
        :disabled="disabled"
        @change="commit"
        @keyup.enter="commit"
        @blur="commit"
      />
      <span class="bottom-control-unit">S</span>
      <label class="bottom-control-label bottom-control-spaced" for="admin-cancel-seconds">取消:</label>
      <input
        id="admin-cancel-seconds"
        v-model="draft.cancelSeconds"
        class="bottom-control-input"
        type="text"
        inputmode="numeric"
        autocomplete="off"
        :disabled="disabled"
        @change="commit"
        @keyup.enter="commit"
        @blur="commit"
      />
      <span class="bottom-control-unit">S</span>
      <label class="bottom-control-label bottom-control-spaced" for="admin-rebate">返水:</label>
      <input
        id="admin-rebate"
        v-model="draft.rebateRate"
        class="bottom-control-input bottom-control-input-narrow"
        type="text"
        inputmode="decimal"
        autocomplete="off"
        :disabled="disabled"
        @change="commit"
        @keyup.enter="commit"
        @blur="commit"
      />
      <span v-if="notice" class="bottom-control-notice" role="status">{{ notice }}</span>
      <span v-if="error" class="bottom-control-error" role="alert">{{ error }}</span>
      <span v-else-if="hintText" class="bottom-control-hint">{{ hintText }}</span>
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
  position: relative;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 5px 14px;
  min-height: 36px;
  margin-top: 8px;
  overflow: visible;
  border: 0;
  border-radius: 0;
  background: linear-gradient(#fff, var(--ops-panel-soft, #f3f8ff));
  color: var(--ops-text, #243b53);
  padding: 4px 8px;
  font-size: 12px;
  box-shadow: none;
}

.admin-bottom-control::before {
  position: absolute;
  top: 0;
  right: -6px;
  left: -6px;
  height: 1px;
  background: var(--ops-line-strong, #4b8ed3);
  content: '';
  pointer-events: none;
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

.bottom-control-input:disabled {
  background: #eef4f9;
  color: #93aec2;
}

.bottom-control-input-narrow {
  width: 42px;
}

.bottom-control-unit {
  color: var(--ops-blue-deep, #15599d);
  font-weight: 400;
  white-space: nowrap;
}

.bottom-control-notice {
  color: var(--ops-green, #23845f);
  white-space: nowrap;
}

.bottom-control-error {
  color: var(--ops-red, #d94747);
  white-space: nowrap;
}

.bottom-control-hint {
  color: var(--ops-muted, #6d8093);
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
  width: 20px;
  height: auto;
  margin: 0;
  accent-color: #409eff;
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
