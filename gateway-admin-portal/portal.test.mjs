import {JSDOM} from 'jsdom';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';

const base = 'http://localhost:48081';
const login = await fetch('http://localhost:48180/realms/gateway/protocol/openid-connect/token', {
    method: 'POST',
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: new URLSearchParams({
        grant_type: 'client_credentials',
        client_id: 'admin',
        client_secret: 'local-admin-secret'
    })
});
assert.equal(login.status, 200, 'administrator token request failed');
const {access_token} = await login.json();
const page = await fetch(base);
assert.equal(page.status, 200);
assert.ok(page.headers.get('content-security-policy').includes("script-src 'self'"));
const dom = await JSDOM.fromURL(base, {
    resources: 'usable', runScripts: 'dangerously', beforeParse(window) {
        window.fetch = (path, options) => fetch(new URL(path, base), options);
    }
});
const document = dom.window.document;

async function wait(predicate, message) {
    const end = Date.now() + 10000;
    while (Date.now() < end) {
        if (predicate()) return;
        await new Promise(resolve => setTimeout(resolve, 20));
    }
    throw new Error(message);
}

await wait(() => typeof document.getElementById('create').onclick === 'function', 'portal JavaScript did not bind');
const el = id => document.getElementById(id);

async function click(id, status) {
    el('result').textContent = '';
    el(id).click();
    await wait(() => el('result').textContent.startsWith(status + ' '), 'portal operation ' + id + ' did not return ' + status);
}

try {
    el('token').value = access_token;
    el('resource').value = 'apis';
    el('template').click();
    assert.equal(JSON.parse(el('body').value).tenant, 'demo');
    const id = 'uitest' + randomUUID().replaceAll('-', '').slice(0, 10);
    el('body').value = JSON.stringify({id, tenant: 'demo', enabled: true});
    await click('create', 201);
    await click('load', 200);
    await wait(() => el('records').textContent.includes(id), 'created resource not rendered');
    const row = [...el('records').querySelectorAll('tr')].find(row => row.textContent.includes(id));
    row.click();
    assert.equal(el('id').value, id);
    assert.equal(el('revision').value, '1');
    el('body').value = JSON.stringify({id, tenant: 'demo', enabled: true, description: 'Updated by portal test'});
    await click('update', 200);
    el('id').value = 'demo-client';
    await click('usage', 200);
    await wait(() => el('usage-table').querySelector('table'), 'usage table not rendered');
    el('resource').value = 'gateways';
    await click('load', 200);
    assert.ok(el('records').textContent.includes('version'));
    el('resource').value = 'configurations/versions';
    await click('load', 200);
    assert.ok(el('records').querySelectorAll('tr').length > 1);
    el('resource').value = 'apis';
    el('id').value = id;
    el('revision').value = '2';
    await click('remove', 204);
    assert.equal(dom.window.localStorage.length, 0, 'token must never be persisted');
    console.log('PASS portal DOM + live HTTP: static assets/CSP, templates, creation, selection, optimistic update, usage table, fleet/versions and deletion');
} finally {
    el('token').value = '';
    el('result').textContent = '';
    dom.window.close();
}
