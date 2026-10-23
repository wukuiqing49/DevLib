'use strict';
(() => {
  const $ = id => document.getElementById(id);
  let messages = {}, authorized = false, cursor = null, polling = false, pairing = null;
  let probeSpeed = null;
  let probeAt = 0;
  let preferredWebSocket = false;
  let events = null, lastEvent = 0, nextEventsAttempt = 0;
  const fileRows = new Map(), advancedRows = new Map();
  const activeStates = ['QUEUED', 'READY', 'STREAMING'];
  const taskFor = id => [...tasks.values()].filter(task => task.fileId === id).reverse().find(task => activeStates.includes(task.state)) || [...tasks.values()].filter(task => task.fileId === id).at(-1);
  const measurements = new Map();
  let advice = null, adviceBusy = false, adviceAt = -Infinity, adviceKey = '', connectionGeneration = 0;
  const socketSinks = new Map();
  const canStreamSave = () => window.isSecureContext && typeof window.showSaveFilePicker === 'function' && typeof WebSocket === 'function';
  const files = new Map(), tasks = new Map();
  const entry = location.hash.slice(1);
  history.replaceState(null, '', location.pathname);
  const text = key => messages[key] || messages.generic || key;
  const showError = error => { $('notice').textContent = text(error.code || 'networkError'); };
  // 系统信息仅用于选择保存帮助，绝不据此推断性能或最快协议；用户可自行切换。
  $('save-platform').value = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
    ? 'apple' : /Android/.test(navigator.userAgent) ? 'android' : 'desktop';
  const renderSavePlatform = () => document.querySelectorAll('[data-platform]').forEach(node => { node.hidden = node.dataset.platform !== $('save-platform').value; });
  $('save-platform').addEventListener('change', renderSavePlatform); renderSavePlatform();
  function element(tag, value, className) {
    const node = document.createElement(tag);
    if (value != null) node.textContent = value;
    if (className) node.className = className;
    return node;
  }
  async function api(path, options = {}) {
    const control = new AbortController();
    const timeout = setTimeout(() => control.abort(), 10000);
    try {
      const response = await fetch('/api/v1' + path, {
        credentials: 'same-origin', cache: 'no-store', ...options,
        headers: { 'X-DropLAN': '1', ...options.headers }, signal: control.signal
      });
      const data = await response.json();
      if (!response.ok) {
        if (response.status === 401) {
          events?.close(); events = null; lastEvent = 0;
          socketSinks.forEach(state => { state.socket?.close(); state.sink.abort().catch(() => {}); });
          socketSinks.clear();
          authorized = false; files.clear(); tasks.clear();
          probeSpeed = null; document.body.classList.remove('connected');
          clearAdvice();
          $('files').replaceChildren(); $('advanced-files').replaceChildren(); fileRows.clear(); advancedRows.clear();
          $('connection-state').hidden = true;
          $('receiver').hidden = true; $('pair-panel').hidden = false;
        }
        throw { code: data.error || 'generic' };
      }
      return data;
    } finally { clearTimeout(timeout); }
  }
  function size(value) {
    if (value == null) return text('unknown');
    const number = Number(value);
    if (!Number.isFinite(number) || number < 0) return text('unknown');
    if (number < 1024) return number + ' B';
    const units = ['KiB', 'MiB', 'GiB', 'TiB'];
    let amount = number / 1024, index = 0;
    while (amount >= 1024 && index < units.length - 1) { amount /= 1024; index++; }
    return amount.toFixed(2) + ' ' + units[index];
  }
  function key() {
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  }
  const supportedLanguages = ['zh', 'en', 'ja', 'ko', 'de', 'fr', 'es', 'pt-BR'];
  function receiverLanguage(tag) {
    const base = String(tag).toLowerCase().split('-')[0];
    return base === 'pt' ? 'pt-BR' : supportedLanguages.includes(base) ? base : 'en';
  }
  async function loadLanguage(language) {
    language = supportedLanguages.includes(language) ? language : 'en';
    const response = await fetch('/assets/' + language + '.json', { cache: 'no-store' });
    if (!response.ok) throw { code: 'networkError' };
    messages = await response.json();
    document.documentElement.lang = language === 'zh' ? 'zh-CN' : language;
    $('language').value = language;
    document.querySelectorAll('[data-i18n]').forEach(node => { node.textContent = text(node.dataset.i18n); });
    if (pairing) $('pair-state').textContent = text('pending');
    renderFiles(); renderTasks();
  }
  function renderFiles() {
    for (const [id, row] of fileRows) {
      if (!files.has(id)) { row.remove(); advancedRows.get(id)?.remove(); fileRows.delete(id); advancedRows.delete(id); }
    }
    $('files').querySelector('.empty')?.remove();
    files.forEach(file => {
      const pending = taskFor(file.id);
      const signature = JSON.stringify([file, pending?.id, pending?.state, preferredWebSocket, document.documentElement.lang]);
      let row = fileRows.get(file.id);
      if (row?.dataset.signature === signature) return;
      if (!row) { row = element('li'); row.dataset.file = file.id; fileRows.set(file.id, row); $('files').append(row); }
      const hadFocus = row.contains(document.activeElement);
      const advancedOpen = advancedRows.get(file.id)?.querySelector('details')?.open || false;
      row.dataset.signature = signature;
      row.replaceChildren();
      advancedRows.get(file.id)?.remove();
      const info = element('div', null, 'file-info');
      info.append(element('span', file.name, 'file-name'));
      if (file.bundle) info.append(element('span', text('bundle'), 'badge'));
      info.append(element('span', size(file.size), 'detail'));
      const estimate = element('span', null, 'detail estimate'); estimate.dataset.file = file.id; info.append(estimate);
      const busyTask = pending && activeStates.includes(pending.state);
      const button = pending?.state === 'READY' && !pending.websocket ? nativeDownloadLink(pending, file.name)
        : element('button', text(busyTask ? pending.state : 'download'));
      if (button.tagName === 'BUTTON') {
        button.type = 'button'; button.disabled = !!busyTask;
        button.addEventListener('click', async () => {
          button.disabled = true; button.classList.add('loading');
          try { await createTask(file.id); } catch (error) { showError(error); }
          finally { button.disabled = false; button.classList.remove('loading'); }
        });
      }
      row.append(info, button);
      info.append(element('span', null, 'transfer-status detail'));
      const progress = element('div', null, 'transfer-progress');
      progress.setAttribute('role', 'progressbar'); progress.setAttribute('aria-label', text('responseProgress'));
      progress.setAttribute('aria-valuemin', '0'); progress.setAttribute('aria-valuemax', '100');
      progress.append(element('span')); progress.hidden = true; info.append(progress);
      if (busyTask) {
        const cancel = element('button', text('cancel'), 'secondary'); cancel.type = 'button';
        cancel.addEventListener('click', async () => {
          cancel.disabled = true;
          const state = socketSinks.get(pending.id);
          try {
            if (state) { state.socket?.close(); await state.sink.abort().catch(() => {}); socketSinks.delete(pending.id); }
            await api('/downloads/' + pending.id, {method:'DELETE'});
            pending.state = 'CANCELLED'; renderTasks();
          } catch (error) { showError(error); cancel.disabled = false; }
        });
        row.append(cancel);
      }
      const advanced = element('details', null, 'advanced-transfer'); advanced.open = advancedOpen;
      advanced.append(element('summary', file.name),element('p',text(file.range ? 'range' : 'sequential'),'detail'));
      if (!file.stableVersion) advanced.append(element('p',text('unstable'),'detail'));
      if (canStreamSave()) {
        advanced.append(element('p', text('websocketHelp'), 'detail'));
        const socketButton = element('button', text('websocketDownload'));
        socketButton.type = 'button';
        socketButton.addEventListener('click', async () => {
          socketButton.disabled = true;
          let sink, task;
          try {
            // 文件选择必须直接来自点击；先取得写句柄，再创建受调度的任务。
            const handle = await window.showSaveFilePicker({ suggestedName: file.name });
            sink = await handle.createWritable();
            task = await api('/downloads', { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key() }, body: JSON.stringify({ fileId: file.id }) });
            socketSinks.set(task.id, { sink, file, started: false });
            tasks.set(task.id, { ...task, attempted: false, websocket: true });
            renderTasks(); if (task.state === 'READY') startTask(task.id);
          } catch (error) {
            if (sink) await sink.abort().catch(() => {});
            if (task) await api('/downloads/' + task.id, { method: 'DELETE' }).catch(() => {});
            if (error.name !== 'AbortError') showError(error);
          } finally { socketButton.disabled = false; }
        });
        if (preferredWebSocket && !busyTask) {
          socketButton.className = 'download'; socketButton.textContent = text('download');
          button.textContent = text('downloadFallback');
          row.replaceChildren(info, socketButton);
          advanced.append(button);
        } else advanced.append(socketButton);
      } else if (preferredWebSocket) {
        advanced.append(element('p', text('websocketFallback'), 'detail'));
      }
      const advancedRow = element('li'); advancedRow.append(advanced);
      advancedRows.set(file.id, advancedRow); $('advanced-files').append(advancedRow);
      if (hadFocus) row.querySelector('a, button:not(:disabled)')?.focus({preventScroll:true});
    });
    if (!files.size) $('files').append(element('li', text('empty'), 'empty'));
    renderConnectionAdvice();
  }
  // 仅使用当前页面的测量，复用活动响应均速；不按系统名称推断最快协议。
  function currentMeasurement() {
    const active = [...tasks.values()].filter(task => task.state === 'STREAMING' && !task.invalidatedMeasurement);
    const speed = active.reduce((sum,task) => sum + (performance.now() - task.observedAt <= 7000 && Number.isFinite(task.bytesPerSecond) && task.bytesPerSecond > 0 ? task.bytesPerSecond : 0),0);
    if (active.length) return speed > 0 ? {speed,live:true} : null;
    return probeSpeed > 0 && performance.now() - probeAt <= 120000 ? {speed:probeSpeed,live:false} : null;
  }
  function renderConnectionAdvice() {
    const fresh = [...measurements.entries()].filter(([transport, sample]) => performance.now() - sample.at < 120000 && (transport !== 'WEBSOCKET' || canStreamSave()));
    const comparable = fresh.length === 2 && advice?.compared;
    $('recommendation').textContent = comparable ? text('adviceMeasured') + ' ' + (advice.transport === 'WEBSOCKET' ? 'WebSocket' : location.protocol === 'https:' ? 'HTTPS' : 'HTTP')
      : text(preferredWebSocket && canStreamSave() ? 'adviceStreaming' : 'adviceNative');
    $('apply-advice').hidden = !comparable || (advice.transport === 'WEBSOCKET') === preferredWebSocket;
    $('apply-advice').disabled = [...tasks.values()].some(task => ['STREAMING', 'READY', 'QUEUED'].includes(task.state));
    const measurement = currentMeasurement();
    $('speed').textContent = measurement ? text(measurement.live ? 'transferMeasured' : 'probeResult') + ': ' + (measurement.speed/1000000).toFixed(2) + ' MB/s' : '';
    document.querySelectorAll('.estimate').forEach(node => {
      const file = files.get(node.dataset.file), length = Number(file?.size);
      const active = [...tasks.values()].find(task => task.fileId === node.dataset.file && task.state === 'STREAMING' && !task.invalidatedMeasurement
        && performance.now() - task.observedAt <= 7000 && task.bytesPerSecond > 0 && task.responseLength != null);
      node.textContent = active ? text('remaining') + ' ' + Math.ceil(Math.max(0, Number(active.responseLength) - Number(active.bytesSent)) / active.bytesPerSecond) + ' s'
        : measurement && !measurement.live && file?.size != null && Number.isFinite(length) && length >= 0 ? text('estimatedTime') + ' ' + Math.ceil(length/measurement.speed) + ' s' : '';
    });
    if (!authorized || !fresh.length || adviceBusy || performance.now() - adviceAt < 5000) return;
    const samples = fresh.map(([transport, sample]) => ({ transport, bytesPerSecond: sample.speed }));
    const fileId = files.keys().next().value;
    const signature = JSON.stringify([fileId, samples.map(sample => [sample.transport, Math.round(sample.bytesPerSecond / 65536)])]);
    if (signature === adviceKey) return;
    adviceBusy = true; adviceAt = performance.now();
    const generation = connectionGeneration;
    api('/advice', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({samples, ...(fileId ? {fileId} : {})}) })
      .then(result => { if (generation === connectionGeneration) { advice = result; adviceKey = signature; } })
      .catch(() => { /* 推荐失败不阻止原生下载；下一轮刷新可重试。 */ })
      .finally(() => { adviceBusy = false; if (generation === connectionGeneration) renderConnectionAdvice(); });
  }
  function clearAdvice() {
    measurements.clear(); advice = null; adviceKey = ''; adviceAt = -Infinity; connectionGeneration++;
  }
  async function loadFiles(reset = false) {
    const data = await api('/manifest' + (!reset && cursor != null ? '?cursor=' + encodeURIComponent(cursor) : ''));
    if (reset) files.clear();
    data.items.forEach(file => files.set(file.id, file));
    cursor = data.nextCursor; $('more').hidden = cursor == null; renderTasks();
  }
  async function createTask(fileId) {
    const task = await api('/downloads', { method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key() }, body: JSON.stringify({ fileId }) });
    tasks.set(task.id, { ...task, attempted: true, localUntil: performance.now() + 10000 });
    renderTasks();
    $('notice').textContent = text(task.state === 'READY' ? 'downloadReady' : 'downloadQueued');
    const action = document.querySelector('a[data-task="' + CSS.escape(task.id) + '"]') || fileRows.get(fileId);
    action.tabIndex = 0; action.focus({preventScroll:true}); action.scrollIntoView({block:'nearest'});
  }
  function nativeDownloadLink(task, name) {
    const link = element('a', text('start'), 'download');
    // 真实用户点击同源下载链接；不在异步回调中模拟点击或导航隐藏 iframe。
    link.href = '/api/v1/files/' + encodeURIComponent(task.fileId) + '?task=' + encodeURIComponent(task.id);
    link.download = name || 'download'; link.dataset.task = task.id;
    link.addEventListener('click', () => {
      $('notice').textContent = text('downloadRequested');
    });
    return link;
  }
  function startTask(id) {
    const task = tasks.get(id);
    if (!task || !task.websocket || task.state !== 'READY' || task.attempted) return;
    task.attempted = true;
    startSocket(task);
  }
  function startSocket(task) {
    const state = socketSinks.get(task.id);
    if (!state || state.started) return;
    state.started = true;
    const socket = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host
      + '/api/v1/ws/files/' + encodeURIComponent(task.fileId) + '?task=' + encodeURIComponent(task.id), 'droplan.v1');
    state.socket = socket; socket.binaryType = 'arraybuffer';
    let count = 0n, busy = false, saved = false, failed = false;
    async function abort() {
      if (failed || saved) return;
      failed = true; socket.close(); socketSinks.delete(task.id);
      await state.sink.abort().catch(() => {});
      await api('/downloads/' + task.id, { method: 'DELETE' }).catch(() => {});
      showError({ code: 'networkError' });
    }
    socket.onopen = () => socket.send('next');
    socket.onmessage = async event => {
      if (busy || failed || saved) { await abort(); return; }
      busy = true;
      try {
        if (typeof event.data === 'string') {
          const result = JSON.parse(event.data);
          if (!result.done || BigInt(result.bytes) !== count || (state.file.size != null && BigInt(state.file.size) !== count)) throw new Error();
          await state.sink.close(); saved = true;
          $('notice').textContent = text('savedToChosenLocation');
          socket.send('saved'); socketSinks.delete(task.id);
        } else {
          if (event.data.byteLength > 65536 || event.data.byteLength === 0) throw new Error();
          await state.sink.write(event.data); count += BigInt(event.data.byteLength);
          socket.send('next');
        }
      } catch (_) { await abort(); }
      finally { busy = false; }
    };
    socket.onerror = () => { abort(); };
    socket.onclose = () => { if (!saved) abort(); };
  }
  function renderTasks() {
    renderFiles();
    fileRows.forEach((row, id) => {
      const task = taskFor(id), status = row.querySelector('.transfer-status');
      const progress = row.querySelector('.transfer-progress');
      const previousState = row.dataset.state;
      row.dataset.state = task?.state || '';
      let detail = task ? text(task.state) : '';
      if (task?.state === 'QUEUED' && task.queuePosition) detail += ' · ' + text('position') + ' ' + task.queuePosition;
      if (task?.state === 'STREAMING') {
        detail += ' · ' + size(task.bytesSent);
        if (task.bytesPerSecond > 0) detail += ' · ' + (task.bytesPerSecond / 1000000).toFixed(2) + ' MB/s';
      }
      if (task?.state === 'SENT') detail = text('sentShort');
      if (task && ['FAILED', 'EXPIRED'].includes(task.state)) detail = text('downloadFailure');
      status.textContent = detail;
      progress.hidden = !task || !['STREAMING', 'SENT'].includes(task.state);
      if (!progress.hidden) {
        const length = Number(task.responseLength), sent = Number(task.bytesSent);
        const known = task.responseLength != null && Number.isFinite(length) && length >= 0;
        const ratio = known ? (length === 0 ? 1 : Math.max(0, Math.min(1, sent / length))) : null;
        progress.classList.toggle('indeterminate', ratio == null);
        if (ratio == null) progress.removeAttribute('aria-valuenow');
        else progress.setAttribute('aria-valuenow', String(Math.round(ratio * 100)));
        progress.firstChild.style.width = ratio == null ? '30%' : (ratio * 100) + '%';
      }
      if (task?.state === 'SENT' && previousState && previousState !== 'SENT') {
        row.classList.remove('just-completed'); void row.offsetWidth; row.classList.add('just-completed');
      }
    });
  }
  async function connected() {
    const session = await api('/session'); authorized = true; pairing = null;
    preferredWebSocket = session.preferredTransport === 'WEBSOCKET';
    $('pair-panel').hidden = true; $('receiver').hidden = false; $('notice').textContent = '';
    document.body.classList.add('connected'); $('connection-state').hidden = false; probeSpeed = null; clearAdvice();
    await loadFiles(true);
    mergeTasks((await api('/downloads')).tasks);
    if (session.events) startEvents();
  }
  // 恢复列表只恢复观察状态，不能替浏览器重启下载或恢复丢失的写句柄。
  function mergeTasks(results) {
    if (!Array.isArray(results) || results.length > 128) throw new Error('Invalid tasks');
    const present = new Set(results.map(task => task.id));
    // 快照可能早于刚完成的 POST；短暂保留本页新任务，随后移除服务端已淘汰的历史。
    tasks.forEach((task, id) => {
      if (!present.has(id) && !socketSinks.has(id) && !(task.localUntil > performance.now())) tasks.delete(id);
    });
    results.forEach(result => {
      const task = tasks.get(result.id);
      if (task && !task.restored && !task.invalidatedMeasurement && ['STREAMING', 'SENT'].includes(result.state) && result.bytesSent !== task.bytesSent && Number(result.bytesSent) >= 65536
          && Number.isFinite(result.bytesPerSecond) && result.bytesPerSecond > 0) {
        measurements.set(task.websocket ? 'WEBSOCKET' : 'HTTP', {speed:result.bytesPerSecond, at:performance.now()});
      }
      if (task) Object.assign(task, result, { observedAt: performance.now() });
      else tasks.set(result.id, { ...result, attempted: true, restored: true, observedAt: performance.now() });
    });
    renderTasks();
  }
  function startEvents() {
    if (events || !authorized || typeof EventSource !== 'function' || Date.now() < nextEventsAttempt) return;
    const stream = new EventSource('/api/v1/events'); events = stream;
    stream.addEventListener('heartbeat', () => { if (events === stream) lastEvent = Date.now(); });
    stream.addEventListener('snapshot', async event => {
      if (events !== stream) return;
      try {
        const data = JSON.parse(event.data);
        if (!Array.isArray(data.tasks) || data.tasks.length > 128) throw new Error();
        lastEvent = Date.now();
        mergeTasks(data.tasks);
        for (const result of data.tasks) {
          const task = tasks.get(result.id); if (!task) continue;
          Object.assign(task, result, {observedAt:performance.now()});
          if (task.websocket && ['EXPIRED', 'CANCELLED', 'FAILED'].includes(task.state)) {
            const state = socketSinks.get(task.id);
            if (state) { state.socket?.close(); await state.sink.abort().catch(() => {}); socketSinks.delete(task.id); }
          }
        }
        renderTasks(); tasks.forEach(task => { if (task.state === 'READY') startTask(task.id); });
      } catch (_) { stream.close(); if (events === stream) events = null; lastEvent = 0; nextEventsAttempt = Date.now() + 15000; }
    });
    stream.onerror = () => {
      stream.close(); if (events === stream) events = null;
      lastEvent = 0; nextEventsAttempt = Date.now() + 15000;
    };
  }
  async function poll() {
    if (polling) return;
    polling = true;
    try {
      if (pairing) {
        if (Date.now() > pairing.expires) { pairing = null; throw { code: 'not_found' }; }
        const result = await api('/pairings/' + pairing.id, { headers: { 'X-Pairing-Ticket': pairing.ticket } });
        if (result.state === 'APPROVED') await connected();
        else if (result.state === 'REJECTED') { pairing = null; throw { code: 'rejected' }; }
      } else if (authorized) {
        startEvents();
        if (events && Date.now() - lastEvent < 7000) return;
        await api('/session');
        mergeTasks((await api('/downloads')).tasks);
        for (const [id, task] of tasks) {
          if (task.websocket && ['EXPIRED', 'CANCELLED', 'FAILED'].includes(task.state)) {
            const state = socketSinks.get(id);
            if (state) { state.socket?.close(); await state.sink.abort().catch(() => {}); socketSinks.delete(id); }
          }
        }
        renderTasks(); tasks.forEach(task => { if (task.state === 'READY') startTask(task.id); });
      }
    } catch (error) { showError(error); }
    finally { polling = false; renderConnectionAdvice(); setTimeout(poll, document.hidden ? 5000 : 2000); }
  }
  $('pair-form').addEventListener('submit', async event => {
    event.preventDefault();
    const button = event.submitter || $('pair-form').querySelector('button'); button.disabled = true;
    try {
      const result = await api('/pairings', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token: $('token').value.trim() }) });
      pairing = { id: result.id, ticket: result.ticket, expires: Date.now() + 120000 };
      $('token').value = ''; $('pair-state').textContent = text('pending'); $('pair-state').classList.add('waiting'); $('notice').textContent = '';
    } catch (error) { showError(error); } finally { button.disabled = false; button.classList.remove('loading'); }
  });
  $('probe').addEventListener('click', async () => {
    if ([...tasks.values()].some(task => task.state === 'STREAMING')) { renderConnectionAdvice(); return; }
    $('probe').disabled = true; $('speed').textContent = text('probeWorking');
    const control = new AbortController(), timeout = setTimeout(() => control.abort(), 3000);
    const start = performance.now(); let reader, bytes = 0;
    try {
      if (!window.ReadableStream) throw { code: 'probeUnsupported' };
      const response = await fetch('/api/v1/probe', { cache: 'no-store', credentials: 'same-origin', signal: control.signal });
      if (!response.ok) { const result = await response.json(); throw { code: result.error }; }
      if (!response.body?.getReader) throw { code: 'probeUnsupported' };
      reader = response.body.getReader();
      while (true) {
        const { value, done } = await reader.read(); if (done) break;
        bytes += value.byteLength;
        if (bytes >= 32 * 1024 * 1024 || performance.now() - start >= 3000) { await reader.cancel(); break; }
      }
      const seconds = (performance.now() - start) / 1000;
      probeSpeed = bytes / Math.max(seconds, .001); probeAt = performance.now(); measurements.set('HTTP', {speed:probeSpeed, at:probeAt}); renderConnectionAdvice();
    } catch (error) {
      if (control.signal.aborted && bytes > 0) { probeSpeed = bytes / Math.max((performance.now()-start)/1000,.001); probeAt = performance.now(); measurements.set('HTTP', {speed:probeSpeed, at:probeAt}); renderConnectionAdvice(); }
      else { probeSpeed = null; showError(error); renderConnectionAdvice(); }
    }
    finally { if (reader) { await reader.cancel().catch(() => {}); reader.releaseLock(); } clearTimeout(timeout); $('probe').disabled = false; }
  });
  $('language').addEventListener('change', () => loadLanguage($('language').value).catch(showError));
  $('apply-advice').addEventListener('click', () => {
    if (!advice?.compared || $('apply-advice').disabled) return;
    preferredWebSocket = advice.transport === 'WEBSOCKET' && canStreamSave(); renderFiles();
  });
  const invalidateMeasurement = () => { probeSpeed = null; clearAdvice(); tasks.forEach(task => { task.observedAt = -Infinity; task.invalidatedMeasurement = true; }); renderConnectionAdvice(); };
  window.addEventListener('offline',invalidateMeasurement); window.addEventListener('online',invalidateMeasurement);
  navigator.connection?.addEventListener('change',invalidateMeasurement);
  window.addEventListener('pagehide', () => {
    events?.close(); events = null; lastEvent = 0;
    socketSinks.forEach(state => { state.socket?.close(); state.sink.abort().catch(() => {}); });
    socketSinks.clear();
  });
  window.addEventListener('pageshow', event => {
    if (event.persisted && authorized) connected().catch(showError);
  });
  $('refresh').addEventListener('click', () => loadFiles(true).catch(showError));
  $('more').addEventListener('click', async () => {
    $('more').disabled = true;
    try { await loadFiles(); } catch (error) { showError(error); } finally { $('more').disabled = false; }
  });
  (async () => {
    try {
      await loadLanguage(receiverLanguage(navigator.language));
      $('token').value = entry;
      try { await connected(); } catch (error) {
        if (error.code !== 'authorization_required') showError(error);
        $('pair-panel').hidden = false;
      }
    } catch (error) { showError(error); }
    poll();
  })();
})();
