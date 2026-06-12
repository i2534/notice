<script>
  import { detailMessageId, messages } from '../../lib/store.js'
  import { renderMarkdown } from '../../lib/utils.js'

  export let showToast = () => {}
  export let showConfirm = () => {}

  $: msg = $messages.find(m => m.id === $detailMessageId)

  function close() { detailMessageId.set(null) }

  function handleDelete() {
    const target = msg
    showConfirm('确定要删除这条消息吗？', () => {
      if (target) { messages.update(list => list.filter(m => m.id !== target.id)); showToast('已删除','success') }
      close()
    })
  }

  function fmt(t) {
    if (!t) return ''
    const d = new Date(t)
    return d.toLocaleString('zh-CN', { month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit', second:'2-digit', hour12:false })
  }

  async function copyContent() {
    if (msg) { try { await navigator.clipboard.writeText(msg.content); showToast('已复制','success') } catch { showToast('复制失败','error') } }
  }
</script>

{#if msg}
  <!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
  <!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
  <div class="overlay" onclick={close}></div>
  <div class="modal">
    <div class="header">
      <div>
        <div class="d-title">{msg.title}</div>
        <div class="d-meta">
          <span class="d-tag">{msg.topic}</span>
          {#if msg.client}<span>{msg.client}</span>{/if}
          <span>{fmt(msg.timestamp)}</span>
        </div>
      </div>
      <button class="close-btn" onclick={close}>&times;</button>
    </div>
    <div class="body">{@html renderMarkdown(msg.content)}</div>
    <div class="footer">
      <div><button class="btn-text danger" onclick={handleDelete}>删除</button></div>
      <div class="right">
        <button class="btn-text" onclick={close}>关闭</button>
        <button class="btn-primary-sm" onclick={copyContent}>复制</button>
      </div>
    </div>
  </div>
{/if}

<style>
  .overlay { position:absolute; top:0; left:0; right:0; bottom:0; background:rgba(0,0,0,.6); z-index:30; backdrop-filter:blur(6px); }
  .modal { position:absolute; top:50%; left:50%; transform:translate(-50%,-50%); width:520px; max-width:calc(100% - 60px); max-height:calc(100% - 80px); background:var(--bg-elevated); border:1px solid var(--border-light); border-radius:var(--radius-lg); z-index:31; display:flex; flex-direction:column; box-shadow:0 24px 64px rgba(0,0,0,.5); }
  .header { padding:18px 20px 14px; border-bottom:1px solid var(--border); display:flex; justify-content:space-between; align-items:flex-start; gap:12px; }
  .d-title { font-size:16px; font-weight:700; }
  .d-meta { font-size:11px; color:var(--text-hint); margin-top:4px; display:flex; gap:12px; align-items:center; }
  .d-tag { padding:1px 8px; border-radius:10px; background:var(--accent-dim); color:var(--accent); font-size:10px; font-weight:600; }
  .close-btn { width:32px; height:32px; border-radius:8px; border:1px solid var(--border); background:transparent; color:var(--text-hint); cursor:pointer; font-size:22px; display:flex; align-items:center; justify-content:center; flex-shrink:0; }
  .close-btn:hover { background:var(--bg-hover); color:var(--text-primary); }
  .body { flex:1; padding:20px; overflow-y:auto; line-height:1.7; font-size:14px; color:var(--text-secondary); white-space:pre-wrap; word-break:break-word; }
  .body :global(p) { margin:0; }
  .body :global(p+p) { margin-top:.5em; }
  .body :global(code) { background:var(--bg-card); padding:.1em .35em; border-radius:4px; font-size:.9em; }
  .body :global(pre) { background:var(--bg-card); padding:.5rem; border-radius:6px; overflow-x:auto; margin:.5em 0; }
  .body :global(img) { max-width:100%; border-radius:8px; margin:.5em 0; }
  .body :global(a) { color:var(--accent); }
  .body :global(blockquote) { border-left:3px solid var(--border); padding-left:.75rem; margin:.5em 0; color:var(--text-hint); }
  .body :global(table) { border-collapse:collapse; width:100%; margin:.5em 0; }
  .body :global(th),.body :global(td) { border:1px solid var(--border); padding:.3em .5em; text-align:left; }
  .body :global(th) { background:var(--bg-card); }
  .body :global(audio) { display:block; width:100%; margin:.5em 0; height:32px; }
  .footer { display:flex; align-items:center; padding:12px 20px; border-top:1px solid var(--border); }
  .right { margin-left:auto; display:flex; gap:8px; }
  .btn-text { background:none; border:none; color:var(--text-secondary); font-size:13px; cursor:pointer; font-family:inherit; padding:6px 12px; border-radius:var(--radius-sm); }
  .btn-text:hover { background:var(--bg-hover); color:var(--text-primary); }
  .btn-text.danger:hover { color:var(--danger); background:var(--danger-dim); }
  .btn-primary-sm { background:var(--accent); color:#0d0d16; border:none; font-size:13px; font-weight:600; cursor:pointer; padding:6px 16px; border-radius:var(--radius-sm); }
  .btn-primary-sm:hover { filter:brightness(1.1); }
</style>
