<script>
  import { settings, settingsPanelOpen } from '../../lib/store.js'
  import { applyTheme } from '../../lib/theme.js'
  import { connect } from '../../lib/mqtt.js'

  function update(key, val) {
    settings.update(s => ({ ...s, [key]: val }))
    if (key === 'theme') applyTheme(val)
  }

  function handleSave() {
    const s = $settings
    if (s.broker && s.token) connect(s.broker, s.topic, s.token)
    settingsPanelOpen.set(false)
  }
</script>

<!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
<!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
<div class="overlay" onclick={() => settingsPanelOpen.set(false)}></div>
<aside class="panel">
  <div class="header">
    <h3>设置</h3>
    <button class="close-btn" onclick={() => settingsPanelOpen.set(false)}>&times;</button>
  </div>
  <div class="body">
    <div class="group">
      <div class="group-label">连接</div>
      <div class="field">
        <label for="sBroker">Broker 地址</label>
        <input id="sBroker" type="text" value={$settings.broker} oninput={(e) => update('broker', e.target.value)} />
        <span class="hint">支持 ws:// wss:// 协议</span>
      </div>
      <div class="field">
        <label for="sTopic">订阅主题</label>
        <input id="sTopic" type="text" value={$settings.topic} oninput={(e) => update('topic', e.target.value)} />
      </div>
      <div class="field">
        <label for="sToken">认证 Token</label>
        <input id="sToken" type="password" value={$settings.token} oninput={(e) => update('token', e.target.value)} />
      </div>
    </div>
    <div class="group">
      <div class="group-label">显示</div>
      <div class="field">
        <label for="sTheme">界面主题</label>
        <select id="sTheme" value={$settings.theme} onchange={(e) => update('theme', e.target.value)}>
          <option value="dark">暗色</option>
          <option value="light">亮色</option>
        </select>
      </div>
      <div class="field">
        <label for="sCache">最大缓存消息</label>
        <input id="sCache" type="number" value={$settings.maxMessages} oninput={(e) => update('maxMessages', parseInt(e.target.value) || 200)} />
      </div>
    </div>
    <button class="save-btn" onclick={handleSave}>保存并重连</button>
  </div>
</aside>

<style>
  .overlay { position:absolute; top:0; left:0; right:0; bottom:0; background:rgba(0,0,0,.5); z-index:var(--z-overlay-panel); backdrop-filter:blur(4px); }
  .panel { position:absolute; top:0; right:0; width:380px; height:100%; background:var(--bg-elevated); border-left:1px solid var(--border); z-index:var(--z-panel); display:flex; flex-direction:column; box-shadow:var(--shadow-lg); }
  .header { display:flex; align-items:center; justify-content:space-between; padding:18px 20px; border-bottom:1px solid var(--border); }
  .header h3 { font-size:15px; font-weight:700; }
  .close-btn { width:32px; height:32px; border-radius:8px; border:1px solid var(--border); background:transparent; color:var(--text-hint); cursor:pointer; font-size:22px; display:flex; align-items:center; justify-content:center; }
  .close-btn:hover { background:var(--bg-hover); color:var(--text-primary); }
  .body { flex:1; padding:20px; overflow-y:auto; }
  .group { margin-bottom:24px; }
  .group-label { font-size:10px; font-weight:700; text-transform:uppercase; letter-spacing:.8px; color:var(--text-hint); margin-bottom:12px; }
  .field { margin-bottom:14px; }
  .field label { display:block; font-size:12px; color:var(--text-secondary); margin-bottom:4px; font-weight:500; }
  .field input, .field select { width:100%; padding:9px 12px; background:var(--bg-surface); border:1px solid var(--border); border-radius:var(--radius-sm); color:var(--text-primary); font-size:13px; font-family:inherit; outline:none; }
  .field input:focus { border-color:var(--accent); }
  .hint { font-size:11px; color:var(--text-hint); margin-top:4px; display:block; }
  .save-btn { width:100%; padding:10px; background:var(--accent); color:var(--text-on-accent); border:none; border-radius:var(--radius-sm); font-size:14px; font-weight:600; cursor:pointer; }
  .save-btn:hover { filter:brightness(1.1); }
  @media (max-width: 640px) { .panel { width: 100%; } }
</style>
