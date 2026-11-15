#!/usr/bin/env python3
"""Fail CI if the standard edition accidentally packages the Aurora feature."""
import hashlib
import json
import os
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[2]
RENDERERS = ("AuroraBackdrop", "AuroraRenderer", "AuroraView", "AuroraBeatCycle",
             "AuroraDepthWind", "AuroraFramePacer", "AuroraFrameRate", "AuroraMaterialCache",
             "AuroraMotion", "AuroraNoiseCache", "AuroraPreRendered", "AuroraQuality", "AuroraSampling")


def shader_fingerprints():
    paths = sorted((ROOT / "app/src/aurora/res/raw").glob("aurora_*.glsl"))
    assert paths, "Aurora shader source set is missing"
    return {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}


def verify(aurora, standard, files, mapping, shaders=None):
    shaders = shader_fingerprints() if shaders is None else shaders
    fingerprints = set(shaders.values())
    with zipfile.ZipFile(aurora) as full, zipfile.ZipFile(standard) as small:
        for item in files:
            name = "assets/" + item["file"]
            info = full.getinfo(name)
            assert info.compress_type == zipfile.ZIP_STORED, f"Must be seekable: {name}"
            assert info.file_size == item["bytes"]
            assert hashlib.sha256(full.read(name)).hexdigest() == item["sha256"], name
        # Media3 legitimately contributes its own GLSL to both editions.
        # Match our identities; hashes also catch renamed Aurora shader resources.
        leaked = [n for n in small.namelist() if n.startswith("assets/aurora_")]
        for n in small.namelist():
            if n.endswith(".glsl") and (Path(n).name in shaders or
                    hashlib.sha256(small.read(n)).hexdigest() in fingerprints):
                leaked.append(n)
        assert not leaked, "Aurora resources leaked into standard APK: " + ", ".join(leaked)
        packaged = {hashlib.sha256(full.read(n)).hexdigest() for n in full.namelist() if n.endswith(".glsl")}
        assert fingerprints <= packaged, "Aurora shaders missing or changed in APK"
    for name in RENDERERS:
        assert "io.github.jqssun.airplay.ui." + name not in mapping, f"Renderer leaked into standard: {name}"
    assets = sum(item["bytes"] for item in files)
    difference = aurora.stat().st_size - standard.stat().st_size
    assert difference >= assets * .95, "Standard package did not remove the material payload"
    return {"auroraApk": str(aurora), "auroraBytes": aurora.stat().st_size,
            "standardApk": str(standard), "standardBytes": standard.stat().st_size,
            "savedBytes": difference, "verifiedAssetBytes": assets}


def main():
    def apk(edition):
        paths = list((ROOT / f"app/build/outputs/apk/{edition}/release").glob("*.apk"))
        assert len(paths) == 1, f"Expected one {edition} APK, found {paths}"
        return paths[0]
    files = json.loads((ROOT / "tools/aurora-cache/manifest.json").read_text())["files"]
    mapping = (ROOT / "app/build/outputs/mapping/standardRelease/mapping.txt").read_text()
    report = verify(apk("aurora"), apk("standard"), files, mapping)
    print(json.dumps(report, indent=2))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as out:
            out.write("## APK editions\n\n| Edition | APK MiB |\n| --- | ---: |\n")
            out.write(f"| Aurora | {report['auroraBytes'] / 1048576:.2f} |\n")
            out.write(f"| Standard | {report['standardBytes'] / 1048576:.2f} |\n\n")
            out.write(f"Standard saves {report['savedBytes'] / 1048576:.2f} MiB; no aurora assets, aurora shaders or renderer classes.\n")


if __name__ == "__main__":
    main()
