/* 支付原型仅模拟状态；不连接 Play、不验证订单、不写入付费授权。 */
(() => {
  'use strict';
  const names = window.BILLING_LOCALE_NAMES;
  const dictionaries = window.BILLING_LOCALES;
  const savedLocale = new URLSearchParams(location.search).get('lang');
  let locale = dictionaries[savedLocale] ? savedLocale : 'zh-CN';
  const model = { sheet: false, dark: matchMedia('(prefers-color-scheme: dark)').matches, large: false,
    expanded: false, selected: '', state: 'ready', redeem: null, closed: false, feedback: '' };
  const states = ['ready', 'loading', 'pending', 'pro', 'offline', 'error', 'empty', 'cancelled', 'checking'];
  const plans = ['month', 'year', 'lifetime'];
  const amounts = { month: 2.99, year: 19.99, lifetime: 49.99 };
  const esc = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  /** 按当前语言取完整资源，并校验命名参数；缺少翻译不能静默回退。 */
  function t(key, args = {}) {
    const value = dictionaries[locale][key];
    if (typeof value !== 'string') throw Error(`Missing translation: ${locale}/${key}`);
    return value.replace(/\{(\w+)\}/g, (_, name) => {
      if (!(name in args)) throw Error(`Missing parameter: ${key}/${name}`);
      return name === 'price' ? '\u2068' + args[name] + '\u2069' : args[name];
    });
  }
  /** 所有语言使用相同 USD 示例金额，仅按 Locale 格式化，不进行汇率换算。 */
  function price(plan) { return new Intl.NumberFormat(locale, { style: 'currency', currency: 'USD' }).format(amounts[plan]); }
  function button(id, key, css = 'text-button', extra = '') {
    return `<button id="${id}" class="${css}" ${extra}>${esc(t(key))}</button>`;
  }
  function setAction(id, action) { const el = document.getElementById(id); if (el) el.onclick = action; }
  function notice(key, body) { return `<div class="notice" style="display:block" role="status"><strong>${esc(t(key))}</strong>${esc(t(body))}</div>`; }
  /** 付费页与兑换页共享形态，兑换使用同层替换，避免叠加两个可交互弹窗。 */
  function payment() {
    let message = '';
    if (model.state !== 'ready') message = notice(model.state, model.state === 'pro' ? 'successBody' : `${model.state}Body`);
    const selectable = ['ready', 'cancelled'].includes(model.state);
    const showPlans = !['loading', 'pro', 'empty', 'checking'].includes(model.state);
    const selected = model.selected;
    const disclosureKey = selected ? ({ month: 'renewMonth', year: 'renewYear', lifetime: 'oneTime' })[selected] : 'select';
    const disclosure = model.state === 'ready' || model.state === 'cancelled'
      ? t(disclosureKey, { price: selected ? price(selected) : '' }) : t(model.state === 'pro' ? 'successBody' : `${model.state}Body`);
    const action = model.state === 'pro' ? t('done') : ['error','empty'].includes(model.state) ? t('retry')
      : selectable ? selected ? t('buy', {price:price(selected)}) : t('select') : t(model.state);
    const disabled = !(model.state === 'pro' || ['error','empty'].includes(model.state) || selectable && selected);
    return `<div class="screen ${model.expanded ? 'expanded' : ''}">
      ${model.sheet ? `<button class="handle" id="expand" aria-label="${esc(t('expand'))}" aria-expanded="${model.expanded}"></button>` : ''}
      <div class="toolbar"><strong>${esc(t('choose'))}</strong><button id="close" class="icon" aria-label="${esc(t('close'))}">×</button></div>
      <div class="content">${model.feedback ? `<div class="toast" style="display:block" role="status">${esc(t(model.feedback))}</div>` : ''}${message}
      <div class="mark" aria-hidden="true">${icon('tool')}</div>
      <h2>${esc(t(model.state === 'pro' ? 'success' : 'title'))}</h2>
      ${model.state === 'pro' ? `<section class="owned" style="display:block"><strong>${esc(t('year'))}</strong><p>${esc(t('successBody'))}</p>${button('manage','manage')}</section>` : `<p class="intro">${esc(t('intro'))}</p><div class="benefits"><div class="benefit">${icon('check')}<span>${esc(t('benefit1'))}</span></div><div class="benefit">${icon('check')}<span>${esc(t('benefit2'))}</span></div></div>`}
      ${showPlans ? `<h3 class="section-title">${esc(t('choose'))}</h3><div class="plans" role="radiogroup" aria-label="${esc(t('choose'))}">${plans.map(plan => `<button class="plan" data-plan="${plan}" role="radio" aria-checked="${selected === plan}" tabindex="${selected ? selected === plan ? 0 : -1 : plan === 'month' ? 0 : -1}" ${selectable ? '' : 'disabled'}><span class="radio" aria-hidden="true"></span><span class="plan-copy"><span class="plan-name">${esc(t(plan))}</span><small>${esc(t(({month:'perMonth',year:'perYear',lifetime:'once'})[plan]))}</small></span><span class="price"><bdi>${esc(price(plan))}</bdi></span></button>`).join('')}</div>` : ''}</div>
      <div class="footer"><p class="selected-summary">${esc(t(model.state === 'pro' ? 'year' : selected || 'choose'))}</p><p class="disclosure">${esc(disclosure)}</p><button class="primary" id="buy" ${disabled ? 'disabled' : ''}>${esc(action)}</button><p class="trust">${esc(t('secure'))}</p><div class="secondary">${button('restore','restore')}${button('redeem','redeem')}</div><div class="legal">${button('terms','terms')}${button('privacy','privacy')}</div></div></div>`;
  }
  function icon(type) {
    const path = type === 'check' ? '<path d="m5 12 4 4L19 6"/>' : type === 'ticket'
      ? '<path d="M3 7h18v4a2 2 0 0 0 0 4v4H3v-4a2 2 0 0 0 0-4V7ZM15 7v12M7 11h4M7 15h3"/>'
      : '<rect x="3" y="8" width="18" height="13" rx="3"/><path d="M8 8V5a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v3M3 13h18M10 12v4h4v-4"/>';
    return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">${path}</svg>`;
  }
  function redemption() {
    const phase = model.redeem;
    const titleKey = phase === 'intro' ? 'redeemTitle' : phase === 'checking' ? 'checking' : phase;
    return `<section id="redeemPage" class="redeem-page open ${model.expanded ? 'expanded' : ''}" aria-label="${esc(t('redeemTitle'))}">
      <div class="toolbar"><button class="icon back-icon" id="redeemBack" aria-label="${esc(t('back'))}">‹</button><strong>${esc(t('redeem'))}</strong></div>
      <div class="redeem-body"><div class="redeem-symbol">${icon('ticket')}</div><h2 id="redeemHeading" tabindex="-1">${esc(t(titleKey))}</h2>
      ${phase === 'intro' ? `<p>${esc(t('redeemIntro'))}</p><div class="redeem-tip">${esc(t('redeemAccount'))}</div><p>${esc(t('redeemGift'))}</p>` : `<div role="status" aria-live="polite"><p>${esc(t(`${phase}Body`))}</p>${phase === 'missing' ? `<div class="redeem-tip">${esc(t('redeemGift'))}</div>` : ''}</div>`}</div>
      <div class="footer"><p class="disclosure">${esc(t('prototype'))}</p><button class="primary" id="redeemAction" ${phase === 'checking' ? 'disabled' : ''}>${esc(t(phase === 'intro' ? 'redeemGo' : phase === 'success' ? 'done' : phase === 'checking' ? 'checking' : 'retry'))}</button>${phase === 'intro' ? button('redeemRestore','redeemAlready') : ''}</div></section>`;
  }
  /** 根据状态重新绑定原型，保留滚动位置与可用焦点；语言切换不重置购买选择。 */
  function render(focusId = '') {
    const activeId = focusId || document.activeElement?.id;
    const scroll = document.querySelector('.content')?.scrollTop || 0;
    document.documentElement.lang = locale;
    document.documentElement.dir = locale === 'ar' ? 'rtl' : 'ltr';
    document.title = `${t('choose')} · Google Play`;
    document.getElementById('app').innerHTML = `<header><div><div class="eyebrow">DEVLIB / GOOGLE BILLING</div><h1>${esc(t('choose'))}</h1><p>${esc(t('intro'))}</p></div></header>
      <div class="controls"><button id="activity" aria-pressed="${!model.sheet}">Activity</button><button id="sheet" aria-pressed="${model.sheet}">BottomSheet</button><button id="theme" aria-pressed="${model.dark}">${esc(t('dark'))}</button><button id="font" aria-pressed="${model.large}">${esc(t('large'))}</button><label><span class="control-label">${esc(t('language'))}</span><select id="language" aria-label="${esc(t('language'))}">${Object.entries(names).map(([id,name])=>`<option value="${id}" ${id===locale?'selected':''}>${esc(name)}</option>`).join('')}</select></label><label><span class="control-label">${esc(t(model.redeem ? 'preview' : 'state'))}</span><select id="state" aria-label="${esc(t(model.redeem ? 'preview' : 'state'))}">${(model.redeem ? ['intro','checking','success','missing','failed'] : states).map(state=>`<option value="${state}" ${(model.redeem || model.state)===state?'selected':''}>${esc(t(state==='intro'?'redeemTitle':state))}</option>`).join('')}</select></label></div>
      <div class="stage"><section id="phone" class="phone ${model.sheet?'sheet':''} ${model.dark?'dark':''} ${model.large?'large-text':''} ${model.closed?'closed':''}" aria-label="${esc(t('choose'))}"><div class="system" aria-hidden="true"><span><bdi>9:41</bdi></span><span>▰ ▰ ▰</span></div>${model.sheet || model.closed ? `<div class="host" ${model.closed?'':'inert aria-hidden="true"'}><h3>${esc(t('benefit1'))}</h3><p>${esc(t('intro'))}</p><div class="host-card">${esc(t('benefit2'))}</div>${model.closed?button('reopen','reopen','primary'):''}</div>`:''}${model.sheet&&!model.closed?'<div class="shade"></div>':''}${model.closed?'':model.redeem?redemption():payment()}<div class="navigation" aria-hidden="true"></div></section><aside class="notes"><div class="note-block"><h3>${esc(t('prototype'))}</h3><p>${esc(t('secure'))}</p><p>${esc(t('redeemGift'))}</p><p>${esc(t('redeemAccount'))}</p></div><div class="note-block"><strong>Activity / BottomSheet</strong><p>${esc(t('intro'))}</p></div></aside></div>`;
    bind();
    const content = document.querySelector('.content'); if (content) content.scrollTop = scroll;
    if (activeId) document.getElementById(activeId)?.focus({preventScroll:true});
  }
  function update(action, focusId) { action(); render(focusId); }
  function showFeedback() { model.feedback = 'sample'; render(); document.querySelector('.content')?.scrollTo(0,0); }
  /** 仅在设计控制区切换模拟结果，支付界面不提供伪造成功按钮。 */
  function bind() {
    setAction('activity',()=>update(()=>{model.sheet=false;model.closed=false}));
    setAction('sheet',()=>update(()=>{model.sheet=true;model.closed=false}));
    setAction('theme',()=>update(()=>{model.dark=!model.dark}));
    setAction('font',()=>update(()=>{model.large=!model.large}));
    document.getElementById('language').onchange=e=>update(()=>{locale=e.target.value},'language');
    document.getElementById('state').onchange=e=>update(()=>{if(model.redeem)model.redeem=e.target.value;else model.state=e.target.value;model.feedback=''},'state');
    setAction('close',()=>update(()=>{model.closed=true;model.redeem=null},'reopen'));
    setAction('reopen',()=>update(()=>{model.closed=false},'close'));
    setAction('expand',()=>update(()=>{model.expanded=!model.expanded},'expand'));
    document.querySelectorAll('[data-plan]').forEach(el=>el.onclick=()=>{
      model.selected=el.dataset.plan; render(); document.querySelector(`[data-plan="${model.selected}"]`)?.focus({preventScroll:true});
    });
    const group=document.querySelector('.plans');
    if(group)group.onkeydown=e=>{
      if(!['ArrowUp','ArrowDown','ArrowLeft','ArrowRight'].includes(e.key)||!['ready','cancelled'].includes(model.state))return;
      e.preventDefault();const rtl=locale==='ar';let direction=['ArrowUp','ArrowLeft'].includes(e.key)?-1:1;
      if(rtl&&['ArrowLeft','ArrowRight'].includes(e.key))direction=-direction;
      const index=plans.indexOf(model.selected || 'month');model.selected=plans[(index+direction+plans.length)%plans.length];render();document.querySelector(`[data-plan="${model.selected}"]`).focus({preventScroll:true});
    };
    setAction('buy',()=>{if(model.state==='pro')update(()=>model.closed=true,'reopen');else if(['error','empty'].includes(model.state))update(()=>model.state='ready','buy');else showFeedback()});
    setAction('restore',()=>update(()=>{model.state='checking';model.feedback=''},'state'));
    setAction('manage',()=>{model.feedback='manage';render();document.querySelector('.content')?.scrollTo(0,0)});setAction('terms',()=>{model.feedback='terms';render();document.querySelector('.content')?.scrollTo(0,0)});setAction('privacy',()=>{model.feedback='privacy';render();document.querySelector('.content')?.scrollTo(0,0)});
    setAction('redeem',()=>update(()=>{model.redeem='intro';model.feedback=''},'redeemHeading'));
    setAction('redeemBack',()=>update(()=>{model.redeem=null},'redeem'));
    setAction('redeemRestore',()=>update(()=>{model.redeem='checking'},'redeemHeading'));
    setAction('redeemAction',()=>update(()=>{if(model.redeem==='success'){model.state='pro';model.redeem=null}else model.redeem='checking'},model.redeem==='success'?'buy':'redeemHeading'));
  }
  document.addEventListener('keydown',e=>{
    if(e.key!=='Escape'||e.target.id==='language'||e.target.id==='state')return;
    if(model.redeem)update(()=>model.redeem=null,'redeem');else if(!model.closed)update(()=>model.closed=true,'reopen');
  });
  /* 暴露只读副本供本地设计验证；不构成生产计费 API。 */
  window.billingDesign = { getState:()=>({locale,...model}), locales:Object.keys(dictionaries) };
  render();
})();
