"""Seed the normal Wrapper cache from Gradle's official GitHub release API."""
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
API = "https://api.github.com/repos/gradle/gradle-distributions"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def request(url, accept="application/vnd.github+json"):
    headers = {"User-Agent": "airplay-gradle-bootstrap", "Accept": accept,
               "Cache-Control": "no-cache"}
    token = os.environ.get("GITHUB_TOKEN")
    if token and urllib.parse.urlparse(url).hostname == "api.github.com":
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, headers=headers)
    try:
        return urllib.request.build_opener(NoRedirect).open(req, timeout=120)
    except urllib.error.HTTPError as error:
        if error.code not in (301, 302, 303, 307, 308):
            raise
        location = error.headers["Location"]
        error.close()
        parsed = urllib.parse.urlparse(location)
        if parsed.scheme != "https" or parsed.hostname not in (
            "release-assets.githubusercontent.com", "objects.githubusercontent.com"
        ):
            raise RuntimeError("Unexpected release download redirect host") from error
        # Never forward the GitHub API token to the signed asset CDN URL.
        return urllib.request.urlopen(urllib.request.Request(location, headers={
            "User-Agent": "airplay-gradle-bootstrap"
        }), timeout=120)


def cache_location(props, home):
    url = props["distributionUrl"].replace("\\:", ":")
    name = Path(urllib.parse.urlparse(url).path).name
    match = re.fullmatch(r"gradle-([0-9][0-9A-Za-z.+-]*)-bin\.zip", name)
    if not match:
        raise RuntimeError("Unsupported Wrapper distribution name")
    for key in ("distributionBase", "zipStoreBase"):
        if props.get(key, "GRADLE_USER_HOME") != "GRADLE_USER_HOME":
            raise RuntimeError("Bootstrap requires GRADLE_USER_HOME cache bases")
    if props.get("distributionPath", "wrapper/dists") != props.get("zipStorePath", "wrapper/dists"):
        raise RuntimeError("Bootstrap requires matching distribution/ZIP cache paths")
    # Match Gradle 9.8 PathAssembler: unsigned MD5 of URL, encoded in base 36.
    number = int.from_bytes(hashlib.md5(url.encode(), usedforsecurity=False).digest(), "big")
    alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
    encoded = ""
    while number:
        number, digit = divmod(number, 36)
        encoded = alphabet[digit] + encoded
    directory = home / props.get("zipStorePath", "wrapper/dists") / name[:-4] / (encoded or "0")
    return match[1], directory / name


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as file:
        for block in iter(lambda: file.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def main():
    props = dict(line.split("=", 1) for line in
                 (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text().splitlines()
                 if line.strip() and not line.startswith("#") and "=" in line)
    expected = props.get("distributionSha256Sum", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise RuntimeError("A pinned official distribution SHA-256 is required")
    home = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
    version, archive = cache_location(props, home)
    installed = archive.parent / ("gradle-" + version) / "bin/gradle"
    if Path(str(archive) + ".ok").is_file() and installed.is_file():
        print("Using installed Gradle Wrapper cache", flush=True)
        return
    archive.parent.mkdir(parents=True, exist_ok=True)
    if archive.is_file() and digest(archive) == expected:
        print("Using SHA-256 verified Gradle archive cache", flush=True)
        return
    partial = Path(str(archive) + ".bootstrap-part")
    for attempt in range(3):
        try:
            with request(API + "/releases/tags/v" + version) as response:
                release = json.load(response)
            asset = next(item for item in release["assets"] if item["name"] == archive.name)
            if asset.get("digest") and asset["digest"] != "sha256:" + expected:
                raise RuntimeError("Official release digest differs from pinned checksum")
            url = API + "/releases/assets/" + str(asset["id"]) + "?download=1&ts=" + str(time.time_ns())
            with request(url, "application/octet-stream") as response, partial.open("wb") as output:
                for block in iter(lambda: response.read(1024 * 1024), b""):
                    output.write(block)
            if partial.stat().st_size != asset["size"] or digest(partial) != expected:
                raise RuntimeError("Gradle distribution integrity check failed")
            partial.replace(archive)
            print("Verified Gradle " + version + " cached for standard Wrapper startup", flush=True)
            return
        except (OSError, http.client.HTTPException, RuntimeError, ValueError, KeyError, StopIteration) as error:
            partial.unlink(missing_ok=True)
            # Error type/status only: never log tokens or signed redirect URLs.
            status = " HTTP " + str(error.code) if isinstance(error, urllib.error.HTTPError) else ""
            print("Gradle bootstrap attempt " + str(attempt + 1) + " failed: " + type(error).__name__ + status, flush=True)
            if attempt == 2:
                raise RuntimeError("Official Gradle asset bootstrap failed after three attempts") from None
            time.sleep(2 ** (attempt + 1))


if __name__ == "__main__":
    main()
