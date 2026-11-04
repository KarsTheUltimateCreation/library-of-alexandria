#!/usr/bin/env python3
"""Builds the static extension repository (served from the `repo` branch / GitHub Pages / raw.githubusercontent).

Outputs into OUT_DIR:
  repo.json        legacy repo metadata; `index_v2` points Mihon at index.pb
  index.pb         gzipped protobuf index (schema below) - what current Mihon / Tachimanga read
  index.json       the same index as proto-JSON (human readable; Mihon also accepts it)
  index.min.json   legacy list format for older Tachiyomi forks
  apk/<file>.apk   extension APK (legacy layout: <base>/apk/<apk>)
  jar/<file>.jar   JVM jar flavour (advertised as Resources.jarUrl, field 501, as keiyoushi does)
  icon/<pkg>.png   icon (legacy layout: <base>/icon/<pkg>.png)

Schema: mihonapp/tachiyomix index/index.proto (as mirrored in keiyoushi/extensions-source
.github/scripts/index.proto, including the keiyoushi `jarUrl = 501` extension):

  message Index { string name = 1; string badgeLabel = 2; string signingKey = 3; Contact contact = 4;
                  oneof extensions { ExtensionList extensionList = 101; string extensionListUrl = 102; } }
  message Contact { string website = 1; optional string discord = 2; }
  message ExtensionList { repeated Extension extensions = 1; }
  message Extension { string name = 1; string packageName = 2; Resources resources = 3; string extensionLib = 4;
                      int64 versionCode = 5; string versionName = 6; ContentWarning contentWarning = 7;
                      repeated Source sources = 8; }
  message Resources { string apkUrl = 1; string iconUrl = 2; string jarUrl = 501; }
  message Source { int64 id = 1; string name = 2; string language = 3; string homeUrl = 4;
                   repeated string mirrorUrls = 5; optional string message = 7; }
  enum ContentWarning { UNSPECIFIED = 0; SAFE = 1; MIXED = 2; NSFW = 3; }

Usage:
  publish_repo.py --metadata extension/build/outputs/extension-metadata.json \
      --apk <apk> --jar <jar> --icon docs/icon-512.png \
      --base-url https://raw.githubusercontent.com/OWNER/library-of-alexandria/repo \
      --fingerprint <sha256 of signing cert> --out repo-out
"""
import argparse
import gzip
import json
import shutil
import sys
from pathlib import Path

CONTENT_WARNINGS = ["CONTENT_WARNING_UNSPECIFIED", "CONTENT_WARNING_SAFE", "CONTENT_WARNING_MIXED", "CONTENT_WARNING_NSFW"]


# ----------------------------------------------------------------------------- protobuf wire format

def _varint(n: int) -> bytes:
    if n < 0:
        n += 1 << 64  # int64 two's complement
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def _key(field: int, wire: int) -> bytes:
    return _varint((field << 3) | wire)


def f_str(field: int, value) -> bytes:
    if value is None or value == "":
        return b""  # proto3 default values are not serialized
    data = value.encode("utf-8")
    return _key(field, 2) + _varint(len(data)) + data


def f_opt_str(field: int, value) -> bytes:
    if value is None:
        return b""
    data = value.encode("utf-8")
    return _key(field, 2) + _varint(len(data)) + data


def f_int(field: int, value: int) -> bytes:
    return b"" if not value else _key(field, 0) + _varint(int(value))


def f_msg(field: int, payload: bytes) -> bytes:
    return _key(field, 2) + _varint(len(payload)) + payload


def encode_index(index: dict) -> bytes:
    out = f_str(1, index["name"]) + f_str(2, index.get("badgeLabel")) + f_str(3, index["signingKey"])
    c = index["contact"]
    out += f_msg(4, f_str(1, c["website"]) + f_opt_str(2, c.get("discord")))
    exts = b""
    for e in index["extensionList"]["extensions"]:
        r = e["resources"]
        res = f_str(1, r["apkUrl"]) + f_str(2, r["iconUrl"]) + f_str(501, r.get("jarUrl"))
        body = f_str(1, e["name"]) + f_str(2, e["packageName"]) + f_msg(3, res) + f_str(4, e["extensionLib"])
        body += f_int(5, int(e["versionCode"])) + f_str(6, e["versionName"])
        body += f_int(7, CONTENT_WARNINGS.index(e["contentWarning"]))
        for s in e["sources"]:
            src = f_int(1, int(s["id"])) + f_str(2, s["name"]) + f_str(3, s["language"]) + f_str(4, s.get("homeUrl"))
            for m in s.get("mirrorUrls", []):
                src += f_str(5, m)
            src += f_opt_str(7, s.get("message"))
            body += f_msg(8, src)
        exts += f_msg(1, body)
    out += f_msg(101, exts)
    return out


# ----------------------------------------------------------------------------- minimal decoder (for verification)

def _read_varint(buf: bytes, i: int):
    shift = result = 0
    while True:
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, i
        shift += 7


def decode_fields(buf: bytes):
    """Yields (field, wire, value) for one message level. Length-delimited values are raw bytes."""
    i = 0
    while i < len(buf):
        key, i = _read_varint(buf, i)
        field, wire = key >> 3, key & 7
        if wire == 0:
            v, i = _read_varint(buf, i)
        elif wire == 2:
            n, i = _read_varint(buf, i)
            v = buf[i:i + n]
            i += n
        elif wire == 1:
            v = buf[i:i + 8]; i += 8
        elif wire == 5:
            v = buf[i:i + 4]; i += 4
        else:
            raise ValueError(f"unsupported wire type {wire}")
        yield field, wire, v


def decode_index(buf: bytes) -> dict:
    idx = {"extensionList": {"extensions": []}}
    for f, _, v in decode_fields(buf):
        if f == 1: idx["name"] = v.decode()
        elif f == 2: idx["badgeLabel"] = v.decode()
        elif f == 3: idx["signingKey"] = v.decode()
        elif f == 4: idx["contact"] = {({1: "website", 2: "discord"})[cf]: cv.decode() for cf, _, cv in decode_fields(v)}
        elif f == 101:
            for ef, _, ev in decode_fields(v):
                if ef != 1:
                    continue
                e = {"sources": [], "contentWarning": CONTENT_WARNINGS[0]}
                for xf, _, xv in decode_fields(ev):
                    if xf == 1: e["name"] = xv.decode()
                    elif xf == 2: e["packageName"] = xv.decode()
                    elif xf == 3: e["resources"] = {({1: "apkUrl", 2: "iconUrl", 501: "jarUrl"}).get(rf, str(rf)): rv.decode() for rf, _, rv in decode_fields(xv)}
                    elif xf == 4: e["extensionLib"] = xv.decode()
                    elif xf == 5: e["versionCode"] = str(xv)
                    elif xf == 6: e["versionName"] = xv.decode()
                    elif xf == 7: e["contentWarning"] = CONTENT_WARNINGS[xv]
                    elif xf == 8:
                        s = {}
                        for sf, _, sv in decode_fields(xv):
                            if sf == 1: s["id"] = str(sv if sv < (1 << 63) else sv - (1 << 64))
                            elif sf == 2: s["name"] = sv.decode()
                            elif sf == 3: s["language"] = sv.decode()
                            elif sf == 4: s["homeUrl"] = sv.decode()
                            elif sf == 5: s.setdefault("mirrorUrls", []).append(sv.decode())
                            elif sf == 7: s["message"] = sv.decode()
                        e["sources"].append(s)
                idx["extensionList"]["extensions"].append(e)
    return idx


# ----------------------------------------------------------------------------- main

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--metadata", required=True)
    ap.add_argument("--apk", required=True)
    ap.add_argument("--jar")
    ap.add_argument("--icon", required=True)
    ap.add_argument("--base-url", required=True, help="public URL of the published repo directory, no trailing slash")
    ap.add_argument("--artifact-base-url", help="where the APK/JAR are downloadable (e.g. a GitHub release download URL); "
                    "defaults to <base-url>/apk and <base-url>/jar")
    ap.add_argument("--fingerprint", required=True, help="SHA-256 of the APK signing certificate (lowercase hex)")
    ap.add_argument("--website", default="https://github.com/")
    ap.add_argument("--name", default="Library of Alexandria")
    ap.add_argument("--badge", default="LoA")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    meta = json.loads(Path(a.metadata).read_text())
    fp = a.fingerprint.replace(":", "").lower()
    if len(fp) != 64 or any(ch not in "0123456789abcdef" for ch in fp):
        print("fingerprint must be a SHA-256 hex digest", file=sys.stderr)
        return 1
    base = a.base_url.rstrip("/")
    out = Path(a.out)
    for d in ("apk", "jar", "icon"):
        (out / d).mkdir(parents=True, exist_ok=True)

    apk_name = Path(a.apk).name
    shutil.copy2(a.apk, out / "apk" / apk_name)
    jar_url = None
    if a.jar:
        shutil.copy2(a.jar, out / "jar" / Path(a.jar).name)
        jar_url = f"{base}/jar/{Path(a.jar).name}"
    shutil.copy2(a.icon, out / "icon" / f"{meta['pkg']}.png")

    resources = {"apkUrl": f"{base}/apk/{apk_name}", "iconUrl": f"{base}/icon/{meta['pkg']}.png"}
    if jar_url:
        resources["jarUrl"] = jar_url
    if a.artifact_base_url:
        # Same hosting as keiyoushi: binaries served from GitHub Releases, index/icons from the repo branch.
        art = a.artifact_base_url.rstrip("/")
        resources["apkUrl"] = f"{art}/{apk_name}"
        if jar_url:
            resources["jarUrl"] = f"{art}/{Path(a.jar).name}"
    index = {
        "name": a.name,
        "badgeLabel": a.badge,
        "signingKey": fp,
        "contact": {"website": a.website},
        "extensionList": {"extensions": [{
            "name": meta["name"],
            "packageName": meta["pkg"],
            "resources": resources,
            "extensionLib": meta["libVersion"],
            "versionCode": str(meta["code"]),
            "versionName": meta["version"],
            "contentWarning": CONTENT_WARNINGS[meta["contentWarning"]],
            "sources": [{"id": str(s["id"]), "name": s["name"], "language": s["lang"], "homeUrl": s["baseUrl"]} for s in meta["sources"]],
        }]},
    }
    pb = encode_index(index)
    assert decode_index(pb)["extensionList"]["extensions"][0]["resources"] == resources, "protobuf round trip failed"
    (out / "index.pb").write_bytes(gzip.compress(pb, mtime=0))
    (out / "index.json").write_text(json.dumps(index, indent=2, ensure_ascii=False) + "\n")

    legacy = [{
        "name": f"Tachiyomi: {meta['name']}",
        "pkg": meta["pkg"],
        "apk": apk_name,
        "lang": meta["lang"],
        "code": meta["code"],
        "version": meta["version"],
        "nsfw": meta["nsfw"],
        "sources": [{"name": s["name"], "lang": s["lang"], "id": str(s["id"]), "baseUrl": s["baseUrl"]} for s in meta["sources"]],
    }]
    (out / "index.min.json").write_text(json.dumps(legacy, separators=(",", ":"), ensure_ascii=False))
    (out / "repo.json").write_text(json.dumps({
        "index_v2": f"{base}/index.pb",
        "meta": {"name": a.name, "shortName": a.badge, "website": a.website, "signingKeyFingerprint": fp},
    }, indent=2) + "\n")
    print(f"repository written to {out} ({len(pb)} byte index)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
