"""Build the sleep and onboarding update on the signed v24 APK.

The new feature, explicit routing hooks, audio service, model and visual assets,
and version metadata may change. Every other archive entry is compared byte for byte. The output remains unsigned unless the original signing key is supplied.
"""
import argparse
import ast
import hashlib
import importlib.util
import io
import json
import pathlib
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile

BASE_SHA256 = "853ad79920e283d819a40c4bcbdddbc0ea0f99e4a1a0c3b49e65d763f1c8f200"
SIGNER = "6fc56c1ff164d46b4994fdbfa3e38c786e804c191897ccad581d278aa8feed74"
VERSION = "3.7-online.25"
VERSION_CODE = 55
MODEL_SHA256 = "453caa355b98c860cd5e30780dd5543c8cfbb42b1e376af1126d645c6381de67"
ROOT = pathlib.Path(__file__).resolve().parent
STUBS = {"CleanFile", "ContentFinding", "CleanReport", "CleanupViewModel",
         "VisualPrint", "CleanupScreenLegacyKt", "Placement", "CleanupOutcome",
         "CleanupScan", "CleanupOperations", "DuplicateGroup", "SimilarGroup", "SleepScreenKt"}


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def replace_once(path, old, new):
    body = path.read_text()
    if body.count(old) != 1:
        raise ValueError(f"Unexpected base content in {path}: {old}")
    path.write_text(body.replace(old, new))


def manifest_version(data):
    """Change version values only; preserve permissions and component declarations."""
    output = bytearray(data)
    offset, strings, changed_name, changed_code = 8, [], False, False
    while offset < len(output):
        kind, header, size = struct.unpack_from("<HHI", output, offset)
        if kind == 1:
            count, _, flags, start, _ = struct.unpack_from("<IIIII", output, offset + 8)
            if flags & 0x100:
                raise ValueError("The pinned base manifest must use UTF16 strings")
            for index in range(count):
                at = offset + start + struct.unpack_from("<I", output, offset + header + 4 * index)[0]
                length = struct.unpack_from("<H", output, at)[0]
                if length >= 32768:
                    raise ValueError("Unexpected long manifest string")
                at += 2
                value = bytes(output[at:at + length * 2]).decode("utf-16le")
                strings.append(value)
                if value == "3.7-online.24":
                    replacement = VERSION.encode("utf-16le")
                    assert len(replacement) == length * 2
                    output[at:at + length * 2] = replacement
                    changed_name = True
        elif kind == 0x102:
            _, name, start, stride, count = struct.unpack_from("<IIHHH", output, offset + 16)
            if strings[name] == "manifest":
                for index in range(count):
                    at = offset + 16 + start + index * stride
                    _, key, _ = struct.unpack_from("<III", output, at)
                    if strings[key] == "versionCode":
                        assert output[at + 15] == 0x10
                        assert struct.unpack_from("<I", output, at + 16)[0] == 54
                        struct.pack_into("<I", output, at + 16, VERSION_CODE)
                        changed_code = True
        offset += size
    assert changed_name and changed_code
    return bytes(output)


def signature_entry(name):
    return name.startswith("META-INF/") and name.endswith((".RSA", ".DSA", ".EC", ".SF", "MANIFEST.MF"))


def ensure_model(model):
    if not model.exists():
        parts = ROOT.parent / "audio-update/model/data"
        body = b"".join((parts / f"yamnet.onnx.part{index}").read_bytes() for index in (1, 2))
        assert hashlib.sha256(body).hexdigest() == MODEL_SHA256, "Model parts do not match the validated model"
        model.parent.mkdir(parents=True, exist_ok=True)
        model.write_bytes(body)
    assert hashlib.sha256(model.read_bytes()).hexdigest() == MODEL_SHA256, "Use the validated official model conversion"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=pathlib.Path, required=True)
    parser.add_argument("--tools", type=pathlib.Path, required=True)
    parser.add_argument("--compiled", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--permissions-patch", type=pathlib.Path)
    parser.add_argument("--app-patch", type=pathlib.Path)
    parser.add_argument("--assets", type=pathlib.Path, default=ROOT.parent / "visual-update/assets")
    parser.add_argument("--keystore", type=pathlib.Path)
    parser.add_argument("--store-pass-env", default="FORJA_KEYSTORE_PASSWORD")
    parser.add_argument("--key-pass-env", default="FORJA_KEYSTORE_PASSWORD")
    args = parser.parse_args()
    assert hashlib.sha256(args.base.read_bytes()).hexdigest() == BASE_SHA256, "Use the signed, delivered v24 APK"
    ensure_model(args.assets / "forja/yamnet.onnx")
    work = pathlib.Path(tempfile.mkdtemp(prefix="forja-sleep-v25-"))
    print("WORK_DIR=" + str(work), flush=True)
    android = args.tools / "sdk/android-35/android.jar"
    build_tools = args.tools / "sdk/android-15"
    smali_classpath = str(args.tools / "smali/*")

    def disassemble(dex, destination):
        run("java", "-Xmx2g", "-cp", smali_classpath, "org.jf.baksmali.Main", "d", dex, "-o", destination)

    with zipfile.ZipFile(args.compiled / "feature/build/outputs/aar/feature-debug.aar") as archive:
        classes = archive.read("classes.jar")
    with zipfile.ZipFile(io.BytesIO(classes)) as source, zipfile.ZipFile(work / "cleanup.jar", "w", zipfile.ZIP_DEFLATED) as target:
        for name in source.namelist():
            simple_name = name.rsplit("/", 1)[-1].split("$")[0].removesuffix(".class")
            if not name.endswith(".class") or simple_name in STUBS:
                continue
            assert name.startswith("com/forja/app/feature/cleanup/"), name
            target.writestr(name, source.read(name))
    classpath = []
    for index, line in enumerate((args.compiled / "feature/build/compile-classpath.txt").read_text().splitlines()):
        dependency = pathlib.Path(line)
        if dependency.suffix == ".aar":
            with zipfile.ZipFile(dependency) as archive:
                if "classes.jar" not in archive.namelist():
                    continue
                dependency = work / f"dependency-{index}.jar"
                dependency.write_bytes(archive.read("classes.jar"))
        if dependency.suffix == ".jar":
            classpath += ["--classpath", dependency]
    cleanup_dex = work / "cleanup-dex"
    cleanup_dex.mkdir()
    run("java", "-Xmx2g", "-cp", args.tools / "r8-8.7.18.jar", "com.android.tools.r8.D8",
        "--min-api", "26", "--lib", android, *classpath, "--output", cleanup_dex, work / "cleanup.jar")
    assert len(list(cleanup_dex.glob("*.dex"))) == 1
    shutil.copyfile(cleanup_dex / "classes.dex", work / "classes22.dex")
    disassemble(cleanup_dex / "classes.dex", work / "new-smali")

    # Compile the existing Java feature source with its historical compile-only
    # ABI stubs. The allowlisted recorder and UI classes replace delivered classes;
    # the original authenticated upload worker is retained with one reservation hook.
    audio_root = ROOT.parent / "audio-update"
    historical = ast.parse((audio_root / "build.py").read_text())
    stub_assignment = next(node for node in historical.body if isinstance(node, ast.Assign)
                           and any(isinstance(target, ast.Name) and target.id == "stubs" for target in node.targets))
    java_stubs = ast.literal_eval(stub_assignment.value)
    java_stubs["com/forja/app/feature/cleanup/OrganizerBridge.java"] = """
        package com.forja.app.feature.cleanup;
        public final class OrganizerBridge {
            public static boolean enabled(android.content.Context c) { return false; }
            public static String status(android.content.Context c) { return null; }
            public static String tree(android.content.Context c) { return null; }
            public static void check(android.content.Context c) {}
            public static void activate(android.content.Context c, boolean photos, boolean files,
                String tree, boolean moves, java.util.function.Consumer<String> callback) {}
            public static void stop(android.content.Context c, java.util.function.Consumer<String> callback) {}
        }
    """
    java_stubs["com/forja/app/feature/cleanup/SleepBridge.java"] = "package com.forja.app.feature.cleanup; public final class SleepBridge { public static void sync(android.content.Context c) {} public static void checkAlarm(android.content.Context c) {} public static void reserveBeforeUpload(android.content.Context c,String id) {} }"
    java_stubs["com/forja/app/feature/cleanup/SleepJournalBridge.java"] = "package com.forja.app.feature.cleanup; public final class SleepJournalBridge { public static void persist(android.content.Context c,String id,long start,long end) {} }"
    java_stubs["com/forja/app/feature/research/TimedRecording.java"] = java_stubs["com/forja/app/feature/research/TimedRecording.java"].replace("public long getEpoch()", "public long getDuration(){return 0;} public long getEpoch()")
    for name, body in java_stubs.items():
        path = work / "stubs" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body)
    java_classes = work / "java-classes"
    java_classes.mkdir()
    run("java", "com.sun.tools.javac.Main", "-source", "8", "-target", "8", "-encoding", "UTF-8",
        "-classpath", __import__("os").pathsep.join([str(android)]+[str(classpath[i]) for i in range(1,len(classpath),2)]), "-d", java_classes,
        *sorted((work / "stubs").rglob("*.java")), *sorted((audio_root / "src").rglob("*.java")))
    with zipfile.ZipFile(work / "webpair.jar", "w", zipfile.ZIP_DEFLATED) as archive:
        for path in java_classes.rglob("*.class"):
            if path.stem.split("$")[0] in {"WebPairActivity", "TimedRecordingService", "AudioDiagnostics", "AudioWebStatus", "AudioReadyPolicy", "ErrorText", "SleepAudioState", "SleepAudioPolicy", "SleepSoundClassifier"}:
                archive.write(path, path.relative_to(java_classes))
    pair_dex = work / "pair-dex"
    pair_dex.mkdir()
    run("java", "-Xmx2g", "-cp", args.tools / "r8-8.7.18.jar", "com.android.tools.r8.D8",
        "--min-api", "26", "--lib", android, "--output", pair_dex, work / "webpair.jar")
    disassemble(pair_dex / "classes.dex", work / "pair-smali")

    with zipfile.ZipFile(args.base) as archive:
        for number in [4, 6, 9, 11, 12, 16, 23]:
            dex = work / f"classes{number}.dex"
            dex.write_bytes(archive.read(dex.name))
            disassemble(dex, work / f"smali{number}")
    research = pathlib.Path("com/forja/app/feature/research")
    replacing = {"WebPairActivity", "TimedRecordingService", "AudioDiagnostics", "AudioWebStatus", "AudioReadyPolicy", "ErrorText", "SleepAudioState", "SleepAudioPolicy", "SleepSoundClassifier"}
    for path in (work / "smali23" / research).glob("*.smali"):
        if path.name.split("$")[0].removesuffix(".smali") in replacing:
            path.unlink()
    for path in (work / "pair-smali" / research).glob("*.smali"):
        shutil.copyfile(path, work / "smali23" / research / path.name)
    # The existing ABI verifier inspects both cleanup and retained recording code.
    shutil.copytree(work / "smali23" / research, work / "new-smali" / research)
    replace_once(work / "smali4/com/forja/app/BuildConfig.smali", '"3.7-online.24"', f'"{VERSION}"')
    replace_once(work / "smali4/com/forja/app/BuildConfig.smali", ".field public static final VERSION_CODE:I = 0x36", ".field public static final VERSION_CODE:I = 0x37")
    replace_once(work / "smali16" / research / "HealthExportActivity.smali", '"3.7-online.24"', f'"{VERSION}"')
    if args.permissions_patch:
        spec = importlib.util.spec_from_file_location("permissions_patch", args.permissions_patch)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        module.patch(work / "smali16")
    from patches import patch_sleep
    patch_sleep(work)
    if args.app_patch:
        spec = importlib.util.spec_from_file_location("app_patch", args.app_patch)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        module.patch(work / "smali4", work / "smali9", work / "smali12", work / "smali16")
    for number in [4, 6, 11, 12, 16, 23]:
        run("java", "-Xmx2g", "-cp", smali_classpath, "org.jf.smali.Main", "a",
            work / f"smali{number}", "-o", work / f"classes{number}.dex", "--api", "26")
    changed = {"classes4.dex", "classes6.dex", "classes11.dex", "classes12.dex", "classes16.dex", "classes22.dex", "classes23.dex", "AndroidManifest.xml"}
    added_assets = {}
    if args.assets.exists():
        added_assets = {"assets/"+str(path.relative_to(args.assets)):path.read_bytes() for path in args.assets.rglob("*") if path.is_file()}
    with zipfile.ZipFile(args.base) as before, zipfile.ZipFile(work / "unsigned.apk", "w") as after:
        for entry in before.infolist():
            name = entry.filename
            if signature_entry(name) or name in added_assets:
                continue
            body = (work / name).read_bytes() if name in changed and name.endswith(".dex") else before.read(name)
            if name == "AndroidManifest.xml":
                body = manifest_version(body)
            after.writestr(entry, body)
        for name,body in added_assets.items():
            after.writestr(name,body,compress_type=zipfile.ZIP_DEFLATED)
    run(build_tools / "zipalign", "-f", "-P", "16", "4", work / "unsigned.apk", work / "aligned.apk")
    final = work / "aligned.apk"
    if args.keystore:
        run("java", "-jar", build_tools / "lib/apksigner.jar", "sign", "--ks", args.keystore,
            "--ks-pass", "env:" + args.store_pass_env, "--key-pass", "env:" + args.key_pass_env,
            "--out", work / "signed.apk", final)
        final = work / "signed.apk"
        result = subprocess.check_output(["java", "-jar", str(build_tools / "lib/apksigner.jar"),
                                          "verify", "--print-certs", str(final)], text=True)
        assert SIGNER in result, "The original app signing identity is required"
    unchanged_dex = 0
    with zipfile.ZipFile(args.base) as before, zipfile.ZipFile(final) as after:
        assert after.testzip() is None
        for name in before.namelist():
            if name not in changed and name not in added_assets and not signature_entry(name):
                assert before.read(name) == after.read(name), name
                unchanged_dex += bool(re.fullmatch(r"classes\d*\.dex", name))
        assert sorted(name for name in before.namelist() if not signature_entry(name) and name not in added_assets) == sorted(
            name for name in after.namelist() if not signature_entry(name) and name not in added_assets)
    # Fail before producing a deliverable if any retained ABI or safety gate is lost.
    run("python3", ROOT / "verify.py", final, "--base", args.base,
        "--work-dir", work, "--android-jar", android)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(final, args.output)
    report = {"version": VERSION, "version_code": VERSION_CODE, "base_sha256": BASE_SHA256,
              "sha256": hashlib.sha256(args.output.read_bytes()).hexdigest(),
              "bytes": args.output.stat().st_size, "work_dir": str(work),
              "signed": bool(args.keystore), "signer_matches": True if args.keystore else None,
              "unchanged_dex": unchanged_dex, "original_assets_preserved": True,
              "new_permissions": [], "recording_owner_permission_session_gates_preserved": True, "sleep_audio_chunks": True, "added_assets": sorted(added_assets),
              "permission_ui_patch_applied": bool(args.permissions_patch),
              "android_device_tested": False, "live_server_tested": False}
    args.output.with_suffix(".verification.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report), flush=True)


if __name__ == "__main__":
    main()
