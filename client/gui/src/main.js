const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

// DOM 元素 - 主界面
const connectBtn = document.getElementById('connectBtn');
const statusDot = document.getElementById('statusDot');
const statusText = document.getElementById('statusText');
const latestCard = document.getElementById('latestCard');
const latestTitle = document.getElementById('latestTitle');
const latestContent = document.getElementById('latestContent');
const latestTime = document.getElementById('latestTime');
const clearBtn = document.getElementById('clearBtn');
const selectModeBtn = document.getElementById('selectModeBtn');
const deleteSelectedBtn = document.getElementById('deleteSelectedBtn');
const selectedCount = document.getElementById('selectedCount');
const messageList = document.getElementById('messageList');
const toastContainer = document.getElementById('toastContainer');

// DOM 元素 - 设置面板
const settingsBtn = document.getElementById('settingsBtn');
const settingsPanel = document.getElementById('settingsPanel');
const settingsOverlay = document.getElementById('settingsOverlay');
const closeSettingsBtn = document.getElementById('closeSettingsBtn');
const saveBtn = document.getElementById('saveBtn');
const aboutBtn = document.getElementById('aboutBtn');
const serverInput = document.getElementById('server');
const clientIdInput = document.getElementById('clientId');
const tokenInput = document.getElementById('token');
const topicInput = document.getElementById('topic');

// DOM 元素 - 日志面板
const logsBtn = document.getElementById('logsBtn');
const logsPanel = document.getElementById('logsPanel');
const logsOverlay = document.getElementById('logsOverlay');
const closeLogsBtn = document.getElementById('closeLogsBtn');
const clearLogsBtn = document.getElementById('clearLogsBtn');
const logList = document.getElementById('logList');

// DOM 元素 - 关于弹窗
const aboutOverlay = document.getElementById('aboutOverlay');
const aboutDialog = document.getElementById('aboutDialog');
const aboutCloseBtn = document.getElementById('aboutCloseBtn');
const githubLink = document.getElementById('githubLink');

// DOM 元素 - 消息详情弹窗
const detailDialog = document.getElementById('detailDialog');
const detailOverlay = document.getElementById('detailOverlay');
const detailTitle = document.getElementById('detailTitle');
const detailTime = document.getElementById('detailTime');
const detailTopic = document.getElementById('detailTopic');
const detailContent = document.getElementById('detailContent');
const detailCloseBtn = document.getElementById('detailCloseBtn');
const detailCopyBtn = document.getElementById('detailCopyBtn');
const detailDeleteBtn = document.getElementById('detailDeleteBtn');

// DOM 元素 - 确认弹窗
const confirmOverlay = document.getElementById('confirmOverlay');
const confirmDialog = document.getElementById('confirmDialog');
const confirmMessage = document.getElementById('confirmMessage');
const confirmCancelBtn = document.getElementById('confirmCancelBtn');
const confirmOkBtn = document.getElementById('confirmOkBtn');

// 状态
let messages = [];
let logs = [];
let isConnected = false;
let currentDetailMessage = null;
let currentDetailIndex = -1;
let isSelectMode = false;
let selectedIndices = new Set();
let confirmCallback = null;

// 初始化
async function init() {
  // 加载配置
  try {
    const config = await invoke('get_config');
    serverInput.value = config.server || 'tcp://localhost:1883';
    clientIdInput.value = config.client_id || '';
    tokenInput.value = config.token || '';
    topicInput.value = config.topic || 'notice/#';
  } catch (e) {
    console.error('加载配置失败:', e);
    addLog('error', '加载配置失败: ' + e);
  }

  // 加载消息历史
  try {
    const savedMessages = await invoke('get_messages');
    if (savedMessages && savedMessages.length > 0) {
      messages = savedMessages;
      if (messages.length > 0) {
        updateLatestCard(messages[0]);
      }
      renderMessages();
      addLog('info', `已加载 ${messages.length} 条历史消息`);
    }
  } catch (e) {
    console.error('加载消息历史失败:', e);
    addLog('error', '加载消息历史失败: ' + e);
  }

  await listen('connection-state', (event) => {
    updateConnectionState(event.payload);
    addLog('info', '连接状态: ' + event.payload);
  });

  await listen('message', (event) => {
    addMessage(event.payload);
    addLog('info', '收到消息: ' + (event.payload.message?.title || 'Notice'));
  });

  bindEvents();
  addLog('info', '应用已启动');
}

// 绑定事件
function bindEvents() {
  // 设置面板
  settingsBtn.addEventListener('click', openSettings);
  closeSettingsBtn.addEventListener('click', closeSettings);
  settingsOverlay.addEventListener('click', closeSettings);
  saveBtn.addEventListener('click', saveConfig);
  aboutBtn.addEventListener('click', openAbout);

  // 日志面板
  logsBtn.addEventListener('click', openLogs);
  closeLogsBtn.addEventListener('click', closeLogs);
  logsOverlay.addEventListener('click', closeLogs);
  clearLogsBtn.addEventListener('click', clearLogs);

  // 关于弹窗
  aboutCloseBtn.addEventListener('click', closeAbout);
  aboutOverlay.addEventListener('click', closeAbout);
  githubLink.addEventListener('click', (e) => {
    e.preventDefault();
    openGitHub();
  });

  // 连接按钮
  connectBtn.addEventListener('click', toggleConnection);

  // 消息操作
  clearBtn.addEventListener('click', confirmClearMessages);
  selectModeBtn.addEventListener('click', toggleSelectMode);
  deleteSelectedBtn.addEventListener('click', confirmDeleteSelected);

  // 消息详情
  detailCloseBtn.addEventListener('click', closeDetail);
  detailOverlay.addEventListener('click', closeDetail);
  detailCopyBtn.addEventListener('click', copyMessage);
  detailDeleteBtn.addEventListener('click', confirmDeleteMessage);

  // 确认弹窗
  confirmCancelBtn.addEventListener('click', closeConfirm);
  confirmOverlay.addEventListener('click', closeConfirm);
  confirmOkBtn.addEventListener('click', executeConfirm);

  // 最新消息卡片点击
  latestCard.addEventListener('click', () => {
    if (messages.length > 0 && !isSelectMode) {
      showDetail(messages[0], 0);
    }
  });
}

// ========== 确认弹窗 ==========

function showConfirm(message, callback) {
  confirmMessage.textContent = message;
  confirmCallback = callback;
  confirmDialog.classList.remove('hidden');
  confirmOverlay.classList.remove('hidden');
}

function closeConfirm() {
  confirmDialog.classList.add('hidden');
  confirmOverlay.classList.add('hidden');
  confirmCallback = null;
}

function executeConfirm() {
  if (confirmCallback) {
    confirmCallback();
  }
  closeConfirm();
}

// ========== 设置面板 ==========

function openSettings() {
  settingsPanel.classList.remove('hidden');
  settingsOverlay.classList.remove('hidden');
  requestAnimationFrame(() => {
    settingsPanel.classList.add('show');
  });
}

function closeSettings() {
  settingsPanel.classList.remove('show');
  setTimeout(() => {
    settingsPanel.classList.add('hidden');
    settingsOverlay.classList.add('hidden');
  }, 300);
}

function buildConfig() {
  // 如果没有设置 client_id，自动生成一个
  let clientId = clientIdInput.value.trim();
  if (!clientId) {
    clientId = 'notice-gui-' + Date.now().toString(16);
    clientIdInput.value = clientId;
  }
  
  return {
    server: serverInput.value || 'tcp://localhost:1883',
    token: tokenInput.value,
    topic: topicInput.value || 'notice/#',
    client_id: clientId
  };
}

async function saveConfig() {
  const config = buildConfig();

  try {
    await invoke('save_config', { config });
    showToast('配置已保存');
    addLog('info', '配置已保存');
    closeSettings();
  } catch (e) {
    showToast('保存失败: ' + e, 'error');
    addLog('error', '保存配置失败: ' + e);
  }
}

// ========== 日志面板 ==========

function openLogs() {
  logsPanel.classList.remove('hidden');
  logsOverlay.classList.remove('hidden');
  requestAnimationFrame(() => {
    logsPanel.classList.add('show');
  });
  renderLogs();
}

function closeLogs() {
  logsPanel.classList.remove('show');
  setTimeout(() => {
    logsPanel.classList.add('hidden');
    logsOverlay.classList.add('hidden');
  }, 300);
}

function addLog(level, message) {
  logs.unshift({
    level,
    message,
    time: new Date()
  });

  if (logs.length > 200) {
    logs.pop();
  }
}

function renderLogs() {
  if (logs.length === 0) {
    logList.innerHTML = '<div class="empty-state small"><p>暂无日志</p></div>';
    return;
  }

  logList.innerHTML = logs.map(log => `
    <div class="log-item ${log.level}">
      <span class="log-time">${formatLogTime(log.time)}</span>
      <span class="log-message">${escapeHtml(log.message)}</span>
    </div>
  `).join('');
}

function clearLogs() {
  logs = [];
  renderLogs();
  showToast('日志已清除');
}

function formatLogTime(date) {
  return date.toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  });
}

// ========== 关于弹窗 ==========

function openAbout() {
  closeSettings();
  setTimeout(() => {
    aboutDialog.classList.remove('hidden');
    aboutOverlay.classList.remove('hidden');
  }, 300);
}

function closeAbout() {
  aboutDialog.classList.add('hidden');
  aboutOverlay.classList.add('hidden');
}

async function openGitHub() {
  const url = 'https://github.com/i2534/notice';
  try {
    await invoke('open_url', { url });
  } catch (e) {
    try {
      if (window.__TAURI__?.opener?.open) {
        await window.__TAURI__.opener.open(url);
      } else {
        window.open(url, '_blank');
      }
    } catch (e2) {
      window.open(url, '_blank');
    }
  }
}

// ========== 连接管理 ==========

async function toggleConnection() {
  try {
    if (isConnected) {
      await invoke('disconnect');
      updateConnectionState('disconnected');
      addLog('info', '已断开连接');
    } else {
      const config = buildConfig();
      await invoke('save_config', { config });

      updateConnectionState('connecting');
      addLog('info', '正在连接: ' + config.server);
      await invoke('connect');
    }
  } catch (e) {
    showToast('操作失败: ' + e, 'error');
    addLog('error', '连接失败: ' + e);
    updateConnectionState('disconnected');
  }
}

function updateConnectionState(state) {
  statusDot.className = 'status-dot ' + state;

  switch (state) {
    case 'connected':
      statusText.textContent = '已连接';
      connectBtn.textContent = '断开';
      connectBtn.disabled = false;
      isConnected = true;
      break;
    case 'connecting':
      statusText.textContent = '连接中...';
      connectBtn.textContent = '连接中...';
      connectBtn.disabled = true;
      break;
    case 'disconnected':
    default:
      statusText.textContent = '未连接';
      connectBtn.textContent = '连接';
      connectBtn.disabled = false;
      isConnected = false;
      break;
  }
}

// ========== 消息管理 ==========

function addMessage(event) {
  const { topic, message } = event;

  const msg = {
    topic,
    title: message.title || 'Notice',
    content: message.content || '',
    timestamp: message.timestamp
  };

  messages.unshift(msg);

  if (messages.length > 100) {
    messages.pop();
  }

  // 退出选择模式
  if (isSelectMode) {
    exitSelectMode();
  }

  updateLatestCard(msg);
  renderMessages();
  persistMessages();
}

function updateLatestCard(msg) {
  latestCard.classList.remove('hidden');
  latestTitle.textContent = msg.title;
  latestContent.textContent = msg.content;
  latestTime.textContent = formatTime(msg.timestamp);
}

function renderMessages() {
  if (messages.length === 0) {
    messageList.innerHTML = `
      <div class="empty-state">
        <svg viewBox="0 0 24 24" width="48" height="48">
          <path fill="currentColor" d="M20,2H4C2.9,2,2,2.9,2,4v18l4-4h14c1.1,0,2-0.9,2-2V4C22,2.9,21.1,2,20,2z M20,16H5.17L4,17.17V4h16V16z"/>
        </svg>
        <p>暂无消息</p>
      </div>
    `;
    latestCard.classList.add('hidden');
    exitSelectMode();
    return;
  }

  const historyMessages = messages.slice(1);

  if (historyMessages.length === 0) {
    messageList.innerHTML = '<div class="empty-state small"><p>暂无历史消息</p></div>';
    return;
  }

  const selectableClass = isSelectMode ? ' selectable' : '';

  messageList.innerHTML = historyMessages.map((msg, index) => {
    const realIndex = index + 1;
    const isSelected = selectedIndices.has(realIndex);
    return `
      <div class="message-item${selectableClass}${isSelected ? ' selected' : ''}" data-index="${realIndex}">
        <div class="checkbox"></div>
        <div class="message-header">
          <span class="message-title">${escapeHtml(msg.title)}</span>
          <span class="message-time">${formatTimeShort(msg.timestamp)}</span>
        </div>
        <div class="message-content">${escapeHtml(msg.content)}</div>
      </div>
    `;
  }).join('');

  messageList.querySelectorAll('.message-item').forEach(item => {
    item.addEventListener('click', () => {
      const index = parseInt(item.dataset.index);
      if (isSelectMode) {
        toggleSelect(index);
      } else {
        showDetail(messages[index], index);
      }
    });
  });
}

// ========== 选择模式 ==========

function toggleSelectMode() {
  if (isSelectMode) {
    exitSelectMode();
  } else {
    enterSelectMode();
  }
}

function enterSelectMode() {
  isSelectMode = true;
  selectedIndices.clear();
  selectModeBtn.textContent = '取消';
  deleteSelectedBtn.classList.remove('hidden');
  updateSelectedCount();
  renderMessages();
}

function exitSelectMode() {
  isSelectMode = false;
  selectedIndices.clear();
  selectModeBtn.textContent = '选择';
  deleteSelectedBtn.classList.add('hidden');
  selectedCount.classList.add('hidden');
  renderMessages();
}

function toggleSelect(index) {
  if (selectedIndices.has(index)) {
    selectedIndices.delete(index);
  } else {
    selectedIndices.add(index);
  }
  updateSelectedCount();

  // 更新单个项的选中状态
  const item = messageList.querySelector(`[data-index="${index}"]`);
  if (item) {
    item.classList.toggle('selected', selectedIndices.has(index));
  }
}

function updateSelectedCount() {
  if (selectedIndices.size > 0) {
    selectedCount.textContent = `已选 ${selectedIndices.size} 项`;
    selectedCount.classList.remove('hidden');
  } else {
    selectedCount.classList.add('hidden');
  }
}

// ========== 删除操作 ==========

function confirmClearMessages() {
  if (messages.length === 0) return;

  showConfirm(`确定要清除全部 ${messages.length} 条消息吗？`, () => {
    messages = [];
    exitSelectMode();
    renderMessages();
    persistMessages();
    showToast('已清除全部消息');
    addLog('info', '消息历史已清除');
  });
}

function confirmDeleteSelected() {
  if (selectedIndices.size === 0) {
    showToast('请先选择要删除的消息');
    return;
  }

  showConfirm(`确定要删除选中的 ${selectedIndices.size} 条消息吗？`, () => {
    // 从大到小排序，避免删除时索引变化
    const indices = Array.from(selectedIndices).sort((a, b) => b - a);
    indices.forEach(index => {
      messages.splice(index, 1);
    });

    exitSelectMode();

    if (messages.length > 0) {
      updateLatestCard(messages[0]);
    }

    renderMessages();
    persistMessages();
    showToast(`已删除 ${indices.length} 条消息`);
    addLog('info', `批量删除了 ${indices.length} 条消息`);
  });
}

function confirmDeleteMessage() {
  if (currentDetailIndex < 0) return;

  showConfirm('确定要删除这条消息吗？', () => {
    messages.splice(currentDetailIndex, 1);
    closeDetail();

    if (messages.length > 0) {
      updateLatestCard(messages[0]);
    }

    renderMessages();
    persistMessages();
    showToast('消息已删除');
    addLog('info', '删除了一条消息');
  });
}

// ========== 消息详情 ==========

function showDetail(msg, index) {
  currentDetailMessage = msg;
  currentDetailIndex = index;
  detailTitle.textContent = msg.title;
  detailTime.textContent = formatTime(msg.timestamp);
  detailTopic.textContent = msg.topic;
  detailContent.textContent = msg.content;
  detailDialog.classList.remove('hidden');
  detailOverlay.classList.remove('hidden');
}

function closeDetail() {
  detailDialog.classList.add('hidden');
  detailOverlay.classList.add('hidden');
  currentDetailMessage = null;
  currentDetailIndex = -1;
}

async function copyMessage() {
  if (!currentDetailMessage) return;

  const text = `${currentDetailMessage.title}\n\n${currentDetailMessage.content}`;

  try {
    await navigator.clipboard.writeText(text);
    showToast('已复制到剪贴板', 'success');
  } catch (e) {
    showToast('复制失败', 'error');
  }
}

// ========== 消息持久化 ==========

async function persistMessages() {
  try {
    await invoke('save_messages', { messages });
  } catch (e) {
    console.error('保存消息失败:', e);
  }
}

// ========== 工具函数 ==========

function showToast(message, type = '') {
  const toast = document.createElement('div');
  toast.className = 'toast' + (type ? ' ' + type : '');
  toast.textContent = message;
  toastContainer.appendChild(toast);

  setTimeout(() => {
    toast.remove();
  }, 3000);
}

function escapeHtml(text) {
  if (!text) return '';
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

function formatTime(isoString) {
  if (!isoString) return '';
  const date = new Date(isoString);
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  });
}

function formatTimeShort(isoString) {
  if (!isoString) return '';
  const date = new Date(isoString);
  const now = new Date();

  if (date.toDateString() === now.toDateString()) {
    return date.toLocaleTimeString('zh-CN', {
      hour: '2-digit',
      minute: '2-digit'
    });
  }

  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  });
}

// 启动
init();
