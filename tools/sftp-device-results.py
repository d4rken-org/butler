#!/usr/bin/env python3
"""Checks :app-common-io's connected test results for the SFTP device tests.

Without the sftpHost/sftpPort instrumentation arguments those tests skip and the run still passes,
so this fails unless every expected class ran with no skipped or failed test. The default
expectation is every SFTP device test class; --class narrows it to the given classes. In the
default mode any other class in the SFTP package must not skip or fail either.

Usage: tools/sftp-device-results.py <results-dir> [--class <fqcn>[,<fqcn>...]]
"""
import argparse
import collections
import pathlib
import sys
import xml.etree.ElementTree as ET

PACKAGE = "eu.darken.butler.common.files.sftp"
EXPECTED = [
    f"{PACKAGE}.SftpArtSmokeDeviceTest",
    f"{PACKAGE}.SftpCredentialCipherDeviceTest",
    f"{PACKAGE}.SftpGatewayDeviceTest",
    f"{PACKAGE}.SftpThroughputDeviceTest",
]

parser = argparse.ArgumentParser()
parser.add_argument("results", type=pathlib.Path)
parser.add_argument("--class", dest="classes", default="")
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

for cls in sorted(counts):
    c = counts[cls]
    print(f"{cls}: tests={c['tests']} skipped={c['skipped']} failed={c['failed']}")

requested = [c for c in args.classes.split(",") if c]
expected = requested or EXPECTED
missing = [cls for cls in expected if not counts[cls]["tests"]]
if missing:
    sys.exit(f"SFTP device test classes did not execute: {', '.join(missing)}")

checked = set(expected)
if not requested:
    checked |= {cls for cls in counts if cls.startswith(PACKAGE + ".")}
bad = sorted(cls for cls in checked if counts[cls]["skipped"] or counts[cls]["failed"])
if bad:
    sys.exit(f"SFTP device test classes with skipped or failed tests: {', '.join(bad)}")
