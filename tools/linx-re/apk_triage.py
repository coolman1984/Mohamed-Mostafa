#!/usr/bin/env python3
"""Safe, offline, evidence-first APK triage for an authorized Android app.

Not a decompiler, BLE decoder, crypto breaker or replacement for REA/JADX/Ghidra.
Does not execute the APK or persist its contents. Reports keyword *locations*,
not nearby bytes or cryptographic material. Python stdlib only.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
import re
import sys
import zipfile

MAX_MEMBER_BYTES = 256 * 1024 * 1024
MAX_TOTAL_BYTES = 2 * 1024 * 1024 * 1024
MAX_SCAN_BYTES = 64 * 1024 * 1024
KEYWORDS = (b"aidex", b"linx", b"f005", b"181f", b"gatt", b"pair", b"history", b"histories", b"aes", b"cfb", b"crc", b"bluetooth", b"qrcode", b"datamatrix")
ANALYZABLE = (".dex", ".so")


def digest_path(path: Path) -> str:
    h = sha256()
    with path.open("rb") as f:
        for part in iter(lambda: f.read(1024 * 1024), b""):
            h.update(part)
    return h.hexdigest()


def kind(name: str) -> str:
    name = name.lower()
    if name.endswith(".dex"): return "dex"
    if name.endswith(".so"): return "native"
    if name == "androidmanifest.xml": return "manifest"
    if name.startswith("res/") or name.endswith("resources.arsc"): return "resources"
    if name.startswith("assets/"): return "assets"
    if name.startswith("meta-inf/"): return "metadata"
    return "other"


def elf_arch(prefix: bytes) -> str | None:
    if prefix[:4] != b"\x7fELF" or len(prefix) < 20:
        return None
    machine = int.from_bytes(prefix[18:20], "little" if prefix[5] == 1 else "big")
    return {40: "ARM32", 183: "ARM64", 3: "x86", 62: "x86_64"}.get(machine, f"other({machine})")


def analyze_apk(apk: Path) -> dict:
    if not apk.is_file():
        raise FileNotFoundError(f"APK not found: {apk}")
    if not zipfile.is_zipfile(apk):
        raise ValueError("The provided file is not a readable APK/ZIP archive")
    report = {
        "schema": 1,
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "apk": {"filename": apk.name, "bytes": apk.stat().st_size, "sha256": digest_path(apk)},
        "purpose": "evidence-first authorized static triage; not proof of runtime behavior",
        "members": [], "keyword_evidence": [], "warnings": [], "counts": {},
    }
    with zipfile.ZipFile(apk) as z:
        infos = z.infolist()
        if len(infos) > 50000:
            raise ValueError("Too many archive members")
        if sum(i.file_size for i in infos) > MAX_TOTAL_BYTES:
            raise ValueError("Uncompressed archive exceeds safe inventory bound")
        counts = Counter()
        for info in infos:
            if info.is_dir():
                continue
            name = info.filename
            category = kind(name)
            counts[category] += 1
            entry = {"name": name, "kind": category, "bytes": info.file_size, "compressed_bytes": info.compress_size}
            if info.file_size > MAX_MEMBER_BYTES:
                entry["status"] = "SKIPPED_MEMBER_TOO_LARGE"
                report["warnings"].append(f"Skipped large member: {name}")
                report["members"].append(entry)
                continue
            if category in ("dex", "native", "manifest"):
                # Read at most MAX_SCAN_BYTES, and never write the extracted member to disk.
                with z.open(info) as f:
                    chunk = f.read(MAX_SCAN_BYTES + 1)
                sampled = chunk[:MAX_SCAN_BYTES]
                entry["sampled_bytes"] = len(sampled)
                entry["sample_sha256"] = sha256(sampled).hexdigest()
                if len(chunk) > MAX_SCAN_BYTES:
                    entry["status"] = "PARTIAL_SCAN"
                    report["warnings"].append(f"Partial keyword scan: {name}")
                else:
                    entry["status"] = "SCANNED"
                if category == "native":
                    entry["elf_arch"] = elf_arch(sampled[:32])
                if category == "manifest":
                    entry["encoding"] = "plain-text XML" if sampled.lstrip().startswith(b"<?xml") else "binary-or-unknown"
                if category in ("dex", "native"):
                    low = sampled.lower()
                    for keyword in KEYWORDS:
                        hits = [m.start() for m in re.finditer(re.escape(keyword), low)]
                        if hits:
                            report["keyword_evidence"].append({"member": name, "keyword": keyword.decode("ascii"), "hit_count": len(hits), "first_16_offsets": hits[:16], "evidence_only": True})
            report["members"].append(entry)
        report["counts"] = dict(sorted(counts.items()))
    report["warnings"].append("Static keyword matches are clues, not decoded methods, protocol confirmation or cryptographic proof.")
    report["warnings"].append("Missing methods can be obfuscated, packed, encrypted, loaded dynamically or not represented by ASCII strings.")
    report["warnings"].append("Never publish this report without checking filenames/digests for private identifiers.")
    return report


def make_md(report: dict) -> str:
    a = report["apk"]
    out = ["# Authorized Android APK triage", "", f"- APK: `{a['filename']}`", f"- SHA-256: `{a['sha256']}`", f"- Size: {a['bytes']} bytes", "- Method: offline ZIP inventory and bounded keyword-location scan; **not full reverse engineering**", "", "## File categories", ""]
    for k, n in report["counts"].items():
        out.append(f"- {k}: {n}")
    out.extend(["", "## Native libraries and DEX", ""])
    for m in report["members"]:
        if m["kind"] in ("dex", "native"):
            arch = f" [{m['elf_arch']}]" if m.get("elf_arch") else ""
            out.append(f"- `{m['name']}`{arch}: {m['bytes']} bytes, {m.get('status', 'INVENTORIED')}")
    out.extend(["", "## Interesting evidence locations (NOT confirmed behavior)", ""])
    for h in report["keyword_evidence"]:
        out.append(f"- `{h['member']}`: `{h['keyword']}`, count {h['hit_count']}, offsets {h['first_16_offsets']}")
    if not report["keyword_evidence"]:
        out.append("- No matching ASCII byte patterns in sampled code (not proof of absence).")
    out.extend(["", "## Caveats and next verification", ""])
    for warning in report["warnings"]:
        out.append(f"- {warning}")
    out.extend(["", "Next: run REA's available Android tools/JADX on the **authorized** APK, REA/Ghidra on `.so`, cross-check with pinned Juggluco source, then validate against approved, locally redacted HCI captures.", ""])
    return "\n".join(out)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--apk", type=Path, required=True, help="Local authorized APK path")
    p.add_argument("--out", type=Path, required=True, help="Output directory for report only")
    args = p.parse_args(argv)
    try:
        report = analyze_apk(args.apk)
        args.out.mkdir(parents=True, exist_ok=True)
        (args.out / "inventory.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        (args.out / "inventory.md").write_text(make_md(report), encoding="utf-8")
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError) as exc:
        print(f"Triage failed: {exc}", file=sys.stderr)
        return 1
    print("Inventory complete. Review reports before sharing; no APK payload was extracted.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
