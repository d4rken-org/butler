#!/usr/bin/env python3
"""Checks :app-common-io's connected test results for the WebDAV device tests.

A test class that is missing from the run, or whose tests all skip, still lets the run pass, so this
fails unless every expected class ran and no class in the WebDAV package skipped or failed a test.

Usage: tools/webdav-device-results.py <results-dir>
"""
import argparse
import collections
import pathlib
import sys
import xml.etree.ElementTree as ET

PACKAGE = "eu.darken.butler.common.files.webdav"
EXPECTED = [
    f"{PACKAGE}.WebDavArtDeviceTest",
]

parser = argparse.ArgumentParser()
parser.add_argument("results", type=pathlib.Path)
args = parser.parse_args()

counts = collections.defaultdict(collections.Counter)
for report in args.results.rglob("TEST-*.xml"):
    for case in ET.parse(report).getroot().iter("testcase"):
        tally = counts[case.get("classname", "")]
        tally["tests"] += 1
        if case.find("skipped") is not None:
            tally["skipped"] += 1
        if case.find("failure") is not None or case.find("error") is not None:
            tally["failed"] += 1

checked = set(EXPECTED) | {cls for cls in counts if cls.startswith(PACKAGE + ".")}
for cls in sorted(checked):
    c = counts[cls]
    print(f"{cls}: tests={c['tests']} skipped={c['skipped']} failed={c['failed']}")

missing = [cls for cls in EXPECTED if not counts[cls]["tests"]]
if missing:
    sys.exit(f"WebDAV device test classes did not execute: {', '.join(missing)}")

bad = sorted(cls for cls in checked if counts[cls]["skipped"] or counts[cls]["failed"])
if bad:
    sys.exit(f"WebDAV device test classes with skipped or failed tests: {', '.join(bad)}")
