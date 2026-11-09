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


# ---------------------------------------------------------------------------------------------
# OWNERSHIP, JOIN REQUEST, REACTIONS AND ADD FOLLOWERS TEST STUBS (Android owner worker, 2026-11-02).
# APPENDED. Copies of what Kulan/functions-groups writes for transferGroupOwnership
# (lib/ownertransfer.js decide + transferWrite + handoffRecord + ownerNotice), approveJoinRequest /
# denyJoinRequest, setChannelReactions (channels.normReactSet) and addChannelFollowers, so the
# Android pages can be driven locally. NOT the real server: no transaction, no groups-owned cap, no
# admin log (groupAdminLog), the follower count moves at once, addChannelFollowers checks only an
# accepted 1:1 chat, members and bans (not blocks or "Who can add me"). Never deployed.
OT_FIVE_HOURS = 5 * 60 * 60 * 1000
OT_DEFAULT_RIGHTS = ["changeInfo", "deleteMessages", "banUsers", "inviteUsers", "pinMessages", "manageCalls", "postStories"]
OT_MSG = {
    "noPassword": "Create a password for your account first. You need it to transfer group ownership.",
    "createdRecently": "You can transfer group ownership after 5 hours because you recently created your password.",
    "changedRecently": "You can transfer group ownership after 5 hours because you recently changed your password.",
    "reauth": "Enter your password again to transfer ownership.",
    "notOwner": "Only the group owner can transfer ownership.",
    "notMember": "That person is no longer in this group.",
    "self": "You already own this group.",
    "noGroup": "This group no longer exists.",
    "closed": "Ownership of this group cannot be transferred right now.",
}


def _ot_refuse(status, reason, key):
    raise Refuse(status, OT_MSG[key], {"reason": reason})


def _ot_now():
    return int(time.time() * 1000)


def _ot_add(collection_path, fields):
    """A new document with an automatic id (the server's .doc())."""
    body = json.dumps({"fields": {k: wval(v) for k, v in fields.items()}}).encode()
    with urllib.request.urlopen(urllib.request.Request(f"{FS}/{collection_path}", data=body, method="POST", headers=ADMIN)) as r:
        return json.loads(r.read())["name"].rsplit("/", 1)[-1]


def transfer_group_ownership(uid, data):
    d = data or {}
    cid, to, check = str(d.get("cid") or ""), str(d.get("to") or ""), d.get("check") is True
    if not cid or not to or "/" in cid or "/" in to:
        raise Refuse("INVALID_ARGUMENT", "Missing group or member.")
    conv = get(f"conversations/{cid}")
    if not conv or conv.get("type") != "group":
        _ot_refuse("NOT_FOUND", "no-group", "noGroup")
    users = conv.get("users") or []
    if conv.get("createdBy", "") != uid or uid not in users:
        _ot_refuse("PERMISSION_DENIED", "not-owner", "notOwner")
    if to == uid:
        _ot_refuse("FAILED_PRECONDITION", "self", "self")
    if to not in users:
        _ot_refuse("FAILED_PRECONDITION", "not-member", "notMember")
    mod = conv.get("mod") or {}
    if conv.get("endedAt") or mod.get("state") in ("ended", "suspended", "disabled"):
        _ot_refuse("FAILED_PRECONDITION", "closed", "closed")
    acct = _user(uid)
    has_pw = any((p or {}).get("providerId") == "password" for p in acct.get("providerUserInfo") or []) or bool(acct.get("passwordHash"))
    if not has_pw:
        _ot_refuse("FAILED_PRECONDITION", "no-password", "noPassword")
    at = acct.get("passwordUpdatedAt")
    at = int(at) if at not in (None, "") else None
    if at is not None and _ot_now() - at < OT_FIVE_HOURS:
        created = int(acct.get("createdAt") or 0)
        if created and abs(at - created) < 10 * 60 * 1000:
            _ot_refuse("FAILED_PRECONDITION", "password-created-recently", "createdRecently")
        _ot_refuse("FAILED_PRECONDITION", "password-changed-recently", "changedRecently")
    if check:
        return {"ok": True, "ready": True, "transferred": False}
    tok = _token()
    provider = (tok.get("firebase") or {}).get("sign_in_provider")
    if provider != "password" or not tok.get("auth_time") or _ot_now() - int(tok["auth_time"]) * 1000 > 5 * 60 * 1000:
        _ot_refuse("UNAUTHENTICATED", "reauth", "reauth")
    names = conv.get("names") or {}
    text = (str(names.get(to) or "")[:64] or "A member") + " is now the owner"
    admins = list(conv.get("admins") or [])
    if to not in admins: admins.append(to)
    if uid not in admins: admins.append(uid)
    old_rights = (conv.get("adminRights") or {}).get(to)
    old_by = (conv.get("adminBy") or {}).get(to)
    write(f"conversations/{cid}", {
        "createdBy": to, "admins": admins,
        f"adminRights.{to}": DELETE, f"adminBy.{to}": DELETE, f"restrictedFlags.{to}": DELETE, f"restrictedUntil.{to}": DELETE,
        f"adminRights.{uid}": CHANNEL_ADMIN_RIGHTS[:] if conv.get("channel") is True else OT_DEFAULT_RIGHTS[:],
        f"adminBy.{uid}": DELETE,
        "lastMessage": text, "lastSender": uid, "updatedAt": Ts(),
    })
    _ot_add(f"conversations/{cid}/messages", {"text": text, "authorId": uid, "type": "system", "createdAt": Ts()})
    now = _ot_now()
    until = now + 48 * 60 * 60 * 1000
    group = str(conv.get("title") or "")[:128] or "the group"
    giver = str(names.get(uid) or "")[:64] or "The previous owner"
    note = _ot_add(f"users/{to}/announcements", {
        "kind": "security", "title": f"You now own {group}",
        "body": f"{giver} has handed {group} over to you, so you are now its owner. From here on you answer for what is posted in it and you decide who runs it.\n\n"
                "If this was a mistake, or you would rather not take it on, tap Reject Ownership within 48 hours to give it back. "
                "If you are happy to keep it, there is nothing you need to do.",
        "buttons": [{"label": "Reject Ownership", "action": "rejectOwnership", "value": cid, "until": until}],
        "createdBy": "system", "deleted": False, "publishAt": Ts(), "createdAt": Ts()})
    write(f"groupOwnerHandoffs/{cid}", {"from": uid, "to": to, "at": now, "until": until,
                                        "toWasAdmin": to in (conv.get("admins") or []),
                                        "toRights": old_rights if isinstance(old_rights, list) else None,
                                        "toAdminBy": old_by or None, "noticeId": note})
    return {"ok": True, "ready": False, "transferred": True}


def _can_manage_joins(conv, uid):
    if uid not in (conv.get("admins") or []): return False
    if conv.get("createdBy") == uid: return True
    r = (conv.get("adminRights") or {}).get(uid)
    return not isinstance(r, list) or "inviteUsers" in r


def approve_join_request(uid, data):
    cid, target = _cid_uid(data)
    conv = get(f"conversations/{cid}")
    if not conv: raise Refuse("NOT_FOUND", "Group gone.")
    if not _can_manage_joins(conv, uid): raise Refuse("PERMISSION_DENIED", "Admins only.")
    req = get(f"conversations/{cid}/joinRequests/{target}")
    if req is None: raise Refuse("NOT_FOUND", "No such join request.")
    prof = get(f"users/{target}")
    if not prof:
        remove(f"conversations/{cid}/joinRequests/{target}")
        raise Refuse("NOT_FOUND", "This account no longer exists.")
    if target in (conv.get("bannedUids") or []):
        remove(f"conversations/{cid}/joinRequests/{target}")
        raise Refuse("FAILED_PRECONDITION", "This person was removed from the group.")
    name = prof.get("name") or prof.get("handle") or "New member"
    if conv.get("channel") is True:
        if get(f"conversations/{cid}/followers/{target}") is None and target not in (conv.get("users") or []):
            write(f"conversations/{cid}/followers/{target}", {"at": Ts(), "approvedBy": uid})
            write(f"users/{target}/channels/{cid}", {"at": Ts(), "read": int(conv.get("postCount", 0)), "muted": False})
            _bump_followers(cid, conv, 1)
    elif target not in (conv.get("users") or []):
        text = str(name).strip()[:64] + " joined the group"
        upd = {"users": (conv.get("users") or []) + [target], "lastMessage": text, "lastSender": target, "updatedAt": Ts(),
               f"names.{target}": name, f"unreadCount.{target}": 0}
        if prof.get("photoUrl"): upd[f"photos.{target}"] = prof["photoUrl"]
        write(f"conversations/{cid}", upd)
        _ot_add(f"conversations/{cid}/messages", {"text": text, "authorId": target, "type": "system", "createdAt": Ts()})
    remove(f"conversations/{cid}/joinRequests/{target}")
    return {"ok": True}


def deny_join_request(uid, data):
    cid, target = _cid_uid(data)
    conv = get(f"conversations/{cid}")
    if not conv: raise Refuse("NOT_FOUND", "Group gone.")
    if not _can_manage_joins(conv, uid): raise Refuse("PERMISSION_DENIED", "Admins only.")
    if get(f"conversations/{cid}/joinRequests/{target}") is None: raise Refuse("NOT_FOUND", "No such join request.")
    remove(f"conversations/{cid}/joinRequests/{target}")
    return {"ok": True}


def set_channel_reactions(uid, data):
    d = data or {}
    cid = str(d.get("cid") or "")
    mode = d.get("mode") if d.get("mode") in ("some", "off") else "all"
    seen, lst = set(), []
    for e in d.get("list") or []:
        e = str(e)
        if e and len(e) <= 16 and e not in seen:
            seen.add(e); lst.append(e)
    lst = lst[:6]
    if mode == "some" and not lst: mode = "off"
    out = {"mode": mode, "list": lst if mode == "some" else []}
    cd = get(f"conversations/{cid}")
    if not cd or cd.get("channel") is not True: raise Refuse("NOT_FOUND", "This channel no longer exists.")
    if "changeInfo" not in rights_of(cd, uid):
        raise Refuse("PERMISSION_DENIED", "Only admins who can change the channel's info can do this.")
    write(f"conversations/{cid}", {"reactSet": out})
    return out


def add_channel_followers(uid, data):
    d = data or {}
    cid = str(d.get("cid") or "")
    uids = list(dict.fromkeys(str(u) for u in (d.get("uids") or []) if u and u != uid))
    if not uids: return {"added": [], "refused": []}
    cd = get(f"conversations/{cid}")
    if not cd or cd.get("channel") is not True: raise Refuse("NOT_FOUND", "This channel no longer exists.")
    if "inviteUsers" not in rights_of(cd, uid):
        raise Refuse("PERMISSION_DENIED", "Only admins who can add people can add followers.")
    adds = get(f"channelAdds/{cid}") or {}
    used = int(adds.get("n") or 0)
    if used >= 10:
        raise Refuse("RESOURCE_EXHAUSTED", "This channel has added 10 people, the most it can. Others can follow it with its link.")
    added, refused, capped = [], [], []
    for u in uids:
        if u in (cd.get("users") or []): continue
        if u in (cd.get("bannedUids") or []): refused.append(u); continue
        chat = get(f"conversations/{'_'.join(sorted([u, uid]))}")
        if not get(f"users/{u}") or not chat or (chat.get("startedBy") and chat.get("accepted") is not True):
            refused.append(u); continue
        if get(f"conversations/{cid}/followers/{u}") is not None: continue
        if used >= 10: capped.append(u); continue
        used += 1
        write(f"channelAdds/{cid}", {"cid": cid, "n": used})
        write(f"conversations/{cid}/followers/{u}", {"at": Ts(), "addedBy": uid})
        write(f"users/{u}/channels/{cid}", {"at": Ts(), "read": int(cd.get("postCount", 0)), "muted": False})
        _bump_followers(cid, cd, 1)
        cd = get(f"conversations/{cid}")
        added.append(u)
    return {"added": added, "refused": refused, "linkOnly": [], "capped": capped}


# rejectGroupOwnership: lib/ownertransfer.js decideReject + rejectWrite + returnedNotice. Same gaps
# as the transfer above (no transaction, no owned-groups cap for an admin heir, no admin log).
OT_REJECT_MSG = {
    "none": "There is no ownership hand-over to reject for this group.",
    "over": "The 48 hours to reject this group have passed. You can still transfer it to another member in Group Settings.",
    "notOwner": "You are no longer the owner of this group.",
    "noHeir": "The previous owner has left and the group has no other admins, so there is nobody to hand it back to. You can transfer it to a member or delete it.",
}


def reject_group_ownership(uid, data):
    cid = str((data or {}).get("cid") or "")
    if not cid or "/" in cid:
        raise Refuse("INVALID_ARGUMENT", "Missing group.")
    conv = get(f"conversations/{cid}")
    if not conv or conv.get("type") != "group":
        _ot_refuse("NOT_FOUND", "no-group", "noGroup")
    hand = get(f"groupOwnerHandoffs/{cid}")
    if not hand or hand.get("to") != uid:
        raise Refuse("FAILED_PRECONDITION", OT_REJECT_MSG["none"], {"reason": "no-handoff"})
    if not _ot_now() <= int(hand.get("until") or 0):
        raise Refuse("FAILED_PRECONDITION", OT_REJECT_MSG["over"], {"reason": "window-over"})
    users = conv.get("users") or []
    if conv.get("createdBy", "") != uid or uid not in users:
        raise Refuse("FAILED_PRECONDITION", OT_REJECT_MSG["notOwner"], {"reason": "not-owner"})
    mod = conv.get("mod") or {}
    if conv.get("endedAt") or mod.get("blocked") or mod.get("state") == "ended":
        _ot_refuse("FAILED_PRECONDITION", "closed", "closed")
    banned = conv.get("bannedUids") or []
    ok = lambda u: u in users and u not in banned and u != uid
    giver = hand.get("from")
    if ok(giver):
        heir, returning = giver, True
    else:
        heir = next((u for u in (conv.get("admins") or []) if ok(u) and u != giver), None)
        returning = False
    if not heir:
        raise Refuse("FAILED_PRECONDITION", OT_REJECT_MSG["noHeir"], {"reason": "no-heir"})
    names = conv.get("names") or {}
    who = str(names.get(uid) or "")[:64] or "A member"
    nxt = str(names.get(heir) or "")[:64] or "A member"
    text = f"{who} turned down ownership. {nxt} is the owner again" if returning else f"{who} turned down ownership. {nxt} is now the owner"
    admins = list(conv.get("admins") or [])
    if heir not in admins: admins.append(heir)
    upd = {"createdBy": heir, f"adminRights.{heir}": DELETE, f"adminBy.{heir}": DELETE,
           f"restrictedFlags.{heir}": DELETE, f"restrictedUntil.{heir}": DELETE,
           "lastMessage": text, "lastSender": uid, "updatedAt": Ts()}
    if hand.get("toWasAdmin"):
        if uid not in admins: admins.append(uid)
        rights = hand.get("toRights")
        upd[f"adminRights.{uid}"] = rights[:] if isinstance(rights, list) else DELETE
        upd[f"adminBy.{uid}"] = hand.get("toAdminBy") or DELETE
    else:
        admins = [u for u in admins if u != uid]
        upd[f"adminRights.{uid}"] = DELETE
        upd[f"adminBy.{uid}"] = DELETE
    upd["admins"] = admins
    write(f"conversations/{cid}", upd)
    _ot_add(f"conversations/{cid}/messages", {"text": text, "authorId": uid, "type": "system", "createdAt": Ts()})
    remove(f"groupOwnerHandoffs/{cid}")
    # The button goes from the note it was on, so it is not offered again.
    note = hand.get("noticeId")
    if note and get(f"users/{uid}/announcements/{note}") is not None:
        write(f"users/{uid}/announcements/{note}", {"buttons": [], "editedAt": Ts()})
    group = str(conv.get("title") or "")[:128] or "the group"
    by = str(names.get(uid) or "")[:64] or "The new owner"
    _ot_add(f"users/{heir}/announcements", {
        "kind": "security",
        "title": f"{group} is yours again" if returning else f"You now own {group}",
        "body": f"{by} turned down ownership of {group}, so you are its owner again." if returning
                else f"{by} turned down ownership of {group}. The previous owner has left, so it passed to you as its longest-standing admin. You are now its owner and answer for what is posted in it.",
        "buttons": [], "createdBy": "system", "deleted": False, "publishAt": Ts(), "createdAt": Ts()})
    return {"ok": True, "returning": returning}


HANDLERS.update({"rejectGroupOwnership": reject_group_ownership})


# ---------------------------------------------------------------------------------------------
# SIGN-UP AND USERNAME TEST STUBS (2026-11-02). Copies of functions-loginalert requestSignupCode /
# verifySignupCode / completeSignup and functions-username checkUsername / claimUsername, so a new
# account can be made locally. NOT the real server: no mail is sent (the code is kept in clear as
# `devCode` on signupCodes/{email} for the test script to read), no rate limits, no IP checks, the
# username rules are the shape check only (no reserved names, skeletons, 30-day holds or audit).
SU_TTL_MS = 10 * 60 * 1000
SU_TICKET_TTL_MS = 15 * 60 * 1000
SU_MAX_ATTEMPTS = 5
NO_AUTH = {"requestSignupCode", "verifySignupCode", "completeSignup"}


def _su_email(data):
    return str((data or {}).get("email") or "").strip().lower()


def _su_key(email):
    return hashlib.sha256(email.encode()).hexdigest()


def _su_exists(email):
    return bool(_auth_post("accounts:lookup", {"email": [email]}).get("users"))


def request_signup_code(uid, data):
    email = _su_email(data)
    if not email or "@" not in email: return {"sent": True}
    key = _su_key(email)
    if _su_exists(email):
        remove(f"signupCodes/{key}")   # the real one mails "You already have a Fariin account"
        return {"sent": True}
    code = f"{random.randint(0, 999999):06d}"
    write(f"signupCodes/{key}", {"hash": _su_key(code + key), "expiresAt": _ot_now() + SU_TTL_MS,
                                 "attempts": 0, "createdAt": _ot_now(), "devCode": code, "email": email})
    return {"sent": True}


def verify_signup_code(uid, data):
    email = _su_email(data)
    code = str((data or {}).get("code") or "").strip()
    if not (len(code) == 6 and code.isdigit()):
        raise Refuse("INVALID_ARGUMENT", "Enter the six-digit code from your email.")
    key = _su_key(email)
    d = get(f"signupCodes/{key}")
    reason = None
    if not d or _ot_now() > int(d.get("expiresAt") or 0): reason = "expired"
    elif int(d.get("attempts") or 0) >= SU_MAX_ATTEMPTS: reason = "burned"
    elif d.get("hash") != _su_key(code + key):
        write(f"signupCodes/{key}", {"attempts": int(d.get("attempts") or 0) + 1})
        reason = "wrong"
    if reason:
        if reason != "wrong": remove(f"signupCodes/{key}")
        raise Refuse("PERMISSION_DENIED", "Too many wrong tries. Ask for a new code." if reason == "burned"
                     else "That code is wrong or has expired.")
    remove(f"signupCodes/{key}")
    ticket = base64.urlsafe_b64encode(os.urandom(32)).rstrip(b"=").decode()
    write(f"signupTickets/{hashlib.sha256(ticket.encode()).hexdigest()}",
          {"email": email, "expiresAt": _ot_now() + SU_TICKET_TTL_MS})
    return {"ticket": ticket}


def complete_signup(uid, data):
    ticket = str((data or {}).get("ticket") or "")
    password = str((data or {}).get("password") or "")
    if len(password) < 8 or not any(ch.isalpha() for ch in password) or not any(ch.isdigit() for ch in password):
        raise Refuse("INVALID_ARGUMENT", "Use at least 8 characters, with a letter and a number.")
    if not ticket: raise Refuse("PERMISSION_DENIED", "Your code has expired. Start again.")
    path = f"signupTickets/{hashlib.sha256(ticket.encode()).hexdigest()}"
    d = get(path)
    email = d.get("email") if d and _ot_now() <= int(d.get("expiresAt") or 0) else None
    if not email:
        remove(path)
        raise Refuse("PERMISSION_DENIED", "Your code has expired. Start again.")
    if _su_exists(email):
        remove(path)
        raise Refuse("ALREADY_EXISTS", "This email already has an account.")
    new_uid = _auth_post("accounts", {"email": email, "password": password, "emailVerified": True}).get("localId")
    remove(path)
    return {"token": _custom_token(new_uid)}


def _un_shape(raw):
    """The plain shape rule only: 5 to 30 letters, digits and underscores, starting with a letter."""
    import re
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{4,29}", raw):
        return "Use 5 to 30 letters, numbers or underscores, starting with a letter."
    return None


def check_username(uid, data):
    raw = str((data or {}).get("username") or "").strip()
    shape = _un_shape(raw)
    if shape: return {"available": False, "code": "invalid", "reason": shape}
    d = get(f"usernames/{raw.lower()}")
    if d and d.get("uid") and d.get("uid") != uid:
        return {"available": False, "code": "taken", "reason": "taken"}
    return {"available": True, "code": None, "reason": None}


def claim_username(uid, data):
    raw = str((data or {}).get("username") or "").strip()
    shape = _un_shape(raw)
    if shape: raise Refuse("INVALID_ARGUMENT", shape, {"reason": shape})
    lower = raw.lower()
    d = get(f"usernames/{lower}")
    if d and d.get("uid") and d.get("uid") != uid:
        raise Refuse("ALREADY_EXISTS", "This username is taken.", {"reason": "taken"})
    me = get(f"users/{uid}") or {}
    old = me.get("handleLower") or ""
    now = _ot_now()
    write(f"usernames/{lower}", {"uid": uid, "kind": "user", "state": "held", "username": raw, "claimedAt": now,
                                 "releasedAt": None, "prevUid": None, "reservedFor": None, "reason": None})
    write(f"users/{uid}", {"handle": raw, "handleLower": lower})
    if old and old != lower:
        write(f"usernames/{old}", {"uid": None, "state": "released", "releasedAt": now, "prevUid": uid})
    return {"username": raw}


HANDLERS.update({"requestSignupCode": request_signup_code, "verifySignupCode": verify_signup_code,
                 "completeSignup": complete_signup, "checkUsername": check_username, "claimUsername": claim_username})
HANDLERS.update({"transferGroupOwnership": transfer_group_ownership, "approveJoinRequest": approve_join_request,
                 "denyJoinRequest": deny_join_request, "setChannelReactions": set_channel_reactions,
                 "addChannelFollowers": add_channel_followers})


# setGroupWallpaper (functions-groups index.js + lib/groupwallpaper.js), so the Android group
# wallpaper can be driven locally. Same checks: a member, a known value, not a closed group, and
# change-info right (owner, an admin with changeInfo or no rights list, or membersCanEditInfo).
# Local only: a photo link may also be the Storage emulator's (http://10.0.2.2:9199/...), which the
# real server never sees. No transaction. Never deployed.
import re as _re
_WP_RE = _re.compile(r"^[gcb]:[A-Za-z0-9_-]{1,64}$")
_WP_PHOTO = _re.compile(r"^u:(https://firebasestorage\.googleapis\.com(:443)?|http://10\.0\.2\.2:9199)/\S*$")


def set_group_wallpaper(uid, data):
    cid = str((data or {}).get("cid") or "")
    w = (data or {}).get("wallpaper")
    if not cid or "/" in cid: raise Refusal("INVALID_ARGUMENT", "Missing cid.")
    conv = get(f"conversations/{cid}")
    if not conv or conv.get("type") != "group": raise Refusal("NOT_FOUND", "No such group.")
    if uid not in (conv.get("users") or []): raise Refusal("PERMISSION_DENIED", "Not in this group.")
    if not isinstance(w, str) or len(w) > 1024 or not (w == "none" or _WP_RE.match(w) or _WP_PHOTO.match(w)):
        raise Refusal("INVALID_ARGUMENT", "Unknown wallpaper.")
    if ((conv.get("mod") or {}).get("state") in ("disabled", "ended")) or conv.get("endedAt"):
        raise Refusal("PERMISSION_DENIED", "This group cannot be changed.")
    rights = (conv.get("adminRights") or {}).get(uid)
    admin = conv.get("createdBy") == uid or (uid in (conv.get("admins") or []) and (not isinstance(rights, list) or "changeInfo" in rights))
    if not (admin or conv.get("membersCanEditInfo") is True):
        raise Refusal("PERMISSION_DENIED", "Only admins who can change group info can set the wallpaper.")
    write(f"conversations/{cid}", {"wallpaper": w, "updatedAt": Ts()})
    return {"ok": True}


HANDLERS.update({"setGroupWallpaper": set_group_wallpaper})


# Chat Key (generateChatPin / chatPinStatus / removeChatPin): enough for the settings page locally.
# The real server keeps only a hash; the stub keeps a flag on chatPins/<uid>.
def chat_pin_status(uid, data):
    d = get(f"chatPins/{uid}")
    return {"set": bool(d)}


def generate_chat_pin(uid, data):
    import random
    pin = "".join(random.choice("0123456789") for _ in range(6))
    write(f"chatPins/{uid}", {"set": True})
    return {"pin": pin}


def remove_chat_pin(uid, data):
    remove(f"chatPins/{uid}")
    return {"ok": True}


HANDLERS.update({"chatPinStatus": chat_pin_status, "generateChatPin": generate_chat_pin, "removeChatPin": remove_chat_pin})

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        name = self.path.rstrip("/").split("/")[-1]
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        uid = caller(self.headers)
        _REQ.headers = self.headers   # account test stubs read auth_time and the claims from it
        try:
            if name not in HANDLERS:
                raise Refusal("NOT_FOUND", "Not emulated locally.")
            if not uid and name not in NO_AUTH:
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
