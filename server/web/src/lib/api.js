export async function apiPost(path, body, token) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers['Authorization'] = 'Bearer ' + token
  const res = await fetch(path, { method: 'POST', headers, body: JSON.stringify(body) })
  const data = await res.json().catch(() => ({}))
  return { ok: res.ok, status: res.status, data }
}

export async function apiGet(path, token) {
  const headers = {}
  if (token) headers['Authorization'] = 'Bearer ' + token
  const res = await fetch(path, { headers })
  const data = await res.json().catch(() => ({}))
  return { ok: res.ok, status: res.status, data }
}

export async function authCheck(token) {
  return apiPost('/webhook', { content: '__auth_check__' }, token)
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
  const res = await fetch('/api/upload', {
    method: 'POST',
    headers: { 'Authorization': 'Bearer ' + token },
    body: form,
  })
  const data = await res.json().catch(() => ({}))
  return { ok: res.ok, status: res.status, data }
}

export async function fetchStatus() {
  return apiGet('/status')
}

export async function fetchClients(token) {
  return apiGet('/api/clients', token)
}
