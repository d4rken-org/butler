#!/usr/bin/env python3
"""Find a UI node by text or content-desc in a uiautomator XML dump and print
the tap center as "x y". Exit 1 if not found.

Usage: find_node.py [-c] ui.xml "Needle" [match_index]
  -c   substring (contains) match instead of exact
"""
import sys, re
import xml.etree.ElementTree as ET

args = sys.argv[1:]
contains = False
if args and args[0] == "-c":
    contains = True
    args = args[1:]
if len(args) < 2:
    sys.exit("usage: find_node.py [-c] ui.xml needle [index]")
path, needle = args[0], args[1]
idx = int(args[2]) if len(args) > 2 else 0

def hit(v):
    return v is not None and (needle in v if contains else v == needle)

root = ET.parse(path).getroot()
matches = [n for n in root.iter("node") if hit(n.get("text")) or hit(n.get("content-desc"))]
if idx < len(matches):
    m = re.findall(r"\d+", matches[idx].get("bounds"))
    print((int(m[0]) + int(m[2])) // 2, (int(m[1]) + int(m[3])) // 2)
    sys.exit(0)
sys.exit(1)
