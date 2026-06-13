<script>
  import { onMount } from 'svelte'
  import { currentView, searchQuery, messages, settings } from '../../lib/store.js'
  import { fetchMessages, fetchStatus } from '../../lib/api.js'
  import { applyTheme } from '../../lib/theme.js'
  import { normalizeMessagePayload } from '../../lib/utils.js'

  export let showToast = () => {}

  $: pageTitle = $currentView === 'messages' ? '消息' : $currentView === 'topics' ? '主题' : '客户端'

  function toggleTheme() {
    const next = $settings.theme === 'dark' ? 'light' : 'dark'
    settings.update(s => ({ ...s, theme: next }))
    applyTheme(next)
  }

  let showAbout = false
  let serverVersion = 'dev'
  let serverBuildTime = 'unknown'

  // 获取服务端版本
  onMount(async () => {
    const res = await fetchStatus()
    if (res.ok && res.data) {
      serverVersion = res.data.version || 'dev'
      serverBuildTime = res.data.build_time || 'unknown'
    }
  })

  let refreshing = false

  async function handleRefresh() {
    if (refreshing) return
    const s = $settings
    if (!s.token) return
    refreshing = true
    try {
      const res = await fetchMessages(s.token)
      if (res.ok && Array.isArray(res.data?.data?.messages)) {
        messages.update(list => {
          const seen = new Set(list.map(m => m.id))
          // 内容+时间窗口去重（与挂载时保持一致）
          const contentSeen = new Set(list.map(m => `${m.title}|${m.content}|${new Date(m.timestamp).toISOString().slice(0, 16)}`))
          const added = res.data.data.messages
            .filter(m => !seen.has(m.id))
            .filter(m => !contentSeen.has(`${m.title}|${m.content}|${new Date(m.timestamp).toISOString().slice(0, 16)}`))
            .map(m => {
              const normalized = normalizeMessagePayload({
                id: m.id,
                topic: m.topic || 'notice',
                title: m.title || '通知',
                content: m.content || '',
                timestamp: m.timestamp || new Date().toISOString(),
                client: m.client || '',
              })
              return {
                ...normalized,
                unread: false,
                cat: (normalized.topic || '').includes('alert') ? 'alert' : (normalized.topic || '').includes('voice') ? 'voice' : 'system',
              }
            })
          return [...list, ...added]
        })
        showToast('已刷新', 'success')
      } else {
        showToast('刷新失败', 'error')
      }
    } catch {
      showToast('网络错误', 'error')
    } finally {
      refreshing = false
    }
  }
</script>

<header class="topbar">
  <span class="page-title">{pageTitle}</span>
  {#if $currentView === 'messages'}
    <div class="search-box">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>
      <input type="text" placeholder="搜索消息内容、标题…" bind:value={$searchQuery} />
    </div>
  {/if}
  <div class="spacer"></div>
  <div class="topbar-actions">
    <button class="icon-btn" title="切换主题" onclick={toggleTheme}>
      {#if $settings.theme === 'dark'}
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="5"/><path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"/></svg>
      {:else}
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>
      {/if}
    </button>
    {#if $currentView === 'messages'}
      <button class="icon-btn" title="刷新" onclick={handleRefresh}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/><path d="M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16"/><path d="M21 21v-5h-5"/></svg>
      </button>
    {/if}
    <button class="icon-btn" title="关于" onclick={() => showAbout = true}>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/></svg>
    </button>
  </div>
</header>

<svelte:window onkeydown={(e) => { if (e.key === 'Escape' && showAbout) showAbout = false; }} />

{#if showAbout}
  <div class="about-overlay" onclick={() => showAbout = false}></div>
  <div class="about-dialog">
    <div class="about-header">
      <div class="about-icon">N</div>
      <h3>Notice</h3>
      <p class="about-ver">{serverVersion} · Web 控制台</p>
    </div>
    <div class="about-body">
      <p>轻量级消息推送系统</p>
      <p class="about-desc">内置 Web 管理界面，支持 HTTP Webhook 接收消息、内置 MQTT Broker、多平台客户端推送。</p>
      <div class="about-links">
        <a href="https://github.com/i2534/notice" target="_blank" rel="noopener" class="about-link" onclick={(e) => { e.stopPropagation(); }}>
          <svg viewBox="0 0 24 24" fill="currentColor" width="14" height="14"><path d="M12 0C5.37 0 0 5.37 0 12c0 5.3 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61-.546-1.385-1.335-1.755-1.335-1.755-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 21.795 24 17.295 24 12 24 5.37 18.63 0 12 0"/></svg>
          GitHub
        </a>
      </div>
    </div>
    <div class="about-footer">
      <span>MIT License</span>
      <button class="about-close" onclick={() => showAbout = false}>关闭</button>
    </div>
  </div>
{/if}

<style>
  .topbar { display:flex; align-items:center; padding:14px 20px; border-bottom:1px solid var(--border); gap:16px; flex-shrink:0; background:var(--bg-surface); }
  .page-title { font-size:15px; font-weight:600; white-space:nowrap; }
  .spacer { flex:1; }
  .search-box { flex:1; max-width:360px; position:relative; }
  .search-box svg { position:absolute; left:12px; top:50%; transform:translateY(-50%); width:16px; height:16px; color:var(--text-hint); pointer-events:none; }
  .search-box input { width:100%; padding:8px 12px 8px 36px; background:var(--bg-elevated); border:1px solid var(--border); border-radius:8px; color:var(--text-primary); font-size:13px; font-family:inherit; outline:none; }
  .search-box input:focus { border-color:var(--accent); }
  .search-box input::placeholder { color:var(--text-hint); }
  .topbar-actions { display:flex; align-items:center; gap:6px; }
  .icon-btn { width:34px; height:34px; border-radius:8px; border:1px solid var(--border); background:transparent; color:var(--text-secondary); cursor:pointer; display:flex; align-items:center; justify-content:center; transition:all .15s; }
  .icon-btn:hover { background:var(--bg-hover); color:var(--text-primary); border-color:var(--border-light); }
  .icon-btn svg { width:16px; height:16px; }

  /* About dialog */
  .about-overlay { position:absolute; top:0; left:0; right:0; bottom:0; background:rgba(0,0,0,.5); z-index:100; backdrop-filter:blur(4px); }
  .about-dialog { position:absolute; top:50%; left:50%; transform:translate(-50%,-50%); width:360px; max-width:calc(100% - 48px); background:var(--bg-elevated); border:1px solid var(--border-light); border-radius:var(--radius-lg); z-index:101; box-shadow:0 24px 64px rgba(0,0,0,.5); overflow:hidden; }
  .about-header { text-align:center; padding:28px 24px 16px; }
  .about-icon { width:48px; height:48px; border-radius:14px; background:linear-gradient(135deg,var(--accent),#6366f1); display:flex; align-items:center; justify-content:center; font-size:22px; font-weight:800; color:#fff; margin:0 auto 12px; }
  .about-header h3 { font-size:18px; font-weight:700; margin-bottom:4px; }
  .about-ver { font-size:12px; color:var(--text-hint); }
  .about-body { padding:0 24px 20px; text-align:center; }
  .about-body p { font-size:14px; color:var(--text-secondary); margin-bottom:8px; }
  .about-desc { font-size:13px; color:var(--text-hint); line-height:1.5; }
  .about-links { margin-top:16px; }
  .about-link { display:inline-flex; align-items:center; gap:6px; padding:8px 16px; background:var(--bg-card); border-radius:20px; color:var(--text-primary); text-decoration:none; font-size:13px; cursor:pointer; transition:background .2s; }
  .about-link:hover { background:var(--bg-hover); }
  .about-footer { display:flex; align-items:center; justify-content:space-between; padding:12px 24px; border-top:1px solid var(--border); font-size:11px; color:var(--text-hint); }
  .about-close { background:none; border:none; color:var(--accent); font-size:13px; cursor:pointer; font-family:inherit; padding:4px 12px; }
  .about-close:hover { text-decoration:underline; }
</style>
