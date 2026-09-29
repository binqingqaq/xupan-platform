<template>
  <section class="report-control-panel" aria-label="报网控制静态区域">
    <div class="report-control-row report-control-main-row">
      <strong class="report-control-label">报网类型:</strong>
      <span class="report-type-select">TT</span>
      <span class="report-control-button report-control-account">设置账号</span>
      <span class="report-control-button report-control-logout">退出</span>
      <span class="report-control-button report-control-login">登录</span>
      <span class="report-control-metric">押注: <b>0</b></span>
      <span class="report-control-metric">网盘余额: <b>0</b></span>
      <span class="report-control-button report-control-move"><i aria-hidden="true">↓</i>移下</span>
    </div>
    <div class="report-control-row report-control-status-row">
      <strong class="report-control-label">报网状态:</strong>
      <span class="report-control-check report-control-offline"><i aria-hidden="true"></i>离线</span>
      <span class="report-control-check"><i aria-hidden="true"></i>失败自动退单</span>
      <span class="report-control-check"><i aria-hidden="true"></i>自动上报网盘</span>
      <span class="report-control-metric">超时订单: <b>0</b></span>
      <span class="report-control-button report-control-refresh">刷新</span>
    </div>
  </section>
</template>

<style scoped>
.report-control-panel {
  margin: 8px 0;
  overflow: hidden;
  border: 1px solid var(--ops-line-strong, #4b8ed3);
  border-radius: 2px;
  background: linear-gradient(#fff, var(--ops-panel-soft, #f3f8ff));
  color: var(--ops-text, #243b53);
  font-size: 12px;
  box-shadow: inset 0 0 0 1px rgb(255 255 255 / 78%);
}

.report-control-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 5px;
  min-height: 30px;
  padding: 3px 8px;
}

.report-control-row + .report-control-row {
  border-top: 1px solid var(--ops-line, #8eb7e6);
}

.report-control-label {
  color: var(--ops-blue-deep, #15599d);
  font-size: 12px;
  font-weight: 800;
  white-space: nowrap;
}

.report-type-select {
  display: inline-flex;
  min-width: 46px;
  height: 23px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--ops-line, #8eb7e6);
  background: #fff;
  color: var(--ops-blue-deep, #15599d);
  font-weight: 700;
}

.report-control-button {
  display: inline-flex;
  height: 23px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--ops-line, #8eb7e6);
  background: linear-gradient(#fff, #e8f3ff);
  color: var(--ops-text, #243b53);
  padding: 0 8px;
  white-space: nowrap;
}

.report-control-account {
  min-width: 58px;
}

.report-control-logout {
  min-width: 40px;
  border-color: #d89aa0;
  background: linear-gradient(#ffd9dd, #f3a9b1);
  color: #8e2932;
}

.report-control-login {
  min-width: 40px;
  border-color: #5c9f55;
  background: linear-gradient(#8bd476, #5fb44e);
  color: #fff;
}

.report-control-move {
  min-width: 48px;
  border-color: #b9c7d5;
  background: linear-gradient(#fff, #edf2f6);
  color: #6d7f8d;
}

.report-control-move i {
  margin-right: 2px;
  font-style: normal;
  font-size: 13px;
}

.report-control-metric {
  color: var(--ops-text, #243b53);
  white-space: nowrap;
}

.report-control-metric b {
  color: var(--ops-blue-deep, #15599d);
  font-weight: 800;
}

.report-control-check {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--ops-blue-deep, #15599d);
  white-space: nowrap;
}

.report-control-check i {
  display: block;
  width: 11px;
  height: 11px;
  border: 1px solid #8faec1;
  background: #fff;
}

.report-control-offline {
  color: #d63333;
  font-weight: 700;
}

.report-control-offline i {
  border-color: #d48b8b;
}

.report-control-refresh {
  min-width: 46px;
  border-color: var(--ops-blue, #2d7bcd);
  background: linear-gradient(#5aa8e7, var(--ops-blue, #2d7bcd));
  color: #fff;
  font-weight: 700;
}
</style>
