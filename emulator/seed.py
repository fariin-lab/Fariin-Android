"""Seed the LOCAL Firebase emulator (project demo-fariin) with two fake users.

Nothing here can reach the real project: the URLs are 127.0.0.1 and the project id is demo-fariin.
Accounts and their generated passwords go to test-accounts.json next to this file (git-ignored).

    python seed.py              # new accounts
    python seed.py --profiles   # refresh the existing accounts' profile fields
    python seed.py --channel    # demo channels: alice runs them, bobby follows "Fariin News"
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
    if isinstance(v, bool):
        return {"booleanValue": v}
    if isinstance(v, int):
        return {"integerValue": str(v)}
    if isinstance(v, float):
        return {"doubleValue": v}
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


def iso(dt):
    return {"__ts": dt.strftime("%Y-%m-%dT%H:%M:%S.%fZ")}


# Demo channels (Kulan-iOS DemoMode.storeChannels, made-up): the official @fariin one first.
CHANNELS = [
    {"cid": "demoChanFariinNews", "title": "Fariin News", "username": "fariin", "verified": True, "followerCount": 128399,
     "desc": "Official news from Fariin. New features, tips and updates.", "country": "SO",
     "posts": [("Welcome to Fariin News. New features, tips and updates land here first.", 2380, {"❤️": 41, "👍": 12}),
               ("Tip: hold a message to react, reply or copy it.", 1250, {"🔥": 9}),
               ("Channels are here. Find them from New Chat > Find Channels.", 98, {})]},
    {"cid": "demoChanWorldNews", "title": "World News", "username": "worldnews", "verified": True, "followerCount": 2399999,
     "desc": "The day's top stories, in two minutes.", "country": "",
     "posts": [("Good morning. Here are today's top stories.", 51200, {"👍": 320})]},
    {"cid": "demoChanMarket", "title": "Hargeisa Market", "username": "hargeisamarket", "verified": False, "followerCount": 9998,
     "desc": "Prices and news from the market, every morning.", "country": "SO",
     "posts": [("Tomato prices are down this week.", 410, {})]},
]


def seed_channels():
    accts = {a["handle"]: a for a in json.load(open(os.path.join(HERE, "test-accounts.json")))}
    alice, bobby = accts["alice"], accts["bobby"]
    now = datetime.datetime.now(datetime.timezone.utc)
    rows = []
    for n, ch in enumerate(CHANNELS):
        cid, posts = ch["cid"], ch["posts"]
        made = now - datetime.timedelta(days=30 + n)
        last = posts[-1][0]
        patch_doc(f"conversations/{cid}", {
            "type": "group", "channel": True, "title": ch["title"], "users": [alice["uid"]], "admins": [alice["uid"]],
            "createdBy": alice["uid"], "createdAt": iso(made), "updatedAt": iso(now - datetime.timedelta(minutes=5 * n)),
            "names": {alice["uid"]: alice["name"]}, "photos": {}, "unreadCount": {alice["uid"]: 0},
            "isPublic": True, "username": ch["username"], "usernameLower": ch["username"], "verified": ch["verified"],
            "e2ee": False, "onlyAdminsSend": True, "hideMembers": True, "hideMembersBy": "channel",
            "followerCount": ch["followerCount"], "postCount": len(posts), "desc": ch["desc"], "country": ch["country"],
            "lastMessage": last, "lastSender": alice["uid"], "lastPlainTag": "",
        })
        patch_doc(f"usernames/{ch['username']}", {"kind": "group", "cid": cid, "state": "held", "username": ch["username"],
                                                 "claimedAt": iso(made)})
        for i, (text, views, rx) in enumerate(posts):
            at = now - datetime.timedelta(hours=len(posts) - i, minutes=n)
            patch_doc(f"conversations/{cid}/messages/post{i + 1}", {
                "text": text, "authorId": alice["uid"], "type": "text", "createdAt": iso(at), "clientTs": at.timestamp() * 1000,
                "views": views, "reactionCounts": {json.loads(f'"{k}"'): v for k, v in rx.items()}, "seq": i + 1,
            })
        rows.append({"cid": cid, "handle": ch["username"], "title": ch["title"], "photoUrl": "", "description": ch["desc"],
                     "followerCount": ch["followerCount"] + 1, "admins": 1, "verified": ch["verified"], "label": "",
                     "official": ch["username"] == "fariin", "country": ch["country"] or None})
    # bobby follows Fariin News (what followChannel writes), with the last post unread.
    news = CHANNELS[0]["cid"]
    patch_doc(f"conversations/{news}/followers/{bobby['uid']}", {"at": iso(now - datetime.timedelta(days=2))})
    patch_doc(f"users/{bobby['uid']}/channels/{news}", {"at": iso(now - datetime.timedelta(days=2)), "read": 2, "muted": False,
                                                       "mutedUntil": 0})
    # The ranked directory rankChannels writes (official first, then by followers); read by the
    # local functions stand-in (functions_stub.py), never by a phone.
    rows.sort(key=lambda r: (not r["official"], -r["followerCount"]))
    patch_doc("channelDirectory/all", {"rows": rows})
    print("channels seeded:", [c["cid"] for c in CHANNELS], "bobby follows", news)


if sys.argv[1:] == ["--channel"]:
    seed_channels()
    sys.exit(0)

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
