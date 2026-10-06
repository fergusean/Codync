# Context and memory

How a bot keeps its instructions, context and long-term memory. The mechanisms follow Grok Bot's design
(`system-prompt-assembly`, `sand-memory`, `turn-memory`, `upgrade-recreate-resume`) and are adapted to ACP,
where the harness owns the conversation.

## Transcript, context and memory

| | Where | Owner | Lifetime |
|---|---|---|---|
| Transcript | SQLite `entries` | Codync | Persistent main chat and flat reply threads per bot |
| Model context | ACP session (`bots.session_id`) | The harness | Until *New session*, a failed resume, or an agent/command/folder change |
| Long-term memory | `~/.codync/bots/<id>/memory/` | Codync (keeper) + the user | Forever, across sessions |

Codync never replays the transcript into a session. Each turn sends only the new message(s); the harness keeps
its own context and compacts it itself.

## Instruction snapshot (`host/src/chat/context.rs`)

- The bot's instructions are rendered once, in English and kept general: profile (name, description), how to
  message the user, other conversations, workspace, skills, plus the memory section. How to use each tool
  lives with the tool (MCP server instructions, tool descriptions), not here. They're stored as a snapshot in `kv` under `context.<bot>`, keyed by **session + compaction
  epoch**, and don't change until one of those changes. This keeps the prompt byte-identical so the prompt
  cache stays warm.
- **Claude** (`agentCapabilities._meta.claudeCode` present) receives the snapshot as a real system prompt:
  `_meta.systemPrompt.append` on `session/new` and `session/load`. It survives compaction.
- **Other harnesses** receive it in the first message of each session (`<bot-profile>…</bot-profile>`).
- **Compaction epoch:** Codync advertises `clientCapabilities.session.compaction`. Each completed
  `compaction_update` (deduplicated by `compactionId`) increments `context.epoch.<bot>`. The next turn
  re-renders the snapshot, and Claude's session is loaded again with the new system prompt. The adapter
  rebuilds its query and resumes the same conversation.
- **Profile edits mid-session** (name, description, skills) are sent once as an `<agent_profile_update>` block
  appended to the next message. After that turn reaches the agent, the change is recorded in
  `snapshot.announced`. The next compaction folds it into the snapshot.

## Memory (`host/src/chat/memory/`)

- Facts are plain markdown, one `- (YYYY-MM-DD) fact` per line:
  - `profile.md` holds who the user is. All of it goes into the prompt, up to 100 facts. When it grows past
    100, the keeper consolidates it to at most 60 (merging duplicates, dropping superseded facts); every fact it
    drops moves to the log, so nothing is lost.
  - `log/YYYY-MM.md` holds dated history, including `[note]` and `[episode]` lines. The newest 30 go into the
    prompt, within 4,000 characters.
- **Keeper:** after a turn ends normally, if the user's message is memorable (not "thanks"/"ok"), the exchange
  is queued (`memory.unprocessed.<bot>`, so a restart keeps it). Once the bot has been quiet for 5 minutes, or 8
  exchanges are queued, a one-shot agent of the bot's own harness extracts facts from all of them at once using
  Grok Bot's extraction prompt (`profile:` / `log:` / `note:` / `remove:`). Running per exchange would buy
  nothing: the frozen prompt only picks up new facts at the next compaction or session.
  - On Claude it runs with a replaced system prompt, no tools, no settings, `persistSession: false` and the
    `haiku` model.
  - Other harnesses get the instructions inline, in `~/.codync/memory-keeper`.
- **History search:** the built-in `memory` MCP server (`codync-host mcp memory`) gives the bot
  `search_history`, a substring search (every word must appear) over its own chat: the user's messages and its
  final replies, main chat and threads. Whatever the keeper didn't write down can still be found.
- **Episodes:** every 6 remembered exchanges (pending turns in `memory.episode.<bot>`), the keeper writes one
  `[episode]` journal sentence.
- **Automatic names** (`chat/naming.rs`): a bot created without a name is called "New Bot" with `autoName` set;
  clients don't ask for a name when creating one. From its 3rd remembered exchange the keeper shows the last 6
  (`naming.turns.<bot>`) to the same one-shot agent and asks for a 1–4 word name in the user's language, retrying
  after each exchange until the purpose is clear. The name goes through `updateBot`, so the agent gets it as a
  profile update. The description is never touched (it holds standing instructions). Renaming the bot yourself
  clears `autoName` for good.
- The agent is told where its memory folder is so it can grep older facts. Facts learned mid-session reach its
  prompt at the next compaction or session.
- **API:** `memory`, `forgetMemory`, `clearMemory`. The Memory card in bot settings lists and removes facts.

## Turns

- Messages sent while the agent works are folded into one next turn, joined by blank lines.
- Bot-to-bot requests occupy separate turns in the same queue; user messages are only folded together up to the next request. Requests never feed the user-fact memory keeper. See [bot collaboration](bot-collaboration.md).
- Delegated turns do not set the restart marker: their waiter disappears on restart. Pending delegation notices become interrupted errors instead of automatically replaying work.
- `turn.inflight.<bot>` records the running turn start and its thread lane. If the host stops mid-turn (update, crash, restart), the next
  start resumes that session with a hidden "you were interrupted, don't repeat finished steps" prompt. If the
  session can't be resumed, or the turn started over an hour ago, it does nothing.

## Limits

- Harnesses other than Claude have no system prompt channel and don't report compaction. For them, the first
  message carries the instructions and the snapshot only refreshes when a new session starts.
- Compaction is the harness's own. Codync can't choose what survives it, only re-apply its instructions
  afterwards.
- A session a harness has deleted (for example, one cleaned up after a month) can't be resumed. The bot
  starts fresh, but its memory remains.
- User-level memory shared across bots and project memory are not implemented. Consolidation runs only when the
  profile outgrows the prompt, not as Grok Bot's daily "dreaming" pass.
- `search_history` covers the bot's own chat, not group chats it took part in.

Reply threads have separate session/context lanes; see [groups and threads](groups-and-threads.md). Group/delegated requests do not become user facts in the memory keeper.
