#!/usr/bin/env python3
"""Offline declared-identity/profile/XML ledger. Never runs tests or grants acceptance/P1."""

import argparse
import hashlib
import json
import math
from pathlib import Path, PurePosixPath
import re
import sys
import xml.etree.ElementTree as ET

from tools.check_android_results import read_cases
from tools.run_android_tests import discover


class Invalid(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise Invalid(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def decode_json(data):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "Duplicate JSON key")
            result[key] = value
        return result
    value = json.loads(data, object_pairs_hook=unique)
    require(isinstance(value, dict), "Expected JSON object")
    return value


def valid_hash(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def pinned(root, pin):
    require(isinstance(pin, dict) and set(pin) == {"path", "sha256"}, "Malformed artifact pin")
    name = pin["path"]
    require(isinstance(name, str) and name and "\\" not in name, "Invalid artifact path")
    relative = PurePosixPath(name)
    require(not relative.is_absolute() and all(p not in (".", "..") for p in relative.parts)
            and str(relative) == name, "Artifact path must be canonical and relative")
    path = root
    require(not root.is_symlink(), "Linked artifact root")
    for part in relative.parts:
        path = path / part
        require(not path.is_symlink(), "Linked artifact path")
    require(path.is_file() and valid_hash(pin["sha256"]), "Missing artifact or invalid digest")
    data = path.read_bytes()
    require(data and digest(data) == pin["sha256"], "Artifact content changed")
    return path, data


def json_pin(root, pin):
    return decode_json(pinned(root, pin)[1])


def nonempty_text(value):
    return isinstance(value, str) and bool(value.strip())


def identities(root, pin):
    return discover(pinned(root, pin)[1].decode("utf-8"))


def load_plan(root, pin):
    plan = json_pin(root, pin)
    require(plan.get("format") == 1, "Unknown plan format")
    source = json_pin(root, plan["source_manifest"])
    files = source.get("files")
    require(isinstance(files, dict) and files and all(nonempty_text(k) and valid_hash(v)
            for k, v in files.items()), "Invalid frozen source map")
    profiles = plan.get("profiles")
    require(isinstance(profiles, dict) and profiles, "No explicit profiles")
    for name, pin_value in profiles.items():
        require(nonempty_text(name), "Empty profile name")
        config = json_pin(root, pin_value)
        require(nonempty_text(config.get("runner")) and nonempty_text(config.get("variant"))
                and type(config.get("coverage")) is bool, "Incomplete profile configuration")
        apks = config.get("apk_sha256")
        require(isinstance(apks, dict) and len(apks) == 2 and
                all(nonempty_text(k) and valid_hash(v) for k, v in apks.items()), "Both profile APK hashes required")
    discoveries = plan.get("discoveries")
    require(isinstance(discoveries, list) and discoveries, "Real full discovery artifacts required")
    universe, by_profile = set(), {}
    for item in discoveries:
        profile = item["profile"]
        require(profile in profiles and profile not in by_profile and item.get("scope") == "whole-suite",
                "Discovery must be one reviewed whole-suite capture per profile")
        capture = json_pin(root, item["capture"])
        require(capture.get("scope") == "whole-suite" and
                capture.get("source_manifest_sha256") == plan["source_manifest"]["sha256"] and
                capture.get("profile_config_sha256") == profiles[profile]["sha256"] and
                capture.get("discovery_sha256") == item["log"]["sha256"],
                "Full discovery capture has another source/profile/scope/log")
        found = identities(root, item["log"])
        by_profile[profile] = found
        universe.update(found)
    require(set(by_profile) == set(profiles), "A declared profile has no full discovery")
    by_class = {}
    for cls, method in universe:
        by_class.setdefault(cls, set()).add((cls, method))
    units, owners = {}, {}
    raw_units = plan.get("units")
    require(isinstance(raw_units, list) and raw_units, "No complete-class execution units")
    for unit in raw_units:
        name = unit["id"]
        require(nonempty_text(name) and name not in units, "Duplicate or empty unit")
        require(unit.get("profile") in profiles and unit.get("kind") in ("safety", "diagnostic"),
                "Explicit reviewed profile/kind required; no name classification")
        require(type(unit.get("primary")) is bool and type(unit.get("same_process")) is bool
                and nonempty_text(unit.get("reason")), "Explicit unit ownership/contract required")
        classes = unit.get("classes")
        require(isinstance(classes, list) and classes and len(classes) == len(set(classes))
                and all(cls in by_class for cls in classes), "Unknown or duplicate class selection")
        expected = set().union(*(by_class[cls] for cls in classes))
        require(expected <= by_profile[unit["profile"]], "Profile does not discover every assigned class method")
        budgets = unit.get("budgets")
        require(isinstance(budgets, dict) and budgets and all(nonempty_text(k) and type(v) is int and v > 0
                for k, v in budgets.items()), "Predeclared positive phase/batch budgets required")
        if unit["primary"]:
            for cls in classes:
                require(cls not in owners, "Class has multiple primary execution units")
                owners[cls] = name
        units[name] = (unit, expected)
    require(set(owners) == set(by_class), "Full discovery contains unassigned classes")
    return plan, universe, units


def xml_outcomes(root, pins):
    require(isinstance(pins, list) and pins, "Original XML report pins required")
    outcomes, parents = {}, {}
    for pin in pins:
        path, data = pinned(root, pin)
        require(path.name.startswith("TEST-") and path.suffix == ".xml", "Expected original TEST XML")
        require(b"<!DOCTYPE" not in data and b"<!ENTITY" not in data, "XML declarations are not accepted")
        require(path not in parents.setdefault(path.parent, set()), "Duplicate XML artifact")
        parents[path.parent].add(path)
        suite = ET.fromstring(data)
        require(suite.tag == "testsuite", "Expected testsuite")
        counters = {k: int(suite.attrib[k]) for k in ("tests", "failures", "errors", "skipped")}
        cases = suite.findall("testcase")
        require(counters["tests"] == len(cases) and cases, "Empty or inconsistent suite")
        actual_counts = {"failures": 0, "errors": 0, "skipped": 0}
        for case in cases:
            key = (case.attrib["classname"], case.attrib["name"])
            require(all(nonempty_text(v) for v in key) and key not in outcomes, "Duplicate/empty testcase identity")
            seconds = float(case.attrib["time"])
            require(math.isfinite(seconds) and seconds >= 0, "Invalid or unfinished testcase duration")
            bad = [node for node in case if node.tag in ("failure", "error", "skipped")]
            require(len(bad) <= 1, "Ambiguous testcase status")
            state = "PASS"
            if bad:
                state = {"failure": "FAIL", "error": "ERROR", "skipped": "SKIPPED"}[bad[0].tag]
                actual_counts[{"failure": "failures", "error": "errors", "skipped": "skipped"}[bad[0].tag]] += 1
            outcomes[key] = {"state": state, "seconds": seconds, "xml": pin,
                             "failure_sha256": digest(ET.tostring(bad[0])) if bad else None}
        require(all(counters[k] == v for k, v in actual_counts.items()), "XML counters conceal case outcomes")
    # Never select only passing XML files from one result directory.
    for parent, declared in parents.items():
        require(set(parent.glob("TEST-*.xml")) == declared, "Unpinned XML report in result directory")
    if all(item["state"] == "PASS" for item in outcomes.values()):
        strict = set()
        for parent in parents:
            cases, errors = read_cases(parent)
            require(not errors and not strict.intersection(cases), "Original strict all-pass XML validator rejected input")
            strict.update(cases)
        require(strict == set(outcomes), "Strict XML identity mismatch")
    return outcomes


def ledger(root, plan_pin, results_pin=None):
    plan, universe, units = load_plan(root, plan_pin)
    runs = {}
    if results_pin is not None:
        results = json_pin(root, results_pin)
        require(results.get("format") == 1 and results.get("plan_sha256") == plan_pin["sha256"],
                "Result collection belongs to another plan")
        require(isinstance(results.get("runs"), list), "Missing run collection")
        for run in results["runs"]:
            name = run["unit"]
            require(name in units and name not in runs, "Unknown or repeated unit run; retain attempts separately")
            runs[name] = run
    rows, reported_primary = [], set()
    for name, (unit, expected) in units.items():
        row = {"unit": name, "profile": unit["profile"], "kind": unit["kind"],
               "primary": unit["primary"], "expected_methods": len(expected), "issues": [], "cases": []}
        run = runs.get(name)
        if run is None:
            row["issues"].append("NOT_RUN")
        else:
            capture = json_pin(root, run["capture"])
            require(nonempty_text(run.get("run_id")) and capture.get("run_id") == run["run_id"]
                    and capture.get("unit") == name and capture.get("discovery") == run["discovery"]
                    and capture.get("reports") == run["reports"],
                    "Capture does not bind this exact run, unit, discovery and XML reports")
            require(capture.get("source_manifest_sha256") == plan["source_manifest"]["sha256"] and
                    capture.get("profile_config_sha256") == plan["profiles"][unit["profile"]]["sha256"],
                    "Run source/profile binding differs from reviewed unit")
            require(type(capture.get("exit_code")) is int and type(capture.get("cleanup_completed")) is bool,
                    "Run exit/cleanup record missing")
            pids = capture.get("process_ids")
            require(isinstance(pids, list) and pids and len(pids) == len(set(pids)) and
                    all(type(pid) is int and pid > 0 for pid in pids), "Actual process IDs required")
            require(identities(root, run["discovery"]) == expected, "Run discovery is not the exact complete-class unit")
            actual = xml_outcomes(root, run["reports"])
            require(set(actual) <= expected, "Result has identities outside its declared unit")
            if set(actual) != expected:
                row["issues"].append("INCOMPLETE_METHOD_SET")
            if capture["exit_code"] != 0:
                row["issues"].append("NONZERO_EXIT")
            if not capture["cleanup_completed"]:
                row["issues"].append("CLEANUP_NOT_PROVEN")
            if unit["same_process"] and len(pids) != 1:
                row["issues"].append("SAME_PROCESS_CONTRACT_NOT_MET")
            for key in sorted(expected):
                value = actual.get(key, {"state": "NOT_REPORTED"})
                row["cases"].append({"class": key[0], "method": key[1], **value})
            if unit["primary"]:
                reported_primary.update(actual)
        rows.append(row)
    all_xml_pass = all(not row["issues"] and all(c["state"] == "PASS" for c in row["cases"]) for row in rows)
    return {"format": 1, "status": "OFFLINE_LEDGER_ONLY", "plan_sha256": plan_pin["sha256"],
            "declared_discovered_methods": len(universe), "primary_reported_methods": len(reported_primary),
            "all_declared_primary_identities_reported": reported_primary == universe, "all_xml_pass": all_xml_pass,
            "units": rows, "discovery_scope_independently_verified": False,
            "performance_waivers_evaluated": False,
            "is_full_gate_success": False, "is_p1_admission": False,
            "limit": "Pins and reviewed captures are inputs, not runtime attestation. No automatic waiver, coverage calibration or P1 success is produced."}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--plan", required=True)
    parser.add_argument("--plan-sha256", required=True)
    parser.add_argument("--results")
    parser.add_argument("--results-sha256")
    args = parser.parse_args(argv)
    try:
        require(bool(args.results) == bool(args.results_sha256), "Results need both path and digest")
        result = ledger(args.root, {"path": args.plan, "sha256": args.plan_sha256},
                        {"path": args.results, "sha256": args.results_sha256} if args.results else None)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        print("Offline ledger parsed; NOT a safety, full-suite or P1 pass.", file=sys.stderr)
        return 0
    except (Invalid, ValueError, KeyError, TypeError, OSError, ET.ParseError) as error:
        print(f"Invalid profile ledger: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
