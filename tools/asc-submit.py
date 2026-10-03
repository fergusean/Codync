#!/usr/bin/env python3
"""Submit the iOS build of a version for App Review once Xcode Cloud has uploaded it.

Usage: asc-submit.py 2.3.0
Env: ASC_KEY_ID, ASC_ISSUER_ID, ASC_PRIVATE_KEY (.p8 contents); DRY_RUN=1 prints writes instead;
SINCE (ISO 8601) ignores builds uploaded before it, so a tag run never submits an older build.

What's New comes from the version's section in apps/ios/WhatsNew.md; without one, empty fields
get a generic line.

Latest version wins: a version still waiting for review is pulled back, renamed and
resubmitted with the new build. A version already in review is left alone.
"""

import json
import os
import sys
import time
import urllib.error
import re
import urllib.request
from datetime import datetime
from pathlib import Path

import jwt

APP_ID = "6760984418"
API = "https://api.appstoreconnect.apple.com/v1"
WHATS_NEW = {"en-US": "Bug fixes and improvements.", "zh-Hant": "錯誤修正與改進。"}
EDITABLE = {"PREPARE_FOR_SUBMISSION", "READY_FOR_REVIEW", "DEVELOPER_REJECTED", "REJECTED",
            "METADATA_REJECTED", "INVALID_BINARY"}
DONE = {"READY_FOR_DISTRIBUTION", "REPLACED_WITH_NEW_VERSION", "REMOVED_FROM_SALE"}
DRY_RUN = os.environ.get("DRY_RUN") == "1"
NOTES = Path(__file__).resolve().parent.parent / "apps/ios/WhatsNew.md"


def release_notes(version):
    """{locale: text} from the `## <version>` section, split by `### <locale>` headings."""
    text = NOTES.read_text() if NOTES.exists() else ""
    section = re.search(rf"^## {re.escape(version)}\s*$(.*?)(?=^## |\Z)", text, re.M | re.S)
    if not section:
        return {}
    parts = re.split(r"^### +(\S+)\s*$", section.group(1), flags=re.M)[1:]
    return {loc: body.strip() for loc, body in zip(parts[::2], parts[1::2]) if body.strip()}


def token():
    now = int(time.time())
    return jwt.encode(
        {"iss": os.environ["ASC_ISSUER_ID"], "iat": now, "exp": now + 1200, "aud": "appstoreconnect-v1"},
        os.environ["ASC_PRIVATE_KEY"], algorithm="ES256", headers={"kid": os.environ["ASC_KEY_ID"]})


def call(method, path, body=None):
    if method != "GET" and DRY_RUN:
        print(f"DRY_RUN {method} {path} {json.dumps(body)}")
        return {"data": {"id": "dry-run"}}
    req = urllib.request.Request(
        API + path, method=method, data=json.dumps(body).encode() if body else None,
        headers={"Authorization": f"Bearer {token()}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req) as res:
            raw = res.read()
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {path} -> {e.code}\n{e.read().decode()}")
    return json.loads(raw) if raw else {}


def wait_for(what, check, minutes):
    for _ in range(minutes):
        if result := check():
            return result
        print(f"waiting for {what}…", flush=True)
        time.sleep(60)
    sys.exit(f"timed out waiting for {what}")


def main(version):
    since = datetime.fromisoformat(os.environ.get("SINCE", "2000-01-01T00:00:00Z"))
    build = wait_for(f"build {version} to finish processing", lambda: next(iter(
        b for b in call("GET", f"/builds?filter[app]={APP_ID}&filter[preReleaseVersion.version]={version}"
                        "&filter[processingState]=VALID&sort=-uploadedDate&limit=1")["data"]
        if datetime.fromisoformat(b["attributes"]["uploadedDate"]) >= since), None), 150)
    print(f"build {build['attributes']['version']} ({build['id']})")

    def in_flight():
        versions = call("GET", f"/apps/{APP_ID}/appStoreVersions?filter[platform]=IOS&limit=10"
                        "&fields[appStoreVersions]=versionString,appVersionState")["data"]
        return next((v for v in versions if v["attributes"]["appVersionState"] not in DONE), None)

    current = in_flight()
    state = current and current["attributes"]["appVersionState"]
    if state == "WAITING_FOR_REVIEW":
        for s in call("GET", f"/reviewSubmissions?filter[app]={APP_ID}&filter[platform]=IOS"
                      "&filter[state]=WAITING_FOR_REVIEW")["data"]:
            print(f"pulling {current['attributes']['versionString']} back from review")
            call("PATCH", f"/reviewSubmissions/{s['id']}",
                 {"data": {"type": "reviewSubmissions", "id": s["id"], "attributes": {"canceled": True}}})
        if not DRY_RUN:
            current = wait_for("the pulled version to become editable",
                               lambda: (v := in_flight()) and v["attributes"]["appVersionState"] in EDITABLE and v, 30)
            state = current["attributes"]["appVersionState"]
    if current and state not in EDITABLE and not DRY_RUN:
        print(f"{current['attributes']['versionString']} is {state}; leaving it alone. "
              "Rerun this workflow once it is released.")
        return

    if current:
        if current["attributes"]["versionString"] != version:
            call("PATCH", f"/appStoreVersions/{current['id']}", {"data": {
                "type": "appStoreVersions", "id": current["id"], "attributes": {"versionString": version}}})
        version_id = current["id"]
    else:
        version_id = call("POST", "/appStoreVersions", {"data": {
            "type": "appStoreVersions",
            "attributes": {"platform": "IOS", "versionString": version, "releaseType": "AFTER_APPROVAL"},
            "relationships": {"app": {"data": {"type": "apps", "id": APP_ID}}}}})["data"]["id"]

    notes = release_notes(version)
    print(f"What's New from WhatsNew.md: {sorted(notes) or 'none, generic text'}")
    if version_id != "dry-run":
        for loc in call("GET", f"/appStoreVersions/{version_id}/appStoreVersionLocalizations")["data"]:
            locale = loc["attributes"]["locale"]
            text = notes.get(locale) or notes.get("en-US")
            if not text and not loc["attributes"].get("whatsNew"):
                text = WHATS_NEW.get(locale, WHATS_NEW["en-US"])
            if text:
                call("PATCH", f"/appStoreVersionLocalizations/{loc['id']}", {"data": {
                    "type": "appStoreVersionLocalizations", "id": loc["id"], "attributes": {"whatsNew": text}}})

    call("PATCH", f"/appStoreVersions/{version_id}/relationships/build",
         {"data": {"type": "builds", "id": build["id"]}})

    submissions = call("GET", f"/reviewSubmissions?filter[app]={APP_ID}&filter[platform]=IOS"
                       "&filter[state]=READY_FOR_REVIEW,UNRESOLVED_ISSUES")["data"]
    submission_id = submissions[0]["id"] if submissions else call("POST", "/reviewSubmissions", {"data": {
        "type": "reviewSubmissions", "attributes": {"platform": "IOS"},
        "relationships": {"app": {"data": {"type": "apps", "id": APP_ID}}}}})["data"]["id"]
    items = [] if submission_id == "dry-run" else call(
        "GET", f"/reviewSubmissions/{submission_id}/items?include=appStoreVersion")["data"]
    if not any(i["relationships"]["appStoreVersion"]["data"] for i in items):
        call("POST", "/reviewSubmissionItems", {"data": {"type": "reviewSubmissionItems", "relationships": {
            "reviewSubmission": {"data": {"type": "reviewSubmissions", "id": submission_id}},
            "appStoreVersion": {"data": {"type": "appStoreVersions", "id": version_id}}}}})
    call("PATCH", f"/reviewSubmissions/{submission_id}",
         {"data": {"type": "reviewSubmissions", "id": submission_id, "attributes": {"submitted": True}}})
    print(f"submitted {version} ({build['attributes']['version']}) for review")


if __name__ == "__main__":
    main(sys.argv[1])
