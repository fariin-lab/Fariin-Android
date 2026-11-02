"""Tiny adb UI driver for the emulator: tap by visible text, type, screenshot.

    python -I tools/ui.py [-s emulator-5554] tap "Log in"
    python -I tools/ui.py type "hello"
    python -I tools/ui.py tapfield 0          # the n-th text field
    python -I tools/ui.py shot out.png
    python -I tools/ui.py texts               # list visible texts
"""
import os, re, subprocess, sys, time
sys.stdout.reconfigure(encoding="utf-8")

ADB = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb.exe")
args = sys.argv[1:]
serial = []
if args[:1] == ["-s"]:
    serial = ["-s", args[1]]; args = args[2:]


def adb(*a, binary=False):
    r = subprocess.run([ADB, *serial, *a], capture_output=True)
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    out = []
    for m in re.finditer(r"<node [^>]*>", xml):
        n = m.group(0)
        g = lambda k: (re.search(k + r'="([^"]*)"', n) or [None, ""])[1]
        b = re.findall(r"\d+", g("bounds"))
        if len(b) == 4:
            x1, y1, x2, y2 = map(int, b)
            out.append({"text": g("text"), "desc": g("content-desc"), "cls": g("class"),
                        "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2})
    return out


cmd = args[0]
if cmd == "tap":
    want = args[1]
    for _ in range(6):
        hit = [n for n in nodes() if want in (n["text"], n["desc"])]
        if hit:
            adb("shell", "input", "tap", str(hit[-1]["cx"]), str(hit[-1]["cy"])); print("tapped", want); break
        time.sleep(1)
    else:
        print("NOT FOUND", want); sys.exit(1)
elif cmd == "tapfield":
    fields = [n for n in nodes() if n["cls"].endswith("EditText")]
    f = fields[int(args[1])]
    adb("shell", "input", "tap", str(f["cx"]), str(f["cy"])); print("field", args[1])
elif cmd == "type":
    adb("shell", "input", "text", args[1].replace(" ", "%s")); print("typed")
elif cmd == "key":
    adb("shell", "input", "keyevent", args[1])
elif cmd == "shot":
    open(args[1], "wb").write(adb("exec-out", "screencap", "-p", binary=True)); print("shot", args[1])
elif cmd == "texts":
    for n in nodes():
        if n["text"] or n["desc"]:
            print(repr(n["text"] or n["desc"]), n["cx"], n["cy"])
