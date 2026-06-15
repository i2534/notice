<script>
  import { sendWebhook, uploadImages } from '../../lib/api.js'
  import { settings, messages, sendPanelOpen } from '../../lib/store.js'
  import { gzipBase64Encode } from '../../lib/utils.js'

  export let showToast = () => {}

  let title = ''
  let content = ''
  let topic = ''
  let sending = false

  async function handleSend() {
    if (!content.trim() || sending) return
    sending = true
    const body = {
      title: title.trim() || '通知',
      content: content.trim(),
      topic: topic.trim() || undefined,
    }

    // 内容较长时自动压缩
    if (body.content.length >= 255) {
      const encoded = await gzipBase64Encode(body.content)
      if (encoded && encoded.length < body.content.length) {
        body.content = encoded
        body.content_encoding = 'gzip+base64'
      }
    }

    const res = await sendWebhook(
      body.title,
      body.content,
      body.topic,
      $settings.token,
      'web',
      body.content_encoding,
    )
    if (res.ok) {
      // 移除乐观更新，等待 MQTT 回环消息（携带服务端时间戳）到达
      // MQTT 回环通常 <100ms，用户感知无延迟
      title = ''; content = ''; topic = ''
      showToast('消息已发送', 'success')
    } else if (res.status === 401) { showToast('认证失败，请重新登录', 'error') }
    else if (res.status === 429) { showToast('请求过于频繁', 'error') }
    else { showToast(res.data?.message || '发送失败', 'error') }
    sending = false
  }

  async function handleUpload() {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = '.jpg,.jpeg,.png,.gif,.webp,image/*'
    input.multiple = true
    input.onchange = async () => {
      const files = input.files
      if (!files || files.length === 0) return
      showToast('上传中…', 'success')
      const res = await uploadImages(files, $settings.token)
      if (res.ok && res.data?.image_urls) {
        const lines = res.data.image_urls.map(url => {
          const u = url.startsWith('http') ? url : window.location.origin + (url.startsWith('/') ? url : '/' + url)
          return '![](' + u + ')'
        })
        content = content + (content ? '\n\n' : '') + lines.join('\n')
        showToast('已插入图片', 'success')
      } else {
        showToast(res.data?.message || '上传失败', 'error')
      }
    }
    input.click()
  }
</script>

<div class="send-panel">
  <div class="send-header">
    <h4>发送消息</h4>
    <button class="btn-ghost-sm" onclick={() => sendPanelOpen.set(false)}>收起</button>
  </div>
  <div class="send-row">
    <div class="field">
      <label for="sendTitle">标题</label>
      <input id="sendTitle" type="text" bind:value={title} placeholder="消息标题" />
    </div>
    <div class="field">
      <label for="sendTopic">主题</label>
      <input id="sendTopic" type="text" bind:value={topic} placeholder="留空使用默认" />
    </div>
  </div>
  <div class="field">
    <label for="sendContent">内容</label>
    <textarea id="sendContent" bind:value={content} placeholder="消息内容… Markdown 格式支持"
      onkeydown={(e) => { if (e.ctrlKey && e.key === 'Enter') { e.preventDefault(); handleSend(); } }}></textarea>
  </div>
  <div class="send-toolbar">
    <button class="btn-secondary-sm" onclick={handleUpload}>📷 图片</button>
    <button class="btn-primary" onclick={handleSend} disabled={sending}>
      {sending ? '发送中…' : '发送消息'}
    </button>
    <span class="hint">Ctrl+Enter</span>
  </div>
</div>

<style>
  .send-panel { flex-shrink:0; border-top:1px solid var(--border); background:var(--bg-elevated); padding:16px 20px 18px; }
  .send-header { display:flex; align-items:center; justify-content:space-between; margin-bottom:12px; }
  .send-header h4 { font-size:13px; font-weight:600; color:var(--text-secondary); }
  .send-row { display:flex; gap:12px; margin-bottom:10px; }
  .send-row .field { flex:1; }
  .field { margin-bottom:10px; }
  .field label { display:block; font-size:10px; font-weight:600; color:var(--text-hint); text-transform:uppercase; letter-spacing:.5px; margin-bottom:4px; }
  .field input, .field textarea { width:100%; padding:8px 12px; background:var(--bg-surface); border:1px solid var(--border); border-radius:var(--radius-sm); color:var(--text-primary); font-size:13px; font-family:inherit; outline:none; }
  .field input:focus, .field textarea:focus { border-color:var(--accent); }
  .field textarea { height:66px; resize:vertical; line-height:1.5; }
  .send-toolbar { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
  .btn-primary { padding:7px 18px; border-radius:var(--radius-sm); font-size:13px; font-weight:600; font-family:inherit; border:none; background:var(--accent); color:var(--text-on-accent); cursor:pointer; }
  .btn-primary:hover { filter:brightness(1.1); }
  .btn-primary:disabled { opacity:.4; cursor:not-allowed; transform:none; }
  .btn-secondary-sm { padding:5px 12px; border-radius:var(--radius-sm); font-size:12px; font-weight:500; background:transparent; color:var(--text-secondary); border:1px solid var(--border); cursor:pointer; font-family:inherit; }
  .btn-secondary-sm:hover { color:var(--text-primary); border-color:var(--border-light); }
  .hint { margin-left:auto; font-size:11px; color:var(--text-hint); }
  .btn-ghost-sm { background:none; border:none; color:var(--text-hint); font-size:12px; cursor:pointer; font-family:inherit; }
  .btn-ghost-sm:hover { color:var(--text-primary); }
</style>
