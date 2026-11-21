"""Smoke-check visible navigation and editor on real Android emulator surfaces."""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "dev.antigravity.mobile"
OUT = "phone-layout-evidence"
os.makedirs(OUT, exist_ok=True)

def adb(*args):
    return subprocess.check_output(["adb", *args], timeout=45)

def shell(*args):
    return adb("shell", *args)

def dump(tag):
    for _ in range(4):
        shell("uiautomator", "dump", "/sdcard/window.xml")
        data = shell("cat", "/sdcard/window.xml")
        try:
            root = ET.fromstring(data)
            if any(n.attrib.get("package") == PACKAGE for n in root.iter("node")):
                with open(os.path.join(OUT, tag + ".xml"), "wb") as f:
                    f.write(data)
                with open(os.path.join(OUT, tag + ".png"), "wb") as f:
                    f.write(adb("exec-out", "screencap", "-p"))
                return root
        except ET.ParseError:
            pass
        time.sleep(2)
    raise AssertionError("Application UI not visible: " + tag)

def find(root, text):
    nodes = [n for n in root.iter("node") if n.get("package") == PACKAGE and n.get("text") == text]
    assert nodes, "Missing visible label: " + text
    n = nodes[0]
    x1,y1,x2,y2 = map(int,re.findall(r"\d+",n.get("bounds","")))
    assert x2 > x1 and y2 > y1, "Zero-sized control: " + text
    return (x1,y1,x2,y2)

def tap(root,text):
    x1,y1,x2,y2 = find(root,text)
    shell("input","tap",str((x1+x2)//2),str((y1+y2)//2))
    time.sleep(1)

def check_case(tag, size, density, rotation):
    shell("wm","size",size)
    shell("wm","density",str(density))
    shell("settings","put","system","accelerometer_rotation","0")
    shell("settings","put","system","user_rotation",str(rotation))
    shell("am","force-stop",PACKAGE)
    shell("am","start","-W","-n",PACKAGE+"/.MainActivity")
    time.sleep(3)
    root = dump(tag+"-explorer")
    for text in ("Files","Editor","Assistant","Open folder","New file","New folder"):
        find(root,text)
    tap(root,"Editor")
    root = dump(tag+"-editor")
    for text in ("Save","Undo","Redo","Find"):
        find(root,text)
    tap(root,"Assistant")
    root = dump(tag+"-assistant")
    find(root,"API settings")
    # Validate the screen can be scrolled to the send action, even in landscape.
    try:
        find(root,"Send request")
    except AssertionError:
        # Swipe within the actual application window, not hard-coded portrait pixels.
        candidates = [n for n in root.iter("node") if n.get("package") == PACKAGE and n.get("scrollable") == "true"]
        assert candidates, "Assistant must allow vertical scrolling"
        x1,y1,x2,y2 = map(int,re.findall(r"\d+",candidates[0].get("bounds","")))
        shell("input","swipe",str((x1+x2)//2),str(y2-10),str((x1+x2)//2),str(y1+10),"350")
        time.sleep(1)
        root = dump(tag+"-assistant-scrolled")
        find(root,"Send request")
    print("PASS",tag)

adb("install","-r","apk/app-debug.apk")
try:
    check_case("portrait-360x800","1080x2400",480,0)
    check_case("narrow-320x640","640x1280",320,0)
    check_case("landscape-800x360","1080x2400",480,1)
finally:
    shell("wm","size","reset")
    shell("wm","density","reset")
    shell("settings","put","system","user_rotation","0")
