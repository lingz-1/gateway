import { FormEvent, KeyboardEvent, useEffect, useMemo, useRef, useState } from 'react'
import { completeChat, LingShuApiError, listModels, streamChat } from './api'
import type {
  ApiMessage,
  ChatMessage,
  CompletionMetadata,
  Conversation,
  ModelInfo,
  Usage,
} from './types'

const createId = () => crypto.randomUUID()

const initialConversation = (): Conversation => ({
  id: createId(),
  title: '新的对话',
  messages: [],
})

const processorLabels: Record<string, string> = {
  trace: '链路追踪',
  'tenant-policy': '租户策略',
  'pii-redaction': '隐私脱敏',
  'exact-cache-lookup': '精确缓存',
  'semantic-cache-lookup': '语义缓存',
  router: '模型路由',
  'provider-invoke': '模型调用',
  'exact-cache-write': '缓存回写',
  'semantic-cache-write': '语义回写',
  'virtual-billing': '虚拟计费',
}

function Icon({ name }: { name: 'plus' | 'send' | 'stop' | 'retry' | 'spark' | 'menu' }) {
  const paths = {
    plus: <path d="M12 5v14M5 12h14" />,
    send: <path d="m4 4 16 8-16 8 3-8-3-8Zm3 8h13" />,
    stop: <rect x="6" y="6" width="12" height="12" rx="2" />,
    retry: <path d="M20 11a8 8 0 1 0-2.34 5.66M20 4v7h-7" />,
    spark: <path d="m12 3 1.3 4.7L18 9l-4.7 1.3L12 15l-1.3-4.7L6 9l4.7-1.3L12 3Zm6 12 .7 2.3L21 18l-2.3.7L18 21l-.7-2.3L15 18l2.3-.7L18 15Z" />,
    menu: <path d="M5 7h14M5 12h14M5 17h14" />,
  }
  return <svg aria-hidden="true" viewBox="0 0 24 24">{paths[name]}</svg>
}

function formatMoney(value?: number) {
  if (value === undefined) return '—'
  return `¥${Number(value).toFixed(6)}`
}

function App() {
  const [conversations, setConversations] = useState<Conversation[]>([initialConversation()])
  const [activeId, setActiveId] = useState(() => conversations[0].id)
  const [models, setModels] = useState<ModelInfo[]>([])
  const [model, setModel] = useState('')
  const [tenantId, setTenantId] = useState('local-test')
  const [temperature, setTemperature] = useState(0.7)
  const [streaming, setStreaming] = useState(true)
  const [prompt, setPrompt] = useState('')
  const [pending, setPending] = useState(false)
  const [online, setOnline] = useState(false)
  const [error, setError] = useState<string>()
  const [metadata, setMetadata] = useState<CompletionMetadata>()
  const [usage, setUsage] = useState<Usage>()
  const [mobileNav, setMobileNav] = useState(false)
  const [mobileInspector, setMobileInspector] = useState(false)
  const abortRef = useRef<AbortController | undefined>(undefined)
  const chatEndRef = useRef<HTMLDivElement>(null)

  const active = useMemo(
    () => conversations.find((conversation) => conversation.id === activeId) ?? conversations[0],
    [activeId, conversations],
  )

  useEffect(() => {
    const controller = new AbortController()
    listModels(controller.signal)
      .then((response) => {
        setModels(response.data)
        setModel((current) => current || response.data.find((item) => item.status === 'UP')?.id || '')
        setOnline(true)
      })
      .catch((reason: unknown) => {
        if ((reason as Error).name !== 'AbortError') {
          setError('无法连接灵枢网关，请确认本地服务已启动。')
          setOnline(false)
        }
      })
    return () => controller.abort()
  }, [])

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [active?.messages, pending])

  const updateMessages = (conversationId: string, updater: (messages: ChatMessage[]) => ChatMessage[]) => {
    setConversations((current) => current.map((conversation) => (
      conversation.id === conversationId
        ? { ...conversation, messages: updater(conversation.messages) }
        : conversation
    )))
  }

  const runCompletion = async (conversationId: string, history: ChatMessage[], assistantId: string) => {
    const controller = new AbortController()
    abortRef.current = controller
    setPending(true)
    setError(undefined)
    setMetadata(undefined)
    setUsage(undefined)

    const messages: ApiMessage[] = history.map(({ role, content }) => ({ role, content }))
    try {
      if (streaming) {
        const result = await streamChat(
          { tenantId, model, temperature, messages, signal: controller.signal },
          (delta) => updateMessages(conversationId, (current) => current.map((message) => (
            message.id === assistantId ? { ...message, content: message.content + delta } : message
          ))),
        )
        setMetadata(result.metadata)
        setUsage(result.usage)
      } else {
        const result = await completeChat({ tenantId, model, temperature, messages, signal: controller.signal })
        updateMessages(conversationId, (current) => current.map((message) => (
          message.id === assistantId
            ? { ...message, content: result.choices[0]?.message.content ?? '' }
            : message
        )))
        setMetadata(result.metadata)
        setUsage(result.usage)
      }
      setOnline(true)
    } catch (reason) {
      if ((reason as Error).name === 'AbortError') {
        setError('本次生成已停止。')
      } else {
        const detail = reason instanceof LingShuApiError && reason.traceId
          ? `${reason.message} · Trace ${reason.traceId}`
          : (reason as Error).message
        setError(detail || '请求失败，请稍后重试。')
      }
      updateMessages(conversationId, (current) => current.filter(
        (message) => message.id !== assistantId || message.content.length > 0,
      ))
    } finally {
      setPending(false)
      abortRef.current = undefined
    }
  }

  const send = async (event?: FormEvent) => {
    event?.preventDefault()
    const content = prompt.trim()
    if (!content || pending || !model || !active) return

    const userMessage: ChatMessage = { id: createId(), role: 'user', content }
    const assistantMessage: ChatMessage = { id: createId(), role: 'assistant', content: '' }
    const history = [...active.messages, userMessage]
    const title = active.messages.length === 0 ? content.slice(0, 20) : active.title
    setConversations((current) => current.map((conversation) => (
      conversation.id === active.id
        ? { ...conversation, title, messages: [...history, assistantMessage] }
        : conversation
    )))
    setPrompt('')
    await runCompletion(active.id, history, assistantMessage.id)
  }

  const retry = async () => {
    if (!active || pending) return
    const history = active.messages.filter((message) => message.role === 'user' || message.content.length > 0)
    const requestHistory = history.at(-1)?.role === 'assistant' ? history.slice(0, -1) : history
    if (!requestHistory.some((message) => message.role === 'user')) return
    const assistantMessage: ChatMessage = { id: createId(), role: 'assistant', content: '' }
    updateMessages(active.id, () => [...requestHistory, assistantMessage])
    await runCompletion(active.id, requestHistory, assistantMessage.id)
  }

  const newConversation = () => {
    const conversation = initialConversation()
    setConversations((current) => [conversation, ...current])
    setActiveId(conversation.id)
    setMetadata(undefined)
    setUsage(undefined)
    setError(undefined)
    setMobileNav(false)
  }

  const selectConversation = (id: string) => {
    setActiveId(id)
    setMetadata(undefined)
    setUsage(undefined)
    setError(undefined)
    setMobileNav(false)
  }

  const onComposerKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      void send()
    }
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <button className="icon-button mobile-only" aria-label="打开会话列表" onClick={() => setMobileNav(true)}>
          <Icon name="menu" />
        </button>
        <div className="brand">
          <div className="brand-mark">枢</div>
          <div>
            <strong>灵枢</strong>
            <span>LINGSHU GATEWAY</span>
          </div>
        </div>
        <div className="system-state" aria-live="polite">
          <span className={online ? 'status-dot is-online' : 'status-dot'} />
          {online ? '调用链在线' : '等待网关'}
        </div>
        <button className="inspector-toggle mobile-only" onClick={() => setMobileInspector(true)}>
          链路详情
        </button>
      </header>

      <div className="workspace">
        {mobileNav && <button className="backdrop" aria-label="关闭会话列表" onClick={() => setMobileNav(false)} />}
        <aside className={`session-panel ${mobileNav ? 'is-open' : ''}`}>
          <div className="panel-kicker">SESSION / 会话</div>
          <button className="new-chat" onClick={newConversation}>
            <Icon name="plus" />
            新建对话
          </button>
          <nav aria-label="会话列表">
            {conversations.map((conversation, index) => (
              <button
                key={conversation.id}
                className={`session-item ${conversation.id === activeId ? 'is-active' : ''}`}
                onClick={() => selectConversation(conversation.id)}
              >
                <span>{String(index + 1).padStart(2, '0')}</span>
                <strong>{conversation.title}</strong>
              </button>
            ))}
          </nav>
          <div className="session-foot">
            <span>LOCAL CONSOLE</span>
            <span>v0.1</span>
          </div>
        </aside>

        <main className="chat-panel">
          <section className="control-deck" aria-label="请求设置">
            <label>
              <span>租户</span>
              <input value={tenantId} onChange={(event) => setTenantId(event.target.value)} disabled={pending} />
            </label>
            <label>
              <span>模型</span>
              <select value={model} onChange={(event) => setModel(event.target.value)} disabled={pending || !models.length}>
                {!models.length && <option value="">正在发现模型…</option>}
                {models.map((item) => <option key={item.id} value={item.id}>{item.id}</option>)}
              </select>
            </label>
            <label className="temperature-control">
              <span>温度 <b>{temperature.toFixed(1)}</b></span>
              <input
                aria-label="温度"
                type="range"
                min="0"
                max="2"
                step="0.1"
                value={temperature}
                onChange={(event) => setTemperature(Number(event.target.value))}
                disabled={pending}
              />
            </label>
            <label className="stream-switch">
              <span>流式</span>
              <input type="checkbox" checked={streaming} onChange={(event) => setStreaming(event.target.checked)} disabled={pending} />
              <i aria-hidden="true" />
            </label>
          </section>

          <section className={`conversation ${active?.messages.length ? 'has-messages' : ''}`} aria-live="polite">
            {!active?.messages.length ? (
              <div className="empty-state">
                <span className="eyebrow"><Icon name="spark" /> 可观测的 AI 调用入口</span>
                <h1>把请求交给灵枢，<br />看见它穿过整条链路。</h1>
                <p>向本地模型桩发送第一条消息。缓存、路由、计费和处理耗时会在右侧同步展开。</p>
                <div className="prompt-suggestions">
                  {['解释什么是语义缓存', '给我一个简短的产品命名建议', '重复问两次，观察缓存命中'].map((suggestion) => (
                    <button key={suggestion} onClick={() => setPrompt(suggestion)}>{suggestion}<span>↗</span></button>
                  ))}
                </div>
              </div>
            ) : (
              <div className="message-list">
                {active.messages.map((message) => (
                  <article key={message.id} className={`message ${message.role}`}>
                    <div className="message-meta">{message.role === 'user' ? 'YOU / 用户' : 'LINGSHU / 灵枢'}</div>
                    <div className="message-body">
                      {message.content || <span className="typing"><i /><i /><i /></span>}
                    </div>
                  </article>
                ))}
                <div ref={chatEndRef} />
              </div>
            )}
          </section>

          <div className="composer-wrap">
            {error && (
              <div className="error-banner" role="alert">
                <span>{error}</span>
                <button onClick={() => void retry()} disabled={pending}><Icon name="retry" />重试</button>
              </div>
            )}
            <form className="composer" onSubmit={send}>
              <textarea
                aria-label="消息内容"
                value={prompt}
                onChange={(event) => setPrompt(event.target.value)}
                onKeyDown={onComposerKeyDown}
                placeholder="输入消息，Enter 发送，Shift + Enter 换行"
                rows={1}
                disabled={pending}
              />
              {pending ? (
                <button type="button" className="send-button is-stop" onClick={() => abortRef.current?.abort()} aria-label="停止生成">
                  <Icon name="stop" />
                </button>
              ) : (
                <button type="submit" className="send-button" disabled={!prompt.trim() || !model} aria-label="发送消息">
                  <Icon name="send" />
                </button>
              )}
            </form>
            <div className="composer-note">内容由本地 Stub 模型生成 · 不会调用外部 API</div>
          </div>
        </main>

        {mobileInspector && <button className="backdrop" aria-label="关闭链路详情" onClick={() => setMobileInspector(false)} />}
        <aside className={`inspector ${mobileInspector ? 'is-open' : ''}`}>
          <div className="inspector-head">
            <div>
              <div className="panel-kicker">TRACE / 链路</div>
              <h2>处理脉络</h2>
            </div>
            <span className={`trace-state ${metadata ? 'complete' : ''}`}>{pending ? '运行中' : metadata ? '已完成' : '待请求'}</span>
          </div>

          <div className="metric-grid">
            <div><span>总耗时</span><strong>{metadata ? `${metadata.totalDurationMs} ms` : '—'}</strong></div>
            <div><span>缓存</span><strong className={metadata?.cacheStatus === 'EXACT' ? 'jade' : ''}>{metadata?.cacheStatus ?? '—'}</strong></div>
            <div><span>Token</span><strong>{usage?.total_tokens ?? '—'}</strong></div>
            <div><span>本次费用</span><strong>{formatMoney(metadata?.virtualCostCny)}</strong></div>
          </div>

          <div className="trace-card">
            <span>TRACE ID</span>
            <code title={metadata?.traceId}>{metadata?.traceId ?? '等待首个请求'}</code>
          </div>

          <div className="processing-spine">
            {(metadata?.processors ?? []).length ? metadata!.processors.map((step, index) => (
              <div className="spine-step" key={`${step.name}-${index}`}>
                <i />
                <div>
                  <span>{String(index + 1).padStart(2, '0')}</span>
                  <strong>{processorLabels[step.name] ?? step.name}</strong>
                </div>
                <b>{step.durationMs} ms</b>
              </div>
            )) : (
              <div className="spine-placeholder">
                <div className="meridian-line"><i /><i /><i /><i /><i /></div>
                <p>发送消息后，请求经过的处理节点会依次点亮。</p>
              </div>
            )}
          </div>

          <dl className="request-facts">
            <div><dt>Provider</dt><dd>{metadata?.provider ?? models.find((item) => item.id === model)?.provider ?? '—'}</dd></div>
            <div><dt>尝试链路</dt><dd>{metadata?.providerAttempts?.join(' → ') || '—'}</dd></div>
            <div><dt>Tenant</dt><dd>{metadata?.tenantId ?? tenantId}</dd></div>
            <div><dt>Input / Output</dt><dd>{usage ? `${usage.prompt_tokens} / ${usage.completion_tokens}` : '—'}</dd></div>
            <div><dt>剩余余额</dt><dd>{formatMoney(metadata?.virtualRemainingBalanceCny)}</dd></div>
          </dl>
        </aside>
      </div>
    </div>
  )
}

export default App
