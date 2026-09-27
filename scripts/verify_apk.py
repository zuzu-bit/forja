"""Reject ZIP-valid APKs whose DEX payloads were truncated or modified.

This checks DEX integrity; Android install/launch is a separate required gate.
"""
import hashlib
import re
import struct
import sys
import zipfile
import zlib
from pathlib import Path


def verify_apk(path):
    with zipfile.ZipFile(path) as apk:
        bad_entry = apk.testzip()
        if bad_entry:
            raise ValueError('Invalid ZIP CRC: ' + bad_entry)
        dex_names = [name for name in apk.namelist() if re.fullmatch(r'classes(?:[2-9]|[1-9][0-9]+)?\.dex', name)]
        if 'classes.dex' not in dex_names:
            raise ValueError('APK has no primary DEX file')
        for name in dex_names:
            data = apk.read(name)
            if len(data) < 112 or not re.fullmatch(rb'dex\n0[0-9]{2}\x00', data[:8]):
                raise ValueError(name + ': invalid DEX header')
            declared_size = struct.unpack_from('<I', data, 32)[0]
            if declared_size != len(data):
                raise ValueError(f'{name}: declared {declared_size} bytes, received {len(data)}')
            if data[12:32] != hashlib.sha1(data[32:]).digest():
                raise ValueError(name + ': invalid DEX SHA-1')
            if struct.unpack_from('<I', data, 8)[0] != zlib.adler32(data[12:]) & 0xffffffff:
                raise ValueError(name + ': invalid DEX Adler-32')
    digest = hashlib.sha256(Path(path).read_bytes()).hexdigest()
    return {'dex_files': len(dex_names), 'sha256': digest}


if __name__ == '__main__':
    import json
    print(json.dumps(verify_apk(sys.argv[1])))
