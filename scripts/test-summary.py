#!/usr/bin/env python3
"""Summarise JUnit XML results: totals plus the first line of each failure."""
import glob
import sys
import xml.etree.ElementTree as ET

paths = sys.argv[1:] or glob.glob("backend/build/test-results/test/*.xml")
total = failed = skipped = 0
failures = []
for p in paths:
    root = ET.parse(p).getroot()
    for case in root.iter("testcase"):
        total += 1
        f = case.find("failure") if case.find("failure") is not None else case.find("error")
        if case.find("skipped") is not None:
            skipped += 1
        if f is not None:
            failed += 1
            msg = (f.get("message") or f.text or "").strip().splitlines()
            failures.append(f"{case.get('classname').split('.')[-1]} > {case.get('name')}\n    {msg[0][:400] if msg else ''}")
print(f"tests={total} failed={failed} skipped={skipped}")
for x in failures:
    print(x)
sys.exit(1 if failed else 0)
