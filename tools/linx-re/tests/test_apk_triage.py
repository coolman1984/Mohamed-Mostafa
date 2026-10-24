import tempfile
import unittest
from pathlib import Path
import zipfile
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from apk_triage import analyze_apk, elf_arch, kind, make_md


class TriageTest(unittest.TestCase):
    def test_kind_and_elf(self):
        self.assertEqual(kind("lib/arm64-v8a/libblecomm-lib.so"), "native")
        self.assertEqual(kind("classes2.dex"), "dex")
        data = bytearray(32)
        data[:4] = b"\x7fELF"
        data[5] = 1
        data[18:20] = (183).to_bytes(2, "little")
        self.assertEqual(elf_arch(data), "ARM64")

    def test_safe_archive(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "test.apk"
            with zipfile.ZipFile(apk, "w") as z:
                z.writestr("AndroidManifest.xml", b"<?xml version='1.0'?><manifest/>")
                z.writestr("classes.dex", b"\x00AIDEX F005 BLE GATT pair keySECRET")
                z.writestr("lib/arm64-v8a/libblecomm-lib.so", b"\x7fELF" + bytes(14) + (183).to_bytes(2, "little") + b"aes history crc")
            r = analyze_apk(apk)
            self.assertEqual(r["counts"]["native"], 1)
            self.assertEqual(r["counts"]["dex"], 1)
            self.assertTrue(any(x["keyword"] == "f005" for x in r["keyword_evidence"]))
            self.assertNotIn("SECRET", make_md(r))
            self.assertEqual(r["members"][0]["encoding"], "plain-text XML")

    def test_unreadable(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "bad.apk"
            p.write_bytes(b"not an apk")
            with self.assertRaises(ValueError):
                analyze_apk(p)


if __name__ == "__main__":
    unittest.main()
