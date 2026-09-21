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
assert len(before) == len(after)
for (old_tag, old), (new_tag, new) in zip(before, after):
    assert old_tag == new_tag
    if old_tag == "manifest":
        assert old["versionCode"] == 53 and new["versionCode"] == 54
        assert old["versionName"] == "3.7-online.23" and new["versionName"] == "3.7-online.24"
        assert old["package"] == new["package"] == "com.forja.app.research"
        old = {key: value for key, value in old.items() if key not in {"versionCode", "versionName"}}
        new = {key: value for key, value in new.items() if key not in {"versionCode", "versionName"}}
    assert old == new, old_tag

print(json.dumps({"all_new_static_calls_verified": checked,
                  "manifest_permissions_components_and_attributes_preserved": True}))
