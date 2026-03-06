let client = null;
let messageCount = 0;
let currentToken = '';
let clientId = '';
let messages = []; // 消息缓存数组
const MAX_MESSAGES = 100; // 最大缓存消息数
let confirmCallback = null; // 确认对话框回调
let lastSentContent = ''; // 用于 MQTT 去重：刚发送的回复内容
let lastSentTime = 0;
let disconnectIntentional = false; // 用户点击「断开」时为 true，避免自动重连循环

/** 与 server 一致：订阅主题转成可发布主题（notice/# -> notice），避免乐观更新与 MQTT 回显 topic 不同导致重复显示 */
function topicForPublish(topic) {
    topic = topic.trim();
    let i = topic.indexOf('#');
    if (i >= 0) {
        topic = topic.substring(0, i).trim().replace(/\/+$/, '') || 'notice';
    }
    if (topic.indexOf('+') >= 0) {
        topic = topic.split('/').map(function (p) { return p === '+' ? 'reply' : p; }).join('/');
    }
    return topic;
}

function generateClientId() {
    return 'web-' + Math.random().toString(16).substr(2, 8);
}

window.onload = function () {
    // 使用 sessionStorage 保证每个标签页独立 clientId，避免多标签同 id 导致互相踢线、反复重连
    try {
        clientId = sessionStorage.getItem('mqttClientId');
    } catch (e) { clientId = null; }
    if (!clientId) {
        clientId = generateClientId();
        try {
            sessionStorage.setItem('mqttClientId', clientId);
        } catch (e) { }
    }

    const savedBrokerUrl = localStorage.getItem('brokerUrl');
    document.getElementById('brokerUrl').value = savedBrokerUrl || 'wss://mqtt.example.com';

    const savedTopic = localStorage.getItem('mqttTopic');
    if (savedTopic) {
        document.getElementById('topic').value = savedTopic;
    }

    // 加载缓存的消息
    loadCachedMessages();

    const savedToken = localStorage.getItem('authToken');
    if (savedToken) {
        document.getElementById('authTokenInput').value = savedToken;
        authenticate();
    }
};

// 加载缓存的消息（规范化 topic，并合并因 notice/# 与 notice 导致的重复回复）
function loadCachedMessages() {
    try {
        const cached = localStorage.getItem('cachedMessages');
        if (cached) {
            messages = JSON.parse(cached).filter(m => (m.content !== '__auth_check__'));
            messages = messages.map(normalizeMessagePayload);
            messages.forEach(m => { m.topic = topicForPublish(m.topic || ''); });
            const deduped = [];
            for (let i = 0; i < messages.length; i++) {
                const m = messages[i];
                const prev = deduped[deduped.length - 1];
                if (prev && prev.title === '回复' && prev.content === m.content && prev.topic === m.topic) {
                    const t = prev.timestamp ? new Date(prev.timestamp).getTime() : 0;
                    const t2 = m.timestamp ? new Date(m.timestamp).getTime() : 0;
                    if (Math.abs(t - t2) < 15000) continue;
                }
                deduped.push(m);
            }
            messages = deduped;
            saveCachedMessages();
            renderMessages();
        }
    } catch (e) {
        console.error('加载缓存消息失败:', e);
        messages = [];
    }
}

// 保存消息到缓存
function saveCachedMessages() {
    try {
        localStorage.setItem('cachedMessages', JSON.stringify(messages));
    } catch (e) {
        console.error('保存缓存消息失败:', e);
    }
}

/** 拉取服务端消息历史（离线期间未收到的消息），与本地缓存合并后渲染 */
async function fetchMessageHistory() {
    if (!currentToken) return;
    try {
        const res = await fetch('/messages?page_size=50', {
            headers: { 'Authorization': 'Bearer ' + currentToken }
        });
        if (!res.ok) return;
        const json = await res.json();
        if (!json.success || !json.data || !Array.isArray(json.data.messages)) return;
        const list = json.data.messages;
        const toItem = function (m) {
            const t = (m.timestamp && typeof m.timestamp === 'string') ? m.timestamp : (m.timestamp ? new Date(m.timestamp * 1000).toISOString() : new Date().toISOString());
            const client = (m.extra && m.extra.client) ? m.extra.client : '';
            return { topic: topicForPublish(m.topic || ''), title: m.title || '通知', content: m.content || '', timestamp: t, client: client };
        };
        const fromHistory = list.map(toItem).filter(function (m) { return m.content !== '__auth_check__'; });
        const seen = new Set();
        function key(m) {
            const t = m.timestamp ? new Date(m.timestamp).getTime() : 0;
            return (m.topic || '') + '\n' + (m.content || '') + '\n' + (Math.floor(t / 10000));
        }
        messages.forEach(function (m) { seen.add(key(m)); });
        fromHistory.forEach(function (m) {
            if (!seen.has(key(m))) { seen.add(key(m)); messages.push(m); }
        });
        messages.sort(function (a, b) {
            const ta = a.timestamp ? new Date(a.timestamp).getTime() : 0;
            const tb = b.timestamp ? new Date(b.timestamp).getTime() : 0;
            return tb - ta;
        });
        if (messages.length > MAX_MESSAGES) messages = messages.slice(0, MAX_MESSAGES);
        saveCachedMessages();
        renderMessages();
    } catch (e) {
        console.error('拉取消息历史失败:', e);
    }
}

// 渲染所有消息
function renderMessages() {
    const list = document.getElementById('messagesList');
    const emptyState = document.getElementById('emptyState');

    if (messages.length === 0) {
        list.innerHTML = `
                    <div class="empty-state" id="emptyState">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
                        </svg>
                        <p>等待消息...</p>
                    </div>
                `;
        messageCount = 0;
    } else {
        list.innerHTML = messages.map((msg, idx) => createMessageHTML(msg, idx)).join('');
        messageCount = messages.length;
    }
    document.getElementById('messageCount').textContent = messageCount + ' 条';
    updateSelectAllState();
    updateDeleteSelectedBtn();
}

// 创建单条消息的 HTML
function createMessageHTML(msg, idx) {
    const time = msg.timestamp ? new Date(msg.timestamp).toLocaleTimeString() : '';
    const clientLabel = msg.client ? `来自 ${escapeHtml(msg.client)}` : '';
    return `
                <div class="message-item" data-idx="${idx}">
                    <label class="custom-checkbox">
                        <input type="checkbox" onchange="onMessageSelect(${idx})" data-idx="${idx}">
                        <span class="checkmark"></span>
                    </label>
                    <div class="message-body">
                        <div class="message-header">
                            <span class="message-title">${escapeHtml(msg.title)}</span>
                            <span class="message-meta">${clientLabel ? `<span class="message-client">${clientLabel}</span> ` : ''}<span class="message-time">${time}</span></span>
                        </div>
                        <div class="message-content">${renderMarkdown(msg.content)}</div>
                        <div class="message-topic">${escapeHtml(msg.topic)}</div>
                    </div>
                </div>
            `;
}

async function authenticate() {
    const token = document.getElementById('authTokenInput').value.trim();

    if (!token) {
        showAuthError('请输入 Token');
        return;
    }

    try {
        const res = await fetch('/webhook', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': 'Bearer ' + token
            },
            body: JSON.stringify({ content: '__auth_check__' })
        });

        if (res.status === 429) {
            showAuthError('请求过于频繁，请稍后再试');
            return;
        }

        if (res.status === 401) {
            const data = await res.json().catch(() => ({}));
            showAuthError(data.message || 'Token 验证失败');
            return;
        }

        currentToken = token;
        localStorage.setItem('authToken', token);

        document.getElementById('authSection').style.display = 'none';
        document.getElementById('mainContent').classList.add('active');
        document.getElementById('topbarRight').style.display = 'flex';
        document.getElementById('tokenDisplay').textContent = token.substring(0, 6) + '**';

        loadServerStatus();
        showToast('认证成功', 'success');
        // 刷新后自动重连：若 localStorage 中曾保存过 broker 则恢复输入框并连接
        const savedBroker = localStorage.getItem('brokerUrl');
        if (savedBroker) {
            document.getElementById('brokerUrl').value = savedBroker;
            const savedT = localStorage.getItem('mqttTopic');
            if (savedT) document.getElementById('topic').value = savedT;
            setTimeout(function () { connect(); }, 0);
        }
    } catch (e) {
        showAuthError('连接失败: ' + e.message);
    }
}

function showAuthError(msg) {
    const box = document.getElementById('authResponse');
    box.style.display = 'block';
    box.className = 'response-box error';
    box.textContent = '❌ ' + msg;
}

function logout() {
    currentToken = '';
    localStorage.removeItem('authToken');
    if (client) {
        client.end();
        client = null;
    }
    document.getElementById('mainContent').classList.remove('active');
    document.getElementById('authSection').style.display = 'flex';
    document.getElementById('topbarRight').style.display = 'none';
    document.getElementById('authResponse').style.display = 'none';
    updateStatus('disconnected', '离线');
}

async function loadServerStatus() {
    try {
        const res = await fetch('/status');
        const data = await res.json();
        document.getElementById('serverStats').innerHTML = `
                    <span>📊 ${data.status}</span>
                    <span>👥 ${data.clients}</span>
                `;
    } catch (e) {
        document.getElementById('serverStats').innerHTML = '<span style="color: var(--error)">⚠️</span>';
    }
}

function toggleConnection() {
    if (client && client.connected) {
        disconnect();
    } else {
        connect();
    }
}

function connect() {
    disconnectIntentional = false;

    if (client) {
        try {
            client.end();
        } catch (e) { /* ignore */ }
        client = null;
    }

    const brokerUrl = document.getElementById('brokerUrl').value;
    const topic = document.getElementById('topic').value;

    updateStatus('connecting', '连接中');

    // 每次连接使用新的 clientId，避免多标签/重复连接时服务端「同 id 踢线」导致反复重连
    var connectClientId = generateClientId();

    client = mqtt.connect(brokerUrl, {
        clientId: connectClientId,
        username: currentToken,
        reconnectPeriod: 0, // 关闭库内自动重连，由我们在 close 时按需延迟重连，避免循环
        clean: true,
    });

    client.on('connect', () => {
        updateStatus('connected', '在线');
        localStorage.setItem('brokerUrl', brokerUrl);
        localStorage.setItem('mqttTopic', topic);
        localStorage.setItem('authToken', currentToken);

        client.subscribe(topic, (err) => {
            if (err) showToast('订阅失败: ' + err.message, 'error');
        });
        loadServerStatus();
        fetchMessageHistory();
    });

    client.on('message', (topic, payload) => {
        const normTopic = topicForPublish(topic);
        var msg;
        if (payload != null && typeof payload === 'object' && !Array.isArray(payload) && ('content' in payload || 'title' in payload)) {
            msg = payload;
        } else {
            var str = typeof payload === 'string' ? payload : (payload && payload.toString && payload.toString());
            try {
                msg = JSON.parse(str);
            } catch (e) {
                msg = { content: str };
            }
        }
        if (msg.content === '__auth_check__') return;
        addMessage(normTopic, msg);
    });

    client.on('error', (err) => {
        updateStatus('error', '错误');
        showToast('MQTT: ' + err.message, 'error');

        const errMsg = (err.message || '').toLowerCase();
        if (errMsg.includes('not authorized') || errMsg.includes('bad user') || errMsg.includes('auth')) {
            localStorage.removeItem('authToken');
            showToast('认证失败', 'error');
            logout();
        }
    });

    client.on('close', () => {
        updateStatus('disconnected', '离线');
        if (!disconnectIntentional) {
            client = null;
            setTimeout(function () { if (!disconnectIntentional) connect(); }, 3000);
        }
    });

    client.on('reconnect', () => {
        updateStatus('connecting', '重连');
    });
}

function disconnect() {
    disconnectIntentional = true;
    if (client) {
        client.end();
        client = null;
    }
    updateStatus('disconnected', '离线');
}

function updateStatus(status, text) {
    const dot = document.getElementById('statusDot');
    const statusText = document.getElementById('statusText');
    const btn = document.getElementById('connectBtn');

    statusText.textContent = text;
    dot.className = 'status-dot';

    if (status === 'connected') {
        dot.classList.add('connected');
        btn.textContent = '断开';
        btn.classList.add('danger');
    } else {
        btn.textContent = '连接';
        btn.classList.remove('danger');
    }
}

/** 若 content 是整段 JSON 字符串（未正确解析的回显），解析出 title/content/client/timestamp，保留原 topic */
function normalizeMessagePayload(msg) {
    var c = msg.content;
    if (c !== undefined && c !== null && typeof c === 'object' && (c.title !== undefined || c.content !== undefined)) {
        var out = { title: c.title, content: c.content, client: c.client, timestamp: c.timestamp };
        if (msg.topic !== undefined) out.topic = msg.topic;
        return out;
    }
    var raw = (c !== undefined && c !== null) ? String(c).trim().replace(/^\uFEFF/, '') : '';
    if (raw.length < 10) return msg;
    var start = raw.indexOf('{');
    if (start === -1) return msg;
    var end = raw.lastIndexOf('}');
    if (end === -1 || end <= start) return msg;
    raw = raw.substring(start, end + 1);
    if (raw.indexOf('"content"') === -1 && raw.indexOf('"title"') === -1) return msg;
    try {
        var parsed = JSON.parse(raw);
        if (parsed && (parsed.title !== undefined || parsed.content !== undefined)) {
            if (msg.topic !== undefined) parsed.topic = msg.topic;
            return parsed;
        }
    } catch (e) { /* ignore */ }
    return msg;
}

function addMessage(topic, msg) {
    msg = normalizeMessagePayload(msg);
    const content = (msg.content !== undefined && msg.content !== null) ? String(msg.content).trim() : JSON.stringify(msg);
    const title = (msg.title !== undefined && msg.title !== null) ? String(msg.title) : '通知';
    // 去重1：刚通过回复栏发送的内容，MQTT 会再推一次
    if (lastSentContent && content === lastSentContent.trim() && (Date.now() - lastSentTime) < 5000) {
        lastSentContent = '';
        return;
    }
    // 去重2：列表第一条若是刚加的「回复」且内容相同（主题规范化后一致），视为 MQTT 回显，跳过
    const normTopic = topicForPublish(topic);
    if (messages.length > 0) {
        const first = messages[0];
        const firstTime = first.timestamp ? new Date(first.timestamp).getTime() : 0;
        const firstNormTopic = topicForPublish(first.topic || '');
        if (first.title === '回复' && first.content === content && firstNormTopic === normTopic && (Date.now() - firstTime) < 5000) {
            return;
        }
    }
    var timestamp = msg.timestamp != null ? msg.timestamp : new Date().toISOString();
    if (typeof timestamp === 'number') {
        if (timestamp < 1e12) timestamp = timestamp * 1000;
        timestamp = new Date(timestamp).toISOString();
    }
    const client = (msg.client !== undefined && msg.client !== null) ? String(msg.client).trim() : '';

    // 添加到消息数组开头（主题统一用可发布形式，避免 notice/# 与 notice 各显示一条）
    messages.unshift({
        topic: normTopic,
        title: title,
        content: content,
        timestamp: timestamp,
        client: client
    });

    // 保持最多 MAX_MESSAGES 条消息
    if (messages.length > MAX_MESSAGES) {
        messages = messages.slice(0, MAX_MESSAGES);
    }

    // 保存到缓存并重新渲染
    saveCachedMessages();
    renderMessages();

    if (Notification.permission === 'granted') {
        new Notification(title, { body: content });
    }
}

// 确认清空消息
function confirmClearMessages() {
    if (messages.length === 0) {
        showToast('没有消息可清空', 'error');
        return;
    }
    showConfirm('清空消息', `确定要清空全部 ${messages.length} 条消息吗？`, () => {
        messages = [];
        saveCachedMessages();
        renderMessages();
        document.getElementById('selectAllCheckbox').checked = false;
        showToast('已清空所有消息', 'success');
    });
}

// 消息选中状态变化
function onMessageSelect(idx) {
    const item = document.querySelector(`.message-item[data-idx="${idx}"]`);
    const checkbox = item.querySelector('input[type="checkbox"]');
    if (checkbox.checked) {
        item.classList.add('selected');
    } else {
        item.classList.remove('selected');
    }
    updateSelectAllState();
    updateDeleteSelectedBtn();
}

// 全选/取消全选
function toggleSelectAll() {
    const selectAll = document.getElementById('selectAllCheckbox').checked;
    const checkboxes = document.querySelectorAll('.message-item input[type="checkbox"]');
    const items = document.querySelectorAll('.message-item');
    checkboxes.forEach(cb => cb.checked = selectAll);
    items.forEach(item => {
        if (selectAll) {
            item.classList.add('selected');
        } else {
            item.classList.remove('selected');
        }
    });
    updateDeleteSelectedBtn();
}

// 更新全选复选框状态
function updateSelectAllState() {
    const checkboxes = document.querySelectorAll('.message-item input[type="checkbox"]');
    const checkedCount = document.querySelectorAll('.message-item input[type="checkbox"]:checked').length;
    const selectAllCheckbox = document.getElementById('selectAllCheckbox');
    if (checkboxes.length === 0) {
        selectAllCheckbox.checked = false;
        selectAllCheckbox.indeterminate = false;
    } else if (checkedCount === 0) {
        selectAllCheckbox.checked = false;
        selectAllCheckbox.indeterminate = false;
    } else if (checkedCount === checkboxes.length) {
        selectAllCheckbox.checked = true;
        selectAllCheckbox.indeterminate = false;
    } else {
        selectAllCheckbox.checked = false;
        selectAllCheckbox.indeterminate = true;
    }
}

// 更新删除选中按钮显示
function updateDeleteSelectedBtn() {
    // 按钮始终显示 "删除选中"，不显示数字避免视觉晃动
}

// 确认删除选中消息
function confirmDeleteSelected() {
    const checkedCount = document.querySelectorAll('.message-item input[type="checkbox"]:checked').length;
    if (checkedCount === 0) {
        showToast('请先选择要删除的消息', 'error');
        return;
    }
    showConfirm('删除消息', `确定要删除选中的 ${checkedCount} 条消息吗？`, () => {
        deleteSelectedMessages();
    });
}

// 删除选中的消息
function deleteSelectedMessages() {
    const checkedBoxes = document.querySelectorAll('.message-item input[type="checkbox"]:checked');
    const indicesToDelete = Array.from(checkedBoxes).map(cb => parseInt(cb.dataset.idx));
    // 从大到小排序，避免删除时索引变化
    indicesToDelete.sort((a, b) => b - a);
    indicesToDelete.forEach(idx => {
        messages.splice(idx, 1);
    });
    saveCachedMessages();
    renderMessages();
    document.getElementById('selectAllCheckbox').checked = false;
    showToast(`已删除 ${indicesToDelete.length} 条消息`, 'success');
}

// 显示确认对话框
function showConfirm(title, message, callback) {
    document.getElementById('confirmTitle').textContent = title;
    document.getElementById('confirmMessage').textContent = message;
    confirmCallback = callback;
    document.getElementById('confirmModal').classList.add('active');
}

// 隐藏确认对话框
function hideConfirm(event) {
    if (!event || event.target === event.currentTarget) {
        document.getElementById('confirmModal').classList.remove('active');
        confirmCallback = null;
    }
}

// 执行确认操作
function executeConfirm() {
    if (confirmCallback) {
        confirmCallback();
    }
    hideConfirm();
}

/** 发送消息：主题为空时使用连接栏当前订阅主题 */
async function sendMessage() {
    const title = document.getElementById('sendTitle').value.trim();
    const content = document.getElementById('sendContent').value.trim();
    let topic = document.getElementById('sendTopic').value.trim();
    if (!topic) {
        topic = document.getElementById('topic').value.trim();
        if (topic) topic = topicForPublish(topic);
    }

    if (!content) {
        showToast('请输入消息内容', 'error');
        return;
    }

    const btn = document.getElementById('sendBtn');
    const responseBox = document.getElementById('sendResponse');

    btn.disabled = true;
    btn.textContent = '发送中...';

    lastSentContent = content;
    lastSentTime = Date.now();

    try {
        const body = { title: title || '通知', content, client: 'web' };
        if (topic) body.topic = topic;

        const res = await fetch('/webhook', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': 'Bearer ' + currentToken
            },
            body: JSON.stringify(body)
        });

        if (res.status === 429) {
            lastSentContent = '';
            responseBox.style.display = 'block';
            responseBox.className = 'response-box error';
            responseBox.textContent = '❌ 请求过于频繁';
            return;
        }

        if (res.status === 401) {
            lastSentContent = '';
            responseBox.style.display = 'block';
            responseBox.className = 'response-box error';
            responseBox.textContent = '❌ 认证失败';
            logout();
            return;
        }

        const data = await res.json();

        if (data.success) {
            responseBox.style.display = 'none';
            showToast('消息已发送', 'success');

            document.getElementById('sendTitle').value = '';
            document.getElementById('sendContent').value = '';

            const publishTopic = topic ? topicForPublish(topic) : topicForPublish(document.getElementById('topic').value.trim() || '');
            messages.unshift({
                topic: publishTopic,
                title: title || '通知',
                content: content,
                timestamp: new Date().toISOString(),
                client: 'web'
            });
            if (messages.length > MAX_MESSAGES) messages = messages.slice(0, MAX_MESSAGES);
            saveCachedMessages();
            renderMessages();

            loadServerStatus();
        } else {
            responseBox.className = 'response-box error';
            responseBox.textContent = `❌ ${data.message}`;
        }
    } catch (e) {
        responseBox.style.display = 'block';
        responseBox.className = 'response-box error';
        responseBox.textContent = `❌ ${e.message}`;
    } finally {
        btn.disabled = false;
        btn.textContent = '发送消息';
    }
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

/** 将消息内容按 Markdown 渲染为安全 HTML */
function renderMarkdown(text) {
    if (text == null || text === '') return '';
    if (typeof marked === 'undefined' || typeof DOMPurify === 'undefined') return escapeHtml(text);
    try {
        const raw = marked.parse(String(text), { gfm: true, breaks: true });
        return DOMPurify.sanitize(raw, { ALLOWED_TAGS: ['p', 'br', 'strong', 'em', 's', 'code', 'pre', 'ul', 'ol', 'li', 'a', 'img', 'blockquote', 'h1', 'h2', 'h3', 'hr'], ALLOWED_ATTR: ['href', 'title', 'src', 'alt'] });
    } catch (e) {
        return escapeHtml(text);
    }
}

function showToast(message, type = 'info') {
    const toast = document.createElement('div');
    toast.className = 'toast ' + type;
    toast.textContent = message;
    document.body.appendChild(toast);
    setTimeout(() => toast.remove(), 2500);
}

document.addEventListener('keydown', (e) => {
    if (e.ctrlKey && e.key === 'Enter') {
        sendMessage();
    }
});

if ('Notification' in window && Notification.permission === 'default') {
    Notification.requestPermission();
}

function clearSendForm() {
    document.getElementById('sendTitle').value = '';
    document.getElementById('sendContent').value = '';
    document.getElementById('sendTopic').value = '';
    document.getElementById('sendResponse').style.display = 'none';
    document.getElementById('uploadInput').value = '';
    document.getElementById('uploadFilesList').textContent = '';
}

document.getElementById('uploadInput') && document.getElementById('uploadInput').addEventListener('change', function () {
    const list = document.getElementById('uploadFilesList');
    const files = this.files;
    if (!files || files.length === 0) {
        list.textContent = '';
        return;
    }
    list.textContent = '已选 ' + files.length + ' 个文件：' + Array.from(files).map(function (f) { return f.name; }).join(', ');
});

async function uploadImages() {
    const input = document.getElementById('uploadInput');
    const files = input.files;
    if (!files || files.length === 0) {
        showToast('请先选择图片', 'error');
        return;
    }
    const btn = document.getElementById('uploadBtn');
    const listEl = document.getElementById('uploadFilesList');
    const contentEl = document.getElementById('sendContent');
    btn.disabled = true;
    listEl.textContent = '上传中...';
    try {
        const form = new FormData();
        for (let i = 0; i < files.length; i++) {
            form.append('file', files[i]);
        }
        const res = await fetch('/api/upload', {
            method: 'POST',
            headers: { 'Authorization': 'Bearer ' + currentToken },
            body: form
        });
        const data = await res.json().catch(function () { return {}; });
        if (!res.ok || !data.success || !Array.isArray(data.image_urls) || data.image_urls.length === 0) {
            showToast(data.message || '上传失败', 'error');
            listEl.textContent = '已选 ' + files.length + ' 个文件';
            return;
        }
        const lines = data.image_urls.map(function (url) {
            const u = url.startsWith('http') ? url : (window.location.origin + (url.startsWith('/') ? url : '/' + url));
            return '![](' + u + ')';
        });
        const insert = (contentEl.value ? '\n\n' : '') + lines.join('\n');
        contentEl.value = contentEl.value + insert;
        input.value = '';
        listEl.textContent = '';
        showToast('已插入 ' + data.image_urls.length + ' 张图片', 'success');
    } catch (e) {
        showToast('上传失败: ' + e.message, 'error');
        listEl.textContent = '已选 ' + files.length + ' 个文件';
    } finally {
        btn.disabled = false;
    }
}

function fillTestMessage() {
    const now = new Date();
    const time = now.toLocaleTimeString();
    const titles = ['系统通知', '测试消息', '服务提醒', '状态更新', '调试信息'];
    const contents = [
        '这是一条测试消息，用于验证推送功能是否正常工作。\n时间：' + time,
        '服务运行正常，所有组件状态良好。\n时间：' + time,
        '您有一条新的通知消息，请及时查看。\n时间：' + time,
        '测试多行内容：\n第一行\n第二行\n第三行\n时间：' + time,
        '🎉 恭喜！消息推送测试成功！\n时间：' + time
    ];
    const idx = Math.floor(Math.random() * titles.length);
    document.getElementById('sendTitle').value = titles[idx];
    document.getElementById('sendContent').value = contents[idx];
    document.getElementById('sendResponse').style.display = 'none';
}

function showHelp() {
    document.getElementById('helpModal').classList.add('active');
}

function hideHelp(event) {
    if (!event || event.target === event.currentTarget) {
        document.getElementById('helpModal').classList.remove('active');
    }
}

document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
        hideHelp();
        hideConfirm();
    }
});
