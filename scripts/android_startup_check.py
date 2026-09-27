"""Launch the built APK on a disposable emulator and retain failure evidence."""
import json
import subprocess
import time
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from verify_apk import verify_apk

PACKAGE = 'com.forja.app.research'
COMPONENT = PACKAGE + '/com.forja.app.MainActivity'
OUT = Path('startup-evidence')
OUT.mkdir(exist_ok=True)


def adb(*args, timeout=35, check=True):
    return subprocess.run(['adb', *args], capture_output=True, timeout=timeout, check=check)


def capture(name, *args, timeout=20):
    try:
        r = adb(*args, timeout=timeout, check=False)
        (OUT / name).write_bytes(r.stdout)
    except subprocess.TimeoutExpired:
        (OUT / (name + '.timeout')).write_text('Capture timed out\n')


try:
    integrity = verify_apk('app/build/outputs/apk/research/app-research.apk')
    (OUT / 'apk-integrity.json').write_text(json.dumps(integrity))
    result = adb('install', '-r', 'app/build/outputs/apk/research/app-research.apk', timeout=120)
    (OUT / 'install.txt').write_bytes(result.stdout + result.stderr)
    adb('shell', 'input', 'keyevent', '82')
    adb('shell', 'am', 'force-stop', PACKAGE)
    adb('logcat', '-c')
    started = adb('shell', 'am', 'start', '-W', '-n', COMPONENT)
    (OUT / 'launch.txt').write_bytes(started.stdout + started.stderr)
    for _ in range(15):
        time.sleep(1)
        pid = adb('shell', 'pidof', PACKAGE, check=False).stdout.decode().strip()
        if not pid:
            raise AssertionError('FORJA process exited during startup')
    activities = adb('shell', 'dumpsys', 'activity', 'activities').stdout.decode()
    (OUT / 'activities.txt').write_text(activities)
    resumed = [line for line in activities.splitlines() if 'ResumedActivity' in line or 'topResumedActivity' in line]
    if not any(PACKAGE in line for line in resumed):
        raise AssertionError('FORJA did not retain the foreground activity')
    # First launch must show the shared settings screen, all choices off.
    raw = adb('exec-out', 'uiautomator', 'dump', '/dev/tty').stdout.decode()
    xml = raw[raw.index('<?xml'):raw.rindex('</hierarchy>') + len('</hierarchy>')]
    root = ET.fromstring(xml)
    (OUT / 'setup-ui.xml').write_text(xml)
    capture('setup.png', 'exec-out', 'screencap', '-p')
    assert 'Configurează FORJA' in xml, 'Missing first-launch setup'
    assert not any(n.get('checked') == 'true' for n in root.iter('node')), 'Collection must default to off'
    target = next(n for n in root.iter('node') if n.get('text') == 'Continuă în FORJA')
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', target.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(3)
    login = adb('exec-out', 'uiautomator', 'dump', '/dev/tty').stdout.decode()
    assert 'Intră în cont' in login, 'Continue without optional collection must reach login'
    assert 'AutomaticCollectionService' not in adb('shell', 'dumpsys', 'activity', 'services', PACKAGE).stdout.decode(), 'No collection without consent/account'
    adb('shell', 'am', 'force-stop', PACKAGE)
    adb('shell', 'am', 'start', '-W', '-n', COMPONENT)
    time.sleep(3)
    again = adb('exec-out', 'uiautomator', 'dump', '/dev/tty').stdout.decode()
    assert 'Configurează FORJA' not in again and 'Intră în cont' in again, 'Onboarding must not repeat'
    (OUT / 'result.json').write_text(json.dumps({'startup_alive': True, 'foreground': True, 'observed_seconds': 15, 'setup_defaults_off': True, 'skip_reaches_login': True, 'no_service_without_consent': True, 'setup_not_repeated': True}))
    print('FORJA stayed alive and in the foreground for 15 seconds.')
finally:
    capture('crash.txt', 'logcat', '-d', '-b', 'crash')
    capture('logcat.txt', 'logcat', '-d')
    capture('screen.png', 'exec-out', 'screencap', '-p')
    capture('ui.xml', 'exec-out', 'uiautomator', 'dump', '/dev/tty')
    crash = OUT / 'crash.txt'
    if crash.exists() and PACKAGE in crash.read_text(errors='replace'):
        print(crash.read_text(errors='replace')[-16000:])
