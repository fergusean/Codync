# Chat messages

How a bot's work turns into what the user reads, copied from Grok Bot.

## Messages are sent, not streamed

- Every bot gets the built-in `chat` MCP server (`codync-host mcp chat`,
  `host/src/chat/outbox.rs`) with one tool, `send_message {text}`. In the bot's own chat and its
  threads, each call is one chat bubble, added to the turn's lane at once as an `agent` entry with
  `final: true`. A turn can send several.
- The agent's own reply text (`agent_message_chunk`) stays in the trace when the turn sent
  anything. A turn that sent nothing (an agent that ignores the tool, or has no MCP) falls back to
  the old rule: its last text becomes the turn's final message.
- Group room turns, `ask_bot` requests and routines reject `send_message`: their reply is the
  turn's last text, as before (`chat/group.rs`, `chat/team.rs`, `routines`).
- The rules reach the agent twice: the MCP server's instructions, and the frozen instruction
  snapshot (`chat/context.rs`).
- Clients never stream text into a bubble: the chat shows user messages, `final` agent entries,
  permission cards and notices (`isChat`). Nothing pops in and out while a turn runs.

## While it works

The working line under the last message shows the bot's activity; composing a `send_message`
reads as **Typing…** (`activity()` in `agent/bot.rs`). Thoughts and tool calls stay in the
Full conversation sheet.

## Motion and scrolling

- A message arriving while the list follows the newest pops in: 0.24 s,
  `cubic-bezier(.23, 1, .32, 1)`, from 12 pt lower at 94 % scale (iPhone: `ConversationLayout` in
  `ConversationList.swift`; desktop: `.row-in` in `thread.css`; reduced motion: a 0.12 s fade).
- The list stays pinned to the bottom while the reader is there; scrolling up releases it and the
  round "Jump to latest" button brings it back. Sending always returns to the bottom.
- The terminal UI lists the same messages; its steps line goes under the turn's last message.
