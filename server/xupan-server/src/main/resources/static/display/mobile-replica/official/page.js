(() => {
  const lotteryNames = ["\u65B0\u6FB3\u5F69", "\u8001\u6FB3\u5F69", "\u9999\u6E2F\u5F69", "\u5FEB\u4E508"];
  const liveLotteries = /* @__PURE__ */ new Map([
    ["hong-kong", "\u9999\u6E2F\u5F69"]
  ]);
  const macauLotteries = /* @__PURE__ */ new Map([
    ["macau-new", "\u65B0\u6FB3\u5F69"],
    ["macau-old", "\u8001\u6FB3\u5F69"]
  ]);
  const liveDrawTimes = /* @__PURE__ */ new Map();
  let liveRetryTimer;
  let liveRetryDelay = 1e4;
  let macauRetryTimer;
  let macauRetryDelay = 1e4;
  let macauRefreshInFlight = false;
  let displayObserverHref = null;
  let middleContentHtml = null;
  function initializeMiddleContentToggle() {
    const control = document.getElementById("xupan-middle-content-toggle");
    const button = document.getElementById("xupan-middle-content-toggle-button");
    const anchor = document.getElementById("xupan-middle-content-anchor");
    if (!control || !button || !anchor || control.parentElement !== anchor.parentElement) return;
    button.addEventListener("click", async () => {
      const expanded = button.getAttribute("aria-expanded") === "true";
      if (expanded) {
        let node = control.nextSibling;
        while (node && node !== anchor) {
          const next = node.nextSibling;
          node.remove();
          node = next;
        }
        button.setAttribute("aria-expanded", "false");
        button.textContent = "\u663E\u793A\u4E2D\u6BB5\u5185\u5BB9\uFF08\u7EA6\u5360\u9875\u9762\u4E00\u534A\uFF09";
        return;
      }
      button.disabled = true;
      button.textContent = middleContentHtml ? "\u6B63\u5728\u5C55\u5F00\u4E2D\u6BB5\u5185\u5BB9\u2026" : "\u6B63\u5728\u52A0\u8F7D\u4E2D\u6BB5\u5185\u5BB9\u2026";
      try {
        if (middleContentHtml === null) {
          const response = await fetch("/display/mobile-replica/middle-content.html", {
            headers: { Accept: "text/html" },
            cache: "force-cache"
          });
          if (!response.ok) throw new Error("MOBILE_DISPLAY_MIDDLE_CONTENT_UNAVAILABLE");
          middleContentHtml = await response.text();
        }
        const range = document.createRange();
        range.selectNode(anchor);
        anchor.before(range.createContextualFragment(middleContentHtml));
        button.setAttribute("aria-expanded", "true");
        button.textContent = "\u6536\u8D77\u4E2D\u6BB5\u5185\u5BB9";
      } catch (e) {
        button.textContent = "\u52A0\u8F7D\u5931\u8D25\uFF0C\u70B9\u51FB\u91CD\u8BD5";
      } finally {
        button.disabled = false;
      }
    });
  }
  function issueForDisplay(issue) {
    return String(issue || "").replace(/^20\d{2}(?=\d{3}$)/, "");
  }
  function au8IssueForDisplay(issue) {
    return String(issue || "").slice(-3);
  }
  function nextAu8IssueForDisplay(issue) {
    const value = Number(issue);
    return Number.isSafeInteger(value) ? String(value + 1).slice(-3) : "";
  }
  function formatNextDrawAt(value) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return "";
    const parts = new Intl.DateTimeFormat("zh-CN", {
      timeZone: "Asia/Shanghai",
      month: "numeric",
      day: "numeric",
      weekday: "long",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23"
    }).formatToParts(date);
    const part = (type) => {
      var _a;
      return ((_a = parts.find((item) => item.type === type)) == null ? void 0 : _a.value) || "";
    };
    return `${part("month")}\u6708${part("day")}\u65E5 ${part("weekday")} ${part("hour")}:${part("minute")}`;
  }
  function refreshCountdowns() {
    var _a;
    for (const [name, nextDrawAt] of liveDrawTimes) {
      const selected = (_a = [...document.querySelectorAll("#widgetContainer40617 .baomalaodaName")].find((item) => item.textContent.trim() === name)) == null ? void 0 : _a.closest(".baomalaoda");
      const countdown = selected == null ? void 0 : selected.querySelector(".baomalaodaCountdown");
      if (!countdown) continue;
      const seconds = Math.max(0, Math.floor((nextDrawAt - Date.now()) / 1e3));
      if (name === "\u5FEB\u4E508" && seconds === 0) {
        countdown.textContent = "\u5F00\u5956\u4E2D";
        const summary = selected.querySelector(".baomalaodaInfo3 > div:first-child");
        if (summary) summary.textContent = "\u5F00\u5956\u4E2D";
        continue;
      }
      const hours = String(Math.floor(seconds / 3600)).padStart(2, "0");
      const minutes = String(Math.floor(seconds % 3600 / 60)).padStart(2, "0");
      const remainingSeconds = String(seconds % 60).padStart(2, "0");
      countdown.textContent = `${hours}:${minutes}:${remainingSeconds}`;
    }
  }
  function applyLiveCard(name, card) {
    var _a;
    const cardContainer = document.querySelector("#widgetContainer40617 .box-ttkj");
    const selected = [...(cardContainer == null ? void 0 : cardContainer.children) || []].find((item) => {
      var _a2;
      return ((_a2 = item.querySelector(".baomalaodaName")) == null ? void 0 : _a2.textContent.trim()) === name;
    });
    if (!selected || ((_a = card.numbers) == null ? void 0 : _a.length) !== 7) return false;
    const issue = selected.querySelector(".baomalaodaQi");
    if (issue) issue.textContent = issueForDisplay(card.issue);
    const nextDrawAt = Date.parse(card.nextDrawAt);
    if (Number.isFinite(nextDrawAt)) {
      liveDrawTimes.set(name, nextDrawAt);
      const nextDrawSummary = selected.querySelector(".baomalaodaInfo3 > div:first-child");
      if (nextDrawSummary) {
        const nextIssue = card.upcomingIssue ? issueForDisplay(card.upcomingIssue) : "";
        nextDrawSummary.textContent = nextIssue ? `${name}\u7B2C${nextIssue}\u671F: ${formatNextDrawAt(nextDrawAt)}` : `${name}\u4E0B\u671F\u5F00\u5956\u65F6\u95F4: ${formatNextDrawAt(nextDrawAt)}`;
      }
    }
    const numbers = [...selected.querySelectorAll(".baomalaodaMa")];
    const animals = [...selected.querySelectorAll(".baomalaodaXiao")];
    if (numbers.length < 7 || animals.length < 7) return false;
    card.numbers.forEach((number, index) => {
      var _a2, _b;
      const numberNode = numbers[index];
      const animalNode = animals[index];
      const source = numberNode.parentElement;
      const color = (_a2 = card.numberColors) == null ? void 0 : _a2[index];
      const zodiac = String(((_b = card.summary) == null ? void 0 : _b[index]) || "").trim().split(/\s+/)[0];
      if (!source || !zodiac) return;
      numberNode.textContent = String(number).padStart(2, "0");
      animalNode.textContent = zodiac;
      source.classList.remove("wdrc", "wdgc", "wdbc");
      if (color === "red") source.classList.add("wdrc");
      else if (color === "green") source.classList.add("wdgc");
      else if (color === "blue") source.classList.add("wdbc");
    });
    return true;
  }
  async function refreshLiveDraws() {
    try {
      const response = await fetch("/api/display/mobile/home", {
        headers: { Accept: "application/json" },
        cache: "no-store"
      });
      if (!response.ok) throw new Error("MOBILE_DISPLAY_API_UNAVAILABLE");
      const result = await response.json();
      if (result.stale) throw new Error("MOBILE_DISPLAY_API_STALE");
      window.clearTimeout(liveRetryTimer);
      liveRetryDelay = 1e4;
      const matched = [];
      for (const card of result.cards || []) {
        const name = liveLotteries.get(card.key);
        if (name && applyLiveCard(name, card)) matched.push(name);
      }
      const active = document.querySelector("#baomaqgBtn4BoxFatherBoxCopy25Father .bnav-activeam, #baomaqgBtn4BoxFatherBoxCopy25Father .bnav-activexg");
      const activeName = active == null ? void 0 : active.textContent.trim();
      if (matched.includes(activeName)) selectLottery(activeName);
    } catch (e) {
      window.clearTimeout(liveRetryTimer);
      liveRetryTimer = window.setTimeout(() => {
        liveRetryDelay = Math.min(liveRetryDelay * 2, 6e4);
        void refreshLiveDraws();
      }, liveRetryDelay);
    }
  }
  async function refreshMacauDraws() {
    if (macauRefreshInFlight) return;
    macauRefreshInFlight = true;
    try {
      const response = await fetch("/api/display/mobile/macau", {
        headers: { Accept: "application/json" },
        cache: "no-store"
      });
      if (!response.ok) throw new Error("MOBILE_DISPLAY_MACAU_API_UNAVAILABLE");
      const result = await response.json();
      if (result.stale) throw new Error("MOBILE_DISPLAY_MACAU_API_STALE");
      window.clearTimeout(macauRetryTimer);
      macauRetryDelay = 1e4;
      const matched = [];
      for (const card of result.cards || []) {
        const name = macauLotteries.get(card.key);
        if (name && applyLiveCard(name, card)) matched.push(name);
      }
      const active = document.querySelector("#baomaqgBtn4BoxFatherBoxCopy25Father .bnav-activeam, #baomaqgBtn4BoxFatherBoxCopy25Father .bnav-activexg");
      const activeName = active == null ? void 0 : active.textContent.trim();
      if (matched.includes(activeName)) selectLottery(activeName);
    } catch (e) {
      window.clearTimeout(macauRetryTimer);
      macauRetryTimer = window.setTimeout(() => {
        macauRetryDelay = Math.min(macauRetryDelay * 2, 6e4);
        void refreshMacauDraws();
      }, macauRetryDelay);
    } finally {
      macauRefreshInFlight = false;
    }
  }
  function renderAu8Unavailable(message, currentIssueLabel) {
    const card = [...document.querySelectorAll("#widgetContainer40617 .baomalaoda")].find((item) => {
      var _a;
      return ((_a = item.querySelector(".baomalaodaName")) == null ? void 0 : _a.textContent.trim()) === "\u5FEB\u4E508";
    });
    if (!card) return;
    const issue = card.querySelector(".baomalaodaQi");
    if (issue) issue.textContent = "--";
    card.querySelectorAll(".baomalaodaMa").forEach((node) => {
      node.textContent = "--";
    });
    card.querySelectorAll(".baomalaodaXiao").forEach((node) => {
      node.textContent = "";
    });
    const currentSummary = card.querySelector(".baomalaodaInfo3 > div:first-child");
    if (currentSummary) currentSummary.textContent = currentIssueLabel || message;
    const countdown = card.querySelector(".baomalaodaCountdown");
    if (!currentIssueLabel && countdown) countdown.textContent = "--:--:--";
    if (!currentIssueLabel) liveDrawTimes.delete("\u5FEB\u4E508");
    const active = document.querySelector("#baomaqgBtn4BoxFatherBoxCopy25Father .bnav-activeam, #baomaqgBtn4BoxFatherBoxCopy25Father .bnav-activexg");
    if ((active == null ? void 0 : active.textContent.trim()) === "\u5FEB\u4E508") selectLottery("\u5FEB\u4E508");
  }
  function applyAu8Draw(response) {
    var _a;
    const card = [...document.querySelectorAll("#widgetContainer40617 .baomalaoda")].find((item) => {
      var _a2;
      return ((_a2 = item.querySelector(".baomalaodaName")) == null ? void 0 : _a2.textContent.trim()) === "\u5FEB\u4E508";
    });
    if (!card) return;
    const currentIssue = response.currentIssue;
    const currentSummary = card.querySelector(".baomalaodaInfo3 > div:first-child");
    let currentIssueLabel = "";
    const nextStartAt = currentIssue == null ? void 0 : currentIssue.drawEndsAt;
    const bettingEndsAt = currentIssue == null ? void 0 : currentIssue.bettingEndsAt;
    if ((currentIssue == null ? void 0 : currentIssue.issueNumber) && nextStartAt && bettingEndsAt && Number.isFinite(Date.parse(nextStartAt)) && Number.isFinite(Date.parse(bettingEndsAt))) {
      const nextDrawAt = Date.parse(bettingEndsAt);
      if (currentIssue.phase === "DRAWING" || nextDrawAt <= Date.now()) {
        liveDrawTimes.set("\u5FEB\u4E508", nextDrawAt);
        currentIssueLabel = "\u5F00\u5956\u4E2D";
        if (currentSummary) currentSummary.textContent = currentIssueLabel;
      } else if (Number.isFinite(nextDrawAt)) {
        const nextIssue = nextAu8IssueForDisplay(currentIssue.issueNumber);
        if (!nextIssue) {
          if (currentSummary) currentSummary.textContent = "\u5FEB\u4E508\u4E0B\u671F\u5F00\u5956\u65F6\u95F4\u6682\u4E0D\u53EF\u7528";
          liveDrawTimes.delete("\u5FEB\u4E508");
        } else {
          liveDrawTimes.set("\u5FEB\u4E508", nextDrawAt);
          currentIssueLabel = `\u5FEB\u4E508\u7B2C${nextIssue}\u671F: ${formatNextDrawAt(nextStartAt)}`;
          if (currentSummary) currentSummary.textContent = currentIssueLabel;
        }
      } else {
        if (currentSummary) currentSummary.textContent = "\u5FEB\u4E508\u4E0B\u671F\u5F00\u5956\u65F6\u95F4\u6682\u4E0D\u53EF\u7528";
        liveDrawTimes.delete("\u5FEB\u4E508");
      }
    } else {
      if (currentSummary) currentSummary.textContent = "\u5FEB\u4E508\u4E0B\u671F\u5F00\u5956\u65F6\u95F4\u6682\u4E0D\u53EF\u7528";
      liveDrawTimes.delete("\u5FEB\u4E508");
      const countdown = card.querySelector(".baomalaodaCountdown");
      if (countdown) countdown.textContent = "--:--:--";
    }
    const draw = response.latestDraw;
    if (!draw || ((_a = draw.balls) == null ? void 0 : _a.length) !== 7) {
      renderAu8Unavailable("\u6682\u65E0\u5DF2\u786E\u8BA4\u7684\u4E03\u7403\u5F00\u5956\u7ED3\u679C", currentIssueLabel);
      return;
    }
    const issue = card.querySelector(".baomalaodaQi");
    if (issue) issue.textContent = au8IssueForDisplay(draw.issueNumber);
    updateAu8ObserverLinkLabel();
    const numbers = [...card.querySelectorAll(".baomalaodaMa")];
    const labels = [...card.querySelectorAll(".baomalaodaXiao")];
    if (numbers.length < 7 || labels.length < 7) return;
    draw.balls.forEach((ball, index) => {
      numbers[index].textContent = String(ball.number).padStart(2, "0");
      labels[index].textContent = `${ball.fan}\u756A`;
    });
    const active = document.querySelector("#baomaqgBtn4BoxFatherBoxCopy25Father .bnav-activeam, #baomaqgBtn4BoxFatherBoxCopy25Father .bnav-activexg");
    if ((active == null ? void 0 : active.textContent.trim()) === "\u5FEB\u4E508") selectLottery("\u5FEB\u4E508");
  }
  async function refreshAu8Draw() {
    try {
      const response = await fetch("/api/display/mobile/au8", {
        headers: { Accept: "application/json" },
        cache: "no-store"
      });
      if (!response.ok) throw new Error("MOBILE_AU8_API_UNAVAILABLE");
      applyAu8Draw(await response.json());
    } catch (e) {
      renderAu8Unavailable("\u672C\u5730\u4E03\u7403\u5F00\u5956\u6682\u4E0D\u53EF\u7528");
    }
  }
  function updateAu8ObserverLinkLabel() {
    var _a;
    const card = [...document.querySelectorAll("#widgetContainer40617 .baomalaoda")].find((item) => {
      var _a2;
      return ((_a2 = item.querySelector(".baomalaodaName")) == null ? void 0 : _a2.textContent.trim()) === "\u5FEB\u4E508";
    });
    const title = card == null ? void 0 : card.querySelector(".baomalaodaInfo1 .info1-title");
    if (!title) return;
    if (!displayObserverHref) {
      title.removeAttribute("role");
      title.removeAttribute("tabindex");
      title.removeAttribute("aria-label");
      title.style.cursor = "";
      return;
    }
    const issue = ((_a = card == null ? void 0 : card.querySelector(".baomalaodaQi")) == null ? void 0 : _a.textContent.trim()) || "";
    title.setAttribute("role", "link");
    title.setAttribute("tabindex", "0");
    title.setAttribute("aria-label", `\u6253\u5F00\u5FEB\u4E508\u7B2C${issue}\u671F\u53EA\u8BFB\u89C2\u5BDF\u9875`);
    title.style.cursor = "pointer";
  }
  function updateAu8CardActions() {
    var _a;
    const card = [...document.querySelectorAll("#widgetContainer40617 .baomalaoda")].find((item) => {
      var _a2;
      return ((_a2 = item.querySelector(".baomalaodaName")) == null ? void 0 : _a2.textContent.trim()) === "\u5FEB\u4E508";
    });
    document.querySelectorAll("#widgetContainer40617 .baomalaodaLive").forEach((link) => link.remove());
    if (!card) return;
    card.querySelectorAll(".baomalaodaHistory").forEach((link) => link.remove());
    (_a = card.querySelector(".xupan-au8-history-panel")) == null ? void 0 : _a.remove();
    const actionGroup = card.querySelector(".baomalaodaInfo1 > .baomalaoda-group:last-child");
    if (!actionGroup || actionGroup.querySelector(".xupan-au8-history-label")) return;
    const label = document.createElement("span");
    label.className = "xupan-au8-history-label";
    label.textContent = "\u5386\u53F2\u8BB0\u5F55";
    Object.assign(label.style, { color: "#e71607", fontSize: "1em" });
    actionGroup.append(label);
  }
  async function refreshDisplayObserverLink() {
    try {
      const response = await fetch("/api/display/observer-link", {
        headers: { Accept: "application/json" },
        cache: "no-store"
      });
      if (!response.ok) throw new Error("DISPLAY_OBSERVER_LINK_UNAVAILABLE");
      const result = await response.json();
      const candidate = result.enabled && typeof result.href === "string" ? new URL(result.href, window.location.href) : null;
      displayObserverHref = (candidate == null ? void 0 : candidate.origin) === window.location.origin && candidate.pathname.startsWith("/display/observe/") ? candidate.href : null;
    } catch (e) {
      displayObserverHref = null;
    }
    updateAu8ObserverLinkLabel();
  }
  function openAu8ObserverLink() {
    if (displayObserverHref) window.open(displayObserverHref, "_blank", "noopener,noreferrer");
  }
  function selectLottery(name) {
    var _a;
    const selector = document.querySelector("#baomaqgBtn4BoxFatherBoxCopy25Father");
    const cardContainer = document.querySelector("#widgetContainer40617 .box-ttkj");
    if (!selector || !cardContainer) return;
    const tabs = [...selector.querySelectorAll(".baomaqgBtn4BoxFatherBoxCopy25 > div")];
    const cards = [...cardContainer.children].filter((card) => card.querySelector(".baomalaodaName"));
    const selected = cards.find((card) => {
      var _a2;
      return ((_a2 = card.querySelector(".baomalaodaName")) == null ? void 0 : _a2.textContent.trim()) === name;
    });
    if (!selected) return;
    for (const tab of tabs) {
      const active = tab.textContent.trim() === name;
      const category = tab.getAttribute("data-nameinfo2");
      tab.classList.toggle("bnav-activeam", active && category === "baomaqgxinam");
      tab.classList.toggle("bnav-activexg", active && category === "baomaqgxg");
      tab.setAttribute("aria-pressed", String(active));
    }
    cardContainer.prepend(selected);
    const issue = (_a = selected.querySelector(".baomalaodaQi")) == null ? void 0 : _a.textContent.trim();
    const issueLabel = document.querySelector("#widgetContainer39054 .issue > span");
    if (issue && issueLabel) issueLabel.textContent = `${issue}\u671F\u5F00`;
    const numbers = [...selected.querySelectorAll(".baomalaodaMa")];
    const animals = [...selected.querySelectorAll(".baomalaodaXiao")];
    const boxes = [...document.querySelectorAll("#widgetContainer39054 .number-row .number-box:not(.refresh-box)")];
    boxes.forEach((box, index) => {
      var _a2, _b, _c;
      const number = (_a2 = numbers[index]) == null ? void 0 : _a2.textContent.trim();
      const animal = (_b = animals[index]) == null ? void 0 : _b.textContent.trim().split("/")[0];
      const ball = box.firstElementChild;
      if (!ball) return;
      const source = (_c = numbers[index].parentElement) == null ? void 0 : _c.classList;
      const color = (source == null ? void 0 : source.contains("wdgc")) ? "green-ball" : (source == null ? void 0 : source.contains("wdbc")) ? "blue-ball" : (source == null ? void 0 : source.contains("wdrc")) ? "red-ball" : "";
      if (color) ball.className = color;
      const numberLabel = ball.querySelector(".num");
      const animalLabel = ball.querySelector(".draw-txt");
      if (numberLabel) numberLabel.textContent = number || "--";
      if (animalLabel) animalLabel.textContent = animal;
    });
  }
  document.addEventListener("click", (event) => {
    const tab = event.target.closest("#baomaqgBtn4BoxFatherBoxCopy25Father > div > div");
    if (!tab) return;
    const name = lotteryNames.find((candidate) => tab.textContent.trim() === candidate);
    if (name) {
      selectLottery(name);
      if (name === "\u5FEB\u4E508") void refreshAu8Draw();
    }
  }, true);
  selectLottery("\u9999\u6E2F\u5F69");
  updateAu8CardActions();
  const floatGroup = document.querySelector("#widgetContainer39200 .float-btn-group");
  if (floatGroup && !floatGroup.querySelector(".local-back-to-top")) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "local-back-to-top";
    button.setAttribute("aria-label", "\u56DE\u5230\u9876\u90E8");
    button.title = "\u56DE\u5230\u9876\u90E8";
    const icon = document.createElement("img");
    icon.src = "/display/mobile-replica/official/back-to-top.png";
    icon.alt = "";
    button.append(icon);
    Object.assign(button.style, {
      display: "none",
      width: "55px",
      height: "55px",
      padding: "0",
      border: "0",
      background: "transparent",
      cursor: "pointer"
    });
    floatGroup.append(button);
    button.addEventListener("click", () => window.scrollTo({ top: 0, behavior: "smooth" }));
    const updateBackToTop = () => {
      button.style.display = window.scrollY > 100 ? "flex" : "none";
    };
    button.style.alignItems = "center";
    button.style.justifyContent = "center";
    window.addEventListener("scroll", updateBackToTop, { passive: true });
    updateBackToTop();
  }
  document.addEventListener("click", (event) => {
    var _a, _b;
    const observerTitle = event.target.closest("#widgetContainer40617 .baomalaodaInfo1 .info1-title");
    if (observerTitle && displayObserverHref && ((_b = (_a = observerTitle.closest(".baomalaoda")) == null ? void 0 : _a.querySelector(".baomalaodaName")) == null ? void 0 : _b.textContent.trim()) === "\u5FEB\u4E508") {
      event.preventDefault();
      openAu8ObserverLink();
      return;
    }
    if (event.target.closest("#widgetContainer40617 .baomalaodaRefresh")) {
      event.preventDefault();
      void refreshAu8Draw();
      return;
    }
    const link = event.target.closest('a[data-local-link="isolated"]');
    if (link) event.preventDefault();
    if (event.target.closest('#widgetContainer39200 img[alt="\u5237\u65B0"]')) {
      event.preventDefault();
      window.location.reload();
      return;
    }
    const button = event.target.closest("button");
    if (!button) return;
    const label = `${button.innerText} ${button.getAttribute("aria-label") || ""} ${button.title || ""}`;
    if (/返回顶部|回到顶部|back.?to.?top/i.test(label)) {
      event.preventDefault();
      window.scrollTo({ top: 0, behavior: "smooth" });
    } else if (/刷新|重新加载/i.test(label)) {
      event.preventDefault();
      window.location.reload();
    }
  }, true);
  document.addEventListener("keydown", (event) => {
    var _a, _b;
    const observerTitle = event.target.closest('#widgetContainer40617 .baomalaodaInfo1 .info1-title[role="link"]');
    if (!observerTitle || event.key !== "Enter" && event.key !== " ") return;
    if (((_b = (_a = observerTitle.closest(".baomalaoda")) == null ? void 0 : _a.querySelector(".baomalaodaName")) == null ? void 0 : _b.textContent.trim()) !== "\u5FEB\u4E508") return;
    event.preventDefault();
    openAu8ObserverLink();
  });
  document.addEventListener("submit", (event) => event.preventDefault());
  initializeMiddleContentToggle();
  void refreshLiveDraws();
  void refreshMacauDraws();
  renderAu8Unavailable("\u6B63\u5728\u8BFB\u53D6\u672C\u5730\u4E03\u7403\u5F00\u5956");
  void refreshAu8Draw();
  void refreshDisplayObserverLink();
  window.setInterval(refreshCountdowns, 1e3);
  window.setInterval(() => {
    if (!document.hidden) {
      void refreshLiveDraws();
      void refreshMacauDraws();
      void refreshAu8Draw();
      void refreshDisplayObserverLink();
    }
  }, 15e3);
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) {
      void refreshLiveDraws();
      void refreshMacauDraws();
      void refreshAu8Draw();
      void refreshDisplayObserverLink();
    }
  });
})();
