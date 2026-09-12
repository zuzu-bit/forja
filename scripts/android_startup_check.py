"""Launch the built APK on a disposable emulator and retain failure evidence."""
import json
import subprocess
import time
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
    (OUT / 'result.json').write_text(json.dumps({'startup_alive': True, 'foreground': True, 'observed_seconds': 15}))
    print('FORJA stayed alive and in the foreground for 15 seconds.')
finally:
    capture('crash.txt', 'logcat', '-d', '-b', 'crash')
    capture('logcat.txt', 'logcat', '-d')
    capture('screen.png', 'exec-out', 'screencap', '-p')
    capture('ui.xml', 'exec-out', 'uiautomator', 'dump', '/dev/tty')
    crash = OUT / 'crash.txt'
    if crash.exists() and PACKAGE in crash.read_text(errors='replace'):
        print(crash.read_text(errors='replace')[-16000:])
