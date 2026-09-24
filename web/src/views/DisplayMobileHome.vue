<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api'
import { displayMobileHomeAssets } from '../display/display-mobile-home-assets'
import {
  displayMobileBottomNavItems,
  displayMobileLotteryCards,
  type DisplayMobileLotteryCard,
} from '../display/display-mobile-home-data'
import {
  clockOffsetMs,
  formatCountdown,
  heroIndexAfterSwipe,
  mergeMobileDisplayCards,
  wrapHeroIndex,
} from '../display/display-mobile-home-live'

const HERO_AUTO_INTERVAL_MS = 5000
const HERO_SWIPE_THRESHOLD_PX = 42

const cards = ref<DisplayMobileLotteryCard[]>([...displayMobileLotteryCards])
const sourceClockOffsetMs = ref(0)
const activeHeroIndex = ref(0)
let refreshTimer: number | undefined
let countdownTimer: number | undefined
let heroTimer: number | undefined
let heroPointerStartX: number | null = null

const heroSlides = displayMobileHomeAssets.promo.heroes
const heroTrackStyle = computed(() => ({
  transform: `translateX(-${activeHeroIndex.value * 100}%)`,
}))

const numberClass = (card: DisplayMobileLotteryCard, index: number) => {
  const dynamicColor = card.numberColors?.[index]
  if (dynamicColor) return `display-mobile-number-${dynamicColor}`
  if (card.numberTone === 'animal') return 'display-mobile-number-animal'
  const tones = card.numberTone === 'red'
    ? ['red', 'pink', 'red', 'green', 'red']
    : card.numberTone === 'green'
      ? ['green', 'blue', 'red', 'green', 'blue']
      : card.numberTone === 'cyan'
        ? ['cyan', 'blue', 'green']
        : ['orange', 'blue', 'green', 'red', 'purple', 'cyan', 'orange', 'red', 'blue', 'green']
  return `display-mobile-number-${tones[index % tones.length]}`
}

async function refreshCards() {
  try {
    const response = await api.getMobileDisplayHome()
    sourceClockOffsetMs.value = clockOffsetMs(response)
    cards.value = mergeMobileDisplayCards(response, displayMobileLotteryCards)
  } catch {
    // Keep the last successful snapshot or the bundled fallback snapshot.
  }
}

function updateCountdowns() {
  const now = Date.now()
  cards.value = cards.value.map(card => {
    if (!card.nextDrawAt) return card
    return {
      ...card,
      countdownValue: formatCountdown(
        { nextDrawAt: card.nextDrawAt, countdownFormat: card.countdownFormat ?? 'MM_SS' },
        now,
        sourceClockOffsetMs.value,
      ),
    }
  })
}

function refreshWhenVisible() {
  if (!document.hidden) {
    void refreshCards()
    startHeroAutoPlay()
  } else {
    stopHeroAutoPlay()
  }
}

function showHero(index: number, manual = false) {
  activeHeroIndex.value = wrapHeroIndex(index, heroSlides.length)
  if (manual) startHeroAutoPlay()
}

function nextHero() {
  showHero(activeHeroIndex.value + 1, true)
}

function previousHero() {
  showHero(activeHeroIndex.value - 1, true)
}

function startHeroAutoPlay() {
  stopHeroAutoPlay()
  if (document.hidden) return
  heroTimer = window.setInterval(() => {
    activeHeroIndex.value = wrapHeroIndex(activeHeroIndex.value + 1, heroSlides.length)
  }, HERO_AUTO_INTERVAL_MS)
}

function stopHeroAutoPlay() {
  if (heroTimer !== undefined) {
    window.clearInterval(heroTimer)
    heroTimer = undefined
  }
}

function onHeroPointerDown(event: PointerEvent) {
  heroPointerStartX = event.clientX
  stopHeroAutoPlay()
  if (event.currentTarget instanceof HTMLElement) {
    event.currentTarget.setPointerCapture(event.pointerId)
  }
}

function onHeroPointerUp(event: PointerEvent) {
  if (heroPointerStartX === null) return
  const deltaX = event.clientX - heroPointerStartX
  heroPointerStartX = null
  activeHeroIndex.value = heroIndexAfterSwipe(
    activeHeroIndex.value,
    deltaX,
    HERO_SWIPE_THRESHOLD_PX,
    heroSlides.length,
  )
  startHeroAutoPlay()
}

function onHeroPointerCancel() {
  heroPointerStartX = null
  startHeroAutoPlay()
}

onMounted(() => {
  document.title = '168开奖网移动端首页'
  void refreshCards()
  refreshTimer = window.setInterval(refreshCards, 5000)
  countdownTimer = window.setInterval(updateCountdowns, 1000)
  startHeroAutoPlay()
  document.addEventListener('visibilitychange', refreshWhenVisible)
})

onBeforeUnmount(() => {
  if (refreshTimer !== undefined) window.clearInterval(refreshTimer)
  if (countdownTimer !== undefined) window.clearInterval(countdownTimer)
  stopHeroAutoPlay()
  document.removeEventListener('visibilitychange', refreshWhenVisible)
})
</script>

<template>
  <div class="display-mobile-page">
    <section class="display-mobile-app-banner" aria-label="APP宣传">
      <img class="display-mobile-app-logo" :src="displayMobileHomeAssets.brand.appLogo" alt="168开奖APP" width="40" height="40" />
      <div class="display-mobile-app-copy">
        <strong>168开奖APP</strong>
        <span>App在手，看奖不愁</span>
      </div>
      <button class="display-mobile-download-button" type="button" @click.prevent>立即下载</button>
    </section>

    <header class="display-mobile-header">
      <button class="display-mobile-icon-button display-mobile-menu-button" type="button" aria-label="菜单" @click.prevent>
        <span></span><span></span><span></span>
      </button>
      <img class="display-mobile-logo" :src="displayMobileHomeAssets.brand.logo" alt="168开奖网" width="146" height="71" />
    </header>

    <div class="display-mobile-notice" role="status">
      <img :src="displayMobileHomeAssets.brand.announce" alt="公告" width="15" height="17" />
      <p>168导航站：https://www.1688938.com</p>
    </div>

    <section
      class="display-mobile-hero"
      aria-label="首页宣传"
      @pointerdown="onHeroPointerDown"
      @pointerup="onHeroPointerUp"
      @pointercancel="onHeroPointerCancel"
    >
      <div class="display-mobile-hero-track" :style="heroTrackStyle">
        <img
          v-for="(slide, index) in heroSlides"
          :key="slide"
          :src="slide"
          :alt="`首页宣传图 ${index + 1}`"
          width="535"
          height="236"
          draggable="false"
        />
      </div>
      <div class="display-mobile-hero-dots" aria-label="首页宣传图切换">
        <button
          v-for="(_, index) in heroSlides"
          :key="`hero-dot-${index}`"
          type="button"
          :class="{ active: activeHeroIndex === index }"
          :aria-label="`切换到第${index + 1}张宣传图`"
          @click.stop="showHero(index, true)"
        ></button>
      </div>
    </section>

    <main class="display-mobile-main">
      <section class="display-mobile-lottery-list" aria-label="开奖列表">
        <article
          v-for="card in cards"
          :key="card.key"
          class="display-mobile-lottery-card"
          :class="`is-${card.cardKind}`"
        >
          <header class="display-mobile-card-header">
            <div class="display-mobile-card-title">
              <h2>{{ card.name }} <span>{{ card.issue }}期</span></h2>
            </div>
            <p>{{ card.countdownLabel }} <strong>{{ card.countdownValue }}</strong></p>
          </header>

          <div v-if="card.cardKind === 'six-lottery'" class="display-mobile-card-content display-mobile-six-content">
            <div class="display-mobile-number-row">
              <span v-for="(number, index) in card.numbers" :key="`${card.key}-${number}-${index}`" class="display-mobile-number" :class="numberClass(card, index)">{{ number }}</span>
            </div>
            <div class="display-mobile-summary-row">
              <span v-for="(item, index) in card.summary" :key="`${card.key}-summary-${index}`">{{ item }}</span>
            </div>
          </div>

          <div v-else-if="card.cardKind === 'pk10-summary'" class="display-mobile-card-content display-mobile-pk-content">
            <div class="display-mobile-number-row display-mobile-pk-row">
              <span v-for="(number, index) in card.numbers" :key="`${card.key}-${number}-${index}`" class="display-mobile-number" :class="numberClass(card, index)">{{ number }}</span>
            </div>
            <div class="display-mobile-summary-row">
              <span v-for="(item, index) in card.summary" :key="`${card.key}-summary-${index}`">{{ item }}</span>
            </div>
          </div>

          <div v-else-if="card.cardKind === 'trend-summary'" class="display-mobile-card-content display-mobile-trend-content">
            <div class="display-mobile-number-row">
              <span v-for="(number, index) in card.numbers" :key="`${card.key}-${number}-${index}`" class="display-mobile-number" :class="numberClass(card, index)">{{ number }}</span>
            </div>
            <div class="display-mobile-summary-row">
              <span v-for="(item, index) in card.summary" :key="`${card.key}-summary-${index}`">{{ item }}</span>
            </div>
          </div>

          <div v-else class="display-mobile-card-content display-mobile-standard-content">
            <div class="display-mobile-number-row">
              <span v-for="(number, index) in card.numbers" :key="`${card.key}-${number}-${index}`" class="display-mobile-number" :class="numberClass(card, index)">{{ number }}</span>
            </div>
            <div class="display-mobile-summary-row">
              <span v-for="(item, index) in card.summary" :key="`${card.key}-summary-${index}`">{{ item }}</span>
            </div>
          </div>
        </article>
      </section>
    </main>

    <nav class="display-mobile-bottom-nav" aria-label="移动首页底部菜单">
      <button
        v-for="item in displayMobileBottomNavItems"
        :key="item.key"
        type="button"
        class="display-mobile-bottom-nav-item"
        :class="{ 'is-active': item.key === 'home' }"
        :aria-label="item.label"
        @click.prevent
      >
        <span class="display-mobile-bottom-icon" :class="`is-${item.icon}`" aria-hidden="true"></span>
        <span>{{ item.label }}</span>
      </button>
    </nav>
  </div>
</template>
