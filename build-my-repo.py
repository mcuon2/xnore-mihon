#!/usr/bin/env python3
import gzip
import importlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT_DIR / ".github" / "scripts"))

DIST_DIR = ROOT_DIR / "dist"
APK_DIR = DIST_DIR / "apk"
JAR_DIR = DIST_DIR / "jar"
ICON_DIR = DIST_DIR / "icon"

GITHUB_REPO = os.environ.get("GITHUB_REPOSITORY", "mcuon2/xnore-mihon")
RAW_BASE_URL = f"https://raw.githubusercontent.com/{GITHUB_REPO}/repo"
REPO_WEBSITE = f"https://github.com/{GITHUB_REPO}"
REPO_NAME = "Xnore Extensions"
REPO_SHORT_NAME = "XNORE"
DEFAULT_FINGERPRINT = "6dd0bec61ce11f506a5c90c395accde5ae615622b28e630a68c1b86ad7c1b23b"


def extract_fingerprint_from_jar(jar_path: Path) -> str | None:
    keytool = shutil.which("keytool")
    if not keytool and os.environ.get("JAVA_HOME"):
        candidate = Path(os.environ["JAVA_HOME"]) / "bin" / ("keytool.exe" if os.name == "nt" else "keytool")
        if candidate.exists():
            keytool = str(candidate)
    if not keytool:
        return None
    try:
        out = subprocess.check_output([keytool, "-printcert", "-jarfile", str(jar_path)], text=True)
        m = re.search(r"SHA256:\s*([0-9A-Fa-f:]+)", out)
        if m:
            return m.group(1).replace(":", "").lower()
    except Exception:
        pass
    return None


if DIST_DIR.exists():
    shutil.rmtree(DIST_DIR)

for d in (DIST_DIR, APK_DIR, JAR_DIR, ICON_DIR):
    d.mkdir(parents=True, exist_ok=True)

index_min_entries = []
proto_extensions = []
detected_fingerprint = None

try:
    index_pb2 = importlib.import_module("index_pb2")
    json_format = importlib.import_module("google.protobuf.json_format")
    has_protobuf = True
except ImportError:
    index_pb2 = None
    json_format = None
    has_protobuf = False

for info_path in sorted(ROOT_DIR.glob("src/*/*/build/keiyoushi-source-info.json")):
    info = json.loads(info_path.read_text(encoding="utf-8"))
    build_dir = info_path.parent
    ext_dir = build_dir.parent

    apk_files = list((build_dir / "outputs/apk/release").glob("*.apk"))
    jar_files = list((build_dir / "outputs/jar/release").glob("*.jar"))
    if not apk_files:
        continue

    apk_file = apk_files[0]
    shutil.copy2(apk_file, APK_DIR / apk_file.name)

    jar_file = jar_files[0] if jar_files else None
    if jar_file:
        shutil.copy2(jar_file, JAR_DIR / jar_file.name)
        if not detected_fingerprint:
            detected_fingerprint = extract_fingerprint_from_jar(jar_file)

    pkg_name = info["packageName"]
    icon_candidates = [
        ext_dir / "res/mipmap-xhdpi/ic_launcher.png",
        ext_dir / "res/mipmap-xxhdpi/ic_launcher.png",
        ext_dir / "res/mipmap-hdpi/ic_launcher.png",
    ]
    if info.get("theme"):
        icon_candidates.append(
            ROOT_DIR / "lib-multisrc" / info["theme"] / "res/mipmap-xhdpi/ic_launcher.png"
        )
    icon_candidates.append(ROOT_DIR / "core/src/main/res/mipmap-xhdpi/ic_launcher.png")

    icon_filename = f"{pkg_name}.png"
    for icon_src in icon_candidates:
        if icon_src.exists():
            shutil.copy2(icon_src, ICON_DIR / icon_filename)
            break

    nsfw_flag = 0 if info.get("contentWarning") == 1 else 1
    sources_list = info.get("sources", [])
    primary_lang = sources_list[0]["lang"] if len(sources_list) == 1 else "all"

    entry = {
        "name": f"Tachiyomi: {info['name']}",
        "pkg": pkg_name,
        "apk": apk_file.name,
        "lang": primary_lang,
        "code": info["versionCode"],
        "version": info["versionName"],
        "nsfw": nsfw_flag,
        "sources": [
            {
                "name": s["name"],
                "lang": s["lang"],
                "id": str(s["id"]),
                "baseUrl": s["baseUrl"],
            }
            for s in sources_list
        ],
    }
    index_min_entries.append(entry)

    if index_pb2 is not None:
        ext_proto = index_pb2.Extension(
            name=info["name"],
            packageName=pkg_name,
            resources=index_pb2.Resources(
                apkUrl=f"{RAW_BASE_URL}/apk/{apk_file.name}",
                iconUrl=f"{RAW_BASE_URL}/icon/{icon_filename}",
                jarUrl=f"{RAW_BASE_URL}/jar/{jar_file.name}" if jar_file else "",
            ),
            extensionLib=info["extensionLib"],
            versionCode=info["versionCode"],
            versionName=info["versionName"],
            contentWarning=info["contentWarning"],
            sources=[
                index_pb2.Source(
                    id=int(s["id"]),
                    name=s["name"],
                    language=s["lang"],
                    homeUrl=s["baseUrl"],
                    mirrorUrls=s.get("mirrorUrls", []),
                )
                for s in sources_list
            ],
        )
        proto_extensions.append(ext_proto)

    print(f"Packed: {info['name']} -> {apk_file.name}")

signing_fingerprint = detected_fingerprint or DEFAULT_FINGERPRINT

# 1. Xuất index.min.json (Tương thích Tachimanga iOS & Mihon chuẩn v1)
(DIST_DIR / "index.min.json").write_text(
    json.dumps(index_min_entries, ensure_ascii=False, separators=(",", ":")),
    encoding="utf-8",
)

# 2. Xuất index.pb và index.json (Tương thích Mihon 0.20.1+ chuẩn v2)
if index_pb2 is not None and json_format is not None:
    index_proto = index_pb2.Index(
        name=REPO_NAME,
        badgeLabel=REPO_SHORT_NAME,
        signingKey=signing_fingerprint,
        contact=index_pb2.Contact(website=REPO_WEBSITE),
        extensionList=index_pb2.ExtensionList(extensions=proto_extensions),
    )
    (DIST_DIR / "index.json").write_text(
        json_format.MessageToJson(
            index_proto,
            always_print_fields_with_no_presence=False,
            preserving_proto_field_name=True,
        ),
        encoding="utf-8",
    )
    with (DIST_DIR / "index.pb").open("wb") as f:
        f.write(gzip.compress(index_proto.SerializeToString(deterministic=True), mtime=0))
else:
    (DIST_DIR / "index.json").write_text(
        json.dumps(index_min_entries, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

# 3. Xuất repo.json (Bắt buộc để Mihon xác thực khi thêm link index.min.json)
repo_meta: dict = {
    "meta": {
        "name": REPO_NAME,
        "shortName": REPO_SHORT_NAME,
        "website": REPO_WEBSITE,
        "signingKeyFingerprint": signing_fingerprint,
    }
}
if has_protobuf:
    repo_meta["index_v2"] = f"{RAW_BASE_URL}/index.pb"

(DIST_DIR / "repo.json").write_text(
    json.dumps(repo_meta, ensure_ascii=False, indent=2),
    encoding="utf-8",
)

print(f"\nDone! Packaged {len(index_min_entries)} extension(s) into: {DIST_DIR}")
print(f"Signing Key Fingerprint: {signing_fingerprint}")
