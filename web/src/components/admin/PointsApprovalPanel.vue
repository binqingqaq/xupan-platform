<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { api, apiErrorMessage } from '../../api'
import {
  findNewPendingRequestIds,
  pointRequestLabel,
  readPointSoundPreference,
  writePointSoundPreference,
  type PointSoundChoice,
} from '../../pointsAdmin'
import type { PendingPointRequest } from '../../types'

const emit = defineEmits<{ pointsChanged: [] }>()

const requests = ref<PendingPointRequest[]>([])
const loading = ref(true)
const error = ref('')
const notice = ref('')
const processingId = ref<number | null>(null)
const confirmTarget = ref<PendingPointRequest | null>(null)
const soundChoice = ref<PointSoundChoice>(readPointSoundPreference(window.localStorage))
const soundReady = ref(false)
const soundTestActive = ref(false)

const seenRequestIds = new Set<number>()
let initialized = false
let pollTimer: ReturnType<typeof setInterval> | undefined
let noticeTimer: ReturnType<typeof setTimeout> | undefined
let audioContext: AudioContext | null = null
let loadSequence = 0

function setNotice(value: string) {
  notice.value = value
  if (noticeTimer) clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => { notice.value = '' }, 3200)
}

function selectSound(choice: PointSoundChoice) {
  soundChoice.value = choice
  writePointSoundPreference(window.localStorage, choice)
}

async function ensureAudioContext() {
  if (!audioContext) audioContext = new AudioContext()
  if (audioContext.state === 'suspended') await audioContext.resume()
  soundReady.value = true
  return audioContext
}

async function playTone(choice: PointSoundChoice) {
  const context = await ensureAudioContext()
  const tones = choice === 'sound2'
    ? [{ frequency: 440, offset: 0, duration: 0.16 }, { frequency: 330, offset: 0.11, duration: 0.2 }]
    : [{ frequency: 880, offset: 0, duration: 0.11 }, { frequency: 1320, offset: 0.08, duration: 0.15 }]
  const base = context.currentTime + 0.01
  for (const tone of tones) {
    const oscillator = context.createOscillator()
    const gain = context.createGain()
    const start = base + tone.offset
    const end = start + tone.duration
    oscillator.type = choice === 'sound2' ? 'triangle' : 'sine'
    oscillator.frequency.setValueAtTime(tone.frequency, start)
    gain.gain.setValueAtTime(0.0001, start)
    gain.gain.exponentialRampToValueAtTime(0.18, start + 0.012)
    gain.gain.exponentialRampToValueAtTime(0.0001, end)
    oscillator.connect(gain)
    gain.connect(context.destination)
    oscillator.start(start)
    oscillator.stop(end + 0.01)
  }
}

async function testSound() {
  soundTestActive.value = !soundTestActive.value
  try {
    await playTone(soundChoice.value)
  } catch {}
}

async function loadRequests(showLoading = false) {
  const sequence = ++loadSequence
  if (showLoading) loading.value = true
  try {
    const next = await api.getPendingPointRequests()
    if (sequence !== loadSequence) return
    const isInitialLoad = !initialized
    const newIds = isInitialLoad ? [] : findNewPendingRequestIds(seenRequestIds, next)
    requests.value = next
    seenRequestIds.clear()
    next.forEach(request => seenRequestIds.add(request.id))
    initialized = true
    error.value = ''
    if (!isInitialLoad && newIds.length) {
      if (soundReady.value) {
        try {
          await playTone(soundChoice.value)
        } catch {}
      }
    }
  } catch (cause) {
    if (sequence !== loadSequence) return
    error.value = apiErrorMessage(cause, '待审批上下分加载失败')
  } finally {
    if (showLoading) loading.value = false
  }
}

function openApproval(request: PendingPointRequest) {
  confirmTarget.value = request
}

function closeApproval() {
  if (processingId.value !== null) return
  confirmTarget.value = null
}

async function approveRequest() {
  const target = confirmTarget.value
  if (!target || processingId.value !== null) return
  processingId.value = target.id
  try {
    await api.approvePointRequest(target.id, '后台聊天上下分申请批准')
    confirmTarget.value = null
    setNotice(target.requestType === 'TOP_UP' ? '上分成功' : '下分成功')
    emit('pointsChanged')
    await loadRequests()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '积分申请处理失败，请刷新后重试')
    confirmTarget.value = null
    await loadRequests()
  } finally {
    processingId.value = null
  }
}

async function rejectRequest(request: PendingPointRequest) {
  if (processingId.value !== null) return
  processingId.value = request.id
  try {
    await api.rejectPointRequest(request.id, '后台取消聊天上下分申请')
    setNotice('已取消该申请')
    await loadRequests()
  } catch (cause) {
    error.value = apiErrorMessage(cause, '取消申请失败，请刷新后重试')
    await loadRequests()
  } finally {
    processingId.value = null
  }
}

onMounted(() => {
  void loadRequests(true)
  pollTimer = setInterval(() => { void loadRequests() }, 3000)
})

onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
  if (noticeTimer) clearTimeout(noticeTimer)
  if (audioContext) void audioContext.close()
})
</script>

<template>
  <section class="points-approval-panel" aria-label="上下分确认">
    <header class="panel-heading">
      <div class="panel-title">
        <h2>上下分确认</h2>
      </div>
      <div class="sound-controls">
        <button
          class="sound-test-button"
          :class="{ 'is-active': soundTestActive }"
          type="button"
          @click="testSound"
        >测试提示音</button>
        <label><input type="radio" name="point-sound" :checked="soundChoice === 'sound1'" @change="selectSound('sound1')" />声音1</label>
        <label><input type="radio" name="point-sound" :checked="soundChoice === 'sound2'" @change="selectSound('sound2')" />声音2</label>
      </div>
    </header>

    <div class="panel-body">
      <p v-if="notice" class="panel-notice" role="status">{{ notice }}</p>
      <p v-if="error" class="panel-error" role="alert">{{ error }}</p>

      <div v-if="loading" class="panel-state">正在加载待处理申请...</div>
      <div v-else-if="!requests.length" class="panel-state panel-empty"></div>
      <ul v-else class="request-list">
        <li v-for="request in requests" :key="request.id" class="request-row">
          <div class="request-label">
            <span class="request-text">{{ request.memberCode }}：{{ request.requestType === 'TOP_UP' ? '上分 +' : '下分 -' }}{{ Number(request.amount) }} ({{ request.displayName }})</span>
          </div>
          <div class="request-actions">
            <button class="approve-button" type="button" :disabled="processingId !== null" @click="openApproval(request)">同意</button>
            <button class="cancel-button" type="button" :disabled="processingId !== null" @click="rejectRequest(request)">取消</button>
          </div>
        </li>
      </ul>
    </div>

    <Teleport to="body">
      <div v-if="confirmTarget" class="point-confirm-layer" role="presentation">
        <section class="point-confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="point-confirm-title">
          <h3 id="point-confirm-title">确认积分操作</h3>
          <p>是否确认{{ pointRequestLabel(confirmTarget) }}？</p>
          <p class="confirm-note">确认后余额和积分流水会立即更新，取消只关闭当前弹窗。</p>
          <div class="confirm-actions">
            <button class="cancel-button" type="button" :disabled="processingId !== null" @click="closeApproval">取消</button>
            <button class="approve-button" type="button" :disabled="processingId !== null" @click="approveRequest">
              {{ processingId === confirmTarget.id ? '处理中...' : '确认' }}
            </button>
          </div>
        </section>
      </div>
    </Teleport>
  </section>
</template>

<style scoped>
.points-approval-panel {
  min-width: 0;
  min-height: 300px;
  overflow: hidden;
  border: 1px solid var(--ops-line-strong, #4b8ed3);
  border-radius: 2px;
  background: var(--ops-panel, #fff);
  color: var(--ops-text, #243b53);
  box-shadow: inset 0 0 0 1px rgb(255 255 255 / 78%);
}

.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 36px;
  border-bottom: 1px solid var(--ops-line, #8eb7e6);
  background: linear-gradient(#f4fbff, var(--ops-header, #dceeff));
  padding: 5px 8px;
}

.panel-heading > .panel-title {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
  min-width: max-content;
  white-space: nowrap;
}

.panel-heading h2 {
  margin: 0;
  color: var(--ops-blue-deep, #15599d);
  font-size: 13px;
  white-space: nowrap;
}

.sound-controls {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  flex: 0 1 auto;
  justify-content: flex-end;
  gap: 7px;
  color: var(--ops-text, #243b53);
  font-size: 11px;
}

.sound-controls label {
  display: flex;
  align-items: center;
  gap: 3px;
  min-height: 24px;
  cursor: pointer;
}

.sound-controls input {
  width: 20px;
  height: auto;
  margin: 0 3px 0 5px;
}

.sound-test-button {
  min-height: 24px;
  border: 1px solid var(--ops-line, #8eb7e6);
  border-radius: 2px;
  background: linear-gradient(#fff, #eaf4ff);
  color: var(--ops-blue-deep, #15599d);
  padding: 0 8px;
  font-weight: 700;
  cursor: pointer;
}

.panel-body {
  min-height: 246px;
  background:
    repeating-linear-gradient(0deg, transparent 0 23px, rgb(91 137 183 / 5%) 23px 24px),
    #fff;
  padding: 7px 8px 8px;
}

.panel-notice,
.panel-error {
  margin: 0 0 7px;
  border: 1px solid var(--ops-line, #8eb7e6);
  border-radius: 2px;
  background: #f1f8fe;
  color: var(--ops-blue-deep, #15599d);
  padding: 5px 7px;
  font-size: 11px;
}

.panel-error {
  border-color: #efb6b6;
  background: #fff5f5;
  color: var(--ops-red, #d94747);
}

.panel-state {
  display: grid;
  min-height: 210px;
  place-content: center;
  gap: 6px;
  color: var(--ops-muted, #6d8093);
  text-align: center;
  font-size: 12px;
}

.panel-empty strong {
  color: var(--ops-text, #243b53);
}

.panel-empty span {
  font-size: 11px;
}

.request-list {
  display: grid;
  gap: 0;
  max-height: 360px;
  overflow-y: auto;
  margin: 0;
  padding: 0;
  list-style: none;
}

.request-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0;
  height: 30px;
  min-height: 30px;
  border: 0;
  border-radius: 0;
  background: #fff;
  padding: 0;
  line-height: 30px;
}

.request-row:nth-child(even) { background: #f5f5f5; }
:global(body:not(.ops-console-body)) .request-row:hover { background: #d5f3f7; }

.request-label {
  display: flex;
  align-items: center;
  flex: 1 1 auto;
  min-width: 0;
  height: 30px;
  overflow: hidden;
  padding-left: 10px;
}

.request-text {
  overflow: hidden;
  color: #000;
  font-size: 14px;
  font-weight: 400;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.request-actions,
.confirm-actions {
  display: flex;
  align-items: center;
  gap: 5px;
  flex: 0 0 auto;
}

.request-actions {
  position: relative;
  flex: 0 0 120px;
  width: 120px;
  height: 30px;
}

.approve-button,
.cancel-button {
  min-width: 54px;
  min-height: 26px;
  border: 1px solid var(--ops-line, #8eb7e6);
  border-radius: 2px;
  padding: 0 9px;
  font-weight: 700;
  cursor: pointer;
}

.request-actions .approve-button,
.request-actions .cancel-button {
  position: absolute;
  top: 2px;
  width: 42px;
  min-width: 42px;
  height: 26px;
  min-height: 26px;
  border: 1px solid #000;
  border-radius: 0;
  color: #000;
  padding: 2px 5px;
  font-size: 12px;
  font-weight: 400;
  line-height: 20px;
}

.request-actions .approve-button { left: 0; background: #c9efb5; }
:global(body:not(.ops-console-body)) .request-actions .approve-button:hover:not(:disabled) { background: #31a924; color: #fff; }
.request-actions .cancel-button { left: 60px; background: #f2f2f2; }
:global(body:not(.ops-console-body)) .request-actions .cancel-button:hover:not(:disabled) { background: #808080; color: #fff; }

.approve-button {
  border-color: var(--ops-green, #23845f);
  background: linear-gradient(#47a77d, var(--ops-green, #23845f));
  color: #fff;
}

.cancel-button {
  background: linear-gradient(#fff, #eaf4ff);
  color: var(--ops-text, #243b53);
}

button:disabled {
  cursor: not-allowed;
  opacity: .55;
}

button:focus-visible {
  outline: 2px solid #9acff3;
  outline-offset: 1px;
}

.point-confirm-layer {
  position: fixed;
  inset: 0;
  z-index: 2400;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgb(15 23 42 / 48%);
}

.point-confirm-dialog {
  width: min(430px, 100%);
  border: 1px solid var(--ops-line-strong, #4b8ed3);
  border-radius: 2px;
  background: #fff;
  box-shadow: 0 22px 60px rgb(15 23 42 / 28%);
  padding: 14px;
}

.point-confirm-dialog h3 {
  margin: -14px -14px 10px;
  border-bottom: 1px solid var(--ops-line, #8eb7e6);
  background: linear-gradient(#f4fbff, var(--ops-header, #dceeff));
  color: var(--ops-blue-deep, #15599d);
  padding: 7px 10px;
  font-size: 13px;
}

.point-confirm-dialog p {
  margin: 0 0 8px;
  color: var(--ops-text, #243b53);
  line-height: 1.55;
  font-size: 12px;
}

.point-confirm-dialog .confirm-note {
  color: var(--ops-muted, #6d8093);
  font-size: 11px;
}

.confirm-actions {
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
