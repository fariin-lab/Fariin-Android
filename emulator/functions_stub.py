"""A LOCAL stand-in for three read-mostly channel callables, for testing the Android app against the
Firestore emulator (project demo-fariin). NOT the real server: it answers in the server's shape
(Kulan/functions-groups) so the app's Find Channels and the visitor page can be driven locally.

    python -I functions_stub.py        # listens on 0.0.0.0:5001, where the debug app points callables

Answers:
    discoverChannels   channelDirectory/all rows (seed.py --channel), each checked live: channel and public
    publicGroupPreview the channel's header by cid or @name (followers + admins count, isMember, createdAt)
    makeChannel        a group of mine, alone in it, gets the channel fields (channels.channelFields)
Every other callable answers NOT_FOUND, exactly as before this stub existed (follow, react and views
are NOT emulated here: those stay untested locally).
Reads and writes go to 127.0.0.1:8080 with the emulator's admin token; nothing leaves this PC.
"""
import base64, json, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

FS = "http://127.0.0.1:8080/v1/projects/demo-fariin/databases/(default)/documents"
ADMIN = {"Authorization": "Bearer owner", "Content-Type": "application/json"}


def unval(v):
    k, x = next(iter(v.items()))
    if k == "mapValue":
        return {a: unval(b) for a, b in x.get("fields", {}).items()}
    if k == "arrayValue":
        return [unval(b) for b in x.get("values", [])]
    if k == "integerValue":
        return int(x)
    if k == "timestampValue":
        return {"__ts": x}
    return x


def get(path):
    try:
        with urllib.request.urlopen(urllib.request.Request(f"{FS}/{path}", headers=ADMIN)) as r:
            return {a: unval(b) for a, b in json.loads(r.read()).get("fields", {}).items()}
    except urllib.error.HTTPError:
        return None


def patch(path, fields):
    def val(v):
        if isinstance(v, bool): return {"booleanValue": v}
        if isinstance(v, int): return {"integerValue": str(v)}
        return {"stringValue": str(v)}
    mask = "&".join(f"updateMask.fieldPaths={k}" for k in fields)
    body = json.dumps({"fields": {k: val(v) for k, v in fields.items()}}).encode()
    urllib.request.urlopen(urllib.request.Request(f"{FS}/{path}?{mask}", data=body, method="PATCH", headers=ADMIN)).read()


def caller(headers):
    """The uid in the emulator's unsigned ID token (Auth emulator tokens are not verified here)."""
    auth = headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        return None
    try:
        part = auth[7:].split(".")[1]
        claims = json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
        return claims.get("user_id") or claims.get("sub")
    except Exception:
        return None


class Refusal(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message


NOT_PUBLIC = "This is private now. Ask one of its admins for an invite link."


def ms(ts):
    import datetime
    if isinstance(ts, dict) and "__ts" in ts:
        t = ts["__ts"].replace("Z", "+00:00")
        if "." in t:
            head, tail = t.split(".", 1)
            frac, zone = tail[:tail.index("+")], tail[tail.index("+"):]
            t = f"{head}.{frac[:6].ljust(6, '0')}{zone}"
        return int(datetime.datetime.fromisoformat(t).timestamp() * 1000)
    return 0


def discover(uid, data):
    rows = (get("channelDirectory/all") or {}).get("rows", [])
    out = []
    for r in rows:
        c = get(f"conversations/{r['cid']}") or {}
        if c.get("channel") is True and c.get("isPublic") is True:
            out.append({k: v for k, v in r.items() if k not in ("score", "country")} | {"local": False})
    return {"country": (data or {}).get("country"), "channels": out[:30]}


def preview(uid, data):
    data = data or {}
    cid = data.get("cid") or ""
    name = (data.get("username") or "").lower()
    if name:
        rec = get(f"usernames/{name}") or {}
        if rec.get("kind") != "group" or rec.get("state") != "held":
            raise Refusal("NOT_FOUND", NOT_PUBLIC)
        cid = rec.get("cid", "")
    c = get(f"conversations/{cid}") if cid and "/" not in cid else None
    if not c or c.get("type") != "group" or c.get("isPublic") is not True or not c.get("username"):
        raise Refusal("NOT_FOUND", NOT_PUBLIC)
    users = c.get("users", [])
    if uid in c.get("bannedUids", []):
        raise Refusal("PERMISSION_DENIED", "You're banned from this group.")
    out = {"cid": cid, "username": c.get("username", ""), "title": c.get("title", ""), "photoUrl": c.get("avatarUrl", ""),
           "description": c.get("desc", ""), "memberCount": len(users), "isMember": uid in users, "needsApproval": False,
           "requested": False, "verified": c.get("verified") is True, "label": "", "members": []}
    if c.get("channel") is True:
        fc = max(0, int(c.get("followerCount", 0)))
        out |= {"channel": True, "followerCount": fc, "memberCount": fc + len(users),
                "isMember": uid in users or get(f"conversations/{cid}/followers/{uid}") is not None,
                "createdAt": ms(c.get("createdAt"))}
    return out


def make_channel(uid, data):
    cid = (data or {}).get("cid") or ""
    c = get(f"conversations/{cid}") if cid and "/" not in cid else None
    if not c or c.get("type") != "group":
        raise Refusal("NOT_FOUND", "No such group.")
    if c.get("channel") is True:
        return {"ok": True, "cid": cid}
    if c.get("createdBy") != uid or c.get("users") != [uid]:
        raise Refusal("FAILED_PRECONDITION", "A channel starts with only its creator.")
    fields = {"channel": True, "onlyAdminsSend": True, "hideMembers": True, "hideMembersBy": "channel", "followerCount": 0}
    country = str((data or {}).get("country") or "").upper()
    if len(country) == 2 and country.isalpha():
        fields["country"] = country
    patch(f"conversations/{cid}", fields)
    return {"ok": True, "cid": cid}


HANDLERS = {"discoverChannels": discover, "publicGroupPreview": preview, "makeChannel": make_channel}


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        name = self.path.rstrip("/").split("/")[-1]
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        uid = caller(self.headers)
        try:
            if name not in HANDLERS:
                raise Refusal("NOT_FOUND", "Not emulated locally.")
            if not uid:
                raise Refusal("UNAUTHENTICATED", "Sign in required.")
            code, out = 200, {"result": HANDLERS[name](uid, body.get("data"))}
        except Refusal as r:
            code, out = {"NOT_FOUND": 404, "PERMISSION_DENIED": 403, "FAILED_PRECONDITION": 400, "UNAUTHENTICATED": 401}.get(r.status, 400), \
                {"error": {"status": r.status, "message": r.message}}
        raw = json.dumps(out).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)
        print(name, uid, code, flush=True)

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 5001), Handler).serve_forever()
