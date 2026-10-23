"""Verify Salt Artist host contracts by reading ZIP/class bytes, never loading classes.

Example (replace both paths with your local installation paths):
  python scripts/verify-host.py --jar "/path/to/SPW/app/ffmpeg-x64.dll" \
      --java-home "/path/to/jdk-25" --output build/reports/host-compatibility/1.19.2

The classpath archive is named ffmpeg-x64.dll in Steam's SPW distribution.
Only the output directory is written. Exit code 1 means a contract was not verified.
The output contains local host class files and disassembly; keep it out of source control.
"""
import argparse
import hashlib
import json
import re
import shutil
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

NAVIGATION = "com/xuncorp/voxzen/ui/navigation/ComposableSingletons$AppNavigation_desktopKt"
ARTIST_ROUTE = "com/xuncorp/voxzen/ui/navigation/ScreenRoute$ArtistContent"
ALBUM_ROUTE = "com/xuncorp/voxzen/ui/navigation/ScreenRoute$AlbumContent"
AVATAR_DESCRIPTOR = "(Lcom/xuncorp/voxzen/data/entity/Artist;Landroidx/compose/ui/Modifier;Landroidx/compose/runtime/Composer;II)V"
PAGE_DESCRIPTOR = re.compile(r"\(L" + re.escape(ARTIST_ROUTE) + r";L(androidx/compose/ui/[^;]+);Landroidx/compose/runtime/Composer;II\)V\Z")


def parse_class(blob):
    if blob[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("Invalid class magic")
    count = struct.unpack_from(">H", blob, 8)[0]
    cp = [None] * count
    position, index = 10, 1
    while index < count:
        tag = blob[position]
        position += 1
        if tag == 1:
            size = struct.unpack_from(">H", blob, position)[0]
            position += 2
            cp[index] = (tag, blob[position:position + size].decode("utf-8", "replace"))
            position += size
        elif tag in (3, 4, 5, 6):
            position += 8 if tag in (5, 6) else 4
            if tag in (5, 6):
                index += 1
        elif tag in (7, 8, 16, 19, 20):
            cp[index] = (tag, struct.unpack_from(">H", blob, position)[0])
            position += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            cp[index] = (tag, *struct.unpack_from(">HH", blob, position))
            position += 4
        elif tag == 15:
            position += 3
        else:
            raise ValueError(f"Unknown constant-pool tag {tag}")
        index += 1
    refs = []
    for index, value in enumerate(cp):
        if value and value[0] in (10, 11):
            nt = cp[value[2]]
            refs.append({"cpIndex": index, "owner": cp[cp[value[1]][1]][1],
                         "name": cp[nt[1]][1], "descriptor": cp[nt[2]][1]})
    access, this_class, _ = struct.unpack_from(">HHH", blob, position)
    position += 6
    interface_count = struct.unpack_from(">H", blob, position)[0]
    position += 2
    interfaces = []
    for _ in range(interface_count):
        interface_index = struct.unpack_from(">H", blob, position)[0]
        position += 2
        interfaces.append(cp[cp[interface_index][1]][1])
    members = []
    for kind in ("field", "method"):
        count = struct.unpack_from(">H", blob, position)[0]
        position += 2
        for _ in range(count):
            flags, name_index, descriptor_index, attributes = struct.unpack_from(">HHHH", blob, position)
            position += 8
            members.append({"kind": kind, "access": flags, "name": cp[name_index][1],
                            "descriptor": cp[descriptor_index][1]})
            for _ in range(attributes):
                _, size = struct.unpack_from(">HI", blob, position)
                position += 6 + size
    return {"name": cp[cp[this_class][1]][1], "access": access, "interfaces": interfaces,
            "members": members, "refs": refs}


def codepoints(name):
    return [f"U+{ord(char):04X}" for char in name.rsplit("/", 1)[-1] if ord(char) > 127]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True,
                        help="Local SPW classpath archive (ffmpeg-x64.dll); no archive is bundled")
    parser.add_argument("--java-home", type=Path, help="JDK installation containing bin/javap")
    parser.add_argument("--javap", type=Path, help="Explicit javap executable (otherwise use the JDK or PATH)")
    parser.add_argument("--output", type=Path, default=Path("build/reports/host-compatibility/current"))
    args = parser.parse_args()
    javap_name = "javap.exe" if sys.platform == "win32" else "javap"
    javap = args.javap or (args.java_home / "bin" / javap_name if args.java_home else shutil.which("javap"))
    if not javap or not Path(javap).is_file():
        parser.error("Supply --java-home or --javap, or put javap on PATH")
    jar_path = args.jar.resolve(strict=True)
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    report = {"archive": str(jar_path), "archiveBytes": jar_path.stat().st_size,
              "archiveSha256": hashlib.sha256(jar_path.read_bytes()).hexdigest(),
              "classes": {}, "checks": [], "errors": []}
    config_path = jar_path.parent / "Salt Player for Windows.cfg"
    if config_path.exists():
        version = re.search(r"-Djpackage\.app-version=([^\r\n]+)", config_path.read_text(encoding="utf-8"))
        report["hostVersion"] = version.group(1) if version else None
        report["configSha256"] = hashlib.sha256(config_path.read_bytes()).hexdigest()

    def check(name, passed, details=None):
        report["checks"].append({"name": name, "passed": bool(passed), "details": details})
        if not passed:
            report["errors"].append(name)

    with zipfile.ZipFile(jar_path) as archive:
        cached = {}

        def read(name):
            if name not in cached:
                cached[name] = parse_class(archive.read(name + ".class"))
            return cached[name]

        def dump(label, name):
            blob = archive.read(name + ".class")
            class_path = out / (label + ".class")
            class_path.write_bytes(blob)
            # ASCII filename avoids Windows javap's Unicode argv code-page trap.
            result = subprocess.run([str(javap), "-J-Dfile.encoding=UTF-8", "-J-Dsun.stdout.encoding=UTF-8",
                                     "-p", "-c", "-s", "-classpath", str(jar_path), str(class_path)],
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            (out / (label + ".javap.txt")).write_bytes(result.stdout)
            check("javap:" + label, result.returncode == 0)
            report["classes"][label] = {"name": name, "codepoints": codepoints(name),
                                        "sha256": hashlib.sha256(blob).hexdigest()}
            return result.stdout.decode("utf-8", "replace")

        def member_exists(name, member_name, descriptor, flags):
            return any(m["name"] == member_name and m["descriptor"] == descriptor and
                       m["access"] & flags == flags for m in read(name)["members"])

        nav = read(NAVIGATION)
        page_refs = [r for r in nav["refs"] if PAGE_DESCRIPTOR.fullmatch(r["descriptor"])]
        report["artistPageCandidates"] = page_refs
        check("unique artist page reference in navigation", len(page_refs) == 1, page_refs)
        navigation_dump = dump("navigation-callsite", NAVIGATION)
        if len(page_refs) == 1:
            page = page_refs[0]
            view_model = PAGE_DESCRIPTOR.fullmatch(page["descriptor"]).group(1)
            check("artist page public static method", member_exists(page["owner"], page["name"], page["descriptor"], 0x0001 | 0x0008))
            count = len(re.findall(r"^\s+\d+: invokestatic\s+#" + str(page["cpIndex"]) + r"\s", navigation_dump, re.MULTILINE))
            check("one artist page invocation in navigation", count == 1, count)
            report["artistPage"] = {**page, "invocationsInNavigation": count, "methodCodepoints": codepoints(page["name"]), "viewModel": view_model}
            page_dump = dump("artist-page", page["owner"])
            dump("artist-view-model", view_model)
            avatars = [r for r in read(page["owner"])["refs"] if r["descriptor"] == AVATAR_DESCRIPTOR]
            report["avatarCandidates"] = avatars
            check("unique artist avatar reference in page", len(avatars) == 1, avatars)
            if len(avatars) == 1:
                avatar = avatars[0]
                check("avatar public static method", member_exists(avatar["owner"], avatar["name"], avatar["descriptor"], 0x0001 | 0x0008))
                count = len(re.findall(r"^\s+\d+: invokestatic\s+#" + str(avatar["cpIndex"]) + r"\s", page_dump, re.MULTILINE))
                check("one avatar invocation in artist page", count == 1, count)
                report["avatar"] = {**avatar, "invocationsInPage": count, "methodCodepoints": codepoints(avatar["name"])}
                dump("artist-avatar", avatar["owner"])
                callers = []
                for entry in archive.namelist():
                    if not entry.endswith(".class"):
                        continue
                    blob = archive.read(entry)
                    if b"com/xuncorp/voxzen/data/entity/Artist;Landroidx/compose/ui/Modifier;" not in blob:
                        continue
                    data = parse_class(blob)
                    for ref in data["refs"]:
                        if all(ref[key] == avatar[key] for key in ("owner", "name", "descriptor")):
                            callers.append({"caller": data["name"], **ref})
                report["avatarCallers"] = callers
                item_callers = [r for r in callers if r["caller"] not in (page["owner"], avatar["owner"])]
                check("one external avatar item caller", len(item_callers) == 1, item_callers)
                if len(item_callers) == 1:
                    item = item_callers[0]
                    item_dump = dump("artist-item", item["caller"])
                    count = len(re.findall(r"^\s+\d+: invokestatic\s+#" + str(item["cpIndex"]) + r"\s", item_dump, re.MULTILINE))
                    check("one avatar invocation in artist item", count == 1, count)

        if report.get("hostVersion") in ("1.19.1", "1.19.2"):
            directory = "androidx/compose/ui/\u0417"
            vm = "androidx/compose/ui/\u05e1"
            ready = "androidx/compose/ui/\u04db$\u0528"
            group = "androidx/compose/ui/\u09aa"
            page_descriptor = "(L" + vm + ";Landroidx/compose/runtime/Composer;II)V"
            capture_descriptor = "(Landroidx/compose/foundation/layout/BoxScope;L" + ready + ";Landroidx/compose/foundation/layout/PaddingValues;Landroidx/compose/runtime/Composer;I)V"
            dump("directory-screen-020", directory)
            for label, name in (("directory-ready-020", ready), ("directory-group-020", group)):
                dump(label, name)
                fields = [m for m in read(name)["members"] if m["kind"] == "field" and not m["access"] & 8 and m["descriptor"] == "Ljava/util/List;"]
                check(label + " owns exactly one List field", len(fields) == 1)
            check("directory public screen signature", member_exists(directory, "\u037f", page_descriptor, 1 | 8))
            check("directory ready capture signature", member_exists(directory, "\u037f", capture_descriptor, 2 | 8))
            check("directory screen called from navigation", any(r["owner"] == directory and r["name"] == "\u037f" and r["descriptor"] == page_descriptor for r in nav["refs"]))
            check("directory ready capture called in screen", any(r["owner"] == directory and r["name"] == "\u037f" and r["descriptor"] == capture_descriptor for r in read(directory)["refs"]))

        contracts = [
            ("playback-controller", "com/xuncorp/voxzen/service/PlaybackController", [
                ("INSTANCE", "Lcom/xuncorp/voxzen/service/PlaybackController;", 0x0001 | 0x0008),
                ("setPlaybackQueue", "(Ljava/util/List;I)V", 0x0001),
                ("setPlaybackQueue", "(Lcom/xuncorp/pisces/PiscesMediaItem;)V", 0x0001)]),
            ("pisces-media-item", "com/xuncorp/pisces/PiscesMediaItem", [
                ("<init>", "(" + "Ljava/lang/String;" * 6 + ")V", 0x0001)]),
            ("app-navigation", "com/xuncorp/voxzen/ui/navigation/AppNavigationKt", [
                ("getLocalNavigator", "()Landroidx/compose/runtime/ProvidableCompositionLocal;", 0x0001 | 0x0008)]),
            ("navigator", "com/xuncorp/voxzen/ui/navigation/Navigator", [
                ("back", "()V", 0x0001),
                ("navigate", "(Lcom/xuncorp/voxzen/ui/navigation/ScreenRoute;)V", 0x0001)]),
            ("artist-route", ARTIST_ROUTE, [("<init>", "(Ljava/lang/String;)V", 0x0001),
                                           ("getName", "()Ljava/lang/String;", 0x0001)]),
            ("album-route", ALBUM_ROUTE, [("<init>", "(Ljava/lang/String;Ljava/lang/String;)V", 0x0001)]),
        ]
        for label, name, methods in contracts:
            dump(label, name)
            for method, descriptor, flags in methods:
                check(label + "." + method + descriptor, member_exists(name, method, descriptor, flags))
        dump("screen-route", "com/xuncorp/voxzen/ui/navigation/ScreenRoute")
        route_interface = "com/xuncorp/voxzen/ui/navigation/ScreenRoute"
        check("ScreenRoute extends NavKey", "androidx/navigation3/runtime/NavKey" in read(route_interface)["interfaces"])
        check("ArtistContent implements ScreenRoute", route_interface in read(ARTIST_ROUTE)["interfaces"])
        check("AlbumContent implements ScreenRoute", route_interface in read(ALBUM_ROUTE)["interfaces"])
        if "META-INF/MANIFEST.MF" in archive.namelist():
            (out / "manifest.txt").write_bytes(archive.read("META-INF/MANIFEST.MF"))

    report["passed"] = not report["errors"]
    (out / "evidence.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    # Escape Unicode on console so old Windows terminals preserve the exact evidence.
    print(json.dumps(report, ensure_ascii=True, indent=2))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print(f"Host verification failed: {error}", file=sys.stderr)
        sys.exit(1)
