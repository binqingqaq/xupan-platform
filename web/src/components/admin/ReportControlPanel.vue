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
  margin: 14px 0;
  border: 1px solid #4b91dd;
  background: #fff;
  color: #23506f;
  font-size: 12px;
}

.report-control-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-height: 42px;
  padding: 5px 12px;
}

.report-control-row + .report-control-row {
  border-top: 1px solid #d8e8f6;
}

.report-control-label {
  color: #23506f;
  font-size: 12px;
  font-weight: 800;
  white-space: nowrap;
}

.report-type-select {
  display: inline-flex;
  min-width: 46px;
  height: 24px;
  align-items: center;
  justify-content: center;
  border: 1px solid #4b91dd;
  background: #fff;
  color: #1d76c5;
  font-weight: 700;
}

.report-control-button {
  display: inline-flex;
  height: 24px;
  align-items: center;
  justify-content: center;
  border: 1px solid #5b8cae;
  background: #e8f1f8;
  color: #35647f;
  padding: 0 8px;
  white-space: nowrap;
}

.report-control-account {
  min-width: 58px;
  background: #e5f1fb;
}

.report-control-logout {
  min-width: 40px;
  border-color: #cf6a72;
  background: #ffb5bd;
  color: #8e2932;
}

.report-control-login {
  min-width: 40px;
  border-color: #55a447;
  background: #75c557;
  color: #fff;
}

.report-control-move {
  min-width: 48px;
  border-color: #b6c4cf;
  background: #edf2f5;
  color: #6d7f8d;
}

.report-control-move i {
  margin-right: 2px;
  font-style: normal;
  font-size: 13px;
}

.report-control-metric {
  color: #23506f;
  white-space: nowrap;
}

.report-control-metric b {
  color: #174f78;
  font-weight: 800;
}

.report-control-check {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #22649b;
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
  border-color: #2e80bd;
  background: #2e8bd0;
  color: #fff;
}

@media (max-width: 760px) {
  .report-control-row {
    gap: 6px;
    padding: 7px 8px;
  }

  .report-control-button {
    padding: 0 6px;
  }
}
</style>
