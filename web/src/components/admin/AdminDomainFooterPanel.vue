<template>
  <section class="admin-domain-footer" aria-label="线路与登录链接静态区域">
    <span class="domain-label">线路域名:</span>
    <span class="domain-select">https://box68.137n.com<i aria-hidden="true">⌄</i></span>
    <span class="domain-label domain-register-label">开放注册:</span>
    <span class="domain-radio"><i aria-hidden="true"></i>是</span>
    <span class="domain-radio"><i aria-hidden="true"></i>否</span>
    <span class="domain-label domain-login-label">登录链接:</span>
    <span class="domain-login-link">https://box68.137n.com/home/BYfsxEob</span>
    <span class="domain-copy">复制链接</span>
  </section>
</template>

<style scoped>
.admin-domain-footer {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 5px;
  min-height: 36px;
  margin-top: 8px;
  overflow: hidden;
  border: 1px solid var(--ops-line-strong, #4b8ed3);
  border-radius: 2px;
  background: linear-gradient(#fff, var(--ops-panel-soft, #f3f8ff));
  color: var(--ops-text, #243b53);
  padding: 4px 8px;
  font-size: 12px;
  box-shadow: inset 0 0 0 1px rgb(255 255 255 / 78%);
}

.domain-label {
  color: var(--ops-blue-deep, #15599d);
  font-weight: 800;
  white-space: nowrap;
}

.domain-select,
.domain-login-link {
  display: inline-flex;
  height: 24px;
  align-items: center;
  border: 1px solid var(--ops-line, #8eb7e6);
  background: #fff;
  color: var(--ops-blue-deep, #15599d);
  padding: 0 7px;
  white-space: nowrap;
}

.domain-select i {
  margin-left: 9px;
  color: var(--ops-blue, #2d7bcd);
  font-style: normal;
}

.domain-register-label {
  margin-left: 12px;
}

.domain-radio {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: var(--ops-blue-deep, #15599d);
  white-space: nowrap;
}

.domain-radio i {
  display: block;
  width: 10px;
  height: 10px;
  border: 1px solid var(--ops-blue, #2d7bcd);
  border-radius: 50%;
  background: #fff;
}

.domain-radio:first-of-type i {
  background: var(--ops-blue, #2d7bcd);
}

.domain-login-label {
  margin-left: 12px;
}

.domain-login-link {
  max-width: min(390px, 45vw);
  overflow: hidden;
  text-overflow: ellipsis;
}

.domain-copy {
  color: var(--ops-blue-deep, #15599d);
  text-decoration: underline;
  text-underline-offset: 3px;
  white-space: nowrap;
}
</style>
