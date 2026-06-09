<script>
  import { currentView, connectionStatus, settingsPanelOpen } from '../../lib/store.js'

</script>

<aside class="sidebar">
  <div class="brand">
    <div class="logo-icon">
      <svg viewBox="0 0 28 28" fill="none" width="20" height="20">
        <!-- 消息气泡 -->
        <path d="M6 4h16a4 4 0 0 1 4 4v10a4 4 0 0 1-4 4H10l-6 4V8a4 4 0 0 1 4-4z" fill="#fff"/>
        <!-- 铃铛 -->
        <path d="M14 6.5c-2.2 0-4 1.8-4 4v4l-2 2v1h12v-1l-2-2v-4c0-2.2-1.8-4-4-4z" fill="var(--accent,#00d4aa)"/>
        <!-- 铃铛底部 -->
        <rect x="11" y="16.5" width="6" height="1.5" rx=".75" fill="var(--accent,#00d4aa)"/>
        <!-- 通知红点 -->
        <circle cx="21" cy="5" r="2.5" fill="var(--danger,#f75c5c)"/>
      </svg>
    </div>
    <span class="logo-text">Notice</span>
  </div>
  <nav class="nav">
    <button class="nav-item" class:active={$currentView === 'messages'} onclick={() => currentView.set('messages')}>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z"/><path d="m22 6-10 7L2 6"/></svg>
      <span>消息</span>
    </button>
    <button class="nav-item" class:active={$currentView === 'topics'} onclick={() => currentView.set('topics')}>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M20 7h-4a2 2 0 0 1-2-2V1"/><path d="M4 7V4a2 2 0 0 1 2-2h8l6 6v10a2 2 0 0 1-2 2H4"/><path d="M8 13h8"/><path d="M8 17h6"/></svg>
      <span>主题</span>
    </button>
    <button class="nav-item" class:active={$currentView === 'clients'} onclick={() => currentView.set('clients')}>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="8" r="4"/><path d="M20 21a8 8 0 1 0-16 0"/></svg>
      <span>客户端</span>
    </button>
    <div class="nav-spacer"></div>
    <div class="nav-section-label">管理</div>
    <button class="nav-item" onclick={() => settingsPanelOpen.set(true)}>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
      <span>设置</span>
    </button>
  </nav>
  <div class="footer">
    <div class="conn-status" class:connected={$connectionStatus === 'connected'} class:connecting={$connectionStatus === 'connecting'}>
      <span class="dot" class:connected={$connectionStatus === 'connected'} class:connecting={$connectionStatus === 'connecting'}></span>
      <span class="conn-label">{$connectionStatus === 'connected' ? '已连接' : $connectionStatus === 'connecting' ? '连接中…' : '离线'}</span>
    </div>
  </div>
</aside>

<style>
  .sidebar { width:220px; flex-shrink:0; background:var(--bg-elevated); border-right:1px solid var(--border); display:flex; flex-direction:column; }
  .brand { padding:20px 20px 18px; border-bottom:1px solid var(--border); display:flex; align-items:center; gap:10px; }
  .logo-icon { width:32px; height:32px; background:linear-gradient(135deg,var(--accent),#6366f1); border-radius:8px; display:flex; align-items:center; justify-content:center; font-size:16px; font-weight:800; color:#fff; }
  .logo-text { font-weight:700; font-size:15px; background:linear-gradient(135deg,var(--text-primary),var(--text-secondary)); -webkit-background-clip:text; -webkit-text-fill-color:transparent; }
  .nav { flex:1; padding:12px 10px; display:flex; flex-direction:column; gap:2px; }
  .nav-spacer { flex:1; }
  .nav-section-label { padding:16px 14px 6px; font-size:10px; font-weight:700; color:var(--text-hint); text-transform:uppercase; letter-spacing:.8px; }
  .nav-item { display:flex; align-items:center; gap:10px; padding:10px 14px; border-radius:var(--radius-sm); color:var(--text-secondary); font-size:13px; font-weight:500; cursor:pointer; border:none; background:none; width:100%; text-align:left; font-family:inherit; transition:all .15s; }
  .nav-item:hover { color:var(--text-primary); background:var(--bg-hover); }
  .nav-item.active { color:var(--accent); background:var(--accent-dim); }
  .nav-item svg { width:18px; height:18px; flex-shrink:0; opacity:.7; }
  .nav-item.active svg { opacity:1; }
  .footer { padding:12px 16px; border-top:1px solid var(--border); display:flex; align-items:center; }
  .conn-status { display:flex; align-items:center; gap:8px; font-size:12px; font-weight:500; color:var(--danger); }
  .conn-status.connected { color:var(--accent); }
  .conn-status.connecting { color:var(--warning); }
  .conn-label { color:inherit; }
  .dot { width:10px; height:10px; border-radius:50%; background:var(--danger); transition:all .3s; flex-shrink:0; }
  .dot.connected { background:var(--accent); box-shadow:0 0 10px var(--accent); }
  .dot.connecting { background:var(--warning); animation:pulse 1s infinite; }
  @keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.4} }
</style>
