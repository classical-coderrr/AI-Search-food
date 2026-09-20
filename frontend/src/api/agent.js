import { useAuthStore } from '../stores/auth.js'
import { getAnonymousId } from '../utils/anonymousId.js'
import { http } from './http.js'

export async function streamAgentChat(payload, { image, onEvent, signal } = {}) {
  const auth = useAuthStore()
  const headers = {
    Accept: 'text/event-stream',
    'X-Anonymous-Id': getAnonymousId()
  }
  if (auth.token) {
    headers.Authorization = `Bearer ${auth.token}`
  }

  let body
  if (image) {
    body = new FormData()
    body.append('request', new Blob([JSON.stringify(payload)], { type: 'application/json' }))
    body.append('image', image)
  } else {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(payload)
  }

  const response = await fetch('/api/agent/chat/stream', {
    method: 'POST',
    headers,
    body,
    signal
  })
  if (!response.ok) {
    let message = response.status === 401 ? '请先登录普通用户账号' : '小厨灵暂时无法回应，请稍后重试'
    try {
      const body = await response.json()
      message = body?.message || message
    } catch {
      // The status-specific fallback is enough when the server closes early.
    }
    const error = new Error(message)
    error.status = response.status
    throw error
  }
  if (!response.body) {
    throw new Error('浏览器没有提供流式响应，请刷新后重试')
  }

  return consumeAgentSse(response, { onEvent, signal })
}

export async function resumeAgentEvents(runId, lastEventId = 0, { onEvent, signal } = {}) {
  const auth = useAuthStore()
  const headers = {
    Accept: 'text/event-stream',
    'X-Anonymous-Id': getAnonymousId()
  }
  if (auth.token) {
    headers.Authorization = `Bearer ${auth.token}`
  }
  headers['Last-Event-ID'] = String(Math.max(0, Number(lastEventId) || 0))
  const query = new URLSearchParams({ afterEventSeq: String(Math.max(0, Number(lastEventId) || 0)) })
  const response = await fetch(`/api/agent/runs/${encodeURIComponent(runId)}/events/stream?${query}`, {
    method: 'GET',
    headers,
    signal
  })
  if (!response.ok) {
    const error = new Error(response.status === 401 ? '登录状态已失效，请重新登录后再试' : '暂时无法恢复小厨灵事件，请稍后重试')
    error.status = response.status
    throw error
  }
  if (!response.body) {
    throw new Error('浏览器没有提供恢复流式响应，请刷新后重试')
  }
  return consumeAgentSse(response, { onEvent, signal })
}

async function consumeAgentSse(response, { onEvent, signal } = {}) {

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let eventName = 'message'
  let eventId = ''
  let dataLines = []

  const flush = () => {
    if (!dataLines.length) return
    const rawData = dataLines.join('\n')
    let data = rawData
    try {
      data = JSON.parse(rawData)
    } catch {
      // Keep non-JSON event payloads readable for future server events.
    }
    onEvent?.({ type: eventName, data, id: eventId || null })
    eventName = 'message'
    eventId = ''
    dataLines = []
  }

  const consume = (chunk) => {
    buffer += chunk
    const blocks = buffer.split(/\r?\n\r?\n/)
    buffer = blocks.pop() || ''
    blocks.forEach((block) => {
      block.split(/\r?\n/).forEach((line) => {
        if (line.startsWith('event:')) eventName = line.slice(6).trim()
        if (line.startsWith('id:')) eventId = line.slice(3).trim()
        if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
      })
      flush()
    })
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    consume(decoder.decode(value, { stream: true }))
  }
  consume(decoder.decode())
  if (buffer.trim()) {
    buffer.split(/\r?\n/).forEach((line) => {
      if (line.startsWith('event:')) eventName = line.slice(6).trim()
      if (line.startsWith('id:')) eventId = line.slice(3).trim()
      if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
    })
    flush()
  }
}

export function deleteAgentConversation(conversationId) {
  return http.delete(`/agent/conversations/${conversationId}`)
}

export function getLatestAgentConversation() {
  return http.get('/agent/conversations/latest')
}

export function getAgentConversationMessages(conversationId) {
  return http.get(`/agent/conversations/${conversationId}/messages`)
}

export function getAgentRunStatus(runId) {
  return http.get(`/agent/runs/${runId}`)
}

export function getAgentEventHistory(runId, afterEventSeq = 0) {
  return http.get(`/agent/runs/${encodeURIComponent(runId)}/events`, {
    params: { afterEventSeq }
  })
}

export function getAgentConfirmationStatus(confirmationId) {
  return http.get(`/agent/confirmations/${confirmationId}`)
}

export function getAgentWriteOperationStatus(idempotencyKey) {
  return http.get(`/agent/writes/${encodeURIComponent(idempotencyKey)}`)
}
