/* Local-only API adapter for the mirrored client. No request is forwarded to the reference site. */
(function ($) {
  if (!$) return;

  const DB_KEY = 'local-reference-api-v1';
  const DEFAULT_AVATAR = '/hby019-demo/source/static/xz/admin/defaultheadimg.jpg';
  const AVATAR_POOL = Array.from({ length: 96 }, function (_, i) {
    return '/hby019-demo/source/static/xz/admin/avatars/avatar-' + String(i + 1).padStart(3, '0') + '.jpg';
  });
  const DEMO_QR = '/hby019-demo/source/static/xz/admin/local-demo-code.svg';
  const localKeys = ['fengpanPos', 'baowangPos', 'sxCount'];

  function isLocalAvatar(value) {
    return value === DEFAULT_AVATAR || AVATAR_POOL.indexOf(value) !== -1;
  }

  const playerSeed = [
    [1885, '大家好', '105.30', 0, 0], [1889, '甲乙丙丁', '100.65', 1, 0],
    [1886, '收米', '3.00', 0, 0], [1888, '帝皇侠', '2.00', 1, 1],
    [1811, '后年', '1.00', 0, 0], [1887, '收地豆', '1.00', 0, 0],
    [1884, '好日子', '0.00', 0, 0], [1890, '华仔', '0.00', 0, 0],
    [1891, '大米粉77', '0.00', 0, 0], [1894, '新世纪', '0.00', 0, 0]
  ].map(function (row) {
    return {
      id: row[0], wxid: 'local-' + row[0], NickName: row[1], score: row[2], kind: 0,
      isBlack: row[3], isrobot: 0, chi: 0, zhui: 0, online: row[4], nickChanged: 0,
      avatar: DEFAULT_AVATAR, imgName: DEFAULT_AVATAR, link_expire: 0, link_expire_days: 0
    };
  });
  const trustSeed = Array.from({ length: 18 }, function (_, i) {
    return {
      id: 2101 + i, wxid: 'local-t-' + (2101 + i), NickName: '模拟托' + String(i + 1).padStart(2, '0'),
      score: i === 0 ? '60.00' : i === 1 ? '35.00' : '0.00', kind: 1, isBlack: 0,
      isrobot: 1, isauto: i === 0 ? 1 : 0, chi: 0, zhui: 0, online: 0, nickChanged: 0,
      avatar: DEFAULT_AVATAR, imgName: DEFAULT_AVATAR, link_expire: 0, link_expire_days: 0,
      tuoMinMax: i === 0 ? '1000-3000' : '30-300', tuo_round_tens: i === 0 ? 0 : 1,
      tuo_allowed_plays: i === 0 ? '' : '角,正,番'
    };
  });
  const commandSeed = [
    { id: 70101, gameName: '澳8番摊/1-8球', qihao: '31401224', wid: '演示玩家A', text: '第8球/3无4/172', score: 172, flyers_status: 0, isTuo: 0, chi: 0 },
    { id: 70102, gameName: '澳8番摊/1-8球', qihao: '31401224', wid: '演示玩家B', text: '第2球/4严1/508', score: 508, flyers_status: 3, isTuo: 0, chi: 0 },
    { id: 70201, gameName: '澳8番摊/1-8球', qihao: '31401224', wid: '模拟托01', text: '第5球/2通31/90', score: 90, flyers_status: 0, isTuo: 1, chi: 0 },
    { id: 70202, gameName: '澳8番摊/1-8球', qihao: '31401224', wid: '模拟托02', text: '第8球/15特56', score: 56, flyers_status: 3, isTuo: 1, chi: 0 }
  ];
  const previousCommandSeed = [
    { id: 70001, gameName: '澳8番摊/1-8球', qihao: '31401223', wid: '演示玩家A', text: '第4球/3无4/120', score: 120, flyers_status: 3, isTuo: 0, chi: 0 },
    { id: 70002, gameName: '澳8番摊/1-8球', qihao: '31401223', wid: '模拟托01', text: '第1球/2加41/314', score: 314, flyers_status: 0, isTuo: 1, chi: 0 }
  ];
  const applicationSeed = [
    { id: 8801, type: 0, score: 100, nickName: '试玩玩家', wxid: 'local-1885', isTuo: 0, isTuoManual: 0 },
    { id: 8802, type: 1, score: 300, nickName: '模拟托01', wxid: 'local-t-2101', isTuo: 1, isTuoManual: 1 }
  ];

  function clone(value) { return JSON.parse(JSON.stringify(value)); }
  function initial() {
    return {
      users: clone(playerSeed.concat(trustSeed)),
      applications: clone(applicationSeed),
      ledger: [{ kind: 0, userWxid: 'local-1889', name: '甲乙丙丁', delta: 100, afterScore: 100.65, time: '2026-10-14 15:11' }],
      nickLogs: {},
      settings: {
        open: true, odds: 95, specialOdds: 18, rebate: 1, normalRebate: 5,
        hold: 100, cancel: 20, adapter: true, hideMsg: 0, cmdTuoCount: 20,
        limits: {}, wp: { online: true, auto: 0, withdraw: 0, money: 50000, betting: 0, name: 'lx' },
        tuo: { activeRate: 90, maxBets: 3, cancelledCmdIds: [] }
      }
    };
  }
  function normalizeUser(user) {
    user.score = Number(user.score || 0).toFixed(2);
    if (typeof user.kind === 'undefined') user.kind = 0;
    if (typeof user.isBlack === 'undefined') user.isBlack = 0;
    if (typeof user.isrobot === 'undefined') user.isrobot = user.kind ? 2 : 0;
    if (user.kind && /^local-t-/.test(String(user.wxid))) {
      user.isrobot = 1;
      if (typeof user.isauto === 'undefined') user.isauto = 1;
      if (!user.tuoMinMax) user.tuoMinMax = '1000-3000';
      if (typeof user.tuo_round_tens === 'undefined') user.tuo_round_tens = 0;
      if (typeof user.tuo_allowed_plays === 'undefined') user.tuo_allowed_plays = '';
    }
    if (typeof user.chi === 'undefined') user.chi = 0;
    if (typeof user.zhui === 'undefined') user.zhui = 0;
    if (typeof user.nickChanged === 'undefined') user.nickChanged = 0;
    if (!isLocalAvatar(user.avatar)) user.avatar = DEFAULT_AVATAR;
    if (!isLocalAvatar(user.imgName)) user.imgName = user.avatar;
    user.link = location.origin + '/hby019-demo/player.html?uid=' + encodeURIComponent(user.wxid);
    if (typeof user.link_expire === 'undefined') user.link_expire = 0;
    if (typeof user.link_expire_days === 'undefined') user.link_expire_days = 0;
    return user;
  }
  function readDb() {
    try {
      const saved = JSON.parse(localStorage.getItem(DB_KEY) || 'null');
      if (!saved || !Array.isArray(saved.users)) return initial();
      const fresh = initial();
      saved.users = saved.users.map(normalizeUser);
      saved.applications = Array.isArray(saved.applications) ? saved.applications : fresh.applications;
      saved.ledger = Array.isArray(saved.ledger) ? saved.ledger : fresh.ledger;
      saved.nickLogs = saved.nickLogs || {};
      saved.settings = Object.assign({}, fresh.settings, saved.settings || {});
      saved.settings.wp = Object.assign({}, fresh.settings.wp, saved.settings.wp || {});
      saved.settings.tuo = Object.assign({}, fresh.settings.tuo, saved.settings.tuo || {});
      saved.settings.limits = Object.assign({}, fresh.settings.limits, saved.settings.limits || {});
      return saved;
    } catch (_) {
      return initial();
    }
  }
  if (new URLSearchParams(location.search).get('reset') === '1') {
    localStorage.removeItem(DB_KEY);
    localKeys.forEach(function (key) { localStorage.removeItem(key); });
  }
  let db = readDb();
  function save() { localStorage.setItem(DB_KEY, JSON.stringify(db)); }

  let xupanRequestSequence = 0;
  const xupanRequests = new Map();
  const xupanPlayerDetails = Object.create(null);
  function requestXupan(path, data, success, error) {
    const requestId = 'player-' + (++xupanRequestSequence);
    const timeout = setTimeout(function () {
      xupanRequests.delete(requestId);
      if (typeof error === 'function') error({ status: 0 }, 'timeout');
    }, 30000);
    xupanRequests.set(requestId, {
      path: path,
      data: parseBody(data),
      success: success,
      error: error,
      timeout: timeout
    });
    window.parent.postMessage({ type: 'xupan-player-request', requestId: requestId, path: path, data: parseBody(data) }, location.origin);
  }

  window.addEventListener('message', function (event) {
    if (event.origin !== location.origin || event.source !== window.parent) return;
    const message = event.data;
    if (!message || message.type !== 'xupan-player-response' || !message.requestId) return;
    const pending = xupanRequests.get(message.requestId);
    if (!pending) return;
    clearTimeout(pending.timeout);
    xupanRequests.delete(message.requestId);
    if (message.error) {
      if (typeof pending.error === 'function') pending.error({ status: message.status || 500, responseJSON: message.error }, 'error');
      return;
    }
    if (pending.path === '/xupan/player/detail' && message.data && message.data.wxid) {
      xupanPlayerDetails[String(message.data.wxid)] = clone(message.data);
    } else if (pending.path === '/rbt/index/updatenickname') {
      const user = String(pending.data.wxid || '');
      if (xupanPlayerDetails[user]) xupanPlayerDetails[user].NickName = String(pending.data.newNickName || '');
    } else if (pending.path === '/rbt/index/setlinkexpire') {
      const user = String(pending.data.wxid || '');
      if (xupanPlayerDetails[user] && message.data) {
        xupanPlayerDetails[user].link_expire_days = message.data.link_expire_days;
        xupanPlayerDetails[user].link_expire = message.data.expire;
      }
    } else if (pending.path === '/rbt/index/getuserlink') {
      const user = String(pending.data.wxid || '');
      if (xupanPlayerDetails[user] && message.data) {
        xupanPlayerDetails[user].link = message.data.Data;
        xupanPlayerDetails[user].link_expire = message.data.link_expire;
        xupanPlayerDetails[user].link_expire_days = message.data.link_expire_days;
      }
    } else if (pending.path === '/rbt/index/setblack') {
      const user = String(pending.data.wxid || '');
      if (xupanPlayerDetails[user]) xupanPlayerDetails[user].isBlack = Number(pending.data.type) === 1 ? 1 : 0;
    } else if (pending.path === '/rbt/index/setzhui') {
      const user = String(pending.data.wxid || '');
      if (xupanPlayerDetails[user]) xupanPlayerDetails[user].zhui = Number(pending.data.type) === 1 ? 1 : 0;
    } else if (pending.path === '/rbt/index/setauto') {
      const user = String(pending.data.wxid || '');
      if (xupanPlayerDetails[user]) xupanPlayerDetails[user].isauto = Number(pending.data.type) === 1 ? 1 : 0;
    } else if (pending.path === '/rbt/index/changeheadimage2') {
      const user = String(pending.data.wxid || '');
      if (xupanPlayerDetails[user]) xupanPlayerDetails[user].imgName = message.data;
    }
    if (pending.path === '/rbt/index/getmembers' && message.data && Array.isArray(message.data.data)) {
      message.data.data = message.data.data.map(function (player) {
        return Object.assign({}, player, xupanPlayerDetails[String(player.wxid)] || {});
      });
    } else if (pending.path === '/rbt/index/needhandle' && message.data && Array.isArray(message.data.users)) {
      message.data.users.forEach(function (player) {
        if (xupanPlayerDetails[String(player.wxid)]) {
          player.zhui = xupanPlayerDetails[String(player.wxid)].zhui;
          player.isBlack = xupanPlayerDetails[String(player.wxid)].isBlack;
          player.isauto = xupanPlayerDetails[String(player.wxid)].isauto;
        }
      });
    }
    if (typeof pending.success === 'function') pending.success(message.data, 'success', { status: 200 });
  });

  function memberList(type) { return db.users.filter(function (user) { return Number(user.kind) === Number(type || 0); }); }
  function memberTotal(type) {
    return memberList(type).reduce(function (sum, user) { return sum + Number(user.score || 0); }, 0).toFixed(2);
  }
  function findUser(value) {
    if (value && typeof value === 'object') value = value.wxid || value.uid || value.id;
    return db.users.find(function (user) { return String(user.wxid) === String(value) || String(user.id) === String(value); });
  }
  function parseBody(body) {
    if (!body) return {};
    if (typeof body === 'string') {
      return body.split('&').reduce(function (result, pair) {
        const parts = pair.split('=');
        if (parts[0]) result[decodeURIComponent(parts[0])] = decodeURIComponent(parts.slice(1).join('=') || '');
        return result;
      }, {});
    }
    if (typeof FormData !== 'undefined' && body instanceof FormData) {
      const result = {};
      body.forEach(function (value, key) { result[key] = value; });
      return result;
    }
    return body;
  }
  function recent() {
    return db.ledger.map(function (row) {
      return { time: row.time, nickName: row.name, type: Number(row.delta) >= 0 ? 0 : 1,
        score: Math.abs(Number(row.delta)).toFixed(0), isTuo: Number(row.kind) === 1 ? 1 : 0 };
    });
  }
  function historySummary(kind) {
    const rows = db.ledger.filter(function (row) { return Number(row.kind) === Number(kind); });
    const up = rows.filter(function (row) { return Number(row.delta) > 0; }).reduce(function (sum, row) { return sum + Number(row.delta); }, 0);
    const down = rows.filter(function (row) { return Number(row.delta) < 0; }).reduce(function (sum, row) { return sum + Math.abs(Number(row.delta)); }, 0);
    const detail = [];
    const ids = Array.from(new Set(rows.map(function (row) { return row.userWxid; }).filter(Boolean)));
    ids.forEach(function (id) {
      const user = findUser(id);
      if (!user) return;
      const userRows = rows.filter(function (row) { return row.userWxid === id; });
      const userUp = userRows.filter(function (row) { return Number(row.delta) > 0; }).reduce(function (sum, row) { return sum + Number(row.delta); }, 0);
      const userDown = userRows.filter(function (row) { return Number(row.delta) < 0; }).reduce(function (sum, row) { return sum + Math.abs(Number(row.delta)); }, 0);
      detail.push({ wxid: user.wxid, NickName: user.NickName, nickChanged: user.nickChanged || 0,
        nickNewName: user.nickNewName || '', totalFanShui: 0, totalLiuShui: 0, totalValidLiuShuis: 0,
        totalYingKui: 0, totalUp: Number(userUp.toFixed(2)), totalDown: Number(userDown.toFixed(2)),
        totalUpDown: Number((userUp - userDown).toFixed(2)), ZuoYu: Number(user.score), JieYu: Number(user.score) });
    });
    return { bOK: 0, totalFanShui: 0, totalYingKui: 0, totalLiuShui: 0, totalYouXiaoLiuShui: 0,
      totalYouXiaoLiuShuis: 0, totalUp: Number(up.toFixed(2)), totalDown: Number(down.toFixed(2)),
      totalUpDown: Number((up - down).toFixed(2)), totalZuoYu: 0, flyersWait: 0, flyersSuc: 0,
      flyersFail: 0, detail: detail, data: detail, list: [], total: rows.length };
  }
  function historyDetail(data) {
    return db.ledger.filter(function (row) {
      return !data.wxid || String(row.userWxid) === String(data.wxid);
    }).map(function (row) {
      return { dttype: Number(row.delta) >= 0 ? 0 : 1, score: Math.abs(Number(row.delta)),
        count: Number(row.delta), afterScore: row.afterScore, dtJieSuan: row.time };
    });
  }
  function commandList(data) {
    const isPrev = data.prev === '1';
    const type = Number(data.type || 0);
    const source = isPrev ? previousCommandSeed : commandSeed;
    const active = source.filter(function (item) { return db.settings.tuo.cancelledCmdIds.indexOf(item.id) === -1; });
    const detail = active.filter(function (item) {
      return isPrev || type === -1 || Number(item.isTuo) === (type === 1 ? 1 : 0);
    });
    const normal = active.filter(function (item) { return Number(item.isTuo) !== 1; });
    const tuo = active.filter(function (item) { return Number(item.isTuo) === 1; });
    return { total1: normal.reduce(function (sum, item) { return sum + Number(item.score); }, 0),
      total2: tuo.reduce(function (sum, item) { return sum + Number(item.score); }, 0),
      totalPu: normal.length, totalTuo: tuo.length, totalChi: 0, isPrev: isPrev, detail: clone(detail) };
  }
  function needHandle(req) {
    const s = db.settings;
    const users = db.users.map(function (user) { return { wxid: user.wxid, score: user.score }; });
    const rob = Object.assign({
      ls: 1, dj: 1, vip: 2, log: 2, te: '', pt: '', total: '', zheng: '', jiao: '', nian: '', tong: '', che: '',
      danshuang: '', daxiao: '', fan: '', jia: '', PeiLv: s.odds, zuigao: '', zuidi: '', chidan: '', xiugai: 1,
      lock_account: 1, lock_logout: 1, url: 'local-demo', link: location.origin + '/hby019-demo/player.html', login_url_prefix: '0019'
    }, s.limits || {});
    return Object.assign({
      IsNeedLogin: false, OverdueTime: new Date('2026-11-01T00:00:00+08:00').getTime(), ServerTime: Date.now(),
      score: 157, IsKaiPan: s.open ? 1 : 0, lType: localStorage.getItem('xupan.admin.point-request-sound') === 'sound2' ? 2 : 1, qh: '20261015001', PeiLv: s.odds,
      FanShui: s.normalRebate, Hold5TimeSeconds: s.hold, CancelInSecs: s.cancel,
      tePeilv: s.specialOdds, teFanshui: s.rebate, HideMsg: s.hideMsg,
      rob: rob,
      playersManagedByXupan: true,
      config: {
        gd: '', rbMsg: '需请联系代理！<br>新增飞单失败退单功能<br>新增飞单加倍数功能<br>新增封盘后台合并统一飞单<br>新增聊天室弹出广告<br>新增飞单账号分投功能<br>新增飞单反投功能<br>#预下版全新用GO言语写',
        mp3_1: '/hby019-demo/source/static/xz/admin/861.mp3', mp3_2: '/hby019-demo/source/static/xz/admin/886.mp3',
        yuming: { 'local-demo': location.origin, 'local-backup': '备用本地线路' }
      },
      users: users, count1: memberList(0).length, count2: memberList(1).length,
      heji: memberTotal(Number(req && req.rtype || 0)), data: clone(memberList(Number(req && req.rtype || 0))),
      list: clone(db.applications),
      iswp: 1,
      Data: { id: 1, flyers_auto: s.wp.auto, flyers_withdraw: s.wp.withdraw, flyers_url: '', flyers_username: '',
        flyers_password: '', flyers_type: 0, wpname: s.wp.name, flyers_online: s.wp.online ? 1 : 0,
        money: s.wp.money, betting: s.wp.betting, fly_id: 1, fly_fs: 1 }
    }, commandList({ type: 0, prev: '0' }));
  }
  function ok(message) { return { bOK: 0, code: 0, Data: 0, status: 1, Message: message || '本地模拟成功', msg: message || '本地模拟成功' }; }
  function changeFlag(path, data) {
    const user = findUser(data.wxid || data.uid);
    if (!user) return -1;
    const value = Number(data.type || 0);
    if (path === '/rbt/index/setblack') user.isBlack = value;
    else if (path === '/rbt/index/setauto') user.isauto = value;
    else if (path === '/rbt/index/setmanual2') user.chi = value;
    else if (path === '/rbt/index/setzhui') user.zhui = value;
    save();
    return 0;
  }
  function respond(path, rawData) {
    const data = parseBody(rawData);
    switch (path) {
      case '/rbt/index/needhandle': return needHandle(data);
      case '/rbt/index/getmembers': {
        const type = Number(data.type || 0), list = clone(memberList(type));
        return { data: list, count1: memberList(0).length, count2: memberList(1).length, heji: memberTotal(type) };
      }
      case '/rbt/index/getcmdlist': return commandList(data);
      case '/rbt/index/gettodayscorelog': return { bOK: 0, list: recent() };
      case '/rbt/index/getupdownscorereq': return { bOK: 0, list: clone(db.applications) };
      case '/rbt/index/getwplist': return [{ id: 1, name: '本地模拟', fstype: 0, searchCode: '', apiKey: '' }];
      case '/rbt/index/flyerslist': return needHandle(data);
      case '/rbt/index/getopenresultlist': return { list: [] };
      case '/rbt/index/getscorehis': return historySummary(0);
      case '/rbt/index/getscorehistuo': return historySummary(1);
      case '/rbt/index/getscorehisdetail': return historyDetail(data);
      case '/rbt/index/getnicknamelog': return { list: clone(db.nickLogs[String(data.wxid)] || []), maxCount: 2, todayCount: 0 };
      case '/rbt/index/getuserlink': {
        const user = findUser(data.uid);
        const link = location.origin + '/hby019-demo/player.html' + (user ? '?uid=' + encodeURIComponent(user.wxid) : '');
        return { bOK: 0, Data: link, qr: DEMO_QR, link_expire: user ? user.link_expire : 0,
          link_expire_days: user ? user.link_expire_days : 0, Message: '已生成本地演示链接' };
      }
      case '/rbt/index/getavatarlist': return AVATAR_POOL.slice();
      case '/rbt/index/addmember': {
        const kind = Number(data.type || 0), id = 2400 + db.users.length;
        const name = String(data.NickName || data.nickname || ('本地用户' + id));
        const user = normalizeUser({ id: id, wxid: 'local-' + id, NickName: name, score: '0.00', kind: kind,
          isBlack: 0, isrobot: kind ? 1 : 0, isauto: 0, chi: 0, zhui: 0, online: 0, nickChanged: 0,
          tuoMinMax: '1000-3000', tuo_round_tens: 0, tuo_allowed_plays: '' });
        db.users.push(user); save();
        return { bOK: 1, code: 0, status: 1, Data: user.wxid, data: clone(user), wxid: user.wxid, NickName: user.NickName, msg: '创建成功' };
      }
      case '/rbt/index/updownscore': {
        const user = findUser(data.wxid || data.uid);
        if (!user) return { bOK: 1, code: 1, Data: -1, status: 0, Message: '未找到本地演示用户' };
        const amount = Number(data.count || data.score || data.jifen || data.num || 0);
        if (!Number.isFinite(amount) || amount <= 0) return { bOK: 1, code: 1, Data: -1, status: 0, Message: '积分数值无效' };
        const delta = Number(data.type) === 2 ? -amount : amount;
        user.score = Number((Number(user.score) + delta).toFixed(2)).toFixed(2);
        db.ledger.unshift({ kind: Number(user.kind), userWxid: user.wxid, name: user.NickName, delta: delta,
          afterScore: Number(user.score), time: new Date().toLocaleString('zh-CN', { hour12: false }) });
        save();
        return Object.assign(ok('本地积分已更新'), { bOK: 0, Data: 0 });
      }
      case '/rbt/index/updatenickname': {
        const user = findUser(data.wxid);
        if (user) {
          const oldName = user.NickName;
          user.NickName = String(data.newNickName || user.NickName);
          if (oldName !== user.NickName) {
            user.nickChanged = 1; user.nickNewName = user.NickName;
            db.nickLogs[String(user.wxid)] = (db.nickLogs[String(user.wxid)] || []).concat([{ create_time: new Date().toLocaleString('zh-CN', { hour12: false }), old_name: oldName, new_name: user.NickName }]);
          }
          save();
        }
        return 0;
      }
      case '/rbt/index/delmember':
        db.users = db.users.filter(function (user) { return user.wxid !== data.wxid && String(user.id) !== String(data.uid); }); save(); return 0;
      case '/rbt/index/updatepanstatus': db.settings.open = Number(data.st) !== 0; save(); return 0;
      case '/rbt/index/updatepeilv':
        if (data.tar && /te|special/i.test(String(data.tar))) db.settings.specialOdds = Number(data.newPeiLv || db.settings.specialOdds);
        else db.settings.odds = Number(data.newPeiLv || db.settings.odds);
        save(); return 0;
      case '/rbt/index/changelimit': db.settings.limits = Object.assign({}, db.settings.limits, data); save(); return 0;
      case '/rbt/index/setblack':
      case '/rbt/index/setauto':
      case '/rbt/index/setmanual2':
      case '/rbt/index/setzhui':
        return changeFlag(path, data);
      case '/rbt/index/settype':
        if (data.ltype !== undefined) {
          localStorage.setItem('xupan.admin.point-request-sound', Number(data.ltype) === 2 ? 'sound2' : 'sound1');
        }
        db.settings.limits = Object.assign({}, db.settings.limits, data); save(); return 0;
      case '/rbt/index/updatehidemsg': db.settings.hideMsg = Number(data.HideMsg); save(); return 1;
      case '/rbt/index/setlinkexpire': {
        const user = findUser(data.uid), days = Math.max(0, Number(data.days || 0));
        if (user) { user.link_expire_days = days; user.link_expire = days ? Math.floor(Date.now() / 1000) + days * 86400 : 0; save(); }
        return { bOK: 0, expire: user ? user.link_expire : 0, link_expire_days: days, Message: '本地演示链接有效期已更新' };
      }
      case '/rbt/index/resetpwd': return { bOK: 0, Message: '本地演示：已模拟重置请求（未设置真实密码）' };
      case '/rbt/index/updateusername': return 0;
      case '/rbt/index/updateuserpwd': return 0;
      case '/rbt/index/updateloginpwd': return { status: 0, message: 'LOCAL-DEMO' };
      case '/rbt/index/modifypwd': return { status: 1, Message: '本地演示：修改密码请求已模拟，未保存密码' };
      case '/rbt/index/acceptupdownscorereq':
      case '/rbt/index/denyupdownscorereq': {
        db.applications = db.applications.filter(function (item) { return String(item.id) !== String(data.ReqID); });
        save();
        return Object.assign(ok('本地演示请求已处理'), { Data: 0 });
      }
      case '/rbt/index/allowdel': {
        const id = Number(data.id);
        if (db.settings.tuo.cancelledCmdIds.indexOf(id) === -1) db.settings.tuo.cancelledCmdIds.push(id);
        save();
        return Object.assign(ok('本地演示操作成功'), { Data: 0 });
      }
      case '/rbt/index/refeidan': return Object.assign(ok('本地演示操作成功'), { Data: 0 });
      case '/rbt/index/flyersauto': db.settings.wp.auto = Number(data.flyers_auto || 0); save(); return ok('本地模拟：自动上报设置已更新');
      case '/rbt/index/flyersdraw': db.settings.wp.withdraw = Number(data.flyers_withdraw || 0); save(); return ok('本地模拟：失败退单设置已更新');
      case '/rbt/index/flyerssave': db.settings.wp.name = String(data.un || db.settings.wp.name); save(); return ok('本地模拟：报网设置已保存');
      case '/rbt/index/flyerslogin': db.settings.wp.online = true; save(); return ok('本地模拟：已登录');
      case '/rbt/index/flyerslogout': db.settings.wp.online = false; save(); return ok('本地模拟：已退出');
      case '/rbt/index/flyersrefresh': return Object.assign(ok('本地模拟：余额已刷新'), { money: db.settings.wp.money, betting: db.settings.wp.betting });
      case '/rbt/index/tuominmax': {
        const user = findUser(data.uid); if (user) user.flyers_num = Number(data.num || 0); save(); return ok('本地模拟：托单设置已更新');
      }
      case '/rbt/index/savetuoconfig':
        db.settings.tuo.activeRate = Number(data.tuo_active_rate || db.settings.tuo.activeRate);
        db.settings.tuo.maxBets = Number(data.tuo_max_bets || db.settings.tuo.maxBets); save(); return { code: 0, msg: '本地模拟成功' };
      case '/rbt/index/savesingletuoconfig': {
        const user = findUser(data.uid);
        if (user) {
          if (typeof data.tuo_round_tens !== 'undefined') user.tuo_round_tens = Number(data.tuo_round_tens);
          if (typeof data.tuo_allowed_plays !== 'undefined') user.tuo_allowed_plays = String(data.tuo_allowed_plays);
          save();
        }
        return ok('本地托设置已保存');
      }
      case '/rbt/index/getway2data':
      case '/rbt/index/getway4data':
      case '/rbt/index/getway5data': return [];
      case '/rbt/index/getway3data': return { lstJiLu: [], lstQiDuiZhang: [] };
      case '/rbt/index/qrcode': return { img: DEMO_QR, localDemo: true };
      case '/rbt/index/qrcode4': return ok('本地演示：已模拟切换登录设备');
      case '/rbt/index/changeheadimage':
      case '/rbt/index/changeheadimage2': {
        const user = findUser(data.wxid);
        const avatar = isLocalAvatar(data.imgName) ? data.imgName : (user ? user.avatar : DEFAULT_AVATAR);
        if (user) { user.avatar = avatar; user.imgName = avatar; save(); }
        return avatar;
      }
      default:
        return { bOK: 0, code: 0, Data: 0, status: 1, list: [], data: [], msg: '本地演示：该客户端接口暂无数据' };
    }
  }

  $.ajax = function (url, options) {
    let settings;
    if (typeof url === 'string') settings = $.extend(true, {}, options || {}, { url: url });
    else settings = $.extend(true, {}, url || {});
    const path = new URL(settings.url || location.pathname, location.href).pathname;
    const xupanPaths = [
      '/rbt/index/needhandle', '/rbt/index/updatepeilv', '/rbt/index/settype',
      '/rbt/index/getcmdlist', '/rbt/index/getopenresultlist',
      '/rbt/index/addmember', '/rbt/index/getmembers', '/rbt/index/delmember', '/rbt/index/updatenickname',
      '/rbt/index/getnicknamelog', '/rbt/index/setlinkexpire', '/rbt/index/getuserlink',
      '/rbt/index/setblack', '/rbt/index/setauto', '/rbt/index/setzhui',
      '/rbt/index/updownscore', '/rbt/index/gettodayscorelog',
      '/rbt/index/getupdownscorereq', '/rbt/index/acceptupdownscorereq', '/rbt/index/denyupdownscorereq',
      '/rbt/index/getavatarlist', '/rbt/index/changeheadimage2'
    ];
    if (xupanPaths.indexOf(path) !== -1) {
      (window.__localApiLog || (window.__localApiLog = [])).push({ path: path, method: settings.type || 'GET', source: 'xupan' });
      requestXupan(path, settings.data, function (response) {
        if (path === '/rbt/index/needhandle' && response) {
          const demoState = needHandle(parseBody(settings.data));
          response = Object.assign(demoState, response, { users: demoState.users });
          response.rob = Object.assign({}, demoState.rob, response.rob || {});
        }
        if (path === '/rbt/index/getmembers' && response && Array.isArray(response.data)) {
          response.data = response.data.map(function (player) {
            return Object.assign({}, player, xupanPlayerDetails[String(player.wxid)] || {});
          });
        }
        if (typeof settings.success === 'function') settings.success(response, 'success', { status: 200 });
        if (typeof settings.complete === 'function') settings.complete({ status: 200 }, 'success');
      }, function (xhr, status) {
        if (typeof settings.error === 'function') settings.error(xhr, status);
        if (typeof settings.complete === 'function') settings.complete(xhr, status);
      });
      const deferred = $.Deferred();
      const xhr = deferred.promise({ readyState: 1, status: 0, statusText: 'pending',
        abort: function () { return this; }, getResponseHeader: function () { return 'application/json'; } });
      return xhr;
    }
    const response = respond(path, settings.data);
    (window.__localApiLog || (window.__localApiLog = [])).push({ path: path, method: settings.type || 'GET' });
    const deferred = $.Deferred();
    let aborted = false;
    const xhr = deferred.promise({ readyState: 1, status: 0, statusText: 'pending', responseText: '',
      abort: function () { aborted = true; this.readyState = 0; return this; },
      getResponseHeader: function () { return 'application/json'; } });
    setTimeout(function () {
      if (aborted) return;
      xhr.readyState = 4; xhr.status = 200; xhr.statusText = 'success';
      xhr.responseText = typeof response === 'string' ? response : JSON.stringify(response);
      try { if (typeof settings.success === 'function') settings.success(response, 'success', xhr); }
      catch (err) { console.error('local mock callback failed', path, err); }
      deferred.resolve(response, 'success', xhr);
      if (typeof settings.complete === 'function') settings.complete(xhr, 'success');
    }, 0);
    return xhr;
  };
  $(function () { $('.el-alert__title').text('星海AI，欢迎您'); });
  document.addEventListener('click', function (event) {
    if (event.target.closest && event.target.closest('#ExitLink')) {
      event.preventDefault(); event.stopImmediatePropagation();
      window.parent.postMessage({ type: 'xupan-console-logout' }, location.origin);
    }
  }, true);
  window.localApiBridge = {
    getState: function () { return clone(db); },
    getPlayerDetails: function (wxid, success, error) {
      requestXupan('/xupan/player/detail', { wxid: wxid }, success, error);
    },
    reset: function () { db = initial(); save(); localKeys.forEach(function (key) { localStorage.removeItem(key); }); }
  };
})(window.jQuery);





