<script>
  import { selectedIds } from '../../lib/store.js'
  import { formatTime } from '../../lib/utils.js'

  export let msg
  export let onclick = () => {}

  $: selected = $selectedIds.has(msg.id)

  function renderPreview(text) {
    if (!text) return ''
    const plain = text.replace(/[#*`\[\]()>|_-]/g, ' ').replace(/\s+/g, ' ').trim()
    return plain.length > 180 ? plain.slice(0, 180) + '…' : plain
  }

  function toggleSelect(e) {
    e.stopPropagation()
    selectedIds.update(current => {
      const next = new Set(current)
      if (next.has(msg.id)) next.delete(msg.id)
      else next.add(msg.id)
      return next
    })
  }

  $: pillStyle = msg.cat === 'alert'
    ? 'color:var(--warning);background:rgba(245,166,35,.1)'
    : msg.cat === 'voice'
    ? 'color:var(--info);background:rgba(77,171,247,.1)'
    : ''
</script>

<!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
<div class="msg-card" class:selected class:unread={msg.unread} onclick={onclick}>
  {#if msg.unread}
    <div class="unread-dot"></div>
  {/if}
  <!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
  <div class="msg-checkbox" onclick={toggleSelect}></div>
  <div class="msg-body">
    <div class="msg-head">
      <span class="msg-topic-pill" style={pillStyle}>{msg.topic}</span>
      <span class="msg-title">{msg.title}</span>
      <div class="msg-meta">
        {#if msg.client}<span class="msg-client">{msg.client}</span>{/if}
        <span class="msg-time">{formatTime(msg.timestamp)}</span>
      </div>
    </div>
    <div class="msg-content-preview">{renderPreview(msg.content)}</div>
  </div>
  <div class="hover-actions">
    <button class="ha-btn" title="复制" onclick={(e) => { e.stopPropagation(); navigator.clipboard.writeText(msg.content).catch(() => {}); }}>📋</button>
  </div>
</div>

<style>
  .msg-card { display:flex; gap:12px; padding:14px 16px; background:var(--bg-elevated); border:1px solid var(--border); border-radius:var(--radius-md); transition:all .15s; cursor:pointer; position:relative; }
  .msg-card:hover { border-color:var(--border-light); background:var(--bg-hover); }
  .msg-card.selected { border-color:var(--accent); background:var(--accent-dim); }
  .msg-card.unread { border-left:2px solid var(--accent); }
  .unread-dot { position:absolute; left:-5px; top:50%; transform:translateY(-50%); width:6px; height:6px; border-radius:50%; background:var(--accent); box-shadow:0 0 6px var(--accent); }
  .msg-checkbox { flex-shrink:0; width:16px; height:16px; margin-top:2px; border:2px solid var(--border); border-radius:4px; cursor:pointer; transition:all .15s; }
  .msg-card:hover .msg-checkbox { border-color:var(--text-hint); }
  .msg-card.selected .msg-checkbox { background:var(--accent); border-color:var(--accent); }
  .msg-body { flex:1; min-width:0; }
  .msg-head { display:flex; align-items:center; gap:8px; margin-bottom:4px; }
  .msg-topic-pill { font-size:10px; font-weight:600; color:var(--accent); background:var(--accent-dim); padding:1px 8px; border-radius:10px; flex-shrink:0; }
  .msg-title { font-size:14px; font-weight:600; color:var(--text-primary); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .msg-meta { margin-left:auto; display:flex; align-items:center; gap:8px; flex-shrink:0; }
  .msg-client { font-size:10px; color:var(--text-hint); background:var(--bg-card); padding:1px 7px; border-radius:4px; }
  .msg-time { font-size:11px; font-family:'Plus Jakarta Sans',monospace; color:var(--text-hint); }
  .msg-content-preview { font-size:13px; color:var(--text-secondary); line-height:1.45; display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; word-break:break-word; }
  .hover-actions { display:none; position:absolute; right:8px; top:8px; gap:4px; z-index:2; }
  .msg-card:hover .hover-actions { display:flex; }
  .ha-btn { width:28px; height:28px; border-radius:6px; border:1px solid var(--border); background:var(--bg-elevated); color:var(--text-hint); cursor:pointer; display:flex; align-items:center; justify-content:center; font-size:13px; transition:all .12s; }
  .ha-btn:hover { background:var(--bg-hover); color:var(--text-primary); border-color:var(--border-light); }
</style>
