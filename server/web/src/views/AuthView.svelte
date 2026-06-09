<script>
  import { authenticated, settings } from '../lib/store.js'
  import { authCheck } from '../lib/api.js'
  import { connect } from '../lib/mqtt.js'

  let token = ''
  let loading = false
  let error = ''

  async function handleAuth() {
    if (!token.trim()) return
    loading = true
    error = ''
    const res = await authCheck(token.trim())
    if (res.ok) {
      settings.update(s => ({ ...s, token: token.trim() }))
      authenticated.set(true)
      const s2 = { ...$settings, token: token.trim() }
      if (s2.broker) connect(s2.broker, s2.topic, s2.token)
    } else if (res.status === 429) {
      error = '请求过于频繁，请稍后再试'
    } else {
      error = 'Token 验证失败'
    }
    loading = false
  }
</script>

<div class="auth-section">
  <div class="auth-card">
    <h2>Notice</h2>
    <p class="auth-desc">轻量级消息推送 · 管理控制台</p>
    <div class="field">
      <input type="password" bind:value={token} placeholder="请输入认证 Token"
        onkeydown={(e) => e.key === 'Enter' && handleAuth()}
        disabled={loading} />
    </div>
    {#if error}
      <div class="error-msg">{error}</div>
    {/if}
    <button class="btn-primary full" onclick={handleAuth} disabled={loading}>
      {loading ? '验证中…' : '验证并进入'}
    </button>
  </div>
</div>

<style>
  .auth-section { flex:1; display:flex; align-items:center; justify-content:center; background:var(--bg-deep); background-image:radial-gradient(ellipse 60% 40% at 20% 20%,rgba(0,212,170,.04) 0%,transparent 70%),radial-gradient(ellipse 50% 30% at 80% 80%,rgba(99,102,241,.04) 0%,transparent 70%); }
  .auth-card { width:100%; max-width:360px; background:var(--bg-elevated); border:1px solid var(--border); border-radius:var(--radius-lg); padding:2rem; }
  .auth-card h2 { font-size:1.2rem; margin-bottom:.5rem; text-align:center; }
  .auth-desc { font-size:.8rem; color:var(--text-hint); text-align:center; margin-bottom:1.25rem; }
  .field { margin-bottom:.75rem; }
  .field input { width:100%; padding:.6rem .75rem; background:var(--bg-surface); border:1px solid var(--border); border-radius:var(--radius-sm); color:var(--text-primary); font-size:.85rem; font-family:inherit; outline:none; }
  .field input:focus { border-color:var(--accent); }
  .error-msg { color:var(--danger); font-size:.8rem; margin-bottom:.75rem; text-align:center; }
  .btn-primary { padding:10px 20px; border-radius:var(--radius-sm); font-size:14px; font-weight:600; font-family:inherit; border:none; cursor:pointer; }
  .btn-primary.full { width:100%; }
  .btn-primary { background:var(--accent); color:#0d0d16; }
  .btn-primary:hover { filter:brightness(1.1); }
  .btn-primary:disabled { opacity:.5; cursor:not-allowed; }
</style>
