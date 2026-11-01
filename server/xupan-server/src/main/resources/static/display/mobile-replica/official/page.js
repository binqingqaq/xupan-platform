const lotteryNames = ['新澳彩', '老澳彩', '香港彩', '快乐8'];
const liveLotteries = new Map([
  ['hong-kong', '香港彩'],
]);
const liveDrawTimes = new Map();
let liveRetryTimer;
let liveRetryDelay = 10_000;
let displayObserverHref = null;
let au8HistoryPage = 1;
let au8HistoryOpen = false;

function issueForDisplay(issue) {
  return String(issue || '').replace(/^20\d{2}(?=\d{3}$)/, '');
}

function au8IssueForDisplay(issue) {
  return String(issue || '').slice(-3);
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

function renderAu8Unavailable(message, currentIssueLabel) {
  const card = [...document.querySelectorAll('#widgetContainer40617 .baomalaoda')]
    .find((item) => item.querySelector('.baomalaodaName')?.textContent.trim() === '快乐8');
  if (!card) return;

  const issue = card.querySelector('.baomalaodaQi');
  if (issue) issue.textContent = '--';
  card.querySelectorAll('.baomalaodaMa').forEach((node) => {
    node.textContent = '--';
  });
  card.querySelectorAll('.baomalaodaXiao').forEach((node) => { node.textContent = ''; });
  const currentSummary = card.querySelector('.baomalaodaInfo3 > div:first-child');
  if (currentSummary) currentSummary.textContent = currentIssueLabel || message;
  const countdown = card.querySelector('.baomalaodaCountdown');
  if (!currentIssueLabel && countdown) countdown.textContent = '--:--:--';
  if (!currentIssueLabel) liveDrawTimes.delete('快乐8');
  const active = document.querySelector('#baomaqgBtn4BoxFatherBoxCopy25Father .bnav-activeam, #baomaqgBtn4BoxFatherBoxCopy25Father .bnav-activexg');
  if (active?.textContent.trim() === '快乐8') selectLottery('快乐8');
}

function applyAu8Draw(response) {
  const card = [...document.querySelectorAll('#widgetContainer40617 .baomalaoda')]
    .find((item) => item.querySelector('.baomalaodaName')?.textContent.trim() === '快乐8');
  if (!card) return;

  const currentIssue = response.currentIssue;
  const currentSummary = card.querySelector('.baomalaodaInfo3 > div:first-child');
  let currentIssueLabel = '';
  const deadline = currentIssue?.phase === 'DRAWING'
    ? currentIssue.drawEndsAt
    : currentIssue?.bettingEndsAt;
  if (currentIssue && deadline) {
    const nextDrawAt = Date.parse(deadline);
    if (Number.isFinite(nextDrawAt)) {
      liveDrawTimes.set('快乐8', nextDrawAt);
      const closeTime = new Intl.DateTimeFormat('zh-CN', {
        timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
      }).format(nextDrawAt);
      const phaseLabel = currentIssue.phase === 'DRAWING' ? '开奖中' : '截止下注';
      currentIssueLabel = `本地期 ${au8IssueForDisplay(currentIssue.issueNumber)} ${phaseLabel} ${closeTime}`;
      if (currentSummary) currentSummary.textContent = currentIssueLabel;
    }
  } else {
    if (currentSummary) currentSummary.textContent = '本地 AU8 当前期暂不可用';
    liveDrawTimes.delete('快乐8');
    const countdown = card.querySelector('.baomalaodaCountdown');
    if (countdown) countdown.textContent = '--:--:--';
  }

  const draw = response.latestDraw;
  if (!draw || draw.balls?.length !== 7) {
    renderAu8Unavailable('暂无已确认的七球开奖结果', currentIssueLabel);
    return;
  }

  const issue = card.querySelector('.baomalaodaQi');
  if (issue) issue.textContent = au8IssueForDisplay(draw.issueNumber);
  updateAu8ObserverLinkLabel();
  const numbers = [...card.querySelectorAll('.baomalaodaMa')];
  const labels = [...card.querySelectorAll('.baomalaodaXiao')];
  if (numbers.length < 7 || labels.length < 7) return;

  draw.balls.forEach((ball, index) => {
    numbers[index].textContent = String(ball.number).padStart(2, '0');
    labels[index].textContent = `${ball.fan}番`;
  });

  const active = document.querySelector('#baomaqgBtn4BoxFatherBoxCopy25Father .bnav-activeam, #baomaqgBtn4BoxFatherBoxCopy25Father .bnav-activexg');
  if (active?.textContent.trim() === '快乐8') selectLottery('快乐8');
}

async function refreshAu8Draw() {
  try {
    const response = await fetch('/api/display/mobile/au8', {
      headers: { Accept: 'application/json' },
      cache: 'no-store',
    });
    if (!response.ok) throw new Error('MOBILE_AU8_API_UNAVAILABLE');
    applyAu8Draw(await response.json());
  } catch {
    renderAu8Unavailable('本地七球开奖暂不可用');
  }
}

function updateAu8ObserverLinkLabel() {
  const card = [...document.querySelectorAll('#widgetContainer40617 .baomalaoda')]
    .find((item) => item.querySelector('.baomalaodaName')?.textContent.trim() === '快乐8');
  const title = card?.querySelector('.baomalaodaInfo1 .info1-title');
  if (!title) return;
  if (!displayObserverHref) {
    title.removeAttribute('role');
    title.removeAttribute('tabindex');
    title.removeAttribute('aria-label');
    title.style.cursor = '';
    return;
  }
  const issue = card?.querySelector('.baomalaodaQi')?.textContent.trim() || '';
  title.setAttribute('role', 'link');
  title.setAttribute('tabindex', '0');
  title.setAttribute('aria-label', `打开快乐8第${issue}期只读观察页`);
  title.style.cursor = 'pointer';
}

async function refreshDisplayObserverLink() {
  try {
    const response = await fetch('/api/display/observer-link', {
      headers: { Accept: 'application/json' },
      cache: 'no-store',
    });
    if (!response.ok) throw new Error('DISPLAY_OBSERVER_LINK_UNAVAILABLE');
    const result = await response.json();
    const candidate = result.enabled && typeof result.href === 'string'
      ? new URL(result.href, window.location.href)
      : null;
    displayObserverHref = candidate?.origin === window.location.origin
      && candidate.pathname.startsWith('/display/observe/')
      ? candidate.href
      : null;
  } catch {
    displayObserverHref = null;
  }
  updateAu8ObserverLinkLabel();
}

function openAu8ObserverLink() {
  if (displayObserverHref) window.open(displayObserverHref, '_blank', 'noopener,noreferrer');
}

function renderAu8History(result, errorMessage = '') {
  const card = [...document.querySelectorAll('#widgetContainer40617 .baomalaoda')]
    .find((item) => item.querySelector('.baomalaodaName')?.textContent.trim() === '快乐8');
  if (!card) return;

  let panel = card.querySelector('.xupan-au8-history-panel');
  if (!panel) {
    panel = document.createElement('section');
    panel.className = 'xupan-au8-history-panel';
    Object.assign(panel.style, {
      borderTop: '1px solid #ddd', color: '#333', padding: '6px 0', whiteSpace: 'normal',
    });
    card.querySelector('.baomalaodaInfo3')?.after(panel);
  }
  panel.replaceChildren();

  const header = document.createElement('div');
  Object.assign(header.style, { display: 'flex', justifyContent: 'space-between', alignItems: 'center' });
  const title = document.createElement('strong');
  title.textContent = `AU8 开奖历史${result ? `（共 ${result.total} 期）` : ''}`;
  const close = document.createElement('button');
  close.type = 'button';
  close.textContent = '收起';
  close.addEventListener('click', () => {
    au8HistoryOpen = false;
    panel.remove();
  });
  header.append(title, close);
  panel.append(header);

  if (errorMessage) {
    const error = document.createElement('div');
    error.textContent = errorMessage;
    error.style.padding = '8px 0';
    panel.append(error);
    return;
  }
  if (!result?.items?.length) {
    const empty = document.createElement('div');
    empty.textContent = '暂无已确认的 AU8 七球开奖记录';
    empty.style.padding = '8px 0';
    panel.append(empty);
    return;
  }

  for (const draw of result.items) {
    const row = document.createElement('div');
    Object.assign(row.style, {
      borderBottom: '1px solid #e5e5e5', padding: '6px 0', textAlign: 'left',
    });
    const metadata = document.createElement('div');
    metadata.textContent = `第 ${au8IssueForDisplay(draw.issueNumber)} 期　${new Intl.DateTimeFormat('zh-CN', {
      timeZone: 'Asia/Shanghai', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
    }).format(new Date(draw.drawnAt))}`;
    const balls = document.createElement('div');
    balls.textContent = draw.balls.map((ball) => `${String(ball.number).padStart(2, '0')}(${ball.fan}番)`).join('　');
    row.append(metadata, balls);
    panel.append(row);
  }

  const pageCount = Math.max(1, Math.ceil(result.total / result.pageSize));
  const pagination = document.createElement('div');
  Object.assign(pagination.style, { display: 'flex', justifyContent: 'space-between', paddingTop: '6px' });
  const previous = document.createElement('button');
  previous.type = 'button';
  previous.textContent = '上一页';
  previous.disabled = result.page <= 1;
  previous.addEventListener('click', () => { void refreshAu8History(result.page - 1); });
  const pageLabel = document.createElement('span');
  pageLabel.textContent = `第 ${result.page}/${pageCount} 页`;
  const next = document.createElement('button');
  next.type = 'button';
  next.textContent = '下一页';
  next.disabled = result.page >= pageCount;
  next.addEventListener('click', () => { void refreshAu8History(result.page + 1); });
  pagination.append(previous, pageLabel, next);
  panel.append(pagination);
}

async function refreshAu8History(page = au8HistoryPage) {
  au8HistoryOpen = true;
  au8HistoryPage = page;
  renderAu8History(null, '正在读取 AU8 开奖历史');
  try {
    const response = await fetch(`/api/display/mobile/au8/history?page=${page}&pageSize=10`, {
      headers: { Accept: 'application/json' },
      cache: 'no-store',
    });
    if (!response.ok) throw new Error('AU8_HISTORY_UNAVAILABLE');
    const result = await response.json();
    au8HistoryPage = result.page;
    renderAu8History(result);
  } catch {
    renderAu8History(null, 'AU8 开奖历史暂时不可用');
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
    if (!ball) return;
    const source = numbers[index].parentElement?.classList;
    const color = source?.contains('wdgc') ? 'green-ball' : source?.contains('wdbc') ? 'blue-ball' : source?.contains('wdrc') ? 'red-ball' : '';
    if (color) ball.className = color;
    const numberLabel = ball.querySelector('.num');
    const animalLabel = ball.querySelector('.draw-txt');
    if (numberLabel) numberLabel.textContent = number || '--';
    if (animalLabel) animalLabel.textContent = animal;
  });
}

document.addEventListener('click', (event) => {
  const tab = event.target.closest('#baomaqgBtn4BoxFatherBoxCopy25Father > div > div');
  if (!tab) return;
  const name = lotteryNames.find((candidate) => tab.textContent.trim() === candidate);
  if (name) {
    selectLottery(name);
    if (name === '快乐8') void refreshAu8Draw();
  }
}, true);

selectLottery('香港彩');
const au8Card = [...document.querySelectorAll('#widgetContainer40617 .baomalaoda')]
  .find((item) => item.querySelector('.baomalaodaName')?.textContent.trim() === '快乐8');
const au8LiveLink = au8Card?.querySelector('.baomalaodaLive');
if (au8LiveLink) au8LiveLink.textContent = '最新开奖';

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
  const observerTitle = event.target.closest('#widgetContainer40617 .baomalaodaInfo1 .info1-title');
  if (observerTitle && displayObserverHref
      && observerTitle.closest('.baomalaoda')?.querySelector('.baomalaodaName')?.textContent.trim() === '快乐8') {
    event.preventDefault();
    openAu8ObserverLink();
    return;
  }

  const au8Link = event.target.closest('#widgetContainer40617 .baomalaodaHistory');
  if (au8Link && au8Link.closest('.baomalaoda')?.querySelector('.baomalaodaName')?.textContent.trim() === '快乐8') {
    event.preventDefault();
    if (au8Link.textContent.includes('历史')) void refreshAu8History(1);
    else void refreshAu8Draw();
    return;
  }
  if (event.target.closest('#widgetContainer40617 .baomalaodaRefresh')) {
    event.preventDefault();
    void refreshAu8Draw();
    if (au8HistoryOpen) void refreshAu8History(au8HistoryPage);
    return;
  }

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

document.addEventListener('keydown', (event) => {
  const observerTitle = event.target.closest('#widgetContainer40617 .baomalaodaInfo1 .info1-title[role="link"]');
  if (!observerTitle || (event.key !== 'Enter' && event.key !== ' ')) return;
  if (observerTitle.closest('.baomalaoda')?.querySelector('.baomalaodaName')?.textContent.trim() !== '快乐8') return;
  event.preventDefault();
  openAu8ObserverLink();
});

document.addEventListener('submit', (event) => event.preventDefault());

void refreshLiveDraws();
renderAu8Unavailable('正在读取本地七球开奖');
void refreshAu8Draw();
void refreshDisplayObserverLink();
window.setInterval(refreshCountdowns, 1000);
window.setInterval(() => {
  if (!document.hidden) {
    void refreshLiveDraws();
    void refreshAu8Draw();
    void refreshDisplayObserverLink();
  }
}, 15_000);
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) {
    void refreshLiveDraws();
    void refreshAu8Draw();
    void refreshDisplayObserverLink();
  }
});
