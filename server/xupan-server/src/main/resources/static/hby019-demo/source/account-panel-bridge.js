(() => {
  const channel = 'xupan-hby019-account-panel'
  const root = document.querySelector('.uni-popup.right')
  const view = root?.querySelector('[data-v-30d8da26][style*="85vw"]')
  const rowsElement = root?.querySelector('#myDiv')
  if (!root || !view || !rowsElement) return
  const fontSizes = new Set(['mini', 'small', 'medium', 'big'])

  function post(type, payload = {}) {
    parent.postMessage({ channel, type, payload }, location.origin)
  }

  function setText(element, value) {
    if (element) element.textContent = value == null ? '' : String(value)
  }

  function updateFont(size, bold) {
    document.body.classList.remove('font_mini', 'font_small', 'font_medium', 'font_big', 'font_bold')
    document.body.classList.add(`font_${size}`)
    if (bold) document.body.classList.add('font_bold')
    root.querySelectorAll('.fs-size').forEach(button => {
      button.classList.toggle('fs-active', button.dataset.size === `font_${size}`)
    })
    root.querySelector('.fs-bold')?.classList.toggle('fs-active', Boolean(bold))
  }

  function renderRows(state) {
    rowsElement.replaceChildren()
    if (state.loading) {
      setText(rowsElement.appendChild(document.createElement('div')), '正在加载账户数据...')
      return
    }
    if (state.error) {
      const error = rowsElement.appendChild(document.createElement('div'))
      error.style.cssText = 'min-height:180px;display:grid;place-items:center;color:#c6454b;font-size:13px;'
      setText(error, state.error)
      return
    }

    for (const item of state.rows ?? []) {
      const row = document.createElement('div')
      row.className = 'record-row'
      row.setAttribute('data-v-30d8da26', '')
      row.dataset.rowId = item.id
      row.style.cssText = `display:flex;line-height:30px;align-items:center;cursor:pointer;background-color:${item.colorIndex ? '#fff8f0' : '#f0f7ff'};${item.refunded ? 'opacity:0.7;' : ''}`

      const issue = document.createElement('div')
      issue.setAttribute('data-v-30d8da26', '')
      issue.style.cssText = 'flex:1 1 10%;text-align:center;font-weight:550;'
      setText(issue, item.issue)

      const ball = document.createElement('div')
      ball.className = 'ellipsis-cell'
      ball.setAttribute('data-v-30d8da26', '')
      ball.style.cssText = `flex:3 1 0%;text-align:center;font-weight:550;overflow:hidden;text-overflow:ellipsis;white-space:${item.expanded ? 'normal' : 'nowrap'};${item.expanded ? 'word-break:break-all;' : ''}`
      setText(ball, item.ball)

      const text = document.createElement('div')
      text.className = 'ellipsis-cell'
      text.setAttribute('data-v-30d8da26', '')
      text.style.cssText = `flex:2 1 10%;text-align:center;font-weight:550;overflow:hidden;text-overflow:ellipsis;white-space:${item.expanded ? 'normal' : 'nowrap'};${item.refunded ? 'text-decoration:line-through;' : ''}${item.expanded ? 'word-break:break-all;' : ''}`
      setText(text, item.text)

      const result = document.createElement('div')
      result.setAttribute('data-v-30d8da26', '')
      result.style.cssText = `flex:2 1 0%;text-align:center;font-weight:550;${item.resultColor ? 'color:red;' : ''}${item.refunded ? 'text-decoration:line-through;' : ''}`
      setText(result, item.result)

      row.append(issue, ball, text, result)
      row.addEventListener('click', () => post('toggle-row', { id: item.id }))
      rowsElement.appendChild(row)
    }
  }

  function applyState(state) {
    if (!isValidState(state)) return
    const avatar = root.querySelector('.headimg')
    if (avatar) {
      const avatarUrl = typeof state.avatarUrl === 'string' ? state.avatarUrl : ''
      let safeAvatarUrl = ''
      try {
        const parsed = new URL(avatarUrl, location.origin)
        if (parsed.origin === location.origin) safeAvatarUrl = parsed.href
      } catch {
        safeAvatarUrl = ''
      }
      avatar.hidden = !safeAvatarUrl
      if (safeAvatarUrl && avatar.src !== safeAvatarUrl) avatar.src = safeAvatarUrl
      let fallback = avatar.parentElement.querySelector('.account-avatar-fallback')
      if (!safeAvatarUrl) {
        if (!fallback) {
          fallback = document.createElement('span')
          fallback.className = 'account-avatar-fallback'
          fallback.style.cssText = 'width:66px;height:66px;border-radius:33px;display:inline-grid;place-items:center;color:#2471a7;background:#e9f1f6;font-size:24px;font-weight:700;'
          avatar.after(fallback)
        }
        setText(fallback, state.avatarText || '我')
        fallback.hidden = false
      } else if (fallback) {
        fallback.hidden = true
      }
    }

    const identity = root.querySelector('.headimg')?.parentElement?.nextElementSibling
    const displayName = identity?.children[0]?.firstElementChild
    const username = identity?.children[1]?.firstElementChild
    setText(displayName, state.displayName || '我')
    setText(username, state.username || '')
    setText(root.querySelector('.liushuiTxt'), '累计今日流水')
    setText(root.querySelector('.liushui'), Number(state.todayTurnover ?? 0).toFixed(2))
    setText(root.querySelector('.yingkui'), `${Number(state.todayNetProfit ?? 0) >= 0 ? '+' : ''}${Number(state.todayNetProfit ?? 0).toFixed(2)}`)

    root.querySelectorAll('.btnClass').forEach(button => {
      const selected = button.getAttribute('type') === (state.tab === 'settled' ? '1' : '0')
      button.classList.toggle('btnClassActive', selected)
      button.setAttribute('aria-selected', String(selected))
    })
    updateFont(state.fontSize || 'small', state.fontBold)
    root.style.display = 'block'
    renderRows(state)
  }

  function isValidState(state) {
    if (!state || typeof state !== 'object' || Array.isArray(state)) return false
    if (typeof state.displayName !== 'string' || typeof state.username !== 'string') return false
    if (state.avatarUrl !== null && typeof state.avatarUrl !== 'string') return false
    if (typeof state.avatarText !== 'string' || !Number.isFinite(state.todayTurnover) || !Number.isFinite(state.todayNetProfit)) return false
    if (!['pending', 'settled'].includes(state.tab) || !fontSizes.has(state.fontSize)) return false
    if (typeof state.fontBold !== 'boolean' || typeof state.loading !== 'boolean' || typeof state.error !== 'string') return false
    if (!Array.isArray(state.rows)) return false
    return state.rows.every(item => item && typeof item === 'object'
      && typeof item.id === 'string'
      && typeof item.issue === 'string'
      && typeof item.ball === 'string'
      && typeof item.text === 'string'
      && typeof item.result === 'string'
      && typeof item.resultColor === 'boolean'
      && typeof item.refunded === 'boolean'
      && (item.colorIndex === 0 || item.colorIndex === 1)
      && typeof item.expanded === 'boolean')
  }

  root.querySelector('[name="mask"]')?.addEventListener('click', () => post('close'))
  root.querySelectorAll('.btnClass').forEach(button => {
    button.addEventListener('click', () => post('tab', { value: button.getAttribute('type') === '1' ? 'settled' : 'pending' }))
  })
  root.querySelectorAll('.fs-size').forEach(button => {
    button.addEventListener('click', () => post('font-size', { value: button.dataset.size?.replace('font_', '') }))
  })
  root.querySelector('.fs-bold')?.addEventListener('click', () => post('font-bold'))
  window.addEventListener('message', event => {
    if (event.source !== parent || event.origin !== location.origin) return
    if (event.data?.channel !== channel || event.data.type !== 'state') return
    applyState(event.data.payload)
  })
  post('ready')
})()
