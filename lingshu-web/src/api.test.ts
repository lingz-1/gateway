import { describe, expect, it } from 'vitest'
import { parseSseBlock } from './api'

describe('parseSseBlock', () => {
  it('extracts data fields and ignores comments', () => {
    expect(parseSseBlock(': keepalive\ndata: {"value":1}\ndata: [DONE]')).toEqual([
      '{"value":1}',
      '[DONE]',
    ])
  })

  it('accepts a data field without a space', () => {
    expect(parseSseBlock('data:{"value":2}')).toEqual(['{"value":2}'])
  })
})
