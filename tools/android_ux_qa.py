#!/usr/bin/env python3
"""Interactive, synthetic-device QA. Keeps emulator and adb in one process session.

Run with --sdk SDK --avd-home DIR, then send JSON lines such as
{"action":"status"}, {"action":"install","apk":"/absolute/app.apk"},
{"action":"launch"}, {"action":"dump","name":"permissions"},
{"action":"tap","text":"Continuă"}, {"action":"back"},
{"action":"capture","name":"login"}, {"action":"stop"}.
Tap coordinates always come from the latest fresh UI hierarchy.
No permissions, accounts, or app state are fabricated by this script.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--sdk', required=True, type=Path)
parser.add_argument('--avd-home', required=True, type=Path)
parser.add_argument('--avd', default='forja_qa_api35')
parser.add_argument('--accel', choices=('auto', 'on', 'off'), default='auto')
parser.add_argument('--package', default='com.forja.app.research')
parser.add_argument('--output', type=Path, default=Path('delivery/ux-validation/android'))
parser.add_argument('--runtime-output', type=Path, default=Path('/tmp/forja-android-qa'))
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
args.runtime_output.mkdir(parents=True, exist_ok=True)
adb = str(args.sdk / 'platform-tools/adb')
serial = 'emulator-5554'
package = args.package
env = dict(os.environ, ANDROID_SDK_ROOT=str(args.sdk), ANDROID_AVD_HOME=str(args.avd_home))
emulator_log = (args.runtime_output / 'emulator.log').open('w')
actions = (args.runtime_output / 'actions.jsonl').open('a')
subprocess.run([adb, 'start-server'], check=True)
emulator = subprocess.Popen([
    str(args.sdk / 'emulator/emulator'), '-avd', args.avd, '-no-window',
    '-no-audio', '-no-boot-anim', '-gpu', 'swiftshader', '-accel', args.accel,
    '-no-snapshot', '-memory', '2048', '-cores', '2', '-port', '5554', '-show-kernel',
], env=env, stdin=subprocess.DEVNULL, stdout=emulator_log,
   stderr=subprocess.STDOUT, start_new_session=True)


def run(*command, binary=False, timeout=60):
    result = subprocess.run([adb, '-s', serial, *command], stdin=subprocess.DEVNULL, capture_output=True,
                            text=not binary, timeout=timeout)
    if result.returncode:
        message = result.stderr.decode(errors='replace') if binary else result.stderr
        raise RuntimeError(message.strip() or f'adb exit {result.returncode}')
    return result.stdout


def name_for(command):
    name = command.get('name', command['action'])
    if not re.fullmatch(r'[a-zA-Z0-9_-]+', name):
        raise ValueError('Evidence names must be simple filenames')
    return name


def hierarchy(name):
    text = run('exec-out', 'uiautomator', 'dump', '/dev/tty', timeout=90)
    start, end = text.find('<?xml'), text.rfind('</hierarchy>')
    if start < 0 or end < 0:
        raise RuntimeError('No UI hierarchy: ' + text[-300:])
    xml = text[start:end + len('</hierarchy>')]
    (args.output / (name + '.xml')).write_text(xml)
    return ET.fromstring(xml)


def bounds(node):
    return tuple(map(int, re.findall(r'\d+', node.attrib['bounds'])))


def record(command, result):
    event = {'at': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
             'command': command, 'result': result}
    actions.write(json.dumps(event, ensure_ascii=False) + '\n')
    actions.flush()
    print(json.dumps(result, ensure_ascii=False), flush=True)


print(json.dumps({'ready': True, 'emulator_pid': emulator.pid, 'serial': serial}), flush=True)
try:
    for line in sys.stdin:
        try:
            command = json.loads(line)
            action = command['action']
            if action == 'stop':
                record(command, {'stopped': True})
                break
            if action == 'status':
                devices = subprocess.run([adb, 'devices', '-l'], capture_output=True, text=True).stdout
                state = {'emulator_exit': emulator.poll(), 'devices': devices.strip()}
                if re.search(r'emulator-5554\s+device\b', devices):
                    state['boot_completed'] = run('shell', 'getprop', 'sys.boot_completed', timeout=15).strip()
                    state['boot_animation'] = run('shell', 'getprop', 'init.svc.bootanim', timeout=15).strip()
                record(command, state)
            elif action == 'install':
                apk = Path(command['apk']).resolve(strict=True)
                record(command, {'install': run('install', '-r', str(apk), timeout=300).strip()})
            elif action == 'launch':
                activity = run('shell', 'cmd', 'package', 'resolve-activity', '--brief', package).strip().splitlines()[-1]
                run('logcat', '-c')
                record(command, {'activity': activity, 'launch': run('shell', 'am', 'start', '-W', '-n', activity, timeout=180).strip()})
            elif action == 'reset':
                record(command, {'reset_synthetic_app': run('shell', 'pm', 'clear', package).strip()})
            elif action == 'force-stop':
                run('shell', 'am', 'force-stop', package)
                record(command, {'force_stopped': package})
            elif action == 'app-state':
                permissions = run('shell', 'dumpsys', 'package', package)
                services = run('shell', 'dumpsys', 'activity', 'services', package)
                (args.runtime_output/'package-state.txt').write_text(permissions)
                (args.runtime_output/'services-state.txt').write_text(services)
                record(command, {'state_saved': True, 'services': services[-4000:]})
            elif action in ('dump', 'capture'):
                name = name_for(command)
                root = hierarchy(name)
                nodes = [{'text': n.get('text'), 'description': n.get('content-desc'),
                          'bounds': n.get('bounds'), 'clickable': n.get('clickable'),
                          'scrollable': n.get('scrollable')}
                         for n in root.iter('node')
                         if n.get('text') or n.get('content-desc') or n.get('scrollable') == 'true']
                if action == 'capture':
                    (args.output / (name + '.png')).write_bytes(run('exec-out', 'screencap', '-p', binary=True))
                record(command, {'name': name, 'nodes': nodes})
            elif action == 'tap':
                root = hierarchy('before-tap')
                target = command['text']
                nodes = [n for n in root.iter('node') if n.get('text') == target or n.get('content-desc') == target]
                if len(nodes) != 1:
                    raise RuntimeError(f'Expected one exact UI target {target!r}; found {len(nodes)}')
                x1, y1, x2, y2 = bounds(nodes[0])
                xy = [(x1+x2)//2, (y1+y2)//2]
                run('shell', 'input', 'tap', *map(str, xy))
                record(command, {'tapped': target, 'xy': xy, 'bounds': nodes[0].get('bounds')})
            elif action == 'scroll':
                root = hierarchy('before-scroll')
                nodes = [n for n in root.iter('node') if n.get('scrollable') == 'true']
                if not nodes:
                    raise RuntimeError('No scrollable element in current UI hierarchy')
                x1,y1,x2,y2 = bounds(nodes[0]); x=(x1+x2)//2
                top=y1+(y2-y1)//4; bottom=y1+3*(y2-y1)//4
                start,end=(bottom,top) if command.get('direction','down') == 'down' else (top,bottom)
                run('shell','input','swipe',str(x),str(start),str(x),str(end),'450')
                record(command, {'scrolled': [x,start,x,end], 'bounds': nodes[0].get('bounds')})
            elif action == 'back':
                run('shell', 'input', 'keyevent', '4');record(command, {'back': True})
            elif action == 'unlock':
                run('shell', 'input', 'keyevent', '82');record(command, {'unlock_key': True})
            elif action == 'logs':
                (args.runtime_output/'logcat.txt').write_text(run('logcat','-d',timeout=60))
                crash=run('logcat','-b','crash','-d',timeout=60)
                (args.runtime_output/'crash.txt').write_text(crash)
                record(command, {'crash': crash[-6000:]})
            else:
                raise ValueError('Unknown QA action')
        except Exception as error:
            print(json.dumps({'error': str(error)}, ensure_ascii=False), flush=True)
finally:
    try:
        run('emu','kill',timeout=5)
    except Exception:
        emulator.terminate()
    try:
        emulator.wait(timeout=20)
    except subprocess.TimeoutExpired:
        emulator.kill()
        emulator.wait(timeout=5)
    avd_path = args.avd_home / (args.avd + '.avd')
    for filename in ('hardware-qemu.ini.lock', 'snapshot.lock.lock'):
        lock = avd_path / filename
        if lock.exists() and lock.read_bytes().rstrip(b'\0') == str(emulator.pid).encode():
            lock.unlink()
    subprocess.run([adb,'kill-server'],capture_output=True)
    actions.close(); emulator_log.close()
