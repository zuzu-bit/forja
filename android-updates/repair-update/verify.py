"""Check the retained feature ABI, new static calls, and exact manifest scope."""
import argparse
import json
import pathlib
import re
import runpy
import struct
import sys
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=pathlib.Path)
parser.add_argument("--base", type=pathlib.Path, required=True)
parser.add_argument("--work-dir", type=pathlib.Path, required=True)
parser.add_argument("--android-jar", type=pathlib.Path, required=True)
args = parser.parse_args()
legacy = pathlib.Path(__file__).resolve().parents[1] / "lost-phone-update/verify.py"
sys.argv = [str(legacy), str(args.apk), "--work-dir", str(args.work_dir), "--android-jar", str(args.android_jar)]
state = runpy.run_path(str(legacy), run_name="__main__")

# Compose calls are signature-sensitive: a successful source compilation alone
# cannot prove that a newer UI method exists in the retained dependency DEX.
checked = 0
for path in (args.work_dir / "new-smali").rglob("*.smali"):
    for line in path.read_text().splitlines():
        if not line.strip().startswith("invoke-static"):
            continue
        match = re.search(r"(L[^;]+;)->([^\s,]+)", line)
        if not match:
            continue
        owner, signature = match.groups()
        assert state["exists"](owner, signature), (str(path), owner, signature)
        checked += 1


def manifest_nodes(apk):
    with zipfile.ZipFile(apk) as archive:
        data = archive.read("AndroidManifest.xml")
    offset, strings, nodes = 8, [], []
    while offset < len(data):
        kind, header, size = struct.unpack_from("<HHI", data, offset)
        block = data[offset:offset + size]
        offset += size
        if kind == 1:
            count, _, flags, start, _ = struct.unpack_from("<IIIII", block, 8)
            assert not flags & 0x100
            for index in range(count):
                at = start + struct.unpack_from("<I", block, header + index * 4)[0]
                length = struct.unpack_from("<H", block, at)[0]
                strings.append(block[at + 2:at + 2 + length * 2].decode("utf-16le"))
        elif kind == 0x102:
            _, name, start, stride, count = struct.unpack_from("<IIHHH", block, 16)
            attributes = {}
            for index in range(count):
                at = 16 + start + index * stride
                _, key, _ = struct.unpack_from("<III", block, at)
                value = struct.unpack_from("<I", block, at + 16)[0]
                attributes[strings[key]] = strings[value] if block[at + 15] == 3 else value
            nodes.append((strings[name], attributes))
    return nodes


before, after = manifest_nodes(args.base), manifest_nodes(args.apk)
service=[node for node in after if node[0]=="service" and node[1].get("name")=="com.forja.app.feature.cleanup.JourneyLocationService"]
assert len(service)==1 and service[0][1]["exported"]==0 and service[0][1]["foregroundServiceType"]==8
assert state["exists"]("Lcom/forja/app/feature/cleanup/JourneyLocationService;","onCreate()V")
assert len(before) == len(after)
for (old_tag, old), (new_tag, new) in zip(before, after):
    assert old_tag == new_tag
    if old_tag == "manifest":
        assert old["versionCode"] == 56 and new["versionCode"] == 57
        assert old["versionName"] == "3.7-online.26" and new["versionName"] == "3.7-online.27"
        assert old["package"] == new["package"] == "com.forja.app.research"
        old = {key: value for key, value in old.items() if key not in {"versionCode", "versionName"}}
        new = {key: value for key, value in new.items() if key not in {"versionCode", "versionName"}}
    assert old == new, old_tag

print(json.dumps({"all_new_static_calls_verified": checked,
                  "manifest_permissions_components_and_attributes_preserved": True}))

# Check the compiled reflection ABI and the precise hooks into retained code.
required = {
    "Lcom/forja/app/ForjaApp;": ["getPrefs()Lcom/forja/app/core/data/Prefs;", "getDb()Lcom/forja/app/core/data/db/ForjaDatabase;"],
    "Lcom/forja/app/core/data/db/ForjaDatabase;": ["sleepDao()Lcom/forja/app/core/data/db/SleepDao;"],
    "Lcom/forja/app/core/data/db/SleepDao;": ["finishedSince(J)Lkotlinx/coroutines/flow/Flow;", "insert(Lcom/forja/app/core/data/db/SleepSessionEntity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"],
    "Lcom/forja/app/core/data/db/SleepSessionEntity;": ["<init>(JJLjava/lang/Long;IIIIILjava/lang/String;Ljava/lang/String;J)V"],
    "Lcom/google/firebase/firestore/FirebaseFirestore;": ["getInstance()Lcom/google/firebase/firestore/FirebaseFirestore;", "collection(Ljava/lang/String;)Lcom/google/firebase/firestore/CollectionReference;"],
    "Lcom/google/firebase/firestore/CollectionReference;": ["document(Ljava/lang/String;)Lcom/google/firebase/firestore/DocumentReference;"],
    "Lcom/google/firebase/firestore/DocumentReference;": ["collection(Ljava/lang/String;)Lcom/google/firebase/firestore/CollectionReference;", "set(Ljava/lang/Object;)Lcom/google/android/gms/tasks/Task;"],
    "Lcom/forja/app/ForjaApp$Companion;": ["from(Landroid/content/Context;)Lcom/forja/app/ForjaApp;"],
    "Lcom/forja/app/core/data/Prefs;": ["getAlarmEnabled()Lkotlinx/coroutines/flow/Flow;", "getAlarmHour()Lkotlinx/coroutines/flow/Flow;", "getAlarmMinute()Lkotlinx/coroutines/flow/Flow;"],
    "Lcom/forja/app/feature/research/SleepAudioState;": [
        "authorized(Landroid/content/Context;)Z", "authorizeRemoteSleep(Landroid/content/Context;)Z",
        "active(Landroid/content/Context;)Lorg/json/JSONObject;", "sessions(Landroid/content/Context;)Lorg/json/JSONArray;",
        "recordings(Landroid/content/Context;)Lorg/json/JSONArray;", "acoustic(Landroid/content/Context;Ljava/lang/String;)Lorg/json/JSONObject;",
        "sessionForChunk(Landroid/content/Context;Ljava/lang/String;)Lorg/json/JSONObject;",
        "transferAuthorized(Landroid/content/Context;Lorg/json/JSONObject;)Z", "authorizeSleep(Landroid/content/Context;Z)Z",
        "startNow(Landroid/content/Context;I)Z", "stop(Landroid/content/Context;)V", "interruptedAfterRestart(Landroid/content/Context;)V"],
    "Lcom/forja/app/feature/sleep/SleepScreenKt;": ["SleepScreen(Landroidx/compose/runtime/Composer;I)V", "SleepScreenLegacy(Landroidx/compose/runtime/Composer;I)V"],
}
for owner, signatures in required.items():
    for signature in signatures: assert state["exists"](owner,signature),(owner,signature)
screen=(args.work_dir/"smali6/com/forja/app/feature/sleep/SleepScreenKt.smali").read_text()
assert screen.count("SleepHubKt;->SleepHub(")==1
service=(args.work_dir/"smali11/com/forja/app/core/sleep/SleepTrackService$Companion.smali").read_text()
assert service.count("SleepAudioState;->legacyStart(")==1
worker=(args.work_dir/"smali16/com/forja/app/feature/research/RecordingUploadWorker.smali").read_text()
assert worker.count("SleepBridge;->reserveBeforeUpload(")==1
assert worker.index("SleepBridge;->reserveBeforeUpload(")<worker.index("ResearchTransport;->openRecording(")
main=(args.work_dir/"smali4/com/forja/app/MainActivity.smali").read_text()
assert main.count("PendingSyncReturn;->onHostResume(")==1
with zipfile.ZipFile(args.apk) as archive:
    import hashlib
    assert hashlib.sha256(archive.read("assets/forja/yamnet.onnx")).hexdigest()=="453caa355b98c860cd5e30780dd5543c8cfbb42b1e376af1126d645c6381de67"
print("Sleep route, consent reflection, quota reservation, onboarding return and pinned audio model verified.")

assert state["exists"]("Lcom/forja/app/feature/cleanup/SleepLegacyBridge;", "activeSession(Landroid/content/Context;)Lkotlinx/coroutines/flow/Flow;")
assert screen.count("SleepLegacyBridge;->activeSession(")==1
assert "invoke-interface {v2}, Lcom/forja/app/core/data/db/SleepDao;->activeSession()" not in screen
print("Original sleep screen and current recording Start/Stop observer verified.")
