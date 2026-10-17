(function () {
  'use strict';

  const issue = '31401354';
  const latestDrawIssue = '31401353';
  const quickAmountStorageKey = 'hby019-player-quick-amounts';
  const defaultQuickAmounts = ['50', '100', '200', '500', '1000'];
  const isValidQuickAmounts = (values) => Array.isArray(values)
    && values.length === 5
    && values.every((value) => /^\d{1,8}(?:\.\d{1,2})?$/.test(String(value)));
  let quickAmounts = (() => {
    try {
      const saved = JSON.parse(window.localStorage?.getItem(quickAmountStorageKey) || 'null');
      return isValidQuickAmounts(saved) ? saved.map(String) : [...defaultQuickAmounts];
    } catch {
      return [...defaultQuickAmounts];
    }
  })();
  const hydrateQuickAmounts = () => {
    document.querySelectorAll('.kuaikey .numberStyle').forEach((control, index) => {
      if (index < quickAmounts.length) control.textContent = quickAmounts[index];
    });
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', hydrateQuickAmounts, { once: true });
  else hydrateQuickAmounts();
  const recentMessages = [
    { ids: 910001, rank: 0, wxid: 'demo-a', atUser: '', name: '示例玩家A', type: 1, content: '第1球/3通12/100', time: '20:39', headimg: '/hby019-demo/source/static/reference-player/avatars/u__chat_img8.jpg' },
    { ids: 910002, rank: 2, wxid: 0, atUser: 'ck', name: '机器人', type: 2, content: '@示例玩家A  攻击成功，使用粮草100, 剩余粮草：9900.00', time: '20:39' },
    { ids: 910003, rank: 0, wxid: 'demo-b', atUser: '', name: '示例玩家B', type: 1, content: '第8球/双50', time: '20:39', headimg: '/hby019-demo/source/static/reference-player/avatars/u__chat_img17.jpg' },
    { ids: 910004, rank: 2, wxid: 0, atUser: 'ck', name: '机器人', type: 2, content: '@示例玩家B  攻击成功，使用粮草50, 剩余粮草：9950.00', time: '20:39' },
    { ids: 910005, rank: 0, wxid: 'demo-c', atUser: '', name: '示例玩家C', type: 1, content: '上100', time: '20:40', headimg: '/hby019-demo/source/static/reference-player/avatars/u__chat_img29.jpg' },
    { ids: 910006, rank: 2, wxid: 0, atUser: 'kai5', name: '机器人', type: 2, content: '@示例玩家C, 上分100,待审批!', time: '20:40' },
    { ids: 910007, rank: 0, wxid: 'demo-d', atUser: '', name: '示例玩家D', type: 1, content: '第6球/06特20', time: '20:40', headimg: '/hby019-demo/source/static/reference-player/avatars/u__chat_img33.jpg' },
    { ids: 910008, rank: 2, wxid: 0, atUser: 'ck', name: '机器人', type: 2, content: '@示例玩家D  攻击成功，使用粮草20, 剩余粮草：9980.00', time: '20:40' },
    { ids: 910009, rank: 0, wxid: 'demo-b', atUser: '', name: '示例玩家B', type: 1, content: '流水', time: '20:41', headimg: '/hby019-demo/source/static/reference-player/avatars/u__chat_img17.jpg' },
    { ids: 910010, rank: 2, wxid: 0, atUser: 'kai6', name: '机器人', type: 2, content: '@示例玩家B:\n累计今日流水:12800.00\n盈亏:-320.00\n期数:64', time: '20:41' },
    { ids: 910011, rank: 2, wxid: 0, atUser: 'stop', name: '机器人', type: 2, content: '离封盘还剩30秒！', time: '20:41' },
    { ids: 910012, rank: 1, wxid: 'local-demo', atUser: '', name: '大家好', type: 1, content: '第8球/双50', time: '20:42', headimg: '/hby019-demo/source/static/reference-player/avatars/u__chat_img13.jpg' },
    { ids: 910013, rank: 2, wxid: 0, atUser: 'ck', name: '机器人', type: 2, content: 'ck 本期投注已记录。', time: '20:42' },
    { ids: 910014, rank: 2, wxid: 0, atUser: 'kai4', name: '机器人', type: 1, content: '本期核对完成\n-----------\n以上为核对结果。', time: '20:43' },
    { ids: 910015, rank: 2, wxid: 0, atUser: 'tuidan_notice', name: '机器人', type: 1, content: '退单通知：示例玩家D的订单已撤回。', time: '20:43' },
    { ids: 910016, rank: 0, wxid: 'demo-a', atUser: '', name: '示例玩家A', type: 1, content: '第3球/3无1/50\n第7球/4正20', time: '20:44', headimg: '/hby019-demo/source/static/reference-player/avatars/u__chat_img8.jpg' },
    { ids: 910017, rank: 2, wxid: 0, atUser: 'ck', name: '机器人', type: 2, content: '@示例玩家A  攻击成功，使用粮草70, 剩余粮草：9830.00', time: '20:44' },
    { ids: 910018, rank: 2, wxid: 0, atUser: 'kai', name: '机器人', type: 1, content: `${issue}期开始汇总。`, time: '20:45' },
    { ids: 910019, rank: 2, wxid: 0, atUser: 'kai2', name: '机器人', type: 1, content: `${issue}期汇总：总额200，人数4。`, time: '20:45' },
    { ids: 910020, rank: 2, wxid: 0, atUser: 'kai3', name: '机器人', type: 1, content: '本期汇总\n第1球：100\n第8球：50\n合计：150', time: '20:45' },
    { ids: 910021, rank: 2, wxid: 0, atUser: 'kai5', name: '机器人', type: 1, content: '退单通知：示例玩家D的订单已撤回。', time: '20:45' },
    { ids: 910022, rank: 2, wxid: 0, atUser: '!', name: '机器人', type: 1, content: '公告：请核对当前期号。', time: '20:45' },
    { ids: 910023, rank: 2, wxid: 0, atUser: 'last', name: '机器人', type: 2, content: '本期最后提醒。', time: '20:45' },
  ];
  const historyMessages = [
    { ids: 909997, rank: 0, wxid: 'demo-c', atUser: '', name: '示例玩家C', type: 1, content: '第6球/大小各50', time: '20:37', headimg: '/hby019-demo/source/static/reference-player/avatars/u__chat_img29.jpg' },
    { ids: 909998, rank: 2, wxid: 0, atUser: 'kai6', name: '机器人', type: 2, content: '31401352期已开奖：01,03,08,12,18,24,31,46。', time: '20:38' },
  ];
  const recentHistory = [
    { QiHao: latestDrawIssue, Code: '19,18,10,02,05,11,01,06', fan: '虎', da: '大', dan: '双' },
    { QiHao: '31401352', Code: '01,03,08,12,18,24,31,46', fan: '龙', da: '小', dan: '单' },
    { QiHao: '31401351', Code: '02,05,10,14,19,27,33,42', fan: '马', da: '大', dan: '双' },
  ];
  const records = [
    { status: 0, qihao: issue, qiuNum: '8', gameType: '17', gname: '番摊', text: '第8球/大小/100', ying: 0, score: '100.00', yingScore: '0.00', dtGenerate: '20:40:00' },
    { status: 0, qihao: issue, qiuNum: '3', gameType: '17', gname: '番摊', text: '第3球/3通14/50', ying: 0, score: '50.00', yingScore: '0.00', dtGenerate: '20:38:00' },
    { status: 0, qihao: issue, qiuNum: '6', gameType: '17', gname: '番摊', text: '第6球/06特/20', ying: 0, score: '20.00', yingScore: '0.00', dtGenerate: '20:36:00' },
    { status: 0, qihao: issue, qiuNum: '2', gameType: '17', gname: '番摊', text: '第2球/单/30', ying: 0, score: '30.00', yingScore: '0.00', dtGenerate: '20:35:00' },
    { status: 0, qihao: '31401353', qiuNum: '1', gameType: '17', gname: '番摊', text: '第1球/1番/40', ying: 0, score: '40.00', yingScore: '0.00', dtGenerate: '20:30:00' },
    { status: 0, qihao: '31401353', qiuNum: '5', gameType: '17', gname: '番摊', text: '第5球/12角/25', ying: 0, score: '25.00', yingScore: '0.00', dtGenerate: '20:28:00' },
    { status: 1, qihao: '31401351', qiuNum: '6', gameType: '17', gname: '番摊', text: '第6球/大小/50', ying: 1, score: '50.00', yingScore: '90.00', dtGenerate: '20:35:00' },
    { status: 1, qihao: '31401351', qiuNum: '2', gameType: '17', gname: '番摊', text: '第2球/单/40', ying: 0, score: '40.00', yingScore: '0.00', dtGenerate: '20:31:00' },
    { status: 1, qihao: '31401350', qiuNum: '5', gameType: '17', gname: '番摊', text: '第5球/12无3/30', ying: 1, score: '30.00', yingScore: '44.25', dtGenerate: '20:25:00' },
    { status: 1, qihao: '31401350', qiuNum: '1', gameType: '17', gname: '番摊', text: '第1球/大/20', ying: 0, score: '20.00', yingScore: '0.00', dtGenerate: '20:21:00' },
    { status: 1, qihao: '31401349', qiuNum: '8', gameType: '17', gname: '番摊', text: '第8球/03特/10', ying: 1, score: '10.00', yingScore: '180.00', dtGenerate: '20:16:00' },
    { status: 1, qihao: '31401349', qiuNum: '4', gameType: '17', gname: '番摊', text: '第4球/双/15', ying: 0, score: '15.00', yingScore: '0.00', dtGenerate: '20:12:00' },
    { status: 1, qihao: '31401348', qiuNum: '7', gameType: '17', gname: '番摊', text: '第7球/3番/50', ying: 4, score: '50.00', yingScore: '0.00', dtGenerate: '20:08:00' },
    { status: 1, qihao: '31401348', qiuNum: '1', gameType: '17', gname: '番摊', text: '第1球/1角/50', ying: 5, score: '50.00', yingScore: '0.00', dtGenerate: '20:04:00' },
  ];

  if (window.layui) {
    window.layui.modules.layer = '../modules/layer';
    window.layui.define((exportModule) => exportModule('form', {
      on(eventName, callback) {
        const match = /^([^(]+)\(([^)]+)\)$/.exec(eventName);
        if (!match || typeof callback !== 'function') return this;
        document.addEventListener(match[1], (event) => {
          const target = event.target;
          const filterSelector = `[lay-filter="${match[2]}"]`;
          const trigger = target?.matches?.(filterSelector) ? target : target?.querySelector?.(filterSelector);
          const form = trigger?.closest?.('form') || (target?.tagName === 'FORM' ? target : null);
          if (!trigger || !form) return;
          const field = Object.fromEntries(new window.FormData(form).entries());
          if (callback({ field }) === false) {
            event.preventDefault();
            event.stopPropagation();
          }
        });
        return this;
      },
      render() { return this; },
    }));
    window.layui.define((exportModule) => exportModule('table', {}));
    window.layui.cache.modules.form = true;
    window.layui.cache.modules.table = true;
  }

  function clone(value) {
    return JSON.parse(JSON.stringify(value));
  }

  function payload(pathname, options) {
    switch (pathname) {
      case '/chat/index/timer':
        return { qh: issue, kj: { dtOpen: 180, Code: '19,18,10,02,05,11,01,06', QiHao: latestDrawIssue, type: 17 } };
      case '/chat/index/msgrecord':
        return { Message: recentMessages, isVip: 0 };
      case '/chat/index/autoinfo':
        return {
          islogin: true,
          qh: issue,
          Message: [],
          list: [],
          online: '在线 1',
          yingkui: '-320.00',
          liushui: '12800.00',
          txt: '累计今日流水',
          yijie: '96.00',
          weijie: '100.00',
          rbuser: { score: '100.00', NickName: '大家好' },
          isVip: 0,
          daoji: 1,
          flyFailPopup: [],
        };
      case '/chat/index/gethis':
        return { Message: historyMessages };
      case '/chat/index/getrecord':
        return records;
      case '/chat/index/gethistory':
        return recentHistory;
      case '/chat/index/setnum':
        if (String(options.type || 'GET').toUpperCase() !== 'POST') return undefined;
        {
          const values = String(options.data?.str || '').split(',');
          if (!isValidQuickAmounts(values)) return undefined;
          quickAmounts = values;
          try {
            window.localStorage?.setItem(quickAmountStorageKey, JSON.stringify(quickAmounts));
          } catch {
            // Keep the current page usable when browser storage is unavailable.
          }
          return { code: 0, amounts: quickAmounts };
        }
      default:
        return undefined;
    }
  }

  function responseFor(options) {
    const url = new URL(options.url || '', location.href);
    if (url.origin !== location.origin) return undefined;
    return payload(url.pathname, options);
  }

  function blockedNotice(message) {
    if (window.layui?.layer?.msg) window.layui.layer.msg(message, { time: 1800 });
    else if (window.layer?.msg) window.layer.msg(message, { time: 1800 });
  }

  let socketMessageId = 920000;
  function localCommandReply(command) {
    const normalized = String(command || '').trim().replace(/^第\s*\d+\s*球\s*\/?\s*/, '');
    const time = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false });
    const reply = (content, atUser = 'kai6', type = 1) => ({
      ids: ++socketMessageId,
      rank: 2,
      type,
      name: '机器人',
      wxid: 0,
      wid: '机器人',
      atUser,
      content,
      time,
      headimg: '/hby019-demo/source/static/reference-player/avatars/jqr.png',
      isVip: 0,
    });

    if (normalized === '流水') {
      return reply('@大家好:\n累计今日流水:12800.00\n盈亏:-320.00\n期数:64\n本地演示数据');
    }
    if (normalized === '历史') {
      return reply(`本地演示开奖历史\n${recentHistory.map((item) => `${item.QiHao}期：${item.Code}`).join('\n')}`);
    }
    if (/^查(?:分)?$/.test(normalized)) {
      return reply('@大家好 剩余虚拟积分：100.00（本地演示）', 'kai5');
    }
    if (/^(?:玩法|说明)$/.test(normalized)) return reply('wanfa-local-fixture', 'kai6');
    if (normalized === '取消') return reply('本地演示：当前没有可取消的记录，未修改任何数据。', 'ck', 2);
    if (/^上\s*\d+(?:\.\d{1,2})?$/.test(normalized)) {
      return reply('@大家好，上分申请已进入本地演示待审批状态；虚拟积分未变更。', 'kai5');
    }
    if (/^下\s*\d+(?:\.\d{1,2})?$/.test(normalized)) {
      return reply('@大家好，下分申请已进入本地演示待审批状态；虚拟积分未变更。', 'kai5');
    }
    if (/\S/.test(normalized)) {
      return reply(`@大家好 本地演示已收到“${normalized}”，未提交服务器或记录投注。`, 'ck', 2);
    }
    return null;
  }

  function makeRequest(options, response, errorText) {
    let state = 'pending';
    let value;
    const callbacks = { done: [], fail: [], always: [] };
    const request = {
      abort() { state = 'aborted'; },
      done(callback) { callbacks.done.push(callback); if (state === 'resolved') callback(value, 'success', request); return request; },
      fail(callback) { callbacks.fail.push(callback); if (state === 'rejected') callback(request, 'error', errorText); return request; },
      always(callback) { callbacks.always.push(callback); if (state !== 'pending') callback(value, state === 'resolved' ? 'success' : 'error', request); return request; },
      then(resolve, reject) { return new Promise((res, rej) => { request.done((data) => { resolve?.(data); res(data); }); request.fail((xhr, status, error) => { reject?.(error); rej(error); }); }); },
    };

    window.setTimeout(() => {
      if (state === 'aborted') return;
      if (response === undefined) {
        state = 'rejected';
        value = request;
        options.error?.call(options.context || options, request, 'error', errorText || 'local-only');
        callbacks.fail.forEach((callback) => callback(request, 'error', errorText || 'local-only'));
        callbacks.always.forEach((callback) => callback(request, 'error', errorText || 'local-only'));
        options.complete?.call(options.context || options, request, 'error');
        return;
      }
      state = 'resolved';
      value = clone(response);
      options.success?.call(options.context || options, value, 'success', request);
      callbacks.done.forEach((callback) => callback(value, 'success', request));
      callbacks.always.forEach((callback) => callback(value, 'success', request));
      options.complete?.call(options.context || options, request, 'success');
    }, 20);
    return request;
  }

  const jquery = window.jQuery;
  if (jquery?.ajax) {
    jquery.ajax = function (input, extra) {
      const options = typeof input === 'string' ? { ...(extra || {}), url: input } : { ...(input || {}) };
      const url = new URL(options.url || '', location.href);
      const response = responseFor(options);
      if (response === undefined) {
        blockedNotice('本地演示：此操作不会发送到服务器');
        return makeRequest(options, undefined, 'local-only');
      }
      return makeRequest(options, response);
    };
  }

  class LocalSocket {
    static CONNECTING = 0;
    static OPEN = 1;
    static CLOSING = 2;
    static CLOSED = 3;

    constructor(rawUrl) {
      const url = new URL(rawUrl, location.href);
      if (url.host !== location.host || url.pathname !== '/ws') throw new TypeError('Local demo socket only');
      this.readyState = LocalSocket.CONNECTING;
      this.url = `${url.protocol}//${url.host}/ws`;
      window.setTimeout(() => {
        if (this.readyState !== LocalSocket.CONNECTING) return;
        this.readyState = LocalSocket.OPEN;
        this.onopen?.({ target: this });
      }, 0);
    }

    send(rawData) {
      if (this.readyState !== LocalSocket.OPEN) throw new DOMException('Socket is not open', 'InvalidStateError');
      if (typeof rawData !== 'string') throw new TypeError('Local demo accepts text frames only');
      let payload;
      try { payload = JSON.parse(rawData); } catch { payload = {}; }
      window.setTimeout(() => {
        const bubble = document.querySelector('.msgright:last-of-type');
        const loader = bubble?.querySelector('.msg-loader');
        if (loader) {
          loader.remove();
          const status = document.createElement('small');
          status.className = 'local-only-status';
          status.textContent = '本地演示';
          bubble.querySelector('.fpop')?.append(status);
        }
        const response = localCommandReply(payload.cmd);
        if (response) this.onmessage?.({ target: this, data: JSON.stringify(response) });
      }, 80);
    }

    close() {
      this.readyState = LocalSocket.CLOSED;
    }
  }

  window.WebSocket = LocalSocket;
  window.fetch = function () {
    return Promise.reject(new TypeError('Network access is disabled in this local demo'));
  };
  if (navigator.sendBeacon) navigator.sendBeacon = () => false;

  document.addEventListener('submit', (event) => {
    if (event.target?.matches?.('form[lay-filter="formDemo"]') || event.target?.querySelector?.('[lay-filter="formDemo"]')) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    blockedNotice('本地演示：表单未提交');
  }, true);
  document.addEventListener('click', (event) => {
    const anchor = event.target.closest?.('a[href]');
    if (!anchor) return;
    const target = new URL(anchor.href, location.href);
    if (target.origin !== location.origin) {
      event.preventDefault();
      event.stopImmediatePropagation();
      blockedNotice('本地演示：外部链接已禁用');
    }
  }, true);

  window.localPlayerBridge = Object.freeze({
    mode: 'local-fixtures-only',
    routes: ['/chat/index/timer', '/chat/index/msgrecord', '/chat/index/autoinfo', '/chat/index/gethis', '/chat/index/getrecord', '/chat/index/gethistory', '/chat/index/setnum'],
    fixtureCounts: { messages: recentMessages.length + historyMessages.length, records: records.length, history: recentHistory.length },
  });

})();
