"""Self-check for asc-submit.py's What's New parsing: python3 tools/asc-submit-test.py"""

import importlib.util
import pathlib
import sys
import types

sys.modules.setdefault("jwt", types.ModuleType("jwt"))
spec = importlib.util.spec_from_file_location("asc_submit", pathlib.Path(__file__).with_name("asc-submit.py"))
asc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(asc)

template = pathlib.Path(__file__).parent.parent.joinpath(".github/pull_request_template.md").read_text()
filled = template.replace("### zh-Hant\n\n-", "### zh-Hant\n\n- 例行任務改用同一個表單").replace(
    "### en-US\n\n-", "### en-US\n\n- Routines use one form")
prs = [
    {"body": filled.replace("\n", "\r\n"), "mergeCommit": {"oid": "a"}},
    {"body": filled, "mergeCommit": {"oid": "b"}},  # duplicate bullet is dropped
    {"body": template, "mergeCommit": {"oid": "c"}},  # untouched template adds nothing
    {"body": filled.replace("Routines", "Old"), "mergeCommit": {"oid": "z"}},  # not in this release
    {"body": None, "mergeCommit": None},
]
assert asc.pr_notes(prs, {"a", "b", "c"}) == {
    "zh-Hant": "- 例行任務改用同一個表單", "en-US": "- Routines use one form"}, asc.pr_notes(prs, {"a", "b", "c"})
assert asc.pr_notes(prs, {"c"}) == {}
print("ok")
