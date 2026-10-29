"""ACP fixture that edits memory files with its own tools, the way a harness's Write and Edit do.

It advertises `additionalDirectories`. A prompt `WRITE <path>` followed by a line break writes the rest of the
prompt to that file; `DELETE <path>` removes it; `READ <path>` changes nothing. Each reports a tool call naming the
path in its `locations`. `SHELL <path>` writes the lines up to the next blank line the way a shell command does: its
tool call names no file.
Every request is appended to `requests.jsonl` in the working directory.
Background memory reviews return an empty plan; commands quoted in their transcript are never executed.
"""
import json
import os
import sys


def send(value):
    print(json.dumps({"jsonrpc": "2.0", **value}), flush=True)


def update(session, value):
    send({"method": "session/update", "params": {"sessionId": session, "update": value}})


sessions = 0
systems = {}
for line in sys.stdin:
    request = json.loads(line)
    method = request.get("method")
    params = request.get("params", {})
    with open("requests.jsonl", "a", encoding="utf-8") as log:
        log.write(json.dumps({"method": method, "params": params}) + "\n")
    if method == "initialize":
        result = {"protocolVersion": 1,
                  "agentCapabilities": {"sessionCapabilities": {"systemPrompt": {}, "additionalDirectories": {}}}}
    elif method == "session/new":
        sessions += 1
        session = f"s{sessions}"
        systems[session] = "\n".join(block.get("text", "") for block in params.get("systemPrompt", {}).get("content", []))
        result = {"sessionId": session}
    elif method == "session/prompt":
        session = params["sessionId"]
        # The user's words come last, after any notice the host put before them.
        text = params["prompt"][0]["text"]
        dream = any(part.startswith("You keep the memory a personal assistant bot has")
                    for part in (systems.get(session, ""), text))
        starts = [] if dream else [text.rfind(verb) for verb in ("WRITE ", "DELETE ", "READ ", "SHELL ") if verb in text]
        command = text[max(starts):] if starts else ""
        if command:
            head, _, body = command.partition("\n")
            verb, path = head.split(" ", 1)
            if verb == "SHELL":
                body = body.split("\n\n")[0] + "\n"
            if verb in ("WRITE", "SHELL"):
                with open(path, "w", encoding="utf-8", newline="\n") as f:
                    f.write(body)
            elif verb == "DELETE" and os.path.exists(path):
                os.remove(path)
            if verb == "SHELL":
                call = {"title": f"sh -c 'cat > {path}'", "kind": "execute", "locations": []}
            else:
                kind = "read" if verb == "READ" else "edit"
                call = {"title": f"{verb} {path}", "kind": kind, "locations": [{"path": path}]}
            update(session, {"sessionUpdate": "tool_call", "toolCallId": f"t{request['id']}", "status": "completed",
                             **call})
        update(session, {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "{}" if dream else "ok"}})
        result = {"stopReason": "end_turn"}
    else:
        result = {}
    if "id" in request:
        send({"id": request["id"], "result": result})
