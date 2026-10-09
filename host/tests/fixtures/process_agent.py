"""Keep two descendant generations holding ACP stdout after the leader exits."""

import json
import subprocess
import sys
import time

if len(sys.argv) > 1:
    if sys.argv[1] == "child":
        subprocess.Popen([sys.executable, __file__, "leaf"])
    if sys.argv[1] == "leaf":
        print(json.dumps({"jsonrpc": "2.0", "method": "ready"}), flush=True)
    time.sleep(60)
else:
    subprocess.Popen([sys.executable, __file__, "child"])
    for line in sys.stdin:
        message = json.loads(line)
        if message.get("method") == "exit":
            break
