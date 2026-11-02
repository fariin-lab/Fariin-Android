"""Seed the LOCAL Firebase emulator (project demo-fariin) with two fake users.

Nothing here can reach the real project: the URLs are 127.0.0.1 and the project id is demo-fariin.
Accounts and their generated passwords go to test-accounts.json next to this file (git-ignored).

    python seed.py              # new accounts
    python seed.py --profiles   # refresh the existing accounts' profile fields
    python seed.py --channel    # demo channels: alice runs them, bobby follows "Fariin News"
    python seed.py --carol      # a third fake user, carol (low-end phone tests), added to the same file
    python seed.py --photos     # plain group "Photo Test" (alice + carol) with 10 photos from alice
    python seed.py --widget     # three plain groups with carol, newest lines unread (home screen widget tests)
    python seed.py --carol-stories  # 1:1 chats alice-carol and bobby-carol, and stories for carol to watch:
                                    # alice (plain, and one with Block screenshots), bobby (replies off)
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
    # The server makes this counter on sign-up; the story rules read it, so a story post fails without it.
    patch_doc(f"users/{uid}/limits/storiesActive", {"count": 0})


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


def load_accounts():
    path = os.path.join(HERE, "test-accounts.json")
    return json.load(open(path)) if os.path.exists(path) else []


def seed_carol():
    """A third account for the low-end phone, so alice and bobby stay on their own emulators."""
    accts = load_accounts()
    if any(a["handle"] == "carol" for a in accts):
        print("carol already there"); return
    email, pw = "carol@test.fariin.local", secrets.token_urlsafe(12)
    r = post(f"{AUTH}/accounts:signUp?key=fake-api-key", {"email": email, "password": pw, "returnSecureToken": True})
    uid = r["localId"]
    # The sign-in gate wants a proved email (a function locally missing), as alice and bobby have.
    post(f"{AUTH.replace('/v1', '/v1/projects/demo-fariin')}/accounts:update", {"localId": uid, "emailVerified": True},
         {"Authorization": "Bearer owner"})
    seed_profile(uid, "carol", "Carol Test")
    accts.append({"uid": uid, "handle": "carol", "name": "Carol Test", "email": email, "password": pw})
    json.dump(accts, open(os.path.join(HERE, "test-accounts.json"), "w"), indent=2)
    print("seeded carol", uid)


def upload(path, data, ctype):
    """Firebase Storage emulator upload; returns the download link the phone can open (10.0.2.2)."""
    import urllib.parse
    bucket = "demo-fariin.appspot.com"
    req = urllib.request.Request(f"http://127.0.0.1:9199/v0/b/{bucket}/o?name={urllib.parse.quote(path, safe='')}",
                                 data=data, method="POST", headers={"Content-Type": ctype, "Authorization": "Bearer owner"})
    with urllib.request.urlopen(req) as r:
        meta = json.loads(r.read())
    tok = meta.get("downloadTokens", "").split(",")[0]
    return f"http://10.0.2.2:9199/v0/b/{bucket}/o/{urllib.parse.quote(path, safe='')}?alt=media&token={tok}"


def seed_photos():
    """Plain group with 10 camera-sized photos (1280 x 960, about 80 KB each at JPEG 0.6), to measure what a
    chat with photos costs on a slow, paid connection."""
    import base64, io, random
    from PIL import Image, ImageDraw, ImageFilter
    accts = {a["handle"]: a for a in load_accounts()}
    alice, carol = accts["alice"], accts["carol"]
    cid = "demoPhotoTest"
    now = datetime.datetime.now(datetime.timezone.utc)
    users = [alice["uid"], carol["uid"]]
    patch_doc(f"conversations/{cid}", {
        "type": "group", "title": "Photo Test", "users": users, "admins": [alice["uid"]], "createdBy": alice["uid"],
        "createdAt": iso(now - datetime.timedelta(hours=1)), "updatedAt": iso(now), "e2ee": False,
        "names": {alice["uid"]: alice["name"], carol["uid"]: carol["name"]}, "photos": {},
        "unreadCount": {alice["uid"]: 0, carol["uid"]: 10}, "typing": {u: False for u in users},
        "lastMessage": "📷 Photo", "lastSender": alice["uid"], "lastPlainTag": ""})
    rnd = random.Random(7)
    for i in range(10):
        img = Image.new("RGB", (1280, 960), tuple(rnd.randrange(40, 220) for _ in range(3)))
        d = ImageDraw.Draw(img)
        for _ in range(400):   # busy picture so the JPEG is camera-sized, not a flat colour
            x, y = rnd.randrange(1280), rnd.randrange(960)
            d.ellipse([x, y, x + rnd.randrange(10, 120), y + rnd.randrange(10, 120)], fill=tuple(rnd.randrange(256) for _ in range(3)))
        img = img.filter(ImageFilter.GaussianBlur(1))
        buf = io.BytesIO(); img.save(buf, "JPEG", quality=60)
        th = img.copy(); th.thumbnail((40, 40)); tb = io.BytesIO(); th.save(tb, "JPEG", quality=45)
        mid = f"photo{i:02d}"
        url = upload(f"chat/{cid}/{mid}.enc", buf.getvalue(), "application/octet-stream")
        at = now - datetime.timedelta(minutes=10 - i)
        patch_doc(f"conversations/{cid}/messages/{mid}", {
            "type": "image", "text": "", "authorId": alice["uid"], "imageUrl": url, "width": 1280, "height": 960,
            "thumb": base64.b64encode(tb.getvalue()).decode(), "clientId": mid, "clientTs": at.timestamp() * 1000,
            "createdAt": iso(at)})
        print(mid, len(buf.getvalue()) // 1024, "KB")
    print("photo group seeded:", cid)


def plain_tag(s):
    """FNV-1a 64-bit hex of the UTF-8 bytes (Conversation.plainTag): marks a plain group's line as words."""
    h = 0xcbf29ce484222325
    for b in s.encode("utf-8"):
        h = ((h ^ b) * 0x100000001b3) & 0xFFFFFFFFFFFFFFFF
    return format(h, "x")


def seed_widget_chats():
    """Three plain groups with carol, each with a last line from someone else, so carol's chat list
    (and the home screen widget) has a few recent chats with unread counts."""
    accts = {a["handle"]: a for a in load_accounts()}
    alice, bobby, carol = accts["alice"], accts["bobby"], accts["carol"]
    now = datetime.datetime.now(datetime.timezone.utc)
    groups = [("demoWidgetTrip", "Weekend Plans", [alice, bobby, carol], bobby, "Bring the big tent, I have the stove", 2, 3),
              ("demoWidgetFamily", "Family", [alice, carol], alice, "Dinner at 7 tonight?", 1, 8),
              ("demoWidgetStudy", "Study Group", [bobby, carol], bobby, "Notes for chapter 4 are up", 0, 15)]
    for cid, title, members, author, line, unread, mins in groups:
        users = [m["uid"] for m in members]
        at = now - datetime.timedelta(minutes=mins)
        patch_doc(f"conversations/{cid}", {
            "type": "group", "title": title, "users": users, "admins": [author["uid"]], "createdBy": author["uid"],
            "createdAt": iso(at - datetime.timedelta(hours=1)), "updatedAt": iso(at), "e2ee": False,
            "names": {m["uid"]: m["name"] for m in members}, "photos": {},
            "unreadCount": {u: (unread if u == carol["uid"] else 0) for u in users}, "typing": {u: False for u in users},
            "lastMessage": line, "lastSender": author["uid"], "lastPlainTag": plain_tag(line)})
        patch_doc(f"conversations/{cid}/messages/w1", {
            "type": "text", "text": line, "authorId": author["uid"], "clientId": "w1",
            "clientTs": at.timestamp() * 1000, "createdAt": iso(at)})
        print("seeded", cid, title)


def seed_carol_stories():
    """Stories posted to carol, in the iOS postStory shape (StoriesService payload), so carol's phone can
    watch, react and reply without the other phones. recipientUids holds the hashed audience token."""
    import hashlib, io
    from PIL import Image, ImageDraw
    accts = {a["handle"]: a for a in load_accounts()}
    alice, bobby, carol = accts["alice"], accts["bobby"], accts["carol"]
    now = datetime.datetime.now(datetime.timezone.utc)
    token = hashlib.sha256(("fariin-audience-v1:" + carol["uid"]).encode()).hexdigest()
    for other in (alice, bobby):
        users = sorted([other["uid"], carol["uid"]])
        cid = "_".join(users)
        patch_doc(f"conversations/{cid}", {
            "users": users, "names": {other["uid"]: other["name"], carol["uid"]: carol["name"]}, "photos": {},
            "startedBy": other["uid"], "accepted": True, "createdAt": iso(now - datetime.timedelta(hours=2)),
            "updatedAt": iso(now - datetime.timedelta(hours=2)), "lastMessage": "", "lastSender": "",
            "unreadCount": {u: 0 for u in users}, "typing": {u: False for u in users}})
        print("chat", cid)
    stories = [("seedAlice01", alice, "Morning walk", (40, 120, 200), False, True, 30),
               ("seedAlice02", alice, "No screenshots please", (200, 80, 60), True, True, 20),
               ("seedBobby01", bobby, "Replies are off", (60, 160, 90), False, False, 10)]
    for sid, who, caption, rgb, secure, replies, mins in stories:
        img = Image.new("RGB", (720, 1280), rgb)
        d = ImageDraw.Draw(img)
        for i in range(0, 1280, 80):
            d.rectangle([0, i, 720, i + 40], fill=tuple(min(255, c + 30) for c in rgb))
        d.text((60, 600), caption, fill=(255, 255, 255))
        buf = io.BytesIO(); img.save(buf, "JPEG", quality=70)
        path = f"stories/{sid}/photo.jpg"
        url = upload(path, buf.getvalue(), "image/jpeg")
        at = now - datetime.timedelta(minutes=mins)
        patch_doc(f"stories/{sid}", {
            "authorUid": who["uid"], "createdAt": iso(at), "expiresAt": iso(at + datetime.timedelta(hours=24)),
            "type": "image", "mediaPath": path, "mediaUrl": url, "blurThumb": "", "caption": caption, "stickers": [],
            "audience": {"mode": "all", "listId": "my-story"}, "audienceLabel": "friends", "oneTime": False,
            "captureProtected": secure, "public": False, "allowsReplies": replies, "replyCount": 0,
            "recipientUids": [token]})
        print("story", sid, who["handle"], "secure" if secure else "", "" if replies else "replies off")


if sys.argv[1:] == ["--carol-stories"]:
    seed_carol_stories()
    sys.exit(0)

if sys.argv[1:] == ["--carol"]:
    seed_carol()
    sys.exit(0)

if sys.argv[1:] == ["--widget"]:
    seed_widget_chats()
    sys.exit(0)

if sys.argv[1:] == ["--photos"]:
    seed_photos()
    sys.exit(0)

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
