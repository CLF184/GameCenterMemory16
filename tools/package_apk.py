"""Prepare pinned build tools and package binary APK entries on the host PC."""
from pathlib import Path
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
tools = root / "tools"
build = root / "build"

if sys.argv[1] == "prepare":
    with zipfile.ZipFile(tools / "api-102.0.0.aar") as archive:
        (tools / "libxposed-api-102.jar").write_bytes(archive.read("classes.jar"))
    with zipfile.ZipFile(tools / "aapt2-windows.jar") as archive:
        (tools / "aapt2.exe").write_bytes(archive.read("aapt2.exe"))
    with zipfile.ZipFile(tools / "platform-35_r02.zip") as archive:
        entry = next(name for name in archive.namelist() if name.endswith("/android.jar"))
        (tools / "android-35.jar").write_bytes(archive.read(entry))
elif sys.argv[1] == "package":
    with zipfile.ZipFile(build / "unsigned.apk", "w", zipfile.ZIP_DEFLATED) as target:
        with zipfile.ZipFile(build / "base.apk") as source:
            for entry in source.infolist():
                target.writestr(entry.filename, source.read(entry))
        target.write(build / "dex/classes.dex", "classes.dex")
        resources = root / "src/main/resources"
        for path in sorted(resources.rglob("*")):
            if path.is_file():
                target.write(path, path.relative_to(resources).as_posix())
elif sys.argv[1] == "verify":
    with zipfile.ZipFile(root / "dist/GameCenterMemory16-v1.0-api102.apk") as archive:
        assert archive.testzip() is None
        assert archive.read("META-INF/xposed/scope.list").strip() == b"com.xiaomi.gamecenter"
        assert archive.read("META-INF/xposed/java_init.list").strip() == (
            b"local.gamecenter.memory16.Memory16Module"
        )
        dex = archive.read("classes.dex")
        assert b"Llocal/gamecenter/memory16/Memory16Module;" in dex
        assert b"Lio/github/libxposed/api/XposedModule;" in dex
        assert b"Memory16ModuleTest" not in dex
        # Only module classes (including D8's lambda class) may be defined.
        import struct
        string_count, string_offset = struct.unpack_from("<II", dex, 56)
        strings = []
        for index in range(string_count):
            offset = struct.unpack_from("<I", dex, string_offset + index * 4)[0]
            while dex[offset] & 0x80:
                offset += 1
            offset += 1
            strings.append(dex[offset:dex.index(b"\x00", offset)].decode("utf-8", errors="replace"))
        type_count, type_offset = struct.unpack_from("<II", dex, 64)
        types = [strings[struct.unpack_from("<I", dex, type_offset + i * 4)[0]]
                 for i in range(type_count)]
        definitions, class_offset = struct.unpack_from("<II", dex, 96)
        classes = [types[struct.unpack_from("<I", dex, class_offset + i * 32)[0]]
                   for i in range(definitions)]
        assert all(name.startswith("Llocal/gamecenter/memory16/") for name in classes), classes
        assert b"upload-memory-16g" in dex
    print("APK structure verified: exact scope, only module classes, no API/test classes bundled")
else:
    raise SystemExit("Expected prepare, package or verify")
