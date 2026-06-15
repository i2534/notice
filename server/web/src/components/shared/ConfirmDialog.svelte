<script>
  let show = false
  let message = ''
  let variant = 'default'
  let callback = null

  export function confirm(msg, cb, options = {}) {
    message = msg
    callback = cb
    variant = options.variant || 'default'
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
  <div class="overlay" onclick={dismiss}></div>
  <div class="dialog" role="alertdialog" aria-modal="true">
    <p>{message}</p>
    <div class="actions">
      <button class="btn-ghost" onclick={dismiss}>取消</button>
      <button
        class="btn-confirm"
        class:danger={variant === 'danger'}
        onclick={execute}
      >确定</button>
    </div>
  </div>
{/if}

<style>
  .overlay { position:absolute; top:0; left:0; right:0; bottom:0; background:rgba(0,0,0,.6); z-index:var(--z-overlay-confirm); }
  .dialog { position:absolute; top:50%; left:50%; transform:translate(-50%,-50%); background:var(--bg-elevated); border:1px solid var(--border); border-radius:var(--radius-lg); padding:24px; z-index:var(--z-confirm); min-width:300px; text-align:center; }
  .dialog p { font-size:14px; color:var(--text-primary); margin-bottom:16px; }
  .actions { display:flex; gap:12px; justify-content:center; }
  .btn-ghost { background:none; border:1px solid var(--border); color:var(--text-secondary); padding:6px 20px; border-radius:var(--radius-sm); cursor:pointer; font-size:13px; }
  .btn-ghost:hover { color:var(--text-primary); }
  .btn-confirm { background:var(--accent); border:none; color:var(--text-on-accent); padding:6px 20px; border-radius:var(--radius-sm); cursor:pointer; font-size:13px; }
  .btn-confirm.danger { background:var(--danger); color:var(--text-on-accent-light); }
  .btn-confirm:hover { filter:brightness(1.1); }
</style>
