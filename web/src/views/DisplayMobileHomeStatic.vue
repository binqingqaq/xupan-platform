<script setup lang="ts">
import { displayMobileHomeAssets } from '../display/display-mobile-home-assets'
import {
  displayMobileBottomNavItems,
  displayMobileLotteryCards,
  type DisplayMobileLotteryCard,
} from '../display/display-mobile-home-data'

const numberClass = (card: DisplayMobileLotteryCard, index: number) => {
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
      <button class="display-mobile-icon-button display-mobile-desktop-button" type="button" aria-label="桌面版" @click.prevent>
        <span class="display-mobile-monitor"></span>
      </button>
    </header>

    <div class="display-mobile-notice" role="status">
      <img :src="displayMobileHomeAssets.brand.announce" alt="公告" width="15" height="17" />
      <p>168导航站：https://www.1688938.com</p>
    </div>

    <section class="display-mobile-hero" aria-label="首页宣传">
      <img :src="displayMobileHomeAssets.promo.hero" alt="彩票专家预测方案" width="535" height="236" />
    </section>

    <main class="display-mobile-main">
      <section class="display-mobile-lottery-list" aria-label="开奖列表">
        <article
          v-for="card in displayMobileLotteryCards"
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
              <span v-for="item in card.summary" :key="item">{{ item }}</span>
            </div>
          </div>

          <div v-else-if="card.cardKind === 'pk10-summary'" class="display-mobile-card-content display-mobile-pk-content">
            <div class="display-mobile-number-row display-mobile-pk-row">
              <span v-for="(number, index) in card.numbers" :key="`${card.key}-${number}-${index}`" class="display-mobile-number" :class="numberClass(card, index)">{{ number }}</span>
            </div>
            <div class="display-mobile-summary-row">
              <span v-for="item in card.summary" :key="item">{{ item }}</span>
            </div>
          </div>

          <div v-else-if="card.cardKind === 'trend-summary'" class="display-mobile-card-content display-mobile-trend-content">
            <div class="display-mobile-number-row">
              <span v-for="(number, index) in card.numbers" :key="`${card.key}-${number}-${index}`" class="display-mobile-number" :class="numberClass(card, index)">{{ number }}</span>
            </div>
            <div class="display-mobile-summary-row">
              <span v-for="item in card.summary" :key="item">{{ item }}</span>
            </div>
          </div>

          <div v-else class="display-mobile-card-content display-mobile-standard-content">
            <div class="display-mobile-number-row">
              <span v-for="(number, index) in card.numbers" :key="`${card.key}-${number}-${index}`" class="display-mobile-number" :class="numberClass(card, index)">{{ number }}</span>
            </div>
            <div class="display-mobile-summary-row">
              <span v-for="item in card.summary" :key="item">{{ item }}</span>
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
