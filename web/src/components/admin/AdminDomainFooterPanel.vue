<template>
  <section class="admin-domain-footer" aria-label="线路与登录链接静态区域">
    <div class="domain-row domain-route-row">
      <span class="domain-label">线路域名:</span>
      <span class="domain-select">https://box68.137n.com<i aria-hidden="true">⌄</i></span>
      <span class="domain-label">开放注册:</span>
      <span class="domain-radio"><i aria-hidden="true"></i>是</span>
      <span class="domain-radio is-selected"><i aria-hidden="true"></i>否</span>
    </div>
    <div class="domain-row domain-link-row">
      <span class="domain-label">登录链接:</span>
      <span class="domain-login-link">https://box68.137n.com/home/BYfsxEob</span>
      <span class="domain-copy">复制链接</span>
    </div>
  </section>
</template>

<style scoped>
.admin-domain-footer {
  display: grid;
  gap: 4px;
  min-width: 0;
  margin: 0;
  color: #111;
  font-size: 14px;
  font-family: -apple-system, BlinkMacSystemFont, "Helvetica Neue", Helvetica, "Segoe UI", Arial, Roboto, "PingFang SC", miui, "Hiragino Sans GB", "Microsoft Yahei", sans-serif;
  line-height: 1.4;
}

.admin-domain-footer .domain-row,
.admin-domain-footer .domain-label,
.admin-domain-footer .domain-select,
.admin-domain-footer .domain-radio,
.admin-domain-footer .domain-login-link,
.admin-domain-footer .domain-copy {
  font-size: 14px;
  font-weight: 400;
}

.domain-row {
  display: flex;
  min-width: 0;
  min-height: 24px;
  align-items: center;
  gap: 8px;
}

.domain-label {
  color: #111;
  font-weight: 400;
  white-space: nowrap;
}

.domain-select {
  display: inline-flex;
  width: 200px;
  height: 22px;
  align-items: center;
  justify-content: space-between;
  border: 1px solid #a6a6a6;
  border-radius: 2px;
  background: #fff;
  color: #111;
  padding: 0 5px;
  font-size: 14px;
  white-space: nowrap;
}

.domain-select i {
  margin-left: 6px;
  color: #555;
  font-style: normal;
}

.domain-radio {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #111;
  white-space: nowrap;
}

.domain-radio i {
  display: block;
  width: 13px;
  height: 13px;
  border: 1px solid #a6a6a6;
  border-radius: 50%;
  background: #fff;
}

.domain-radio.is-selected i {
  border-color: #409eff;
  box-shadow: inset 0 0 0 3px #fff;
  background: #409eff;
}

.domain-login-link {
  max-width: min(430px, 48vw);
  overflow: hidden;
  color: #111;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.domain-copy {
  color: #409eff;
  white-space: nowrap;
}

@media (max-width: 600px) {
  .domain-select { width: min(200px, 38vw); }
  .domain-login-link { max-width: 52vw; }
}
</style>
