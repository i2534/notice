const FETCH_TIMEOUT = 10000

async function fetchWithTimeout(input, init, timeout = FETCH_TIMEOUT) {
  const controller = new AbortController()
  const id = setTimeout(() => controller.abort(), timeout)
  try {
    return await fetch(input, { ...init, signal: controller.signal })
  } finally {
    clearTimeout(id)
  }
}

export async function apiPost(path, body, token) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers['Authorization'] = 'Bearer ' + token
  const res = await fetchWithTimeout(path, { method: 'POST', headers, body: JSON.stringify(body) })
  const data = await res.json().catch(() => ({}))
  return { ok: res.ok, status: res.status, data }
}

export async function apiGet(path, token) {
  const headers = {}
  if (token) headers['Authorization'] = 'Bearer ' + token
  const res = await fetchWithTimeout(path, { headers })
  const data = await res.json().catch(() => ({}))
  return { ok: res.ok, status: res.status, data }
}

/** 校验 token 是否有效 — 使用 GET /messages 查询，不产生副作用 */
export async function authCheck(token) {
  return apiGet('/messages?page_size=1', token)
}

export async function fetchMessages(token, pageSize = 50, beforeId) {
  let path = `/messages?page_size=${pageSize}`
  if (beforeId) path += `&before_id=${beforeId}`
  return apiGet(path, token)
}

export async function sendWebhook(title, content, topic, token, client = 'web') {
  const body = { title, content, client }
  if (topic) body.topic = topic
  return apiPost('/webhook', body, token)
}

export async function uploadImages(files, token) {
  const form = new FormData()
  for (const f of files) form.append('file', f)
  const res = await fetchWithTimeout('/api/upload', {
    method: 'POST',
    headers: { 'Authorization': 'Bearer ' + token },
    body: form,
  }, 30000) // 图片上传给 30s 超时
  const data = await res.json().catch(() => ({}))
  return { ok: res.ok, status: res.status, data }
}

export async function fetchStatus() {
  return apiGet('/status')
}

export async function fetchClients(token) {
  return apiGet('/api/clients', token)
}
