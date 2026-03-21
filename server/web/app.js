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
let sendPanelExpanded = false; // 发送面板展开状态，与 localStorage 同步

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

var NOTICE_CONTENT_ENCODING_GZIP_B64 = 'gzip+base64';

/** 若 MQTT JSON 带 gzip+base64 编码的 content，解压为明文（需浏览器支持 DecompressionStream） */
async function decodeNoticeMqttPayloadIfEncoded(msg) {
    if (!msg || msg.content_encoding !== NOTICE_CONTENT_ENCODING_GZIP_B64 || msg.content == null) {
        return msg;
    }
    if (typeof DecompressionStream === 'undefined') {
        console.warn('[notice] DecompressionStream unavailable, cannot decode gzip+base64');
        return msg;
    }
    var b64 = String(msg.content);
    var binStr = atob(b64);
    var bytes = new Uint8Array(binStr.length);
    for (var i = 0; i < binStr.length; i++) {
        bytes[i] = binStr.charCodeAt(i);
    }
    var ds = new DecompressionStream('gzip');
    var stream = new Blob([bytes]).stream().pipeThrough(ds);
    var buf = await new Response(stream).arrayBuffer();
    var dec = new TextDecoder('utf-8').decode(buf);
    var out = Object.assign({}, msg);
    delete out.content_encoding;
    out.content = dec;
    return out;
}

/** Webhook 发送：正文 Unicode 标量值数量 ≥ 此值时才尝试 gzip+base64（与 OpenClaw compressMinRunes 语义一致） */
var WEBHOOK_COMPRESS_MIN_RUNES = 50;

function countRunesWeb(s) {
    return [...s].length;
}

/**
 * 构建 webhook 的 content / content_encoding：与 OpenClaw 一致，仅当 rune 数达标且 base64 短于 UTF-8 字节长度时压缩。
 * 无 CompressionStream 或失败时回退明文。
 */
async function buildWebhookContentFields(plain) {
    if (typeof CompressionStream === 'undefined') {
        return { content: plain };
    }
    var minRunes = WEBHOOK_COMPRESS_MIN_RUNES;
    var utf8 = new TextEncoder().encode(plain);
    var plainLen = utf8.length;
    if (minRunes <= 0 || plainLen === 0 || countRunesWeb(plain) < minRunes) {
        return { content: plain };
    }
    try {
        var cs = new CompressionStream('gzip');
        var stream = new Blob([utf8]).stream().pipeThrough(cs);
        var buf = await new Response(stream).arrayBuffer();
        var bytes = new Uint8Array(buf);
        var bin = '';
        for (var i = 0; i < bytes.length; i++) {
            bin += String.fromCharCode(bytes[i]);
        }
        var b64 = btoa(bin);
        if (b64.length < plainLen) {
            return { content: b64, content_encoding: NOTICE_CONTENT_ENCODING_GZIP_B64 };
        }
    } catch (e) {
        console.warn('[notice] webhook gzip compress failed', e);
    }
    return { content: plain };
}

/** MQTT 载荷转 UTF-8 字符串（浏览器中常为 Uint8Array，勿用默认 toString） */
function mqttPayloadToUtf8String(payload) {
    if (payload == null || payload === undefined) return '';
    if (typeof payload === 'string') return payload;
    if (payload instanceof ArrayBuffer) {
        return new TextDecoder('utf-8', { fatal: false }).decode(payload);
    }
    if (ArrayBuffer.isView(payload)) {
        var buf = payload.buffer.slice(payload.byteOffset, payload.byteOffset + payload.byteLength);
        return new TextDecoder('utf-8', { fatal: false }).decode(buf);
    }
    if (typeof Buffer !== 'undefined' && Buffer.isBuffer && Buffer.isBuffer(payload)) {
        return payload.toString('utf8');
    }
    return String(payload);
}

/** 若为 asr_confirm_request JSON 则返回转写文本，否则 null */
function parseAsrConfirmRequestWeb(content) {
    try {
        var raw = (content != null && content !== undefined) ? String(content).trim() : '';
        if (!raw || raw.charAt(0) !== '{') return null;
        var o = JSON.parse(raw);
        if (o && o.type === 'asr_confirm_request' && typeof o.text === 'string') {
            var t = o.text.trim();
            return t.length > 0 ? t : null;
        }
    } catch (e) { /* ignore */ }
    return null;
}

/** Web：回复栏发出的 asr_confirm / asr_cancel JSON 在列表中的可读展示 */
function formatAsrCommandPayloadForDisplay(msg, raw) {
    if (!raw || raw.charAt(0) !== '{') return null;
    if (msg.title === undefined || msg.title === null || String(msg.title) !== '回复') return null;
    try {
        var p = JSON.parse(raw);
        if (!p || typeof p !== 'object') return null;
        if (p.type === 'asr_confirm' && typeof p.text === 'string') {
            var t = String(p.text).trim();
            return t.length > 0 ? ('语音命令: ' + t) : null;
        }
        if (p.type === 'asr_cancel') {
            return '取消语音命令';
        }
    } catch (e) { /* ignore */ }
    return null;
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
        const fromHistory = list.map(toItem).map(normalizeMessagePayload).filter(function (m) { return m.content !== '__auth_check__'; });
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
    if (!list) return;

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
        attachMediaFallbacks(list);
        attachAsrCardClickHandlers(list);
    }
    document.getElementById('messageCount').textContent = messageCount + ' 条';
    updateSelectAllState();
    updateDeleteSelectedBtn();
}

/** 语音命令卡片：事件委托（innerHTML 每次重绘） */
function attachAsrCardClickHandlers(container) {
    if (!container || container.dataset.asrDelegateBound === '1') return;
    container.dataset.asrDelegateBound = '1';
    container.addEventListener('click', function (e) {
        var btn = e.target.closest('.btn-asr[data-asr-action]');
        if (!btn || btn.disabled) return;
        var idx = parseInt(btn.getAttribute('data-asr-idx'), 10);
        var action = btn.getAttribute('data-asr-action');
        if (action === 'confirm') submitWebAsrConfirm(idx);
        else if (action === 'cancel') submitWebAsrCancel(idx);
    });
}

/** Web：发送 asr_confirm / asr_cancel（webhook），与 lastSentContent 去重对齐 §2.5 */
async function sendWebAsrPayload(idx, contentStr, doneLine, toastMsg) {
    if (!currentToken) {
        showToast('请先登录', 'error');
        return false;
    }
    var m = messages[idx];
    if (!m || m.asrHandled) return false;

    var card = document.querySelector('.asr-confirm-card[data-asr-idx="' + idx + '"]');
    var buttons = card ? card.querySelectorAll('.btn-asr') : [];
    buttons.forEach(function (b) { b.disabled = true; });

    lastSentContent = contentStr;
    lastSentTime = Date.now();

    var topic = topicForPublish(m.topic || '');
    var fields = await buildWebhookContentFields(contentStr);
    var body = Object.assign({ title: '回复', client: 'web', topic: topic }, fields);

    try {
        var res = await fetch('/webhook', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': 'Bearer ' + currentToken
            },
            body: JSON.stringify(body)
        });
        if (res.status === 401) {
            lastSentContent = '';
            logout();
            showToast('认证失败', 'error');
            return false;
        }
        if (res.status === 429) {
            lastSentContent = '';
            showToast('请求过于频繁', 'error');
            return false;
        }
        var data = await res.json().catch(function () { return {}; });
        if (!data.success) {
            lastSentContent = '';
            showToast(data.message || '发送失败', 'error');
            buttons.forEach(function (b) { b.disabled = false; });
            return false;
        }
        m.content = doneLine;
        m.asrHandled = true;
        saveCachedMessages();
        renderMessages();
        showToast(toastMsg, 'success');
        loadServerStatus();
        return true;
    } catch (err) {
        lastSentContent = '';
        showToast(err.message || '网络错误', 'error');
        buttons.forEach(function (b) { b.disabled = false; });
        return false;
    }
}

function submitWebAsrConfirm(idx) {
    var ta = document.querySelector('.asr-confirm-textarea[data-asr-idx="' + idx + '"]');
    var text = ta ? ta.value.trim() : '';
    if (!text) {
        showToast('请保留或修改识别文字', 'error');
        return;
    }
    var contentStr = JSON.stringify({ type: 'asr_confirm', text: text });
    sendWebAsrPayload(idx, contentStr, '【语音命令已确认】', '已确认');
}

function submitWebAsrCancel(idx) {
    var contentStr = JSON.stringify({ type: 'asr_cancel' });
    sendWebAsrPayload(idx, contentStr, '【已取消语音命令】', '已取消');
}

/** 图片/音频加载失败时显示原始地址（可点击） */
function attachMediaFallbacks(container) {
    if (!container) return;
    container.querySelectorAll('.message-content img').forEach(function (img) {
        var src = img.getAttribute('src');
        if (!src) return;
        var fallback = document.createElement('a');
        fallback.href = src;
        fallback.target = '_blank';
        fallback.rel = 'noopener';
        fallback.className = 'message-media-fallback';
        fallback.textContent = src;
        fallback.style.display = 'none';
        img.parentNode.insertBefore(fallback, img.nextSibling);
        img.onerror = function () {
            img.style.display = 'none';
            fallback.style.display = 'inline-block';
        };
    });
    container.querySelectorAll('.message-content audio').forEach(function (audio) {
        var src = audio.getAttribute('src');
        if (!src) return;
        var fallback = document.createElement('a');
        fallback.href = src;
        fallback.target = '_blank';
        fallback.rel = 'noopener';
        fallback.className = 'message-media-fallback';
        fallback.textContent = src;
        fallback.style.display = 'none';
        audio.parentNode.insertBefore(fallback, audio.nextSibling);
        audio.addEventListener('error', function () {
            audio.style.display = 'none';
            fallback.style.display = 'inline-block';
        });
    });
}

// 创建单条消息的 HTML（展示时解析 content 内嵌套 JSON，避免直接显示整段 JSON）
function createMessageHTML(msg, idx) {
    const display = getDisplayMessage(msg);
    const time = msg.timestamp ? new Date(msg.timestamp).toLocaleTimeString() : '';
    const clientLabel = msg.client ? `来自 ${escapeHtml(msg.client)}` : '';
    const rawContent = (msg.content !== undefined && msg.content !== null) ? String(msg.content) : '';
    const asrDraft = parseAsrConfirmRequestWeb(rawContent);
    const asrDone = msg.asrHandled === true;
    var bodyInner;
    if (asrDraft != null && !asrDone) {
        const safeDraft = escapeHtml(asrDraft);
        bodyInner = `
                        <div class="message-content asr-confirm-card-wrap">
                            <div class="asr-confirm-card" data-asr-idx="${idx}">
                                <div class="asr-confirm-title">语音命令</div>
                                <textarea class="asr-confirm-textarea" data-asr-idx="${idx}" rows="3">${safeDraft}</textarea>
                                <div class="asr-confirm-actions">
                                    <button type="button" class="btn btn-asr btn-asr-primary" data-asr-idx="${idx}" data-asr-action="confirm">确认</button>
                                    <button type="button" class="btn btn-asr btn-asr-secondary" data-asr-idx="${idx}" data-asr-action="cancel" title="取消语音命令">取消</button>
                                </div>
                            </div>
                        </div>`;
    } else {
        bodyInner = `<div class="message-content">${renderMarkdown(display.content)}</div>`;
    }
    return `
                <div class="message-item" data-idx="${idx}">
                    <label class="custom-checkbox">
                        <input type="checkbox" onchange="onMessageSelect(${idx})" data-idx="${idx}">
                        <span class="checkmark"></span>
                    </label>
                    <div class="message-body">
                        <div class="message-header">
                            <span class="message-title">${escapeHtml(display.title)}</span>
                            <span class="message-meta">${clientLabel ? `<span class="message-client">${clientLabel}</span> ` : ''}<span class="message-time">${time}</span></span>
                        </div>
                        ${bodyInner}
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

        // 恢复发送面板展开状态（仅当主内容显示时）
        try {
            if (localStorage.getItem('sendPanelExpanded') === 'true') {
                openSendPanel();
            }
        } catch (e) { }

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
    var el = document.getElementById('serverStats');
    if (!el) return;
    try {
        const res = await fetch('/status');
        const data = await res.json();
        var statusStr = (data.status != null && data.status !== undefined) ? String(data.status) : '';
        var clientsStr = (data.clients != null && data.clients !== undefined) ? String(data.clients) : '';
        el.innerHTML = '<span>📊 ' + escapeHtml(statusStr) + '</span><span>👥 ' + escapeHtml(clientsStr) + '</span>';
    } catch (e) {
        el.innerHTML = '<span style="color: var(--error)">⚠️</span>';
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

    client.on('message', async (topic, payload) => {
        const normTopic = topicForPublish(topic);
        var msg;
        if (payload != null && typeof payload === 'object' && !Array.isArray(payload) && !ArrayBuffer.isView(payload) && !(payload instanceof ArrayBuffer) && ('content' in payload || 'title' in payload)) {
            msg = payload;
        } else {
            var str = mqttPayloadToUtf8String(payload);
            try {
                msg = JSON.parse(str);
            } catch (e) {
                msg = { content: str };
            }
        }
        if (msg.content === '__auth_check__') return;
        try {
            msg = await decodeNoticeMqttPayloadIfEncoded(msg);
        } catch (e) {
            console.warn('[notice] decode content_encoding failed', e);
        }
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

/** 展示时用：若 content 为嵌套 JSON 字符串，解析出内部的 title/content 用于显示，避免直接显示整段 JSON */
function getDisplayMessage(msg) {
    if (!msg) return { title: '通知', content: '' };
    var c = msg.content;
    var raw = (c !== undefined && c !== null) ? String(c).trim().replace(/^\uFEFF/, '') : '';
    if (raw.length >= 10 && raw.indexOf('{') !== -1 && (raw.indexOf('"content"') !== -1 || raw.indexOf('"title"') !== -1)) {
        var start = raw.indexOf('{');
        var end = raw.lastIndexOf('}');
        if (end > start) {
            try {
                var parsed = JSON.parse(raw.substring(start, end + 1));
                if (parsed && (parsed.title !== undefined || parsed.content !== undefined)) {
                    return {
                        title: (parsed.title !== undefined && parsed.title !== null) ? String(parsed.title) : (msg.title != null ? String(msg.title) : '通知'),
                        content: (parsed.content !== undefined && parsed.content !== null) ? String(parsed.content) : raw
                    };
                }
            } catch (e) { /* ignore */ }
        }
    }
    var asrLine = formatAsrCommandPayloadForDisplay(msg, raw);
    if (asrLine != null) {
        return {
            title: (msg.title !== undefined && msg.title !== null) ? String(msg.title) : '通知',
            content: asrLine
        };
    }
    return {
        title: (msg.title !== undefined && msg.title !== null) ? String(msg.title) : '通知',
        content: (c !== undefined && c !== null) ? String(c) : ''
    };
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
    const item = document.querySelector('.message-item[data-idx="' + idx + '"]');
    if (!item) return;
    const checkbox = item.querySelector('input[type="checkbox"]');
    if (!checkbox) return;
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
    try {
        if (confirmCallback) confirmCallback();
    } finally {
        hideConfirm();
    }
}

/** 打开发送面板（隐藏悬浮图标） */
function openSendPanel() {
    sendPanelExpanded = true;
    try { localStorage.setItem('sendPanelExpanded', 'true'); } catch (e) { }
    const panel = document.getElementById('sendPanel');
    const fab = document.getElementById('sendFab');
    if (panel) panel.classList.remove('collapsed');
    if (fab) fab.classList.add('hidden');
}

/** 收起发送面板（显示左下角悬浮图标） */
function closeSendPanel() {
    sendPanelExpanded = false;
    try { localStorage.setItem('sendPanelExpanded', 'false'); } catch (e) { }
    const panel = document.getElementById('sendPanel');
    const fab = document.getElementById('sendFab');
    if (panel) panel.classList.add('collapsed');
    if (fab) fab.classList.remove('hidden');
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
        const fields = await buildWebhookContentFields(content);
        const body = Object.assign({ title: title || '通知', client: 'web' }, fields);
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
            responseBox.className = 'response-box';
            responseBox.textContent = '';
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

/** 判断 URL 是否为可内联播放的音频（/api/media 路径或扩展名，含 query 参数 n=xxx.m4a） */
function isAudioUrl(url) {
    if (!url || typeof url !== 'string') return false;
    var decoded = url.replace(/&amp;/gi, '&');
    var pathPart = decoded.split('#')[0].split('?')[0];
    if (/\/api\/media(\/|\?|$)/i.test(pathPart)) return true;
    if (/\.(mp3|ogg|wav|m4a|aac|opus|weba)$/i.test(pathPart)) return true;
    var q = decoded.split('#')[0];
    var qs = q.indexOf('?') >= 0 ? q.substring(q.indexOf('?') + 1) : '';
    if (qs) {
        var parts = qs.split('&');
        for (var i = 0; i < parts.length; i++) {
            var eq = parts[i].indexOf('=');
            if (eq > 0 && parts[i].substring(0, eq).toLowerCase() === 'n') {
                var val = parts[i].substring(eq + 1);
                try { val = decodeURIComponent(val); } catch (e) { }
                if (/\.(mp3|ogg|wav|m4a|aac|opus|weba)$/i.test(val)) return true;
                break;
            }
        }
    }
    return false;
}

/** 将消息内容中的音频链接替换为 <audio controls>，再交给 Markdown 渲染与消毒 */
function replaceAudioLinksWithPlayer(html) {
    return html.replace(/<a\s+href="([^"]+)"[^>]*>[\s\S]*?<\/a>/gi, function (match, href) {
        if (isAudioUrl(href)) return '<audio controls preload="metadata" src="' + escapeHtml(href) + '"></audio>';
        return match;
    });
}

/** 将消息内容按 Markdown 渲染为安全 HTML；音频链接直接显示播放控件 */
function renderMarkdown(text) {
    if (text == null || text === '') return '';
    if (typeof marked === 'undefined' || typeof DOMPurify === 'undefined') return escapeHtml(text);
    try {
        const raw = marked.parse(String(text), { gfm: true, breaks: true });
        const withAudio = replaceAudioLinksWithPlayer(raw);
        return DOMPurify.sanitize(withAudio, {
            ALLOWED_TAGS: ['p', 'br', 'strong', 'em', 's', 'code', 'pre', 'ul', 'ol', 'li', 'a', 'img', 'audio', 'blockquote', 'h1', 'h2', 'h3', 'hr', 'table', 'thead', 'tbody', 'tr', 'th', 'td'],
            ALLOWED_ATTR: ['href', 'title', 'src', 'alt', 'controls', 'preload']
        });
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
