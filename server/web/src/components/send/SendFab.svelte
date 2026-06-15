<script>
  import { sendPanelOpen } from '../../lib/store.js'
</script>

<button class="fab" onclick={() => sendPanelOpen.set(true)} aria-label="打开发送面板">
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
</button>

<style>
  .fab { position:absolute; bottom:16px; left:16px; z-index:var(--z-fab); width:44px; height:44px; border-radius:50%; border:none; background:var(--accent); color:var(--bg-deep); cursor:pointer; display:flex; align-items:center; justify-content:center; box-shadow:0 4px 16px var(--accent-glow); transition:all .2s; }
  .fab:hover { transform:scale(1.08); }
  .fab svg { width:20px; height:20px; }
</style>
