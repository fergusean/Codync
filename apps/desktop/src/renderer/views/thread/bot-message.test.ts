import assert from 'node:assert/strict'
import { test } from 'node:test'
import { botMessage, hasRecipientReply, isRecipientReply } from './bot-message.ts'
import type { Entry } from '../../../shared/models.ts'

test('one-way messages suppress outcome while asks display the reply or failure', () => {
  const heading = 'Message from Miles: Inspect this.\nKeep the changes.'
  assert.deepEqual(botMessage({ delegationId: 'd', heading, text: heading + '\nCompleted.' }), {
    label: 'Message from Miles', body: 'Inspect this.\nKeep the changes.', detail: '', reply: null,
  })
  const ask = 'Asked Dex: Inspect this.'
  assert.deepEqual(botMessage({ delegationId: 'd', heading: ask, text: ask + '\nReply from Dex:\nLooks good.', status: 'completed' })?.reply, { label: 'Reply from Dex', body: 'Looks good.' })
  assert.equal(botMessage({ delegationId: 'd', heading: ask, text: ask + '\nFailed.', status: 'failed' })?.detail, 'Failed.')
  assert.equal(botMessage({ heading, text: heading }), null)
})

test('recipient replies match only the same bot, turn and thread and suppress a duplicate excerpt', () => {
  const request: Entry = { id: 'request', seq: 1, botId: 'b', threadId: null, rev: 1, kind: 'notice', turn: 2, data: { delegationId: 'd', heading: 'Request from Miles: Inspect this.' }, createdAt: 1, updatedAt: 1 }
  const reply: Entry = { ...request, id: 'reply', kind: 'agent', data: { text: 'Full answer.', final: true } }
  assert.equal(isRecipientReply(reply, [request, reply]), true)
  assert.equal(hasRecipientReply(request, [request, reply]), true)
  for (const changed of [{ ...reply, botId: 'other' }, { ...reply, turn: 3 }, { ...reply, threadId: 'thread' }]) {
    assert.equal(isRecipientReply(changed, [request]), false)
    assert.equal(hasRecipientReply(request, [changed]), false)
  }
  assert.equal(hasRecipientReply(request, [{ ...reply, data: { final: false } }]), false)
})
