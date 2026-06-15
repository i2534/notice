const FETCH_TIMEOUT = 10000

/**
 * @param {RequestInfo | URL} input
 * @param {RequestInit} init
 * @param {number} [timeout]
 */
async function fetchWithTimeout(input, init, timeout = FETCH_TIMEOUT) {
  const controller = new AbortController()
  const id = setTimeout(() => controller.abort(), timeout)
  try {
    return await fetch(input, { ...init, signal: controller.signal })
  } finally {
    clearTimeout(id)
  }
}

/** @param {unknown} e */
function networkErrorMessage(e) {
  return e instanceof DOMException && e.name === 'AbortError'
    ? '请求超时'
    : '网络连接失败'
}

/**
 * @param {string} path
 * @param {unknown} body
 * @param {string} [token]
 */
export async function apiPost(path, body, token) {
  /** @type {Record<string, string>} */
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers['Authorization'] = 'Bearer ' + token
  try {
    const res = await fetchWithTimeout(path, { method: 'POST', headers, body: JSON.stringify(body) })
    const data = await res.json()
    return { ok: res.ok, status: res.status, data, error: null }
  } catch (e) {
    const networkError = networkErrorMessage(e)
    return { ok: false, status: 0, data: {}, error: networkError }
  }
}

/**
 * @param {string} path
 * @param {string} [token]
 */
export async function apiGet(path, token) {
  /** @type {Record<string, string>} */
  const headers = {}
  if (token) headers['Authorization'] = 'Bearer ' + token
  try {
    const res = await fetchWithTimeout(path, { headers })
    const data = await res.json()
    return { ok: res.ok, status: res.status, data, error: null }
  } catch (e) {
    const networkError = networkErrorMessage(e)
    return { ok: false, status: 0, data: {}, error: networkError }
  }
}

/** 校验 token 是否有效 — 使用 GET /messages 查询，不产生副作用 */
/** @param {string} token */
export async function authCheck(token) {
  return apiGet('/messages?page_size=1', token)
}

/**
 * @param {string} token
 * @param {number} [pageSize]
 * @param {string | number} [beforeId]
 */
export async function fetchMessages(token, pageSize = 50, beforeId) {
  let path = `/messages?page_size=${pageSize}`
  if (beforeId) path += `&before_id=${beforeId}`
  return apiGet(path, token)
}

/**
 * @param {string} [title]
 * @param {string} [content]
 * @param {string} [topic]
 * @param {string} [token]
 * @param {string} [client]
 * @param {string} [encoding]
 */
export async function sendWebhook(title = '', content = '', topic = undefined, token = undefined, client = 'web', encoding = undefined) {
  const body = { title, content, client }
  if (topic) Object.assign(body, { topic })
  if (encoding) Object.assign(body, { content_encoding: encoding })
  return apiPost('/webhook', body, token)
}

/**
 * @param {File[]} files
 * @param {string} token
 */
export async function uploadImages(files, token) {
  const form = new FormData()
  for (const f of files) form.append('file', f)
  const res = await fetchWithTimeout('/api/upload', {
    method: 'POST',
    headers: { 'Authorization': 'Bearer ' + token },
    body: form,
  }, 30000) // 图片上传给 30s 超时
  const data = await res.json().catch(() => ({}))
  const networkError = res.ok ? null : (data.message || `HTTP ${res.status}`)
  return { ok: res.ok, status: res.status, data, error: networkError }
}

export async function fetchStatus() {
  return apiGet('/status')
}

/** @param {string} token */
export async function fetchClients(token) {
  return apiGet('/api/clients', token)
}
