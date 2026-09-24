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
  gap: 6px;
  min-height: 46px;
  margin-top: 14px;
  border: 1px solid #6ba0d5;
  background: #fff;
  color: #2e5f86;
  padding: 7px 12px;
  font-size: 12px;
}

.domain-label {
  font-weight: 800;
  white-space: nowrap;
}

.domain-select,
.domain-login-link {
  display: inline-flex;
  height: 28px;
  align-items: center;
  border: 1px solid #6ba0d5;
  background: #fff;
  color: #367eae;
  padding: 0 9px;
  white-space: nowrap;
}

.domain-select i {
  margin-left: 9px;
  color: #236ea5;
  font-style: normal;
}

.domain-register-label {
  margin-left: 24px;
}

.domain-radio {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: #1e79bb;
  white-space: nowrap;
}

.domain-radio i {
  display: block;
  width: 10px;
  height: 10px;
  border: 1px solid #208fd4;
  border-radius: 50%;
  background: #fff;
}

.domain-radio:first-of-type i {
  background: #208fd4;
}

.domain-login-label {
  margin-left: 24px;
}

.domain-login-link {
  max-width: min(390px, 45vw);
  overflow: hidden;
  text-overflow: ellipsis;
}

.domain-copy {
  color: #2e78af;
  text-decoration: underline;
  text-underline-offset: 3px;
  white-space: nowrap;
}

@media (max-width: 760px) {
  .admin-domain-footer {
    align-items: flex-start;
    padding: 8px;
  }

  .domain-register-label,
  .domain-login-label {
    margin-left: 0;
  }

  .domain-login-link {
    max-width: 52vw;
  }
}
</style>
