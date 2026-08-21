// AstraDB 管理台公共 JS

// SS-4：HTML 转义（文本插值与 data-* 属性统一走本函数，防存储型 XSS：
// 表名/列名/STRING 数据值经 innerHTML 渲染时不再可注入标签或属性）
function esc(s) {
    return String(s == null ? '' : s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

// SS-4：删除/查看等操作的 onclick 内联字符串参数易被引号注入，改为 data-act + 事件委托，
// 参数经 data-*（HTML 实体转义）传递，读取时浏览器自动解码为原始值
document.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-act]');
    if (!btn) return;
    const act = btn.dataset.act;
    if (act === 'dropTable') dropTable(btn.dataset.name);
    else if (act === 'showSegment') showSegmentSnapshots(btn.dataset.path);
    else if (act === 'deleteSegment') deleteSegmentFile(btn.dataset.path);
    else if (act === 'deleteSnapshot') deleteSnapshotAt(Number(btn.dataset.ts), btn.dataset.path);
});

async function api(path, body) {
    const resp = await fetch(path, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: body === undefined ? '{}' : JSON.stringify(body)
    });
    if (!resp.ok) {
        let msg = 'HTTP ' + resp.status;
        try { const j = await resp.json(); msg = j.message || j.error || msg; } catch (e) { /* 忽略 */ }
        throw msg;
    }
    // 空响应体容错
    const text = await resp.text();
    return text ? JSON.parse(text) : null;
}

async function apiUpload(path, formData) {
    const resp = await fetch(path, {method: 'POST', body: formData});
    if (!resp.ok) {
        let msg = 'HTTP ' + resp.status;
        try { const j = await resp.json(); msg = j.message || j.error || msg; } catch (e) { /* 忽略 */ }
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
        // SS-4：name 经 esc 转义（文本 + data-name 属性），删除按钮走事件委托
        tr.innerHTML = `<td><a href="/table?name=${encodeURIComponent(name)}">${esc(name)}</a></td>
            <td>${st.pointCount}</td><td>${st.segmentCount}</td><td>${st.totalRows}</td>
            <td>${fmtBytes(st.totalSizeBytes)}</td>
            <td><button class="danger" data-act="dropTable" data-name="${esc(name)}">删除</button></td>`;
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
