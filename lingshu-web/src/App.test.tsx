import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'

afterEach(() => {
  vi.restoreAllMocks()
})

describe('App', () => {
  it('discovers models and renders the user console', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        object: 'list',
        data: [{ id: 'stub-echo-v1', object: 'model', provider: 'stub', status: 'UP' }],
      }),
    }))

    render(<App />)

    expect(screen.getByRole('heading', { name: /把请求交给灵枢/ })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByText('调用链在线')).toBeInTheDocument())
    expect(screen.getByRole('option', { name: 'stub-echo-v1' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled()
  })
})
