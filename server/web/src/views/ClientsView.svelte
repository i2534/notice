<script>
  import { onMount } from 'svelte'
  import { fetchClients } from '../lib/api.js'
  import { settings } from '../lib/store.js'

  let clients = []
  let loading = true

  onMount(async () => {
    const res = await fetchClients($settings.token)
    if (res.ok && Array.isArray(res.data?.data)) {
      clients = res.data.data
    }
    loading = false
  })
</script>

<div class="clients-view">
  <div class="header">
    <h3>客户端</h3>
    <span class="count">{clients.length} 个</span>
  </div>

  {#if loading}
    <p class="hint">加载中…</p>
  {:else if clients.length === 0}
    <p class="hint">暂无已连接的客户端</p>
  {:else}
    {#each clients as client}
      <div class="client-card">
        <div class="avatar">{client.id.charAt(0).toUpperCase()}</div>
        <div class="info">
          <div class="name">{client.id}</div>
          <div class="remote">{client.remote}</div>
        </div>
        <div class="subs">
          {#each client.subscriptions as sub}
            <span class="sub-pill">{sub}</span>
          {/each}
        </div>
      </div>
    {/each}
  {/if}
</div>

<style>
  .clients-view { padding: 16px 20px; overflow-y: auto; }
  .header { display:flex; align-items:center; gap:12px; margin-bottom:16px; }
  .header h3 { font-size:14px; font-weight:600; }
  .count { font-size:11px; color:var(--text-hint); background:var(--bg-card); padding:2px 8px; border-radius:10px; }
  .hint { font-size:13px; color:var(--text-hint); }
  .client-card { display:flex; align-items:center; gap:12px; padding:12px 14px; background:var(--bg-elevated); border:1px solid var(--border); border-radius:var(--radius-md); margin-bottom:8px; }
  .client-card:hover { border-color:var(--border-light); }
  .avatar { width:36px; height:36px; border-radius:50%; background:var(--accent-dim); color:var(--accent); display:flex; align-items:center; justify-content:center; font-size:14px; font-weight:700; flex-shrink:0; }
  .info { flex:1; min-width:0; }
  .name { font-size:13px; font-weight:600; }
  .remote { font-size:11px; color:var(--text-hint); font-family:'Plus Jakarta Sans',monospace; margin-top:2px; }
  .subs { display:flex; gap:4px; flex-wrap:wrap; justify-content:flex-end; }
  .sub-pill { font-size:10px; color:var(--accent); background:var(--accent-dim); padding:1px 7px; border-radius:10px; }
</style>
