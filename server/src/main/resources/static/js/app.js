// AstraDB 管理台公共 JS

async function api(path, body) {
    const resp = await fetch(path, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: body === undefined ? '{}' : JSON.stringify(body)
    });
    if (!resp.ok) {
        let msg = 'HTTP ' + resp.status;
        try { msg = (await resp.json()).error || msg; } catch (e) { /* 忽略 */ }
        throw msg;
    }
    // 空响应体（如 deleteTable 旧版）容错
    const text = await resp.text();
    return text ? JSON.parse(text) : null;
}

async function apiUpload(path, formData) {
    const resp = await fetch(path, {method: 'POST', body: formData});
    if (!resp.ok) {
        let msg = 'HTTP ' + resp.status;
        try { msg = (await resp.json()).error || msg; } catch (e) { /* 忽略 */ }
        throw msg;
    }
    const text = await resp.text();
    return text ? JSON.parse(text) : null;
}

function fmtTime(ms) {
    return new Date(ms).toLocaleString('zh-CN');
}

function fmtBytes(n) {
    if (n < 1024) return n + 'B';
    const units = ['KB', 'MB', 'GB', 'TB'];
    let v = n;
    let i = -1;
    do { v /= 1024; i++; } while (v >= 1024 && i < units.length - 1);
    return v.toFixed(1) + units[i];
}

async function loadTables() {
    const names = await api('/api/listTables', {});
    const tbody = document.querySelector('#tables tbody');
    tbody.innerHTML = '';
    for (const name of names) {
        const st = await api('/api/getTableStats', {table: name});
        const tr = document.createElement('tr');
        tr.innerHTML = `<td><a href="/table?name=${encodeURIComponent(name)}">${name}</a></td>
            <td>${st.pointCount}</td><td>${st.segmentCount}</td><td>${st.totalRows}</td>
            <td>${fmtBytes(st.totalSizeBytes)}</td>
            <td><button class="danger" onclick="dropTable('${name}')">删除</button></td>`;
        tbody.appendChild(tr);
    }
}

async function dropTable(name) {
    if (!confirm('确认删除表 ' + name + '？此操作不可恢复！')) return;
    try {
        await api('/api/deleteTable', {table: name, confirm: true});
        loadTables();
    } catch (err) {
        alert('删除失败: ' + err);
    }
}
