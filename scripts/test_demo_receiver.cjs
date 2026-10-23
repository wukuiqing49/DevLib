const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../feature/feature_demo_files/src/main/assets/demo_receiver/receiver.js'), 'utf8');

function harness(reply, hash = '#test-entry') {
  const node = () => ({ children: [], disabled: false, textContent: '', append(...items) { this.children.push(...items); }, replaceChildren() { this.children = []; } });
  const nodes = Object.fromEntries(['status', 'pair', 'refresh', 'files'].map(id => [id, node()]));
  nodes.refresh.disabled = true; // 与 HTML 的初始 disabled 属性一致。
  const requests = [];
  const history = [];
  vm.runInNewContext(source, {
    document: { getElementById: id => nodes[id], createElement: node, createTextNode: text => ({ textContent: text }) },
    location: { hash, pathname: '/' }, history: { replaceState: (...args) => history.push(args) },
    crypto: { getRandomValues: bytes => bytes.fill(7) },
    setTimeout: callback => callback(),
    fetch: async (url, options) => {
      requests.push({ url, options });
      const result = reply(url, options);
      return { ok: result.status === undefined, status: result.status, json: async () => result };
    }
  });
  return { nodes, requests, history };
}

test('pair approval, pagination, queued download and explicit download link', async () => {
  let polls = 0;
  const h = harness(url => {
    if (url.endsWith('/pairings')) return { id: 'pair', ticket: 'ticket' };
    if (url.endsWith('/pairings/pair')) return { state: 'APPROVED' };
    if (url.endsWith('/manifest?cursor=0')) return { items: [{ id: 'file', name: '<img src=x>' }], nextCursor: 1 };
    if (url.endsWith('/manifest?cursor=1')) return { items: [], nextCursor: null };
    if (url.endsWith('/downloads')) return { id: 'task', state: 'QUEUED' };
    if (url.endsWith('/downloads/task')) { polls++; return { id: 'task', state: 'READY' }; }
    throw Error('Unexpected request: ' + url);
  });
  await h.nodes.pair.onclick();
  assert.equal(h.nodes.refresh.disabled, false);
  const row = h.nodes.files.children[0];
  assert.equal(row.children[0].textContent, '<img src=x> ');
  await row.children[1].onclick();
  assert.equal(polls, 1);
  assert.equal(row.children[2].href, '/api/v1/files/file?task=task');
  assert.equal(row.children[2].target, 'download');
  assert.equal(h.history[0][2], '/');
  for (const request of h.requests) {
    assert.equal(request.options.credentials, 'same-origin');
    assert.equal(request.options.headers['X-DropLAN'], '1');
    assert.equal(request.url.includes('test-entry'), false);
  }
  assert.equal(h.requests.find(r => r.url.endsWith('/downloads')).options.headers['Idempotency-Key'].length, 32);
});

test('denied pairing never exposes file manifest', async () => {
  const h = harness(url => url.endsWith('/pairings') ? { id: 'pair', ticket: 'ticket' } : { state: 'REJECTED' });
  await h.nodes.pair.onclick();
  assert.equal(h.nodes.refresh.disabled, true);
  assert.equal(h.nodes.pair.disabled, false);
  assert.equal(h.requests.some(r => r.url.includes('/manifest')), false);
});

test('missing entry credential sends no request', async () => {
  const h = harness(() => { throw Error('must not request'); }, '');
  await h.nodes.pair.onclick();
  assert.equal(h.requests.length, 0);
  assert.equal(h.nodes.pair.disabled, false);
});

test('expired pairing leaves a visible error and permits retry', async () => {
  const h = harness(() => ({ status: 403 }));
  await h.nodes.pair.onclick();
  assert.match(h.nodes.status.textContent, /403/);
  assert.equal(h.nodes.pair.disabled, false);
});
