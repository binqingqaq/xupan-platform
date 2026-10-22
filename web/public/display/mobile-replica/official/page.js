const lotteryNames = ['新澳彩', '老澳彩', '香港彩', '快乐8'];
const liveLotteries = new Map([
  ['hong-kong', '香港彩'],
  ['happy-eight-six', '快乐8'],
]);
const liveDrawTimes = new Map();
let liveRetryTimer;
let liveRetryDelay = 10_000;

function issueForDisplay(issue) {
  return String(issue || '').replace(/^20\d{2}(?=\d{3}$)/, '');
}

function formatNextDrawAt(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    month: 'numeric',
    day: 'numeric',
    weekday: 'long',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(date);
  const part = (type) => parts.find((item) => item.type === type)?.value || '';
  return `${part('month')}月${part('day')}日 ${part('weekday')} ${part('hour')}:${part('minute')}`;
}

function refreshCountdowns() {
  for (const [name, nextDrawAt] of liveDrawTimes) {
    const selected = [...document.querySelectorAll('#widgetContainer40617 .baomalaodaName')]
      .find((item) => item.textContent.trim() === name)?.closest('.baomalaoda');
    const countdown = selected?.querySelector('.baomalaodaCountdown');
    if (!countdown) continue;
    const seconds = Math.max(0, Math.floor((nextDrawAt - Date.now()) / 1000));
    const hours = String(Math.floor(seconds / 3600)).padStart(2, '0');
    const minutes = String(Math.floor((seconds % 3600) / 60)).padStart(2, '0');
    const remainingSeconds = String(seconds % 60).padStart(2, '0');
    countdown.textContent = `${hours}:${minutes}:${remainingSeconds}`;
  }
}

function applyLiveCard(name, card) {
  const cardContainer = document.querySelector('#widgetContainer40617 .box-ttkj');
  const selected = [...(cardContainer?.children || [])]
    .find((item) => item.querySelector('.baomalaodaName')?.textContent.trim() === name);
  if (!selected || card.numbers?.length !== 7) return false;

  const issue = selected.querySelector('.baomalaodaQi');
  if (issue) issue.textContent = issueForDisplay(card.issue);

  const nextDrawAt = Date.parse(card.nextDrawAt);
  if (Number.isFinite(nextDrawAt)) {
    liveDrawTimes.set(name, nextDrawAt);
    const nextDrawSummary = selected.querySelector('.baomalaodaInfo3 > div:first-child');
    if (nextDrawSummary) {
      nextDrawSummary.textContent = `${name}下期开奖时间: ${formatNextDrawAt(nextDrawAt)}`;
    }
  }

  const numbers = [...selected.querySelectorAll('.baomalaodaMa')];
  const animals = [...selected.querySelectorAll('.baomalaodaXiao')];
  if (numbers.length < 7 || animals.length < 7) return false;

  card.numbers.forEach((number, index) => {
    const numberNode = numbers[index];
    const animalNode = animals[index];
    const source = numberNode.parentElement;
    const color = card.numberColors?.[index];
    const zodiac = String(card.summary?.[index] || '').trim().split(/\s+/)[0];
    if (!source || !zodiac) return;

    numberNode.textContent = String(number).padStart(2, '0');
    animalNode.textContent = zodiac;
    source.classList.remove('wdrc', 'wdgc', 'wdbc');
    if (color === 'red') source.classList.add('wdrc');
    else if (color === 'green') source.classList.add('wdgc');
    else if (color === 'blue') source.classList.add('wdbc');
  });

  return true;
}

async function refreshLiveDraws() {
  try {
    const response = await fetch('/api/display/mobile/home', {
      headers: { Accept: 'application/json' },
      cache: 'no-store',
    });
    if (!response.ok) throw new Error('MOBILE_DISPLAY_API_UNAVAILABLE');
    const result = await response.json();
    if (result.stale) throw new Error('MOBILE_DISPLAY_API_STALE');
    window.clearTimeout(liveRetryTimer);
    liveRetryDelay = 10_000;
    const matched = [];
    for (const card of result.cards || []) {
      const name = liveLotteries.get(card.key);
      if (name && applyLiveCard(name, card)) matched.push(name);
    }

    const active = document.querySelector('#baomaqgBtn4BoxFatherBoxCopy25Father .bnav-activeam, #baomaqgBtn4BoxFatherBoxCopy25Father .bnav-activexg');
    const activeName = active?.textContent.trim();
    if (matched.includes(activeName)) selectLottery(activeName);
  } catch {
    window.clearTimeout(liveRetryTimer);
    liveRetryTimer = window.setTimeout(() => {
      liveRetryDelay = Math.min(liveRetryDelay * 2, 60_000);
      void refreshLiveDraws();
    }, liveRetryDelay);
  }
}

function selectLottery(name) {
  const selector = document.querySelector('#baomaqgBtn4BoxFatherBoxCopy25Father');
  const cardContainer = document.querySelector('#widgetContainer40617 .box-ttkj');
  if (!selector || !cardContainer) return;

  const tabs = [...selector.querySelectorAll('.baomaqgBtn4BoxFatherBoxCopy25 > div')];
  const cards = [...cardContainer.children].filter((card) => card.querySelector('.baomalaodaName'));
  const selected = cards.find((card) => card.querySelector('.baomalaodaName')?.textContent.trim() === name);
  if (!selected) return;

  for (const tab of tabs) {
    const active = tab.textContent.trim() === name;
    const category = tab.getAttribute('data-nameinfo2');
    tab.classList.toggle('bnav-activeam', active && category === 'baomaqgxinam');
    tab.classList.toggle('bnav-activexg', active && category === 'baomaqgxg');
    tab.setAttribute('aria-pressed', String(active));
  }
  cardContainer.prepend(selected);

  const issue = selected.querySelector('.baomalaodaQi')?.textContent.trim();
  const issueLabel = document.querySelector('#widgetContainer39054 .issue > span');
  if (issue && issueLabel) issueLabel.textContent = `${issue}期开`;

  const numbers = [...selected.querySelectorAll('.baomalaodaMa')];
  const animals = [...selected.querySelectorAll('.baomalaodaXiao')];
  const boxes = [...document.querySelectorAll('#widgetContainer39054 .number-row .number-box:not(.refresh-box)')];
  boxes.forEach((box, index) => {
    const number = numbers[index]?.textContent.trim();
    const animal = animals[index]?.textContent.trim().split('/')[0];
    const ball = box.firstElementChild;
    if (!number || !animal || !ball) return;
    const source = numbers[index].parentElement?.classList;
    const color = source?.contains('wdgc') ? 'green-ball' : source?.contains('wdbc') ? 'blue-ball' : source?.contains('wdrc') ? 'red-ball' : '';
    if (color) ball.className = color;
    const numberLabel = ball.querySelector('.num');
    const animalLabel = ball.querySelector('.draw-txt');
    if (numberLabel) numberLabel.textContent = number;
    if (animalLabel) animalLabel.textContent = animal;
  });
}

document.addEventListener('click', (event) => {
  const tab = event.target.closest('#baomaqgBtn4BoxFatherBoxCopy25Father > div > div');
  if (!tab) return;
  const name = lotteryNames.find((candidate) => tab.textContent.trim() === candidate);
  if (name) selectLottery(name);
}, true);

selectLottery('香港彩');

const floatGroup = document.querySelector('#widgetContainer39200 .float-btn-group');
if (floatGroup && !floatGroup.querySelector('.local-back-to-top')) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'local-back-to-top';
  button.setAttribute('aria-label', '回到顶部');
  button.title = '回到顶部';
  const icon = document.createElement('img');
  icon.src = '/display/mobile-replica/official/back-to-top.png';
  icon.alt = '';
  button.append(icon);
  Object.assign(button.style, {
    display: 'none', width: '55px', height: '55px', padding: '0', border: '0',
    background: 'transparent', cursor: 'pointer',
  });
  floatGroup.append(button);
  button.addEventListener('click', () => window.scrollTo({ top: 0, behavior: 'smooth' }));
  const updateBackToTop = () => { button.style.display = window.scrollY > 100 ? 'flex' : 'none'; };
  button.style.alignItems = 'center';
  button.style.justifyContent = 'center';
  window.addEventListener('scroll', updateBackToTop, { passive: true });
  updateBackToTop();
}

document.addEventListener('click', (event) => {
  const link = event.target.closest('a[data-local-link="isolated"]');
  if (link) event.preventDefault();

  if (event.target.closest('#widgetContainer39200 img[alt="刷新"]')) {
    event.preventDefault();
    window.location.reload();
    return;
  }

  const button = event.target.closest('button');
  if (!button) return;

  const label = `${button.innerText} ${button.getAttribute('aria-label') || ''} ${button.title || ''}`;
  if (/返回顶部|回到顶部|back.?to.?top/i.test(label)) {
    event.preventDefault();
    window.scrollTo({ top: 0, behavior: 'smooth' });
  } else if (/刷新|重新加载/i.test(label)) {
    event.preventDefault();
    window.location.reload();
  }
});

document.addEventListener('submit', (event) => event.preventDefault());

void refreshLiveDraws();
window.setInterval(refreshCountdowns, 1000);
window.setInterval(() => {
  if (!document.hidden) void refreshLiveDraws();
}, 60_000);
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) void refreshLiveDraws();
});
