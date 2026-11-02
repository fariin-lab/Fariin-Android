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


# ---------------------------------------------------------------------------------------------
# ACCOUNT TEST STUBS (Android account worker, 2026-11-02). Copies of what Kulan/functions-account
# answers for two-step verification and the email change, and functions-loginalert purgeAccountNow
# refusing, so the Android Settings > Account pages and the two-step sign-in door can be driven
# locally. NOT the real server: the hash is a salted sha256, codes are printed to this process's
# output instead of mailed, and refresh tokens are NOT revoked (the emulator session stays).
# The claim shape and the per-sign-in authTimes test are the real ones (setTwoStepClaim,
# twoStepSessionPassed), because the emulator's firestore.rules read them.
import hashlib, os, random, threading, time

_REQ = threading.local()
AUTH = "http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/projects/demo-fariin"
GENERIC_CODE = "That code is not right, or it has expired. Ask for a new one."
GENERIC_PASSWORD = "That password is not right."


class Refuse(Refusal):
    def __init__(self, status, message, details=None):
        super().__init__(status, message)
        self.details = details


def _token():
    auth = (_REQ.headers or {}).get("Authorization", "")
    try:
        part = auth[7:].split(".")[1]
        return json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
    except Exception:
        return {}


def _require_fresh():
    t = _token().get("auth_time", 0)
    if not t:
        raise Refuse("FAILED_PRECONDITION", "Sign in again to continue.")
    if time.time() - t > 5 * 60:
        raise Refuse("FAILED_PRECONDITION", "For your security, sign in again to continue.", {"reauth": True})


def _auth_post(op, body):
    req = urllib.request.Request(f"{AUTH}/{op}", data=json.dumps(body).encode(), method="POST", headers=ADMIN)
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read() or b"{}")


def _user(uid):
    return (_auth_post("accounts:lookup", {"localId": [uid]}).get("users") or [{}])[0]


def _claims(uid):
    raw = _user(uid).get("customAttributes") or "{}"
    try:
        return json.loads(raw)
    except Exception:
        return {}


def _set_claims(uid, claims):
    _auth_post("accounts:update", {"localId": uid, "customAttributes": json.dumps(claims)})


def _set_two_step_claim(uid, state, auth_time=0):
    claims = _claims(uid)
    if state == "off":
        claims.pop("twoStep", None)
    else:
        old = claims.get("twoStep") or {}
        base = [] if state == "reset" else list(old.get("authTimes") or [])
        claims["twoStep"] = {"required": True, "ok": True, "at": int(time.time() * 1000),
                             "authTimes": sorted(set(base + [int(auth_time)]))[-40:]}
    _set_claims(uid, claims)


def _passed(tok):
    c = tok.get("twoStep") or None
    if not c or c.get("required") is not True:
        return True
    if c.get("ok") is not True:
        return False
    t = tok.get("auth_time", 0)
    if isinstance(c.get("authTimes"), list):
        return t in c["authTimes"] or (bool(c.get("legacyAt")) and t * 1000 <= c["legacyAt"])
    return bool(c.get("at")) and t * 1000 <= c["at"]


def _custom_token(uid):
    """An unsigned custom token: the Auth emulator accepts these (the real server signs one)."""
    enc = lambda d: base64.urlsafe_b64encode(json.dumps(d).encode()).rstrip(b"=").decode()
    now = int(time.time())
    return enc({"alg": "none", "typ": "JWT"}) + "." + enc({
        "iss": "stub@demo-fariin.iam.gserviceaccount.com", "sub": "stub@demo-fariin.iam.gserviceaccount.com",
        "aud": "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit",
        "iat": now, "exp": now + 3600, "uid": uid}) + "."


def _hash(pw, salt):
    return hashlib.sha256((salt + pw).encode()).hexdigest()


def _mask(e):
    name, _, domain = str(e).partition("@")
    return f"{name[:1]}{'*' * max(1, min(6, len(name) - 1))}@{domain}" if name and domain else ""


def _looks_like_email(e):
    return "@" in e and "." in e.split("@")[-1] and " " not in e


def _mint(uid, purpose, target):
    code = f"{random.randint(0, 999999):06d}"
    patch(f"accountCodes/{uid}_{purpose}", {"code": code, "target": target, "exp": int(time.time()) + 600})
    print(f"CODE {purpose} {uid} -> {target}: {code}", flush=True)
    return code


def _redeem(uid, purpose, code):
    rec = get(f"accountCodes/{uid}_{purpose}") or {}
    if not rec or str(rec.get("code")) != str(code or "") or int(rec.get("exp", 0)) < time.time():
        raise Refuse("PERMISSION_DENIED", GENERIC_CODE)
    urllib.request.urlopen(urllib.request.Request(f"{FS}/accountCodes/{uid}_{purpose}", method="DELETE", headers=ADMIN)).read()
    return rec.get("target", "")


def two_step_status(uid, data):
    d = get(f"twoStep/{uid}")
    if not d:
        return {"enabled": False, "passed": True}
    return {"enabled": True, "hint": d.get("hint", ""), "recoveryEmail": _mask(d["recoveryEmail"]) if d.get("recoveryEmail") else "",
            "hasRecovery": bool(d.get("recoveryEmail")), "passed": _passed(_token())}


def set_additional_password(uid, data):
    _require_fresh()
    data = data or {}
    pw = data.get("password")
    if not isinstance(pw, str) or not 6 <= len(pw) <= 128:
        raise Refuse("INVALID_ARGUMENT", "Use between 6 and 128 characters.")
    hint = str(data.get("hint") or "").strip()[:120]
    if hint and hint.lower() == pw.lower():
        raise Refuse("INVALID_ARGUMENT", "Your hint cannot be your password.")
    rec = str(data.get("recoveryEmail") or "").strip().lower()
    if rec and not _looks_like_email(rec):
        raise Refuse("INVALID_ARGUMENT", "That does not look like an email address.")
    old = get(f"twoStep/{uid}")
    if old and _hash(str(data.get("current") or ""), old.get("salt", "")) != old.get("hash"):
        raise Refuse("PERMISSION_DENIED", GENERIC_PASSWORD)
    salt = os.urandom(16).hex()
    patch(f"twoStep/{uid}", {"hash": _hash(pw, salt), "salt": salt, "hint": hint,
                             "recoveryEmail": rec or ((old or {}).get("recoveryEmail") or "")})
    _set_two_step_claim(uid, "reset", _token().get("auth_time", 0))
    return {"ok": True, "refreshToken": True}


def verify_additional_password(uid, data):
    d = get(f"twoStep/{uid}")
    if not d:
        return {"ok": True, "refreshToken": False}
    if _hash(str((data or {}).get("password") or ""), d.get("salt", "")) != d.get("hash"):
        raise Refuse("PERMISSION_DENIED", GENERIC_PASSWORD)
    _set_two_step_claim(uid, "passed", _token().get("auth_time", 0))
    return {"ok": True, "refreshToken": True}


def renew_two_step_session(uid, data):
    if _passed(_token()):
        return {"ok": True, "refreshToken": False}
    raw = str((data or {}).get("idToken") or "")
    try:
        part = raw.split(".")[1]
        prev = json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
    except Exception:
        raise Refuse("PERMISSION_DENIED", GENERIC_PASSWORD)
    if (prev.get("user_id") or prev.get("sub")) != uid or not _passed(prev) or prev.get("exp", 0) < time.time():
        raise Refuse("PERMISSION_DENIED", GENERIC_PASSWORD)
    _set_two_step_claim(uid, "passed", _token().get("auth_time", 0))
    return {"ok": True, "refreshToken": True}


def _delete_two_step(uid):
    urllib.request.urlopen(urllib.request.Request(f"{FS}/twoStep/{uid}", method="DELETE", headers=ADMIN)).read()
    _set_two_step_claim(uid, "off")


def disable_additional_password(uid, data):
    _require_fresh()
    d = get(f"twoStep/{uid}")
    if not d:
        return {"ok": True}
    if _hash(str((data or {}).get("password") or ""), d.get("salt", "")) != d.get("hash"):
        raise Refuse("PERMISSION_DENIED", GENERIC_PASSWORD)
    _delete_two_step(uid)
    return {"ok": True, "refreshToken": True, "customToken": _custom_token(uid)}


def start_two_step_recovery(uid, data):
    d = get(f"twoStep/{uid}")
    if not d:
        raise Refuse("FAILED_PRECONDITION", "Two-step verification is not on.")
    address = (d.get("recoveryEmail") or _user(uid).get("email") or "").lower()
    if not _looks_like_email(address):
        raise Refuse("FAILED_PRECONDITION", "This account has no recovery email, so the additional password cannot be reset.")
    _mint(uid, "twoStepRecovery", address)
    return {"sent": True, "maskedEmail": _mask(address)}


def confirm_two_step_recovery(uid, data):
    _redeem(uid, "twoStepRecovery", (data or {}).get("code"))
    _delete_two_step(uid)
    return {"ok": True, "refreshToken": True, "customToken": _custom_token(uid)}


def start_email_change(uid, data):
    _require_fresh()
    target = str((data or {}).get("newEmail") or "").strip().lower()
    if not _looks_like_email(target):
        raise Refuse("INVALID_ARGUMENT", "That does not look like an email address.")
    if (_user(uid).get("email") or "").lower() == target:
        raise Refuse("FAILED_PRECONDITION", "That is already your email address.")
    taken = bool(_auth_post("accounts:lookup", {"email": [target]}).get("users"))
    if not taken:
        _mint(uid, "emailChange", target)
    return {"sent": True}


def confirm_email_change(uid, data):
    _require_fresh()
    target = _redeem(uid, "emailChange", (data or {}).get("code"))
    _auth_post("accounts:update", {"localId": uid, "email": target, "emailVerified": True})
    return {"email": target, "refreshToken": True, "customToken": _custom_token(uid)}


def purge_account_now(uid, data):
    """Refuses only: a local run never purges a test account."""
    prof = get(f"users/{uid}") or {}
    if not prof.get("deletionScheduledFor"):
        raise Refuse("FAILED_PRECONDITION", "This account is not waiting to be deleted.")
    raise Refuse("FAILED_PRECONDITION", "Not emulated locally: the test account is kept.")


ACCOUNT_STUBS = {"twoStepStatus": two_step_status, "setAdditionalPassword": set_additional_password,
                 "verifyAdditionalPassword": verify_additional_password, "renewTwoStepSession": renew_two_step_session,
                 "disableAdditionalPassword": disable_additional_password, "startTwoStepRecovery": start_two_step_recovery,
                 "confirmTwoStepRecovery": confirm_two_step_recovery, "startEmailChange": start_email_change,
                 "confirmEmailChange": confirm_email_change, "purgeAccountNow": purge_account_now}
HANDLERS.update(ACCOUNT_STUBS)


# ---------------------------------------------------------------------------------------------
# GROUP LINK AND GROUP NAME TEST STUBS (Android groups worker, 2026-11-02). Copies of what
# Kulan/functions-groups createGroupLink / editGroupLink / revokeGroupLink / deleteGroupLink /
# listGroupLinks / getShareLink (lib/grouplinks.js) and functions-username checkGroupUsername /
# setGroupUsername write, so the Android Invite Links and Group Type pages can be driven locally.
# NOT the real server: no transaction, no rate limit, no admin log, no reserved-name or
# look-alike checks, no public-group cap. Never deployed.
import secrets as _secrets
import time as _time
_LINK_CHARS = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"


def _gl_now():
    return int(_time.time() * 1000)


def _gl_group(uid, data):
    cid = str((data or {}).get("cid") or "")
    if not cid or "/" in cid: raise Refusal("INVALID_ARGUMENT", "Missing group.")
    conv = get(f"conversations/{cid}")
    if not conv: raise Refusal("NOT_FOUND", "This group no longer exists.")
    if conv.get("type") != "group": raise Refusal("FAILED_PRECONDITION", "Not a group.")
    admins = conv.get("admins") or []
    rights = (conv.get("adminRights") or {}).get(uid)
    if uid not in admins or not (conv.get("createdBy") == uid or not isinstance(rights, list) or "inviteUsers" in rights):
        raise Refusal("PERMISSION_DENIED", "Only admins who can add members can manage invite links.")
    return cid, conv


def _gl_num(v):
    try:
        n = float(v)
        return int(n) if n > 0 else 0
    except (TypeError, ValueError):
        return 0


def _gl_links(cid):
    try:
        return list_docs(f"conversations/{cid}/links")
    except urllib.error.HTTPError:
        return []


def _gl_client(code, rec, conv, now):
    names = conv.get("names") or {}
    st = "revoked" if rec.get("revoked") else "expired" if 0 < _gl_num(rec.get("expireAt")) <= now else \
        "usedUp" if 0 < _gl_num(rec.get("usageLimit")) <= _gl_num(rec.get("usedCount")) else "active"
    return {"code": code, "name": rec.get("name") or "", "isPrimary": rec.get("isPrimary") is True,
            "createdBy": rec.get("createdBy") or "", "createdByName": str(names.get(rec.get("createdBy"), ""))[:64],
            "createdAt": _gl_num(rec.get("createdAt")), "expireAt": _gl_num(rec.get("expireAt")),
            "usageLimit": _gl_num(rec.get("usageLimit")), "usedCount": _gl_num(rec.get("usedCount")),
            "requestApproval": rec.get("requestApproval") is True, "revoked": rec.get("revoked") is True,
            "status": st, "joined": []}


def _gl_options(d, now, base):
    base = base or {}
    out = {"name": base.get("name") or "", "expireAt": _gl_num(base.get("expireAt")),
           "usageLimit": _gl_num(base.get("usageLimit")), "requestApproval": base.get("requestApproval") is True}
    if d.get("name") is not None:
        n = " ".join(str(d["name"]).split())
        if len(n) > 32: raise Refusal("INVALID_ARGUMENT", "A link name can be at most 32 characters.")
        out["name"] = n
    if d.get("expireAt") is not None:
        e = _gl_num(d["expireAt"])
        if 0 < e <= now: raise Refusal("INVALID_ARGUMENT", "The time limit has already passed.")
        out["expireAt"] = e
    if d.get("usageLimit") is not None:
        out["usageLimit"] = _gl_num(d["usageLimit"])
    if d.get("requestApproval") is not None:
        out["requestApproval"] = d["requestApproval"] is True
    if out["requestApproval"]: out["usageLimit"] = 0
    return out


def _gl_mirror(cid, conv, rec):
    return {"cid": cid, "groupTitle": str(conv.get("title") or "Group")[:128], "expireAt": rec["expireAt"],
            "usageLimit": rec["usageLimit"], "usedCount": rec.get("usedCount", 0), "requestApproval": rec["requestApproval"],
            "revoked": rec.get("revoked") is True, "createdBy": rec.get("createdBy", ""), "createdAt": rec.get("createdAt", 0)}


def create_group_link(uid, data):
    cid, conv = _gl_group(uid, data)
    d = data or {}
    now = _gl_now()
    code = "".join(_secrets.choice(_LINK_CHARS) for _ in range(10))
    primary = d.get("primary") is True
    olds = [(c, r) for c, r in _gl_links(cid) if r.get("isPrimary") is True and not r.get("revoked")] if primary else []
    if primary:
        keep = olds[0][1].get("requestApproval") is True if olds else False
        ask = keep if d.get("requestApproval") is None else d.get("requestApproval") is True
        opts = {"name": "", "expireAt": 0, "usageLimit": 0, "requestApproval": ask}
    else:
        opts = _gl_options(d, now, None)
    for c, _ in olds:
        write(f"conversations/{cid}/links/{c}", {"revoked": True, "revokedAt": now})
        write(f"invites/{c}", {"revoked": True})
    rec = dict(code=code, cid=cid, createdBy=uid, createdAt=now, usedCount=0, revoked=False,
               isPrimary=primary, joinedSample=[], **opts)
    write(f"conversations/{cid}/links/{code}", rec)
    write(f"invites/{code}", _gl_mirror(cid, conv, rec))
    if primary:
        write(f"conversations/{cid}", {"inviteCode": code, "updatedAt": Ts()})
    return {"link": _gl_client(code, rec, conv, now)}


def _gl_find(cid, data):
    code = str((data or {}).get("code") or "")
    if not code or "/" in code: raise Refusal("INVALID_ARGUMENT", "Missing link.")
    rec = get(f"conversations/{cid}/links/{code}")
    if not rec: raise Refusal("NOT_FOUND", "This link no longer exists.")
    return code, rec


def _gl_can_edit(conv, uid, rec):
    return conv.get("createdBy") == uid or rec.get("isPrimary") is True or rec.get("createdBy") == uid


def edit_group_link(uid, data):
    cid, conv = _gl_group(uid, data)
    code, rec = _gl_find(cid, data)
    if not _gl_can_edit(conv, uid, rec): raise Refusal("PERMISSION_DENIED", "Only the group's owner can change this link.")
    if rec.get("revoked"): raise Refusal("FAILED_PRECONDITION", "This link was revoked and can't be changed.")
    now = _gl_now()
    src = {"requestApproval": (data or {}).get("requestApproval")} if rec.get("isPrimary") else (data or {})
    opts = _gl_options(src, now, rec)
    if rec.get("isPrimary"): opts.update(name="", expireAt=0, usageLimit=0)
    write(f"conversations/{cid}/links/{code}", opts)
    write(f"invites/{code}", {"expireAt": opts["expireAt"], "usageLimit": opts["usageLimit"], "requestApproval": opts["requestApproval"]})
    return {"link": _gl_client(code, {**rec, **opts}, conv, now)}


def revoke_group_link(uid, data):
    cid, conv = _gl_group(uid, data)
    code, rec = _gl_find(cid, data)
    if not _gl_can_edit(conv, uid, rec): raise Refusal("PERMISSION_DENIED", "Only the group's owner can revoke this link.")
    now = _gl_now()
    write(f"conversations/{cid}/links/{code}", {"revoked": True, "revokedAt": now})
    write(f"invites/{code}", {"revoked": True})
    if conv.get("inviteCode") == code:
        write(f"conversations/{cid}", {"inviteCode": "", "updatedAt": Ts()})
    return {"link": _gl_client(code, {**rec, "revoked": True}, conv, now)}


def delete_group_link(uid, data):
    cid, conv = _gl_group(uid, data)
    d = data or {}
    codes = [c for c, r in _gl_links(cid) if r.get("revoked")] if d.get("allRevoked") is True else [_gl_find(cid, d)[0]]
    deleted = 0
    for c in codes:
        rec = get(f"conversations/{cid}/links/{c}") or {}
        if not rec.get("revoked"):
            if d.get("allRevoked") is True: continue
            raise Refusal("FAILED_PRECONDITION", "Revoke the link before deleting it.")
        if not _gl_can_edit(conv, uid, rec):
            if d.get("allRevoked") is True: continue
            raise Refusal("PERMISSION_DENIED", "Only the group's owner can delete this link.")
        remove(f"conversations/{cid}/links/{c}")
        remove(f"invites/{c}")
        deleted += 1
    return {"deleted": deleted}


def list_group_links(uid, data):
    cid, conv = _gl_group(uid, data)
    now = _gl_now()
    owner = conv.get("createdBy") == uid
    out = [_gl_client(c, r, conv, now) for c, r in _gl_links(cid)
           if owner or r.get("isPrimary") is True or r.get("createdBy") == uid]
    live = sorted([l for l in out if not l["revoked"]], key=lambda l: -l["createdAt"])
    primary = next((l for l in live if l["isPrimary"]), None)
    return {"primary": primary, "links": [l for l in live if l is not primary],
            "revoked": sorted([l for l in out if l["revoked"]], key=lambda l: -l["createdAt"]), "isOwner": owner}


def get_share_link(uid, data):
    cid = str((data or {}).get("cid") or "")
    conv = get(f"conversations/{cid}") if cid and "/" not in cid else None
    if not conv: raise Refusal("NOT_FOUND", "This group no longer exists.")
    if uid not in (conv.get("admins") or []) and conv.get("createdBy") != uid:
        raise Refusal("PERMISSION_DENIED", "Only admins can share this group's link.")
    live = sorted([(c, r) for c, r in _gl_links(cid) if r.get("isPrimary") and not r.get("revoked")],
                  key=lambda x: -_gl_num(x[1].get("createdAt")))
    return {"code": live[0][0] if live else None}


def _gn_owner(uid, data):
    cid = str((data or {}).get("cid") or "")
    conv = get(f"conversations/{cid}") if cid and "/" not in cid else None
    if not conv: raise Refusal("NOT_FOUND", "This group no longer exists.")
    if conv.get("createdBy") != uid: raise Refusal("PERMISSION_DENIED", "Only the owner can do this.")
    return cid, conv


def check_group_username(uid, data):
    if (data or {}).get("warm") is True: return {"ok": True}
    cid, _ = _gn_owner(uid, data)
    lower = str((data or {}).get("username") or "").strip().lower()
    held = get(f"usernames/{lower}") if lower else None
    if held and held.get("cid") != cid and held.get("state", "held") == "held":
        return {"available": False, "code": "USERNAME_TAKEN", "reason": "taken"}
    return {"available": True, "code": None, "reason": None}


def set_group_username(uid, data):
    if (data or {}).get("warm") is True: return {"ok": True}
    cid, conv = _gn_owner(uid, data)
    raw = str((data or {}).get("username") or "").strip()
    lower = raw.lower()
    prev = conv.get("usernameLower") or ""
    if prev and prev != lower:
        write(f"usernames/{prev}", {"state": "released"})
    if not lower:
        write(f"conversations/{cid}", {"isPublic": False, "username": DELETE, "usernameLower": DELETE})
        return {"isPublic": False}
    held = get(f"usernames/{lower}")
    if held and held.get("cid") != cid and held.get("state", "held") == "held":
        raise Refusal("ALREADY_EXISTS", "This link is already taken.")
    write(f"usernames/{lower}", {"kind": "group", "cid": cid, "username": raw, "by": uid, "state": "held"})
    write(f"conversations/{cid}", {"isPublic": True, "username": raw, "usernameLower": lower})
    return {"isPublic": True, "username": raw}


HANDLERS.update({"createGroupLink": create_group_link, "editGroupLink": edit_group_link, "revokeGroupLink": revoke_group_link,
                 "deleteGroupLink": delete_group_link, "listGroupLinks": list_group_links, "getShareLink": get_share_link,
                 "checkGroupUsername": check_group_username, "setGroupUsername": set_group_username})


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        name = self.path.rstrip("/").split("/")[-1]
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        uid = caller(self.headers)
        _REQ.headers = self.headers   # account test stubs read auth_time and the claims from it
        try:
            if name not in HANDLERS:
                raise Refusal("NOT_FOUND", "Not emulated locally.")
            if not uid:
                raise Refusal("UNAUTHENTICATED", "Sign in required.")
            code, out = 200, {"result": HANDLERS[name](uid, body.get("data"))}
        except Refusal as r:
            code, out = {"NOT_FOUND": 404, "PERMISSION_DENIED": 403, "FAILED_PRECONDITION": 400, "UNAUTHENTICATED": 401, "ALREADY_EXISTS": 409, "INVALID_ARGUMENT": 400}.get(r.status, 400), \
                {"error": {"status": r.status, "message": r.message}}
            if getattr(r, "details", None):
                out["error"]["details"] = r.details
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
