#!/usr/bin/env python3
"""Check class definitions in a non-minified APK against Gradle's runtime artifacts.

Reads DEX class_def tables (not string searches, which also find unresolved refs).
Run exportDebugRuntimeArtifacts and assembleDebug before invoking this script.
"""
import argparse
import hashlib
import io
from pathlib import Path
import struct
import zipfile


def dex_classes(data):
    if not data.startswith(b"dex\n"):
        raise ValueError("Unsupported DEX format")
    string_count, string_offset = struct.unpack_from("<II", data, 56)
    type_count, type_offset = struct.unpack_from("<II", data, 64)
    class_count, class_offset = struct.unpack_from("<II", data, 96)
    strings = []
    for i in range(string_count):
        offset = struct.unpack_from("<I", data, string_offset + 4 * i)[0]
        while data[offset] & 128:
            offset += 1
        offset += 1
        strings.append(data[offset:data.index(b"\0", offset)].decode("utf-8", "replace"))
    types = [strings[struct.unpack_from("<I", data, type_offset + 4 * i)[0]]
             for i in range(type_count)]
    return {types[struct.unpack_from("<I", data, class_offset + 32 * i)[0]]
            for i in range(class_count)}


def jar_classes(source):
    with zipfile.ZipFile(source) as jar:
        return {"L" + name[:-6] + ";" for name in jar.namelist()
                if name.endswith(".class") and not name.startswith("META-INF/")
                and name != "module-info.class"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--runtime-artifacts", type=Path, required=True)
    args = parser.parse_args()
    with zipfile.ZipFile(args.apk) as apk:
        dex_entries = [n for n in apk.namelist() if n.endswith(".dex")]
        assert dex_entries, "APK contains no DEX"
        defined = set().union(*(dex_classes(apk.read(n)) for n in dex_entries))
    required = {
        "Landroidx/profileinstaller/ProfileInstaller;",
        "Landroidx/concurrent/futures/AbstractResolvableFuture;",
        "Landroidx/concurrent/futures/ResolvableFuture;",
        "Lcom/google/common/util/concurrent/ListenableFuture;",
        "Landroidx/startup/InitializationProvider;",
    }
    missing = required - defined
    checked = 0
    artifacts = args.runtime_artifacts.read_text().splitlines()
    assert artifacts, "Runtime artifact report is empty"
    for name in artifacts:
        path = Path(name)
        expected = set()
        if path.suffix == ".aar":
            with zipfile.ZipFile(path) as aar:
                for entry in aar.namelist():
                    if entry == "classes.jar" or (entry.startswith("libs/") and entry.endswith(".jar")):
                        expected.update(jar_classes(io.BytesIO(aar.read(entry))))
        elif path.suffix == ".jar":
            expected = jar_classes(path)
        else:
            raise ValueError(f"Unexpected runtime artifact: {path}")
        absent = expected - defined
        if absent:
            print(f"Missing from {path.name}: " + ", ".join(sorted(absent)))
        missing.update(absent)
        checked += len(expected)
    if missing:
        raise SystemExit(f"FAIL: {len(missing)} missing runtime class definitions")
    for name in sorted(required):
        print("DEFINED " + name)
    print(f"PASS: {len(artifacts)} resolved artifacts; {checked} runtime classes; "
          f"{len(dex_entries)} DEX files; {len(defined)} APK class definitions")
    print("SHA-256: " + hashlib.sha256(args.apk.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
