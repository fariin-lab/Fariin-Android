"""A LOCAL stand-in for three read-mostly channel callables, for testing the Android app against the
Firestore emulator (project demo-fariin). NOT the real server: it answers in the server's shape
(Kulan/functions-groups) so the app's Find Channels and the visitor page can be driven locally.

    python -I functions_stub.py        # listens on 0.0.0.0:5001, where the debug app points callables

Answers:
    discoverChannels   channelDirectory/all rows (seed.py --channel), each checked live: channel and public
    publicGroupPreview the channel's header by cid or @name (followers + admins count, isMember, createdAt)
    makeChannel        a group of mine, alone in it, gets the channel fields (channels.channelFields)
    createCallLink / updateCallLink / deleteCallLink / peekCallLink   call links (no media server: nobody is ever in the call)
    listChannelFollowers / removeChannelFollower / makeChannelAdmin / dismissChannelAdmin   channel followers and admins (test stub, see below)
Every other callable answers NOT_FOUND, exactly as before this stub existed (follow, react and views
are NOT emulated here: those stay untested locally).
Reads and writes go to 127.0.0.1:8080 with the emulator's admin token; nothing leaves this PC.
"""
import base64, json, urllib.parse, urllib.request
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


# Call links, in the shape of Kulan/functions index.js createCallLink / updateCallLink /
# deleteCallLink / peekCallLink, so the Android create / list / details / join pages can be driven
# locally. No media server here: peek says nobody is in the call, and groupCallToken stays
# NOT_FOUND (joining is not emulated).
def _room(data):
    r = str((data or {}).get("roomId") or "")
    if len(r) != 64 or any(ch not in "0123456789abcdef" for ch in r):
        raise Refusal("INVALID_ARGUMENT", "roomId required")
    return r


def create_link(uid, data):
    r = _room(data)
    cur = get(f"callLinks/{r}")
    if cur and cur.get("creatorUid") != uid:
        raise Refusal("ALREADY_EXISTS", "Link already exists")
    if not cur:
        patch(f"callLinks/{r}", {"creatorUid": uid, "encName": str(data.get("encName") or ""),
                                 "restrictions": str(data.get("restrictions") or "adminApproval"),
                                 "video": data.get("video") is not False, "revoked": False, "active": False})
    return {"ok": True}


def update_link(uid, data):
    r = _room(data)
    cur = get(f"callLinks/{r}")
    if not cur:
        raise Refusal("NOT_FOUND", "This call link is no longer valid")
    if cur.get("creatorUid") != uid:
        raise Refusal("PERMISSION_DENIED", "Only the link creator can do this")
    f = {k: data[k] for k in ("encName", "restrictions", "video", "revoked") if k in data}
    if f:
        patch(f"callLinks/{r}", f)
    return {"ok": True}


def delete_link(uid, data):
    r = _room(data)
    cur = get(f"callLinks/{r}")
    if not cur:
        raise Refusal("NOT_FOUND", "This call link is no longer valid")
    if cur.get("creatorUid") != uid:
        raise Refusal("PERMISSION_DENIED", "Only the link creator can do this")
    urllib.request.urlopen(urllib.request.Request(f"{FS}/callLinks/{r}", method="DELETE", headers=ADMIN)).read()
    return {"ok": True}


def peek_link(uid, data):
    r = _room(data)
    cur = get(f"callLinks/{r}")
    if not cur or cur.get("revoked") is True:
        return {"gone": True, "approval": False, "video": True, "count": 0, "names": [], "full": False,
                "creator": bool(cur and cur.get("creatorUid") == uid), "encName": ""}
    return {"gone": False, "approval": cur.get("restrictions") == "adminApproval", "video": cur.get("video") is not False,
            "count": 0, "names": [], "full": False, "creator": cur.get("creatorUid") == uid, "encName": cur.get("encName") or ""}



# ---- Channel followers and admins: a TEST STUB of functions-groups listChannelFollowers /
# removeChannelFollower / makeChannelAdmin / dismissChannelAdmin (index.js, lib/channels.js), so the
# Android Followers and Administrators pages can be driven locally. It writes the same documents the
# real functions write, without a transaction, and moves followerCount at once (the real ones go
# through the count buffer, flushChannelCounts, which lands it within a minute). Reactions carried
# between the follow entry and channelRx (rxHandover) are left out. Never deployed.
CHANNEL_ADMIN_RIGHTS = ["changeInfo", "deleteMessages", "banUsers", "inviteUsers", "pinMessages", "postStories"]
DELETE = object()


class Ts:
    """A server timestamp (now) for wval."""


def wval(v):
    import datetime
    if v is None: return {"nullValue": None}
    if isinstance(v, Ts):
        return {"timestampValue": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")}
    if isinstance(v, dict) and "__ts" in v: return {"timestampValue": v["__ts"]}
    if isinstance(v, bool): return {"booleanValue": v}
    if isinstance(v, int): return {"integerValue": str(v)}
    if isinstance(v, float): return {"doubleValue": v}
    if isinstance(v, list): return {"arrayValue": {"values": [wval(x) for x in v]}}
    if isinstance(v, dict): return {"mapValue": {"fields": {k: wval(x) for k, x in v.items()}}}
    return {"stringValue": str(v)}


def write(path, fields):
    """PATCH with field paths ("a" or "a.b"); DELETE drops that path."""
    body, mask = {}, []
    for k, v in fields.items():
        parts = k.split(".")
        mask.append("updateMask.fieldPaths=" + ".".join("`" + x + "`" for x in parts))
        if v is DELETE:
            continue
        if len(parts) == 1:
            body[k] = wval(v)
        else:
            m = body.setdefault(parts[0], {"mapValue": {"fields": {}}})
            m["mapValue"]["fields"][parts[1]] = wval(v)
    data = json.dumps({"fields": body}).encode()
    q = "&".join(urllib.parse.quote(m, safe="=.") for m in mask)
    urllib.request.urlopen(urllib.request.Request(f"{FS}/{path}?{q}", data=data, method="PATCH", headers=ADMIN)).read()


def remove(path):
    try:
        urllib.request.urlopen(urllib.request.Request(f"{FS}/{path}", method="DELETE", headers=ADMIN)).read()
    except urllib.error.HTTPError:
        pass


def list_docs(path):
    with urllib.request.urlopen(urllib.request.Request(f"{FS}/{path}?pageSize=1000", headers=ADMIN)) as r:
        docs = json.loads(r.read()).get("documents", [])
    return [(d["name"].rsplit("/", 1)[-1], {a: unval(b) for a, b in d.get("fields", {}).items()}) for d in docs]


def rights_of(cd, uid):
    if not cd or not uid: return []
    if cd.get("createdBy") == uid: return CHANNEL_ADMIN_RIGHTS + ["addAdmins"]
    if uid not in cd.get("admins", []): return []
    r = (cd.get("adminRights") or {}).get(uid)
    return [str(x) for x in r] if isinstance(r, list) else list(CHANNEL_ADMIN_RIGHTS)


def channel_admin(cd, uid):
    return bool(cd) and cd.get("channel") is True and (uid in cd.get("admins", []) or cd.get("createdBy") == uid)


def _cid_uid(data, key="uid"):
    cid, who = str((data or {}).get("cid") or ""), str((data or {}).get(key) or "")
    if not cid or "/" in cid or not who or "/" in who:
        raise Refusal("INVALID_ARGUMENT", "Missing cid or uid.")
    return cid, who


def _profile(uid):
    u = get(f"users/{uid}")
    gone = not u or u.get("banned") is True or bool(u.get("deletionScheduledFor"))
    if gone:
        return "Deleted Account", ""
    return str(u.get("name") or u.get("handle") or "Fariin user")[:64], str(u.get("photoUrl") or "")


def _bump_followers(cid, cd, delta):
    write(f"conversations/{cid}", {"followerCount": max(0, int(cd.get("followerCount", 0)) + delta)})


def list_followers(uid, data):
    cid = str((data or {}).get("cid") or "")
    after = str((data or {}).get("after") or "")
    if not cid or "/" in cid: raise Refusal("INVALID_ARGUMENT", "Missing cid.")
    if not channel_admin(get(f"conversations/{cid}"), uid):
        raise Refusal("PERMISSION_DENIED", "Only admins can see followers.")
    rows = sorted(list_docs(f"conversations/{cid}/followers"), key=lambda r: -ms(r[1].get("at")))
    if after:
        ids = [r[0] for r in rows]
        if after in ids: rows = rows[ids.index(after) + 1:]
    page = rows[:50]
    out = []
    for fid, f in page:
        name, photo = _profile(fid)
        out.append({"uid": fid, "name": name, "photoUrl": photo, "at": ms(f.get("at"))})
    return {"followers": out, "more": len(page) == 50}


def remove_follower(uid, data):
    cid, who = _cid_uid(data)
    ban = (data or {}).get("ban") is True
    cd = get(f"conversations/{cid}")
    if not (cd and cd.get("channel") is True and "banUsers" in rights_of(cd, uid)):
        raise Refusal("PERMISSION_DENIED", "Only admins who can ban users can remove followers.")
    if who in cd.get("users", []):
        raise Refusal("FAILED_PRECONDITION", "That's an admin, not a follower.")
    remove(f"users/{who}/channels/{cid}")
    if get(f"conversations/{cid}/followers/{who}") is not None:
        remove(f"conversations/{cid}/followers/{who}")
        _bump_followers(cid, cd, -1)
    if ban and who not in cd.get("bannedUids", []):
        write(f"conversations/{cid}", {"bannedUids": cd.get("bannedUids", []) + [who]})
    return {"ok": True}


def make_admin(uid, data):
    cid, target = _cid_uid(data)
    if target == uid: raise Refusal("INVALID_ARGUMENT", "Missing channel or person.")
    cd = get(f"conversations/{cid}")
    if not cd or cd.get("channel") is not True: raise Refusal("NOT_FOUND", "This channel no longer exists.")
    if "addAdmins" not in rights_of(cd, uid):
        raise Refusal("PERMISSION_DENIED", "Only the owner, or an admin allowed to add admins, can add admins.")
    if target in cd.get("users", []): return {"state": "already"}
    if target in cd.get("bannedUids", []): raise Refusal("PERMISSION_DENIED", "This person is banned from the channel.")
    if get(f"conversations/{cid}/followers/{target}") is None:
        raise Refusal("FAILED_PRECONDITION", "Only a follower of the channel can be made an admin.")
    name, photo = _profile(target)
    held = set(rights_of(cd, uid))
    upd = {"users": cd.get("users", []) + [target], "admins": [a for a in cd.get("admins", []) if a != target] + [target],
           f"adminRights.{target}": [r for r in CHANNEL_ADMIN_RIGHTS if r in held], f"adminBy.{target}": uid,
           f"names.{target}": name, f"unreadCount.{target}": 0}
    if photo: upd[f"photos.{target}"] = photo
    remove(f"users/{target}/channels/{cid}")
    remove(f"conversations/{cid}/followers/{target}")
    write(f"conversations/{cid}", upd)
    _bump_followers(cid, cd, -1)
    return {"state": "admin"}


def dismiss_admin(uid, data):
    cid, who = _cid_uid(data)
    cd = get(f"conversations/{cid}")
    ok = bool(cd) and cd.get("channel") is True and uid != who and cd.get("createdBy") != who and who in cd.get("admins", []) \
        and (cd.get("createdBy") == uid or ("addAdmins" in rights_of(cd, uid) and (cd.get("adminBy") or {}).get(who) == uid))
    if not ok: raise Refusal("PERMISSION_DENIED", "You can't dismiss this admin.")
    upd = {"users": [u for u in cd.get("users", []) if u != who], "admins": [a for a in cd.get("admins", []) if a != who],
           "updatedAt": Ts()}
    for k in ("adminRights", "adminBy", "unreadCount", "roles"):
        upd[f"{k}.{who}"] = DELETE
    remove(f"users/{who}/channelRx/{cid}")
    had = get(f"conversations/{cid}/followers/{who}") is not None
    if not had: write(f"conversations/{cid}/followers/{who}", {"at": Ts()})
    if get(f"users/{who}/channels/{cid}") is None:
        write(f"users/{who}/channels/{cid}", {"at": Ts(), "read": int(cd.get("postCount", 0)), "muted": False, "mutedUntil": 0})
    write(f"conversations/{cid}", upd)
    if not had: _bump_followers(cid, cd, 1)
    return {"ok": True}


HANDLERS = {"discoverChannels": discover, "publicGroupPreview": preview, "makeChannel": make_channel,
            "createCallLink": create_link, "updateCallLink": update_link, "deleteCallLink": delete_link,
            "peekCallLink": peek_link,
            "listChannelFollowers": list_followers, "removeChannelFollower": remove_follower,
            "makeChannelAdmin": make_admin, "dismissChannelAdmin": dismiss_admin}


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
            code, out = {"NOT_FOUND": 404, "PERMISSION_DENIED": 403, "FAILED_PRECONDITION": 400, "UNAUTHENTICATED": 401, "ALREADY_EXISTS": 409, "INVALID_ARGUMENT": 400}.get(r.status, 400), \
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
