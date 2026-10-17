(() => {
  const form=document.querySelector('form');
  form?.addEventListener('submit',event=>{event.preventDefault();notify();});
  const button=document.querySelector('#btn3');
  const input=document.querySelector('#pwd');
  function notify(){
    let n=document.querySelector('#local-portal-note');
    if(!n){n=document.createElement('div');n.id='local-portal-note';n.style.cssText='position:fixed;left:50%;bottom:24px;transform:translateX(-50%);padding:8px 14px;background:rgba(0,0,0,.75);color:#fff;border-radius:4px;font:12px Arial,sans-serif;z-index:9999';document.body.append(n);}
    n.textContent=input?.value?'当前版本仅复刻密码校验入口，未连接玩家服务。':'请输入演示口令';
    clearTimeout(window.__portalToastTimer);window.__portalToastTimer=setTimeout(()=>n.remove(),2200);
  }
  button?.addEventListener('click',event=>{event.preventDefault();notify();});
})();
