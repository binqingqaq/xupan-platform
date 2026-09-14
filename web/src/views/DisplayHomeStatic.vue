<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { displayHomeAssets } from '../display/display-home-assets'

type CarouselSlide = {
  title: string
  subtitle: string
  image: string
  accent: string
}

type LotteryCard = {
  name: string
  logo?: string
  issue: string
  numbers: string[]
  status: string
  countdown: string
  colors: string[]
  summary: string[]
}

const navItems = [
  { label: '首页' },
  { label: '彩票大厅', menu: ['热门彩种', '高频彩种', '全国彩种'] },
  { label: '推荐', menu: ['推荐方案', '号码分析', '精选资讯'], badge: '新' },
  { label: '长龙提醒' },
  { label: '开奖调用' },
  { label: '走势图表' },
  { label: '试玩投注' },
  { label: '香港彩' },
  { label: '资讯' },
  { label: '彩票软件' },
  { label: '竞猜中心', badge: '新' },
  { label: '简约版', compact: true },
]

const newsItems = [
  '福彩3D第243期山上的羊分析：定位胆码及直选组选参考',
  '黑龙江合买团中得816万元：约定50万元酬劳是否应支付？',
  '福彩刮刮乐“幸运日”上新：四款缤纷票面亮相',
  '2元彩票真能中500万大奖吗？',
  '排列3第26240期五行分布与近期走势分析',
  '快乐8第2026240期近期走势与号码形态分析',
  '福彩3D第2026237期预测分析：和尾看5，合值走势或将回',
]

const slides: CarouselSlide[] = [
  { title: '彩票专家预测方案', subtitle: '常玩常留 · 精选数据一站查看', image: '/display/banner.svg', accent: '#ff2780' },
  { title: '每期开奖及时更新', subtitle: '热门彩种 · 结果走势快速浏览', image: '/display/banner-blue.svg', accent: '#138fd6' },
  { title: '号码趋势清晰呈现', subtitle: '历史数据 · 轻松找到关注内容', image: '/display/banner-green.svg', accent: '#36ab62' },
]

const quickGames = [
  { name: '极速赛车', time: '00:19', image: displayHomeAssets.quickCards.jisusc },
  { name: '极速飞艇', time: '02:32', image: displayHomeAssets.quickCards.jisuft },
  { name: '幸运飞艇', time: '01:46', image: displayHomeAssets.quickCards.xingyft },
  { name: '澳洲幸运10', time: '03:05', image: displayHomeAssets.quickCards.aozxy10 },
  { name: '幸运时时彩', time: '01:10', image: displayHomeAssets.quickCards.xyssc },
]

const recommendations = [
  { name: '快乐8六合彩', image: displayHomeAssets.recommendations.happy28 },
  { name: '宾果六合彩', image: displayHomeAssets.recommendations.bingo },
  { name: '极速运动会', image: displayHomeAssets.recommendations.sportJsydh },
  { name: '香港彩', image: displayHomeAssets.recommendations.hksmSix },
  { name: '极速赛车', image: displayHomeAssets.recommendations.jisuSaiche },
  { name: '极速时时彩', image: displayHomeAssets.recommendations.sscJisu },
  { name: '快乐运动会', image: displayHomeAssets.recommendations.sportKlydh },
  { name: 'SG时时彩', image: displayHomeAssets.recommendations.sgssc },
  { name: 'SG飞艇', image: displayHomeAssets.recommendations.sgAirship },
]

const categories = ['热门彩', 'PK10', '时时彩', '11选5', '快乐十分', '快3', '六合彩', '其它']

const categoryCards: Record<string, LotteryCard[]> = {
  热门彩: [
    { name: '极速赛车', logo: displayHomeAssets.lotteryLogos.jisusc, issue: '34153934', numbers: ['4', '10', '3', '6', '5', '1', '8', '9', '2', '7'], status: '开奖中...', countdown: '00:13', colors: ['orange', 'green', 'charcoal', 'purple', 'cyan', 'yellow', 'red', 'brown', 'blue', 'gray'], summary: ['龙', '龙', '虎', '龙', '龙'] },
    { name: '香港彩', issue: '2026099', numbers: ['09', '24', '33', '47', '18', '40', '11'], status: '距下期 1天10时52分', countdown: '01:10:52', colors: ['blue', 'red', 'green', 'blue', 'red', 'blue', 'green'], summary: ['狗 木', '羊 木', '狗 火', '猴 木', '牛 火', '兔 火', '猴 火'] },
    { name: '快乐8六合彩', issue: '2026246', numbers: ['23', '20', '18', '03', '35', '16', '08'], status: '距下期 10天22时35分', countdown: '10:22:35', colors: ['green', 'blue', 'red', 'orange', 'blue', 'green', 'red'], summary: ['猴 水', '猪 土', '牛 火', '龙 火', '猴 金', '兔 木', '猪 木'] },
  ],
  PK10: [
    { name: '极速赛车', logo: displayHomeAssets.lotteryLogos.jisusc, issue: '34153934', numbers: ['4', '10', '3', '6', '5', '1', '8', '9', '2', '7'], status: '当前 564 期，剩 588 期', countdown: '00:13', colors: ['orange', 'green', 'charcoal', 'purple', 'cyan', 'yellow', 'red', 'brown', 'blue', 'gray'], summary: ['龙', '龙', '虎', '龙', '龙'] },
    { name: 'SG飞艇', logo: displayHomeAssets.lotteryLogos.sgAirship, issue: '20260914140', numbers: ['2', '6', '4', '3', '1', '5'], status: '当前 140 期，剩 148 期', countdown: '00:25', colors: ['blue', 'red', 'green', 'orange', 'purple', 'cyan'], summary: ['虎', '龙', '龙', '龙', '龙'] },
  ],
  时时彩: [
    { name: 'SG时时彩', issue: '20260914140', numbers: ['3', '2', '2', '7', '2'], status: '当前 140 期，剩 148 期', countdown: '00:25', colors: ['blue', 'red', 'green', 'orange', 'purple'], summary: ['16', '双', '小', '龙', '对子'] },
    { name: '幸运时时彩', logo: displayHomeAssets.lotteryLogos.jisussc, issue: '20260914034', numbers: ['8', '8', '0', '7', '8'], status: '开奖中...', countdown: '00:18', colors: ['blue', 'red', 'green', 'orange', 'purple'], summary: ['31', '单', '大', '和', '对子'] },
  ],
  '11选5': [
    { name: 'SG11选5', logo: displayHomeAssets.lotteryLogos.sg11x5, issue: '20260914140', numbers: ['09', '01', '06', '08', '02'], status: '当前 140 期，剩 148 期', countdown: '00:25', colors: ['blue', 'red', 'green', 'orange', 'purple'], summary: ['26', '小', '双', '杂六', '杂六'] },
    { name: '澳洲幸运5', issue: '51350518', numbers: ['00', '09', '05', '05', '07'], status: '当前 141 期，剩 147 期', countdown: '04:05', colors: ['blue', 'red', 'green', 'orange', 'purple'], summary: ['26', '双', '大', '虎', '对子'] },
  ],
  快乐十分: [
    { name: '极速运动会', issue: '202609140174', numbers: ['2', '6', '4', '3', '1', '5'], status: '距下期开奖', countdown: '00:23', colors: ['blue', 'blue', 'blue', 'blue', 'blue', 'blue'], summary: ['虎', '龙', '龙'] },
    { name: '快乐运动会', issue: '202609140072', numbers: ['2', '4', '3', '1', '6', '5'], status: '距下期开奖', countdown: '01:23', colors: ['blue', 'blue', 'blue', 'blue', 'blue', 'blue'], summary: ['虎', '虎', '龙'] },
  ],
  快3: [
    { name: 'SG快3', logo: displayHomeAssets.lotteryLogos.kuai3Sg, issue: '20260914140', numbers: ['3', '5', '5'], status: '开奖中...', countdown: '00:25', colors: ['blue', 'red', 'green'], summary: ['13', '单', '大'] },
    { name: '江苏快3', issue: '20260914140', numbers: ['2', '4', '6'], status: '当前 140 期，剩 148 期', countdown: '00:25', colors: ['orange', 'green', 'purple'], summary: ['12', '双', '小'] },
  ],
  六合彩: [
    { name: '香港彩', issue: '2026099', numbers: ['09', '24', '33', '47', '18', '40', '11'], status: '距下期 1天10时52分', countdown: '01:10:52', colors: ['blue', 'red', 'green', 'blue', 'red', 'blue', 'green'], summary: ['总分：182', '狗 木', '羊 木'] },
    { name: '极速六合彩', issue: '11519012', numbers: ['24', '47', '10', '28', '13', '25', '17'], status: '距下期 10天22时35分', countdown: '10:22:35', colors: ['red', 'blue', 'green', 'orange', 'blue', 'green', 'red'], summary: ['总分：164', '羊 木', '猴 木'] },
  ],
  其它: [
    { name: '体彩排列3', issue: '26246', numbers: ['2', '8', '6'], status: '开奖中...', countdown: '00:25', colors: ['blue', 'red', 'green'], summary: ['16', '双', '大'] },
    { name: '福彩3D', issue: '2026246', numbers: ['9', '8', '2'], status: '距下期 09时30分25秒', countdown: '09:30:25', colors: ['blue', 'red', 'green'], summary: ['19', '单', '大'] },
  ],
}

const activeNav = ref('首页')
const openNav = ref<string | null>(null)
const activeSlide = ref(0)
const activeCategory = ref('热门彩')
const preferenceExpanded = ref(false)
const selectedCard = ref('极速赛车')
const countdownSeconds = ref(13)
const notice = ref('')
const originalTitle = document.title
let carouselTimer: number | undefined
let countdownTimer: number | undefined
let noticeTimer: number | undefined

const currentSlide = computed(() => slides[activeSlide.value])
const visibleCards = computed(() => categoryCards[activeCategory.value] ?? categoryCards.热门彩)

function selectNav(label: string) {
  activeNav.value = label
  notify(`${label}为静态演示入口`)
}

function selectSlide(index: number) {
  activeSlide.value = index
}

function selectCard(card: LotteryCard) {
  selectedCard.value = card.name
  notify(`已选中${card.name}展示卡片`)
}

function showTool(label: string) {
  notify(`${label}为静态演示入口`)
}

function notify(message: string) {
  notice.value = message
  if (noticeTimer !== undefined) window.clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => {
    notice.value = ''
  }, 1800)
}

function formatCountdown(value: number) {
  return `00:${String(Math.max(0, value)).padStart(2, '0')}`
}

onMounted(() => {
  document.title = '168开奖网首页 - 静态展示'
  carouselTimer = window.setInterval(() => {
    activeSlide.value = (activeSlide.value + 1) % slides.length
  }, 4200)
  countdownTimer = window.setInterval(() => {
    countdownSeconds.value = countdownSeconds.value <= 0 ? 59 : countdownSeconds.value - 1
  }, 1000)
})

onBeforeUnmount(() => {
  document.title = originalTitle
  if (carouselTimer !== undefined) window.clearInterval(carouselTimer)
  if (countdownTimer !== undefined) window.clearInterval(countdownTimer)
  if (noticeTimer !== undefined) window.clearTimeout(noticeTimer)
})
</script>

<template>
  <div class="display-page">
    <header class="display-header">
      <div class="display-topbar">
        <div class="display-topbar-inner">
          <img class="display-speaker" :src="displayHomeAssets.brand.announce" width="15" height="17" alt="" aria-hidden="true" />
          <span>最新公告：</span>
          <div class="display-topbar-links">
            <button type="button" @click="showTool('登录')">登录</button>
            <span>|</span>
            <button type="button" @click="showTool('注册')">注册</button>
            <span>|</span>
            <button type="button" @click="showTool('设为首页')">设为首页</button>
            <span>|</span>
            <button type="button" @click="showTool('收藏本站')">收藏本站</button>
            <span>|</span>
            <button type="button" @click="showTool('帮助中心')">帮助中心</button>
            <span>|</span>
            <button class="display-mobile-link" type="button" @click="showTool('手机版')">手机版</button>
          </div>
        </div>
      </div>

      <div class="display-brand-row display-shell">
        <button class="display-logo" type="button" aria-label="168开奖网首页" @click="selectNav('首页')">
          <img :src="displayHomeAssets.brand.logo" width="190" height="72" alt="168开奖网" />
        </button>
        <div class="display-download">
          <img class="display-app-download" :src="displayHomeAssets.brand.appDownload" width="130" height="52" alt="APP下载" />
        </div>
      </div>

      <nav class="display-nav" aria-label="主导航">
        <div class="display-shell display-nav-inner">
          <div
            v-for="item in navItems"
            :key="item.label"
            class="display-nav-item-wrap"
            @mouseenter="openNav = item.menu ? item.label : null"
            @mouseleave="openNav = null"
          >
            <button
              type="button"
              class="display-nav-item"
              :class="{ active: activeNav === item.label, compact: item.compact }"
              :aria-expanded="item.menu ? openNav === item.label : undefined"
              @click="selectNav(item.label)"
            >
              <span v-if="item.compact" class="display-list-icon" aria-hidden="true"></span>
              {{ item.label }}
              <span v-if="item.menu" class="display-down" aria-hidden="true"></span>
              <sup v-if="item.badge">{{ item.badge }}</sup>
            </button>
            <div v-if="item.menu && openNav === item.label" class="display-nav-menu">
              <button v-for="entry in item.menu" :key="entry" type="button" @click="showTool(entry)">{{ entry }}</button>
            </div>
          </div>
        </div>
      </nav>
    </header>

    <main class="display-main display-shell">
      <div class="display-home-grid">
        <div class="display-content-column">
          <section class="display-top-grid">
        <div class="display-showcase">
          <button class="display-hero" type="button" :style="{ '--slide-accent': currentSlide.accent }" @click="showTool(currentSlide.title)">
            <img :src="currentSlide.image" :alt="currentSlide.title" width="535" height="260" />
            <span class="display-hero-copy">
              <strong>{{ currentSlide.title }}</strong>
              <small>{{ currentSlide.subtitle }}</small>
            </span>
          </button>
          <div class="display-carousel-dots" aria-label="首页横幅切换">
            <button
              v-for="(_, index) in slides"
              :key="index"
              type="button"
              :class="{ active: activeSlide === index }"
              :aria-label="`切换到第${index + 1}张横幅`"
              @click="selectSlide(index)"
            ></button>
          </div>
        </div>

        <section class="display-news display-panel">
          <div class="display-panel-heading">
            <h1>行业新闻</h1>
            <button type="button" @click="showTool('行业新闻更多')">更多</button>
          </div>
          <ul>
            <li v-for="item in newsItems" :key="item">
              <button type="button" @click="showTool('新闻详情')">{{ item }}</button>
            </li>
          </ul>
        </section>
          </section>

          <section class="display-quick-games display-panel">
            <button v-for="game in quickGames" :key="game.name" class="display-quick-game" type="button" @click="showTool(game.name)">
              <img :src="game.image" :alt="game.name" width="157" height="110" loading="lazy" />
              <span>{{ game.name }} <b>{{ game.time }}</b></span>
            </button>
          </section>

          <section class="display-preferences display-panel">
            <div>
              <strong>我的偏好</strong>
              <span>可通过【编辑】功能自主设置彩种</span>
            </div>
            <div class="display-preferences-actions">
              <button type="button" @click="showTool('编辑偏好')">编辑 <span aria-hidden="true">✎</span></button>
              <button type="button" :aria-expanded="preferenceExpanded" @click="preferenceExpanded = !preferenceExpanded">
                {{ preferenceExpanded ? '收起' : '展开' }} <span class="display-chevron" :class="{ up: preferenceExpanded }" aria-hidden="true"></span>
              </button>
            </div>
          </section>

          <section class="display-lottery-section display-panel">
            <div class="display-category-tabs" role="tablist" aria-label="彩种分类">
              <button
                v-for="category in categories"
                :key="category"
                type="button"
                role="tab"
                :aria-selected="activeCategory === category"
                :class="{ active: activeCategory === category }"
                @click="activeCategory = category"
              >{{ category }}</button>
            </div>
            <div v-if="preferenceExpanded" class="display-preference-note">已展开静态偏好彩种：极速赛车、香港彩、快乐8六合彩</div>
            <div class="display-lottery-list">
              <article
                v-for="card in visibleCards"
                :key="`${activeCategory}-${card.name}`"
                class="display-lottery-card"
                :class="{ selected: selectedCard === card.name }"
              >
                <div class="display-lottery-head">
                  <div>
                    <div class="display-lottery-title">
                      <img v-if="card.logo" class="display-lottery-logo" :src="card.logo" :alt="card.name" width="48" height="48" loading="lazy" />
                      <h2>{{ card.name }} <span>{{ card.issue }}期</span></h2>
                    </div>
                    <p>{{ card.status }}</p>
                  </div>
                  <div class="display-countdown"><small>距下期开奖</small><strong>{{ card.name === '极速赛车' ? formatCountdown(countdownSeconds) : card.countdown }}</strong></div>
                </div>
                <button class="display-number-row" type="button" :aria-label="`查看${card.name}号码`" @click="selectCard(card)">
                  <span v-for="(number, index) in card.numbers" :key="`${card.name}-${number}-${index}`" class="display-number" :class="card.colors[index]">{{ number }}</span>
                </button>
                <div class="display-summary-row">
                  <span v-for="(summary, index) in card.summary" :key="`${card.name}-summary-${index}`">{{ summary }}</span>
                </div>
                <div class="display-card-links">
                  <button type="button" @click="showTool('开奖视频')">开奖视频</button>
                  <button type="button" @click="showTool('走势图')">走势图</button>
                  <button type="button" @click="showTool('长龙提醒')">长龙提醒</button>
                  <button type="button" @click="showTool('冷热分析')">冷热分析</button>
                </div>
              </article>
            </div>
          </section>
        </div>

        <aside class="display-sidebar">
          <section class="display-recommend display-panel">
            <div class="display-sidebar-heading">
              <span></span>
              <h2>推荐彩种 <small>RECOMMEND LOTTERY</small></h2>
              <span></span>
            </div>
            <div class="display-recommend-grid">
              <button v-for="item in recommendations" :key="item.name" type="button" @click="showTool(item.name)">
                <img class="display-recommend-image" :src="item.image" :alt="item.name" width="52" height="52" loading="lazy" />
                <strong>{{ item.name }}</strong>
              </button>
            </div>
            <button class="display-recommend-more" type="button" aria-label="查看更多推荐彩种" @click="showTool('更多推荐彩种')"><span></span></button>
          </section>

          <button class="display-side-banner" type="button" @click="showTool('PK10计划')"><img :src="displayHomeAssets.promos.pk10" width="320" height="150" alt="PK10计划" loading="lazy" /></button>
          <div class="display-side-ad-grid">
            <button type="button" @click="showTool('时尚彩计划')"><img :src="displayHomeAssets.promos.ssc" width="155" height="100" alt="时尚彩计划" loading="lazy" /></button>
            <button type="button" @click="showTool('11选5计划')"><img :src="displayHomeAssets.promos.eleven" width="155" height="100" alt="11选5计划" loading="lazy" /></button>
            <button type="button" @click="showTool('快3计划')"><img :src="displayHomeAssets.promos.k3" width="155" height="100" alt="快3计划" loading="lazy" /></button>
            <button type="button" @click="showTool('PC28计划')"><img :src="displayHomeAssets.promos.pcdd" width="155" height="100" alt="PC28计划" loading="lazy" /></button>
          </div>

          <section class="display-scheme display-panel">
            <div class="display-scheme-heading"><strong>方案预测</strong><button type="button" @click="showTool('更多方案预测')">更多</button></div>
            <button v-for="item in ['福彩3D第243期山上的羊分析', '双色球103期号码推荐：精选一注参考', '排列三预测：组选走势分析', '超级大乐透预测策略深度分析']" :key="item" type="button" @click="showTool('方案文章')">{{ item }}</button>
          </section>
        </aside>
      </div>
    </main>

    <footer class="display-footer">
      <div class="display-shell display-footer-grid">
        <div class="display-footer-brand"><img :src="displayHomeAssets.brand.logo" width="150" height="56" alt="168开奖网" /><p>最专业的彩票开奖网站<br />数据分析最全面的开奖数据平台</p></div>
        <div><strong>关于我们</strong><button type="button" @click="showTool('关于我们')">关于我们</button><button type="button" @click="showTool('客服中心')">客服中心</button><button type="button" @click="showTool('免责声明')">免责声明</button></div>
        <div><strong>中奖神器</strong><button type="button" @click="showTool('走势图表')">走势图表</button><button type="button" @click="showTool('玩法规则')">玩法规则</button></div>
        <div><strong>免费调用</strong><button type="button" @click="showTool('自助网址导航')">自助网址导航</button><button type="button" @click="showTool('开奖调用')">开奖调用</button></div>
      </div>
      <p class="display-copyright">Copyright © 2026 All rights reserved 彩票开奖网 版权所有</p>
    </footer>

    <div class="display-float-tools" aria-label="快捷工具">
      <button type="button" title="网址导航" @click="showTool('网址导航')"><span class="display-float-icon compass" aria-hidden="true"></span><b>网址<br />导航</b></button>
      <button type="button" title="客服中心" @click="showTool('客服中心')"><span class="display-float-icon service" aria-hidden="true"></span><b>客服</b></button>
      <button type="button" title="反馈" @click="showTool('反馈')"><span class="display-float-icon message" aria-hidden="true"></span><b>反馈</b></button>
      <button type="button" title="APP下载" @click="showTool('APP下载')"><span class="display-float-icon scan" aria-hidden="true"></span><b>APP</b></button>
    </div>

    <Transition name="display-toast">
      <div v-if="notice" class="display-toast" role="status">{{ notice }}</div>
    </Transition>
  </div>
</template>
