"use strict";
const el = id => document.getElementById(id), esc = encodeURIComponent;

async function call(method, path, body, revision) {
    try {
        const headers = {Authorization: 'Bearer ' + el('token').value, 'Content-Type': 'application/json'};
        if (revision !== undefined && revision !== null && revision !== '') headers['If-Match'] = revision;
        const response = await fetch('/api/v1/admin/' + path, {method, headers, ...(body ? {body} : {})});
        const text = await response.text();
        let result;
        try {
            result = JSON.parse(text)
        } catch {
            result = text
        }
        el('result').textContent = response.status + ' ' + JSON.stringify(result, null, 2);
        if (!response.ok) return null;
        return result;
    } catch (error) {
        el('result').textContent = error.message;
        return null;
    }
}

function table(container, rows) {
    container.replaceChildren();
    if (!rows || !rows.length) {
        container.textContent = 'No records';
        return;
    }
    const table = document.createElement('table'), head = document.createElement('tr');
    for (const key of Object.keys(rows[0])) {
        const cell = document.createElement('th');
        cell.textContent = key;
        head.append(cell);
    }
    table.append(head);
    for (const row of rows) {
        const tr = document.createElement('tr');
        for (const value of Object.values(row)) {
            const td = document.createElement('td');
            td.textContent = typeof value === 'object' ? JSON.stringify(value) : String(value);
            tr.append(td);
        }
        if (row.id && row.body) {
            tr.tabIndex = 0;
            tr.title = 'Select to edit';
            tr.onclick = () => {
                el('id').value = row.id;
                el('revision').value = row.revision;
                el('body').value = JSON.stringify(row.body, null, 2);
            };
            tr.onkeydown = e => {
                if (e.key === 'Enter') tr.click();
            };
        }
        table.append(tr);
    }
    container.append(table);
}

el('load').onclick = async () => {
    let rows = await call('GET', el('resource').value);
    if (rows === null) return;
    if (!Array.isArray(rows)) rows = Object.entries(rows).map(([id, value]) => {
        let body = value;
        try {
            body = JSON.parse(value)
        } catch {
        }
        return {id, ...body};
    });
    table(el('records'), rows);
};
el('template').onclick = () => {
    const kind = el('resource').value;
    const templates = {
        apis: {id: 'new-api', tenant: 'demo', enabled: true},
        consumers: {id: 'new-consumer', tenant: 'demo', enabled: true},
        'rate-limits': {id: 'standard-rate', tenant: 'demo', rate: 100, burst: 100},
        quotas: {id: 'standard-quota', tenant: 'demo', dailyQuota: 10000, monthlyQuota: 100000},
        plans: {id: 'standard', tenant: 'demo', rate: 100, burst: 100, dailyQuota: 10000, monthlyQuota: 100000},
        routes: {
            id: 'new-route',
            apiId: 'new-api',
            tenant: 'demo',
            path: '/api/v1/new-api/**',
            host: null,
            methods: ['GET'],
            upstreams: ['http://orders:8091'],
            scopes: ['orders:read'],
            roles: [],
            priority: 0,
            rate: 100,
            burst: 100,
            dailyQuota: 10000,
            monthlyQuota: 100000,
            timeoutMs: 3000,
            concurrency: 32,
            enabled: true,
            retries: 0
        }
    };
    el('id').value = '';
    el('revision').value = '';
    el('body').value = JSON.stringify(templates[kind] || {}, null, 2);
};
el('create').onclick = () => call('POST', el('resource').value, el('body').value);
el('update').onclick = () => call('PATCH', el('resource').value + '/' + esc(el('id').value), el('body').value, el('revision').value);
el('remove').onclick = () => call('DELETE', el('resource').value + '/' + esc(el('id').value), null, el('revision').value);
el('publish').onclick = () => call('POST', 'configurations/publish');
el('rollback').onclick = () => call('POST', 'configurations/' + esc(el('version').value) + '/rollback');
el('key').onclick = () => call('POST', 'consumers/' + esc(el('id').value) + '/keys', el('body').value);
el('keys').onclick = () => call('GET', 'consumers/' + esc(el('id').value) + '/keys');
el('rotate').onclick = () => call('POST', 'keys/' + esc(el('id').value) + '/rotate', el('body').value);
el('revoke').onclick = () => call('DELETE', 'keys/' + esc(el('id').value));
el('usage').onclick = async () => {
    const result = await call('GET', 'consumers/' + esc(el('id').value) + '/usage');
    if (result) table(el('usage-table'), Object.entries(result.tenantApiUsage).map(([api, values]) => ({api, ...values})));
};
el('adjust').onclick = () => call('POST', 'quotas/' + esc(el('quota-tenant').value) + '/' + esc(el('quota-api').value) + '/adjustments', el('body').value, el('revision').value);
el('clear').onclick = () => el('result').textContent = '';
