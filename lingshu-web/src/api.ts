import type {
  ApiMessage,
  CompletionMetadata,
  CompletionResponse,
  CompletionResult,
  ModelListResponse,
  Usage,
} from './types'

export interface CompletionOptions {
  tenantId: string
  model: string
  temperature: number
  messages: ApiMessage[]
  signal: AbortSignal
}

export class LingShuApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly traceId?: string,
  ) {
    super(message)
    this.name = 'LingShuApiError'
  }
}

async function ensureOk(response: Response): Promise<void> {
  if (response.ok) return

  const text = await response.text()
  try {
    const payload = JSON.parse(text) as { message?: string; code?: string; traceId?: string }
    throw new LingShuApiError(
      payload.message || payload.code || `请求失败（${response.status}）`,
      response.status,
      payload.traceId,
    )
  } catch (error) {
    if (error instanceof LingShuApiError) throw error
    throw new LingShuApiError(text || `请求失败（${response.status}）`, response.status)
  }
}

export async function listModels(signal?: AbortSignal): Promise<ModelListResponse> {
  const response = await fetch('/v1/models', { signal })
  await ensureOk(response)
  return response.json() as Promise<ModelListResponse>
}

export async function completeChat(options: CompletionOptions): Promise<CompletionResponse> {
  const response = await fetch('/v1/chat/completions', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Tenant-Id': options.tenantId,
    },
    body: JSON.stringify({
      model: options.model,
      messages: options.messages,
      stream: false,
      temperature: options.temperature,
    }),
    signal: options.signal,
  })
  await ensureOk(response)
  return response.json() as Promise<CompletionResponse>
}

interface StreamFrame {
  choices?: Array<{ delta?: { content?: string } }>
  usage?: Usage
  metadata?: CompletionMetadata
}

export function parseSseBlock(block: string): string[] {
  return block
    .split(/\r?\n/)
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice(5).trimStart())
}

export async function streamChat(
  options: CompletionOptions,
  onDelta: (content: string) => void,
): Promise<CompletionResult> {
  const response = await fetch('/v1/chat/completions', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      'X-Tenant-Id': options.tenantId,
    },
    body: JSON.stringify({
      model: options.model,
      messages: options.messages,
      stream: true,
      temperature: options.temperature,
    }),
    signal: options.signal,
  })
  await ensureOk(response)

  if (!response.body) {
    throw new LingShuApiError('浏览器未收到流式响应体', response.status)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let usage: Usage | undefined
  let metadata: CompletionMetadata | undefined

  const handleBlock = (block: string) => {
    for (const data of parseSseBlock(block)) {
      if (!data || data === '[DONE]') continue
      const frame = JSON.parse(data) as StreamFrame
      const delta = frame.choices?.[0]?.delta?.content
      if (delta) onDelta(delta)
      if (frame.usage) usage = frame.usage
      if (frame.metadata) metadata = frame.metadata
    }
  }

  while (true) {
    const { done, value } = await reader.read()
    buffer += decoder.decode(value, { stream: !done })
    const blocks = buffer.split(/\r?\n\r?\n/)
    buffer = blocks.pop() ?? ''
    blocks.forEach(handleBlock)
    if (done) break
  }
  if (buffer.trim()) handleBlock(buffer)

  if (!usage || !metadata) {
    throw new LingShuApiError('流式响应缺少最终用量信息', response.status)
  }
  return { usage, metadata }
}
