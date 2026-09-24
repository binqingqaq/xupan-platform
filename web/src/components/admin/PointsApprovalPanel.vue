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
const soundHint = ref('')
const newRequestCount = ref(0)

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
  soundHint.value = ''
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
  try {
    await playTone(soundChoice.value)
  } catch {
    soundHint.value = '浏览器未允许播放提示音，请检查声音权限'
  }
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
      newRequestCount.value += newIds.length
      if (soundReady.value) {
        try {
          await playTone(soundChoice.value)
        } catch {
          soundHint.value = '有新的上下分申请，点击测试提示音可启用声音'
        }
      } else {
        soundHint.value = '有新的上下分申请，点击测试提示音可启用声音'
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
      <div>
        <h2>上下分确认</h2>
        <span v-if="newRequestCount" class="new-count" aria-live="polite">{{ newRequestCount }} 条新申请</span>
      </div>
      <div class="sound-controls">
        <button class="sound-test-button" type="button" @click="testSound">测试提示音</button>
        <label><input type="radio" name="point-sound" :checked="soundChoice === 'sound1'" @change="selectSound('sound1')" />声音1</label>
        <label><input type="radio" name="point-sound" :checked="soundChoice === 'sound2'" @change="selectSound('sound2')" />声音2</label>
      </div>
    </header>

    <div class="panel-body">
      <p v-if="notice" class="panel-notice" role="status">{{ notice }}</p>
      <p v-if="error" class="panel-error" role="alert">{{ error }}</p>
      <p v-else-if="soundHint" class="panel-hint" role="status">{{ soundHint }}</p>

      <div v-if="loading" class="panel-state">正在加载待处理申请...</div>
      <div v-else-if="!requests.length" class="panel-state panel-empty">
        <strong>暂无待处理的上下分申请</strong>
        <span>玩家或托提交后会自动出现在这里</span>
      </div>
      <ul v-else class="request-list">
        <li v-for="request in requests" :key="request.id" class="request-row">
          <div class="request-label">
            <span :class="['request-direction', request.requestType === 'TOP_UP' ? 'is-top-up' : 'is-down']">
              {{ request.requestType === 'TOP_UP' ? '上分' : '下分' }}
            </span>
            <strong>{{ request.requestType === 'TOP_UP' ? '+' : '-' }}{{ Number(request.amount).toFixed(2) }}</strong>
            <span class="request-player">（{{ request.displayName }}）</span>
          </div>
          <div class="request-actions">
            <button class="approve-button" type="button" :disabled="processingId !== null" @click="openApproval(request)">同意</button>
            <button class="cancel-button" type="button" :disabled="processingId !== null" @click="rejectRequest(request)">取消</button>
          </div>
        </li>
      </ul>
    </div>

    <Teleport to="body">
      <div v-if="confirmTarget" class="point-confirm-layer" role="presentation" @click.self="closeApproval">
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
.points-approval-panel { min-width: 0; min-height: 300px; border: 1px solid #218bd0; background: #fff; color: #263746; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 14px; min-height: 54px; border-bottom: 1px solid #d7e8f3; padding: 8px 14px; }
.panel-heading > div:first-child { display: flex; align-items: center; gap: 10px; min-width: 0; }
.panel-heading h2 { margin: 0; color: #244e68; font-size: 15px; white-space: nowrap; }
.new-count { color: #b42318; font-size: 11px; font-weight: 700; }
.sound-controls { display: flex; align-items: center; flex-wrap: wrap; justify-content: flex-end; gap: 10px; color: #476274; font-size: 12px; }
.sound-controls label { display: flex; align-items: center; gap: 5px; min-height: 36px; cursor: pointer; }
.sound-controls input { width: 16px; height: 16px; margin: 0; accent-color: #177dc1; }
.sound-test-button { min-height: 36px; border: 1px solid #7ab6d9; background: #f4fbff; color: #166b9e; padding: 0 10px; font-weight: 700; cursor: pointer; }
.panel-body { min-height: 246px; padding: 12px 14px 14px; }
.panel-notice, .panel-error, .panel-hint { margin: 0 0 10px; padding: 8px 10px; border: 1px solid #b7d7f2; background: #f1f8fe; color: #166b9e; font-size: 12px; }
.panel-error { border-color: #fecaca; background: #fff1f2; color: #b42318; }
.panel-hint { border-color: #f6d68a; background: #fffbeb; color: #9a6700; }
.panel-state { display: grid; min-height: 210px; place-content: center; gap: 8px; color: #718596; text-align: center; font-size: 13px; }
.panel-empty strong { color: #405f74; }
.panel-empty span { font-size: 12px; }
.request-list { display: grid; gap: 8px; max-height: 360px; overflow-y: auto; margin: 0; padding: 0; list-style: none; }
.request-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 58px; border: 1px solid #d9e8f2; background: #f9fcfd; padding: 8px 10px; }
.request-label { display: flex; align-items: baseline; flex-wrap: wrap; gap: 6px; min-width: 0; }
.request-label strong { color: #0b6e46; font-size: 18px; font-variant-numeric: tabular-nums; }
.request-direction { color: #2a6d91; font-size: 12px; font-weight: 700; }
.request-direction.is-down { color: #a14444; }
.request-player { color: #536b7d; font-size: 13px; overflow-wrap: anywhere; }
.request-actions, .confirm-actions { display: flex; align-items: center; gap: 8px; flex: 0 0 auto; }
.approve-button, .cancel-button { min-width: 66px; min-height: 44px; border: 1px solid; padding: 0 12px; font-weight: 700; cursor: pointer; }
.approve-button { border-color: #178357; background: #178357; color: #fff; }
.cancel-button { border-color: #b8cbd8; background: #fff; color: #536b7d; }
button:disabled { cursor: not-allowed; opacity: .55; }
button:focus-visible { outline: 3px solid #9acff3; outline-offset: 2px; }
.point-confirm-layer { position: fixed; inset: 0; z-index: 2400; display: grid; place-items: center; padding: 20px; background: rgb(15 23 42 / 48%); }
.point-confirm-dialog { width: min(430px, 100%); border: 1px solid #96c8e7; background: #fff; box-shadow: 0 22px 60px rgb(15 23 42 / 28%); padding: 20px; }
.point-confirm-dialog h3 { margin: 0 0 12px; color: #244e68; font-size: 17px; }
.point-confirm-dialog p { margin: 0 0 10px; color: #334e60; line-height: 1.6; }
.point-confirm-dialog .confirm-note { color: #718596; font-size: 12px; }
.confirm-actions { justify-content: flex-end; margin-top: 18px; }
@media (max-width: 700px) {
  .panel-heading { align-items: flex-start; flex-direction: column; }
  .sound-controls { justify-content: flex-start; }
  .request-row { align-items: flex-start; flex-direction: column; }
  .request-actions { width: 100%; }
  .request-actions button { flex: 1; }
}
</style>
