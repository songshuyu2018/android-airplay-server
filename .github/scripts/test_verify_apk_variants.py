#!/usr/bin/env python3
"""Regression cases for shared Media3 GLSL versus actual Aurora leakage."""
import hashlib
from pathlib import Path
import tempfile
import unittest
import zipfile
from verify_apk_variants import verify


class EditionAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.full = root / "aurora.apk"
        self.small = root / "standard.apk"
        self.material = b"seekable compressed material" * 64
        self.shader = b"#version 300 es\n// distinct Aurora reveal shader\n"
        self.shared = b"// Media3 video transformation shader\n"
        self.files = [{"file": "aurora_band_0.aurora", "bytes": len(self.material),
                       "sha256": hashlib.sha256(self.material).hexdigest()}]
        self.shaders = {"aurora_composite.glsl": hashlib.sha256(self.shader).hexdigest()}
        self.standard_entries = {"res/raw/fragment_shader_copy_es2.glsl": self.shared}
        self.write(self.full, {"assets/aurora_band_0.aurora": self.material,
                              "res/raw/a.glsl": self.shader, **self.standard_entries})
        self.write(self.small, self.standard_entries)

    @staticmethod
    def write(path, entries):
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as z:
            for name, content in entries.items():
                z.writestr(name, content)

    def audit(self, mapping=""):
        return verify(self.full, self.small, self.files, mapping, self.shaders)

    def test_shared_media3_shader_allowed(self):
        self.assertGreater(self.audit()["savedBytes"], 0)

    def test_material_leak_rejected(self):
        self.write(self.small, {**self.standard_entries, "assets/aurora_band_0.aurora": b"bad"})
        with self.assertRaisesRegex(AssertionError, "assets/aurora_band_0"):
            self.audit()

    def test_named_shader_leak_rejected_even_if_modified(self):
        self.write(self.small, {**self.standard_entries, "res/raw/aurora_composite.glsl": b"modified"})
        with self.assertRaisesRegex(AssertionError, "aurora_composite"):
            self.audit()

    def test_renamed_shader_leak_rejected(self):
        self.write(self.small, {**self.standard_entries, "res/raw/z.glsl": self.shader})
        with self.assertRaisesRegex(AssertionError, "res/raw/z.glsl"):
            self.audit()

    def test_missing_aurora_shader_rejected(self):
        self.write(self.full, {"assets/aurora_band_0.aurora": self.material, **self.standard_entries})
        with self.assertRaisesRegex(AssertionError, "Aurora shaders missing"):
            self.audit()

    def test_renderer_leak_rejected(self):
        with self.assertRaisesRegex(AssertionError, "Renderer leaked"):
            self.audit("io.github.jqssun.airplay.ui.AuroraPreRendered -> a:")


if __name__ == "__main__":
    unittest.main()
