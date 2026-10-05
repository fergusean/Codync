import type { Entry, EntryData } from '../../../shared/models.ts'

export interface BotMessage {
  label: string
  body: string
  detail: string
  reply: { label: string; body: string } | null
}

export function botMessage(data: EntryData): BotMessage | null {
  const { heading, text, delegationId } = data
  if (!delegationId || !heading || !text || !(text === heading || text.startsWith(heading + '\n'))) return null
  const at = heading.indexOf(': ')
  if (at < 0) return null
  const attribution = heading.slice(0, at)
  const prefix = ['Message from ', 'Request from ', 'Messaged ', 'Asked '].find((p) => attribution.startsWith(p))
  if (!prefix) return null
  const name = attribution.slice(prefix.length)
  if (!name) return null
  const label = (prefix === 'Messaged ' || prefix === 'Asked ' ? 'Message to ' : 'Message from ') + name
  const body = heading.slice(at + 2)
  const outcome = text === heading ? '' : text.slice(heading.length + 1)
  if (prefix === 'Messaged ' || prefix === 'Message from ') return { label, body, detail: '', reply: null }
  const separator = outcome.indexOf(':\n')
  if (data.status === 'completed' && outcome.startsWith('Reply from ') && separator >= 0) {
    return { label, body, detail: '', reply: { label: outcome.slice(0, separator), body: outcome.slice(separator + 2) } }
  }
  return { label, body, detail: prefix === 'Request from ' && outcome === 'Waiting for a reply…' ? 'Working on a reply…' : outcome, reply: null }
}

function matches(request: Entry, reply: Entry) {
  return request.kind === 'notice' && !!request.data.delegationId && request.data.heading?.startsWith('Request from ')
    && request.botId === reply.botId && request.turn === reply.turn && (request.threadId ?? null) === (reply.threadId ?? null)
}

export const isRecipientReply = (entry: Entry, entries: Entry[]) => entry.kind === 'agent' && entries.some((request) => matches(request, entry))
export const hasRecipientReply = (entry: Entry, entries: Entry[]) => entries.some((reply) => reply.kind === 'agent' && reply.data.final === true && matches(entry, reply))
