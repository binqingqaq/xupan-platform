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

  const login = () => {
    const username = document.querySelector('#name');
    const notice = document.querySelector('#msg');
    if (!username?.value.trim()) {
      if (notice) {
        notice.textContent = '请输入账号';
        notice.style.display = 'block';
      }
      username?.focus();
      return;
    }
    // 本地重建不验证、不记录、不发送密码。
    window.top.location.href = '/hby019-demo/console';
  };

  document.querySelector('#btnLogin')?.addEventListener('click', event => {
    event.preventDefault();
    login();
  });
  document.querySelector('#btnLogin2')?.addEventListener('click', event => {
    event.preventDefault();
    window.top.location.href = '/hby019-demo/portal';
  });
  document.querySelector('input#password')?.addEventListener('keydown', event => {
    if (event.key === 'Enter') {
      event.preventDefault();
      login();
    }
  });
})();
