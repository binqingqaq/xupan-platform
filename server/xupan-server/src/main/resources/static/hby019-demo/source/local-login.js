(() => {
  const params = new URLSearchParams(location.search);
  if (params.get('view') === 'console') {
    window.top.location.replace('/hby019-demo/console' + (params.get('reset-demo') === '1' ? '?reset=1' : ''));
    return;
  }
  if (params.get('view') === 'portal') {
    window.top.location.replace('/hby019-demo/portal');
    return;
  }

  let busy = false;
  function applyLoginPrefill(data) {
    const username = document.querySelector('#name');
    const password = document.querySelector('#password');
    const button = document.querySelector('#btnLogin');
    if (!username || !password || !button) return;
    username.value = data.username;
    username.readOnly = data.readOnly;
    username.style.background = data.readOnly ? '#f5f5f5' : '';
    password.value = '';
    button.disabled = data.invalid;
    if (data.invalid) showLoginError('机器登录地址无效，请联系管理员重新复制');
  }

  const showLoginError = message => {
    if (window.parent !== window) {
      window.parent.postMessage({ type: 'xupan-admin-notice', level: 'error', message: String(message).slice(0, 300) }, location.origin);
      return;
    }
    for (const selector of ['#msg', '#xupan-login-message']) {
      const notice = document.querySelector(selector);
      if (notice) { notice.textContent = message; notice.style.display = 'block'; }
    }
  };
  const login = () => {
    const username = document.querySelector('#name');
    const password = document.querySelector('#password');
    const visibleNotice = document.querySelector('#xupan-login-message');
    const error = !username?.value.trim() ? '请输入账号' : !password?.value ? '请输入密码' : '';
    if (error) {
      showLoginError(error);
      username?.focus();
      return;
    }
    if (busy) return;
    busy = true;
    const button = document.querySelector('#btnLogin');
    if (button) button.disabled = true;
    if (visibleNotice) visibleNotice.style.display = 'none';
    window.parent.postMessage({
      type: 'xupan-console-login',
      username: username.value.trim(),
      password: password.value,
    }, location.origin);
  };

  window.addEventListener('message', event => {
    if (event.origin !== location.origin || event.source !== window.parent) return;
    const data = event.data;
    if (data && data.type === 'xupan-console-login-prefill'
      && Object.keys(data).length === 4
      && ['type', 'username', 'readOnly', 'invalid'].every(key => Object.prototype.hasOwnProperty.call(data, key))
      && typeof data.username === 'string' && data.username.length <= 128
      && typeof data.readOnly === 'boolean' && typeof data.invalid === 'boolean'
      && !/[\\/\u0000-\u001f\u007f]/.test(data.username)) {
      busy = false;
      applyLoginPrefill(data);
      return;
    }
    if (!data || data.type !== 'xupan-console-login-result') return;
    busy = false;
    const button = document.querySelector('#btnLogin');
    const password = document.querySelector('#password');
    if (button) button.disabled = false;
    if (password) password.value = '';
    showLoginError(String(data.error || '登录失败，请重试'));
  });

  document.querySelector('#btnLogin')?.addEventListener('click', event => {
    event.preventDefault();
    event.stopImmediatePropagation();
    login();
  });
  document.querySelector('#btnLogin2')?.addEventListener('click', event => {
    event.preventDefault();
    window.top.location.href = '/hby019-demo/portal';
  });
  document.querySelector('input#password')?.addEventListener('keydown', event => {
    if (event.key === 'Enter') {
      event.preventDefault();
      event.stopImmediatePropagation();
      login();
    }
  });
})();
