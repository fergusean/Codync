# Bot collaboration

Create two or more bots normally, giving each a clear name, description, project folder and agent. Then message one:

> Ask Reviewer to inspect the changes in /path/to/project. Have it report bugs without editing files, then summarize its findings for me.

Every bot receives the built-in `team` MCP server. `list_bots` returns the other visible bots' IDs, descriptions, agents, folders and status. The same tools work across ACP backends that support MCP servers.

| Tool | When it returns | Where the outcome appears | Sender Stop |
| --- | --- | --- | --- |
| `ask_bot(botId, message)` | After the recipient's final reply, within ten minutes | Returned to the sender, which incorporates it into its response to you | Cancels the ask |
| `message_bot(botId, message)` | As soon as the request is queued | The recipient reports to you in its own chat | Accepted work continues |

For an independent handoff, message a bot:

> Message Reviewer to inspect the changes in /path/to/project without editing files. Have it report its findings to me in its own chat; you do not need to wait for a reply.

The host records the request and its outcome in both chats using existing notice entries, so the current iOS, macOS, Linux and terminal clients can display them. Tool details also remain in the trace. There is no additional team setup screen.

## Execution

- Each bot still owns an ACP process with main/reply-thread sessions and runs one turn at a time. A busy recipient queues the request. Different bots can work concurrently.
- Both tools use the same request queue, with separate turns that never merge with user messages or another request. Asks own a reply channel; independent messages own their completion notices. Contiguous user messages keep the existing batching behavior.
- The recipient uses its own folder, tools, memory and permission policy. Permission cards appear in its chat. The requesting bot's entire transcript is not copied; only the request is passed. Requests are not treated as user facts by the memory keeper.
- The host rejects self-delegation. Asks also reject duplicate outstanding asks to the same recipient and direct or indirect wait cycles, including queued asks. Independent messages create no wait edge; multiple messages to one recipient queue separately. The host permits up to 64 outstanding asks and, separately, 64 outstanding independent messages, including running work.
- Native Claude Code/Codex subagents remain managed by the harness. Codync does not turn them into permanent bots or override their delegation settings.

## Stops, failures and restart

- The ask wait limit is ten minutes, including queue and approval time. Failure is a tool error, not a fabricated successful reply. Independent messages have no ask timeout. Neither tool is automatically retried.
- Stopping the requesting bot cancels its outgoing asks. A queued ask is removed; a running delegated turn's ACP process is stopped. Unrelated queued messages on the recipient remain. Independent messages survive sender completion, Stop and disconnection.
- Stopping the recipient removes its queued independent messages, marks both notices "Cancelled before execution", and stops its running turn. Work that started may have left partial changes. Stopping a running ask also releases its requester once the turn stops.
- Deleting either bot cancels affected asks. Independent messages survive deletion of the sender; deleting the recipient cancels them. Recipient startup failures, crashes, refusals and empty final reports close independent message notices as failed in both chats.
- Independent messages appear as "Messaged …" and "Message from …" notices attributed to their sending bot, never as user messages. The queued receipt contains a request ID and recipient details; it does not promise completion. The recipient's full report stays in its chat, while the sender's notice records the status and where to find the outcome. If admission fails or the receipt is lost, inspect the chats before retrying.
- Pending request notices are persisted with IDs and statuses. After host restart they become interrupted errors; delegated turns are not automatically replayed. Check partial work before retrying. Ordinary user turns retain their existing session-resume behavior.
- Ask completion push notifications come from the requesting bot; delegated replies do not send a second “done” push. Independent message turns use the recipient's normal completion notification policy. Recipient permission requests still use the usual “needs you” notifications.

These tools provide synchronous asks and independent handoffs over MCP, without a task scheduler, durable replay queue or isolated worktrees. Give bots explicit file ownership when they share a working directory. Rebuild and restart the host to load the updated built-in MCP server; phone clients need no protocol upgrade.

## Code and verification

- `host/src/chat/team.rs`: discovery, wait graph, request lifetime and persisted notices.
- `host/src/agent/bot.rs`: queue boundaries, recipient execution, completion and targeted cancellation.
- `host/src/mcp.rs`: the authenticated local MCP → HTTP bridge.
- `host/tests/team_e2e.rs`: a real host and MCP subprocess, with scripted ACP agents, covering discovery → delegation → recipient approval → reply → client sync. No paid provider calls.

Run `cargo test` and `cargo clippy --all-targets -- -D warnings` in `host/`.
