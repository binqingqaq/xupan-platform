import { displayMobileHomeAssets } from './display-mobile-home-assets'

export type MobileCardKind = 'standard' | 'six-lottery' | 'pk10-summary' | 'trend-summary'

export type DisplayMobileLotteryCard = {
  key: string
  name: string
  issue: string
  countdownLabel: string
  countdownValue: string
  numbers: string[]
  summary: string[]
  cardKind: MobileCardKind
  logo?: string
  numberTone?: string
}

export const displayMobileLotteryCards: readonly DisplayMobileLotteryCard[] = [
  {
    key: 'fast-sport',
    name: '极速运动会',
    issue: '202609140421',
    countdownLabel: '距下期开奖',
    countdownValue: '00:15',
    numbers: ['5', '1', '6', '4', '3', '2'],
    summary: ['龙', '虎', '龙'],
    cardKind: 'standard',
    logo: displayMobileHomeAssets.lottery.sportFast,
    numberTone: 'blue',
  },
  {
    key: 'happy-sport',
    name: '快乐运动会',
    issue: '202609140175',
    countdownLabel: '距下期开奖',
    countdownValue: '01:29',
    numbers: ['1', '5', '2', '6', '3', '4'],
    summary: ['虎', '龙', '虎'],
    cardKind: 'standard',
    logo: displayMobileHomeAssets.lottery.sportHappy,
    numberTone: 'blue',
  },
  {
    key: 'hong-kong',
    name: '香港彩',
    issue: '2026099',
    countdownLabel: '距下期开奖',
    countdownValue: '01天 05时 43分',
    numbers: ['09', '24', '33', '47', '18', '40', '11'],
    summary: ['狗 木', '羊 木', '狗 火', '猴 木', '牛 火', '兔 火', '总分：182'],
    cardKind: 'six-lottery',
    logo: displayMobileHomeAssets.lottery.hongKong,
  },
  {
    key: 'lucky-ssc',
    name: '幸运时时彩',
    issue: '20260914058',
    countdownLabel: '距下期开奖',
    countdownValue: '03:29',
    numbers: ['3', '0', '9', '3', '5'],
    summary: ['虎', '|', '总和：20', '小', '双'],
    cardKind: 'trend-summary',
    logo: displayMobileHomeAssets.lottery.jisuSsc,
    numberTone: 'red',
  },
  {
    key: 'airship',
    name: '幸运飞艇',
    issue: '20260914032',
    countdownLabel: '距下期开奖',
    countdownValue: '02:30',
    numbers: ['虎', '龙', '龙', '龙', '龙'],
    summary: ['冠亚和：12', '大', '双'],
    cardKind: 'trend-summary',
    logo: displayMobileHomeAssets.lottery.jisuFt,
    numberTone: 'animal',
  },
  {
    key: 'pc28',
    name: 'PC28',
    issue: '11355760',
    countdownLabel: '距下期开奖',
    countdownValue: '00:58',
    numbers: ['7', '6', '0', '13'],
    summary: ['总和：13', '小', '单'],
    cardKind: 'pk10-summary',
    logo: displayMobileHomeAssets.lottery.pc28,
    numberTone: 'cyan',
  },
  {
    key: 'taiwan-ssc',
    name: '台湾5分彩',
    issue: '115052072',
    countdownLabel: '距下期开奖',
    countdownValue: '开奖中...',
    numbers: ['虎'],
    summary: ['|', '总和：30', '大', '双'],
    cardKind: 'trend-summary',
    logo: displayMobileHomeAssets.lottery.jisuSsc,
    numberTone: 'green',
  },
  {
    key: 'fast-airship',
    name: '极速飞艇',
    issue: '54793505',
    countdownLabel: '距下期开奖',
    countdownValue: '00:44',
    numbers: ['虎', '龙', '虎', '虎', '龙'],
    summary: ['冠亚和：5', '小', '单'],
    cardKind: 'trend-summary',
    logo: displayMobileHomeAssets.lottery.jisuFt,
    numberTone: 'animal',
  },
  {
    key: 'bingo-six',
    name: '宾果六合彩',
    issue: '115052072',
    countdownLabel: '距下期开奖',
    countdownValue: '开奖中...',
    numbers: ['16', '13', '46', '43', '08', '04', '39'],
    summary: ['猴 火', '龙 水', '兔 火', '鸡 火', '马 水', '羊 金', '总分：127'],
    cardKind: 'six-lottery',
    logo: displayMobileHomeAssets.lottery.hongKong,
  },
  {
    key: 'happy-eight-six',
    name: '快乐8六合彩',
    issue: '2026246',
    countdownLabel: '距下期开奖',
    countdownValue: '00天 05时 43分 30秒',
    numbers: ['01', '07', '13', '19', '26', '32', '41'],
    summary: ['猴 水', '猪 土', '牛 火', '龙 火', '猴 金', '兔 木', '总分：123'],
    cardKind: 'six-lottery',
    logo: displayMobileHomeAssets.lottery.hongKong,
  },
  {
    key: 'fast-race',
    name: '极速赛车',
    issue: '34154181',
    countdownLabel: '距下期开奖',
    countdownValue: '00:18',
    numbers: ['虎', '龙', '龙', '龙', '龙'],
    summary: ['冠亚和：9', '小', '单'],
    cardKind: 'trend-summary',
    logo: displayMobileHomeAssets.lottery.sportFast,
    numberTone: 'animal',
  },
  {
    key: 'fast-ssc',
    name: '极速时时彩',
    issue: '14123445',
    countdownLabel: '距下期开奖',
    countdownValue: '00:15',
    numbers: ['8', '8', '7', '7', '2'],
    summary: ['龙', '|', '总和：32', '大', '双'],
    cardKind: 'trend-summary',
    logo: displayMobileHomeAssets.lottery.jisuSsc,
    numberTone: 'red',
  },
  {
    key: 'sg-airship',
    name: 'SG飞艇',
    issue: '20260914189',
    countdownLabel: '距下期开奖',
    countdownValue: '03:30',
    numbers: ['龙', '虎', '虎', '虎', '虎'],
    summary: ['冠亚和：11', '小', '单'],
    cardKind: 'trend-summary',
    logo: displayMobileHomeAssets.lottery.sgAirship,
    numberTone: 'animal',
  },
  {
    key: 'sg-ssc',
    name: 'SG时时彩',
    issue: '20260914189',
    countdownLabel: '距下期开奖',
    countdownValue: '03:30',
    numbers: ['5', '6', '8', '4', '6'],
    summary: ['虎', '|', '总和：29', '大', '单'],
    cardKind: 'trend-summary',
    logo: displayMobileHomeAssets.lottery.jisuSsc,
    numberTone: 'green',
  },
  {
    key: 'sg-k3',
    name: 'SG快3',
    issue: '20260914189',
    countdownLabel: '距下期开奖',
    countdownValue: '03:30',
    numbers: [],
    summary: ['总和：5', '小', '单'],
    cardKind: 'trend-summary',
    logo: displayMobileHomeAssets.lottery.sgK3,
    numberTone: 'blue',
  },
]

export type DisplayMobileBottomNavItem = {
  key: string
  label: string
  icon: 'home' | 'star' | 'news' | 'trophy' | 'more'
}

export const displayMobileBottomNavItems: readonly DisplayMobileBottomNavItem[] = [
  { key: 'home', label: '首页', icon: 'home' },
  { key: 'recommend', label: '推荐', icon: 'star' },
  { key: 'news', label: '资讯', icon: 'news' },
  { key: 'result', label: '开奖', icon: 'trophy' },
  { key: 'more', label: '更多', icon: 'more' },
]
