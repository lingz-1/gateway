export type ChatRole = 'user' | 'assistant'

export interface ChatMessage {
  id: string
  role: ChatRole
  content: string
}

export interface ApiMessage {
  role: ChatRole
  content: string
}

export interface ModelInfo {
  id: string
  object: string
  provider: string
  status: 'UP' | 'DOWN'
}

export interface ModelListResponse {
  object: string
  data: ModelInfo[]
}

export interface ProcessingStep {
  name: string
  durationMs: number
}

export interface Usage {
  prompt_tokens: number
  completion_tokens: number
  total_tokens: number
}

export interface CompletionMetadata {
  traceId: string
  tenantId: string
  provider: string
  cacheStatus: 'MISS' | 'EXACT' | 'SEMANTIC'
  totalDurationMs: number
  processors: ProcessingStep[]
  virtualCostCny: number
  virtualRemainingBalanceCny: number
  providerAttempts: string[]
}

export interface CompletionResponse {
  id: string
  model: string
  choices: Array<{ message: ApiMessage; finish_reason: string }>
  usage: Usage
  metadata: CompletionMetadata
}

export interface CompletionResult {
  usage: Usage
  metadata: CompletionMetadata
}

export interface Conversation {
  id: string
  title: string
  messages: ChatMessage[]
}
