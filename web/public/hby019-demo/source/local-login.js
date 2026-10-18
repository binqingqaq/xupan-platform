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
  const login = () => {
    const username = document.querySelector('#name');
    const password = document.querySelector('#password');
    const notice = document.querySelector('#msg');
    const visibleNotice = document.querySelector('#xupan-login-message');
    const error = !username?.value.trim() ? '请输入账号' : !password?.value ? '请输入密码' : '';
    if (error) {
      if (notice) { notice.textContent = error; notice.style.display = 'block'; }
      if (visibleNotice) { visibleNotice.textContent = error; visibleNotice.style.display = 'block'; }
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
    if (!data || data.type !== 'xupan-console-login-result') return;
    busy = false;
    const button = document.querySelector('#btnLogin');
    const password = document.querySelector('#password');
    const notice = document.querySelector('#xupan-login-message');
    if (button) button.disabled = false;
    if (password) password.value = '';
    if (notice) {
      notice.textContent = String(data.error || '登录失败，请重试');
      notice.style.display = 'block';
    }
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
