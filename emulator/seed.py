"""Seed the LOCAL Firebase emulator (project demo-fariin) with two fake users.

Nothing here can reach the real project: the URLs are 127.0.0.1 and the project id is demo-fariin.
Accounts and their generated passwords go to test-accounts.json next to this file (git-ignored).

    python seed.py              # new accounts
    python seed.py --profiles   # refresh the existing accounts' profile fields
"""
import datetime, json, os, secrets, sys, urllib.request

AUTH = "http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1"
FS = "http://127.0.0.1:8080/v1/projects/demo-fariin/databases/(default)/documents"
HERE = os.path.dirname(os.path.abspath(__file__))
PEOPLE = [("alice", "Alice Test", "alice@test.fariin.local"), ("bobby", "Bobby Test", "bobby@test.fariin.local")]


def post(url, body, headers=None):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read() or b"{}")


def val(v):
    if isinstance(v, dict) and "__ts" in v:
        return {"timestampValue": v["__ts"]}
    if v is None:
        return {"nullValue": None}
    if isinstance(v, str):
        return {"stringValue": v}
    if isinstance(v, list):
        return {"arrayValue": {"values": [val(x) for x in v]}}
    if isinstance(v, dict):
        return {"mapValue": {"fields": {k: val(x) for k, x in v.items()}}}
    raise TypeError(v)


def patch_doc(path, fields):
    body = {"fields": {k: val(v) for k, v in fields.items()}}
    mask = "&".join(f"updateMask.fieldPaths={k}" for k in fields)   # merge, never wipe other fields
    req = urllib.request.Request(f"{FS}/{path}?{mask}", data=json.dumps(body).encode(), method="PATCH",
                                 headers={"Content-Type": "application/json", "Authorization": "Bearer owner"})
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read())


def seed_profile(uid, handle, name):
    """What a real iOS sign-up leaves behind: the server's claimUsername writes handle, handleLower
    and usernameHistory on users/{uid} plus the held usernames/{lower} record (functions-username
    heldRecord), then ProfileStore.save sets name (and about) with a merge. The phone later adds
    publicKey, devices and push tokens itself."""
    now = {"__ts": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")}
    patch_doc(f"users/{uid}", {"name": name, "about": "", "handle": handle, "handleLower": handle,
                               "usernameHistory": [{"username": handle, "at": now}]})
    patch_doc(f"usernames/{handle}", {"uid": uid, "kind": "user", "state": "held", "username": handle,
                                      "claimedAt": now, "releasedAt": None, "prevUid": None,
                                      "reservedFor": None, "reason": None})


if sys.argv[1:] == ["--profiles"]:
    # Refresh the profile fields of the accounts already made (no new sign-ups).
    for a in json.load(open(os.path.join(HERE, "test-accounts.json"))):
        seed_profile(a["uid"], a["handle"], a["name"])
    print("profiles refreshed")
    sys.exit(0)

out = []
for handle, name, email in PEOPLE:
    pw = secrets.token_urlsafe(12)
    r = post(f"{AUTH}/accounts:signUp?key=fake-api-key", {"email": email, "password": pw, "returnSecureToken": True})
    uid = r["localId"]
    seed_profile(uid, handle, name)
    out.append({"uid": uid, "handle": handle, "name": name, "email": email, "password": pw})

with open(os.path.join(HERE, "test-accounts.json"), "w") as f:
    json.dump(out, f, indent=2)
print("seeded:", [(p["handle"], p["uid"]) for p in out])
