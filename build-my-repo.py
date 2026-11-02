#!/usr/bin/env python3
import json
import shutil
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent
DIST_DIR = ROOT_DIR / "dist"
APK_DIR = DIST_DIR / "apk"
JAR_DIR = DIST_DIR / "jar"
ICON_DIR = DIST_DIR / "icon"

# Xóa thư mục dist cũ (nếu có) để tránh tồn đọng các nguồn cũ
if DIST_DIR.exists():
    shutil.rmtree(DIST_DIR)

for d in (DIST_DIR, APK_DIR, JAR_DIR, ICON_DIR):
    d.mkdir(parents=True, exist_ok=True)

index_entries = []

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

    if jar_files:
        jar_file = jar_files[0]
        shutil.copy2(jar_file, JAR_DIR / jar_file.name)

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

    for icon_src in icon_candidates:
        if icon_src.exists():
            shutil.copy2(icon_src, ICON_DIR / f"{pkg_name}.png")
            break

    # contentWarning: 1 = SAFE (nsfw: 0), 2 = MIXED (nsfw: 1), 3 = NSFW (nsfw: 1)
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
    index_entries.append(entry)
    print(f"Packed: {info['name']} -> {apk_file.name}")

(DIST_DIR / "index.min.json").write_text(
    json.dumps(index_entries, ensure_ascii=False, separators=(",", ":")),
    encoding="utf-8",
)
(DIST_DIR / "index.json").write_text(
    json.dumps(index_entries, ensure_ascii=False, indent=2),
    encoding="utf-8",
)

print(f"\nDone! Packaged {len(index_entries)} extension(s) into: {DIST_DIR}")

