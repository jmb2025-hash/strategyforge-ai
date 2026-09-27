#!/usr/bin/env python3
"""Update rows of docs/traceability.csv.

Usage: trace_update.py ID[,ID...] [--status S] [--code C] [--test T] [--evidence E] [--design D]
Values for code/test are ';'-separated. Rendering happens via traceability.py.
"""
import argparse
import csv
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
PATH = os.path.join(ROOT, "docs/traceability.csv")

ap = argparse.ArgumentParser()
ap.add_argument("ids")
for k in ("status", "code", "test", "evidence", "design"):
    ap.add_argument("--" + k)
a = ap.parse_args()
rows = list(csv.DictReader(open(PATH, newline="", encoding="utf-8")))
ids = set(a.ids.split(","))
found = set()
for r in rows:
    if r["id"] in ids:
        found.add(r["id"])
        if a.status: r["status"] = a.status
        if a.code: r["code_ref"] = a.code
        if a.test: r["test_ref"] = a.test
        if a.evidence: r["evidence"] = a.evidence
        if a.design: r["design_ref"] = a.design
missing = ids - found
if missing:
    raise SystemExit(f"unknown ids: {sorted(missing)}")
with open(PATH, "w", newline="", encoding="utf-8") as f:
    w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
    w.writeheader()
    w.writerows(rows)
