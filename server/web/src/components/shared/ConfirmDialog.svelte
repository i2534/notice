<script>
  let show = false
  let message = ''
  let callback = null

  export function confirm(msg, cb) {
    message = msg
    callback = cb
    show = true
  }

  function execute() {
    if (callback) callback()
    show = false
    callback = null
  }

  function dismiss() {
    show = false
    callback = null
  }
</script>

{#if show}
  <!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
  <div class="overlay" onclick={dismiss}></div>
  <div class="dialog">
    <p>{message}</p>
    <div class="actions">
      <button class="btn-ghost" onclick={dismiss}>取消</button>
      <button class="btn-danger" onclick={execute}>确定</button>
    </div>
  </div>
{/if}

<style>
  .overlay { position:absolute; top:0; left:0; right:0; bottom:0; background:rgba(0,0,0,.6); z-index:1000; }
  .dialog { position:absolute; top:50%; left:50%; transform:translate(-50%,-50%); background:var(--bg-elevated); border:1px solid var(--border); border-radius:var(--radius-lg); padding:24px; z-index:1001; min-width:300px; text-align:center; }
  .dialog p { font-size:14px; color:var(--text-primary); margin-bottom:16px; }
  .actions { display:flex; gap:12px; justify-content:center; }
  .btn-ghost { background:none; border:1px solid var(--border); color:var(--text-secondary); padding:6px 20px; border-radius:var(--radius-sm); cursor:pointer; font-size:13px; }
  .btn-ghost:hover { color:var(--text-primary); }
  .btn-danger { background:var(--danger); border:none; color:#fff; padding:6px 20px; border-radius:var(--radius-sm); cursor:pointer; font-size:13px; }
  .btn-danger:hover { filter:brightness(1.1); }
</style>
