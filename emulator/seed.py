"""Seed the LOCAL Firebase emulator (project demo-fariin) with two fake users.

Nothing here can reach the real project: the URLs are 127.0.0.1 and the project id is demo-fariin.
Accounts and their generated passwords go to test-accounts.json next to this file (git-ignored).

    python seed.py
"""
import json, os, secrets, urllib.request

AUTH = "http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1"
FS = "http://127.0.0.1:8080/v1/projects/demo-fariin/databases/(default)/documents"
HERE = os.path.dirname(os.path.abspath(__file__))
PEOPLE = [("alice", "Alice Test", "alice@test.fariin.local"), ("bobby", "Bobby Test", "bobby@test.fariin.local")]


def post(url, body, headers=None):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read() or b"{}")


def patch_doc(path, fields):
    body = {"fields": {k: {"stringValue": v} for k, v in fields.items()}}
    req = urllib.request.Request(f"{FS}/{path}", data=json.dumps(body).encode(), method="PATCH",
                                 headers={"Content-Type": "application/json", "Authorization": "Bearer owner"})
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read())


out = []
for handle, name, email in PEOPLE:
    pw = secrets.token_urlsafe(12)
    r = post(f"{AUTH}/accounts:signUp?key=fake-api-key", {"email": email, "password": pw, "returnSecureToken": True})
    uid = r["localId"]
    patch_doc(f"users/{uid}", {"name": name, "handle": handle, "handleLower": handle})
    patch_doc(f"usernames/{handle}", {"uid": uid})
    out.append({"uid": uid, "handle": handle, "name": name, "email": email, "password": pw})

with open(os.path.join(HERE, "test-accounts.json"), "w") as f:
    json.dump(out, f, indent=2)
print("seeded:", [(p["handle"], p["uid"]) for p in out])
