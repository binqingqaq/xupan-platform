/* Renders the authenticated Xupan header payload; all other API fixtures stay local. */
(function ($) {
  let currentHeader = { state: 'loading' };

  function setText(selector, value) {
    const node = document.querySelector(selector);
    if (node) node.textContent = value;
  }

  function formatDate(value) {
    if (!value) return '--';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '--';
    const pad = number => String(number).padStart(2, '0');
    return date.getFullYear() + '/' + pad(date.getMonth() + 1) + '/' + pad(date.getDate())
      + ' ' + pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds());
  }

  function render() {
    if (currentHeader.state !== 'ready') {
      setText('[data-xupan-username]', currentHeader.state === 'error' ? '读取失败' : '加载中');
      setText('[data-xupan-bot-limit]', '--');
      setText('[data-xupan-expires-at]', '--');
      setText('[data-xupan-score]', '--');
      return;
    }
    setText('[data-xupan-username]', String(currentHeader.username || '--'));
    setText('[data-xupan-bot-limit]', currentHeader.botLimit == null ? '--' : String(currentHeader.botLimit));
    setText('[data-xupan-expires-at]', formatDate(currentHeader.expiresAt));
    const score = Number(currentHeader.score);
    setText('[data-xupan-score]', Number.isFinite(score) ? String(score) : '--');
  }

  window.addEventListener('message', function (event) {
    if (event.origin !== location.origin || event.source !== window.parent) return;
    const message = event.data;
    if (!message || message.type !== 'xupan-agent-header' || !message.data) return;
    currentHeader = message.data;
    render();
  });

  if ($ && typeof $.ajax === 'function') {
    const originalAjax = $.ajax;
    $.ajax = function (url, options) {
      const settings = typeof url === 'string'
        ? $.extend(true, {}, options || {}, { url })
        : $.extend(true, {}, url || {});
      const success = settings.success;
      if (typeof success === 'function') {
        settings.success = function () {
          try {
            return success.apply(this, arguments);
          } finally {
            render();
          }
        };
      }
      return originalAjax.call(this, settings);
    };
  }
})(window.jQuery);
