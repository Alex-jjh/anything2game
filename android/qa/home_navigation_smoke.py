#!/usr/bin/env python3
"""Real-device smoke for the new home. Requires an already installed debug APK.

Use a dedicated QA emulator: this changes its app locale, test nickname/character,
rotates the screen and force-stops only com.anything2game.app. It never clears data.
Screenshots and a machine-readable result are saved to --output.

python3 android/qa/home_navigation_smoke.py --adb "$ANDROID_HOME/platform-tools/adb" \
  --serial emulator-5578 --output build/home-nav-qa/smoke
"""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='adb')
parser.add_argument('--serial', required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
PKG = 'com.anything2game.app'
adb = [args.adb, '-s', args.serial]
args.output.mkdir(parents=True, exist_ok=True)
checks = []

def call(*parts):
    return subprocess.check_output(adb + list(parts), text=True, timeout=30).strip()

def shell(*parts):
    return call('shell', *parts)

def nodes():
    xml = call('exec-out', 'uiautomator', 'dump', '/dev/tty')
    root = ET.fromstring(xml[xml.index('<hierarchy'):xml.index('</hierarchy>')+12])
    return list(root.iter('node'))

def find(label, scroll=True):
    for attempt in range(10):
        tree = nodes()
        for n in tree:
            if label in (n.get('text'), n.get('content-desc')):
                return n
        if scroll and attempt > 0:
            area = next((n for n in tree if n.get('scrollable') == 'true'), None)
            if area is not None:
                x, y, X, Y = map(int, re.findall(r'\d+', area.get('bounds')))
                shell('input', 'swipe', str((x+X)//2), str((y+3*Y)//4), str((x+X)//2), str((3*y+Y)//4), '300')
        time.sleep(.2)
    raise AssertionError(f'Missing control: {label}')

def click_node(n):
    x, y, X, Y = map(int, re.findall(r'\d+', n.get('bounds')))
    shell('input', 'tap', str((x+X)//2), str((y+Y)//2))

def tap(label):
    click_node(find(label))

def screenshot(name):
    with (args.output / f'{name}.png').open('wb') as f:
        subprocess.run(adb + ['exec-out', 'screencap', '-p'], stdout=f, check=True, timeout=15)

def passed(label):
    checks.append(label)
    print('PASS ' + label, flush=True)

def launch():
    shell('am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', '-f', '0x10200000', '-n', PKG+'/.MainActivity')

def set_nickname(value, commit):
    tap('编辑昵称')
    field = next(n for n in nodes() if n.get('class') == 'android.widget.EditText')
    click_node(field)
    shell('input', 'keyevent', 'KEYCODE_MOVE_END')
    shell('input', 'keyevent', *(['KEYCODE_DEL'] * 24))
    shell('input', 'text', value)
    tap('保存' if commit else '取消')

assert args.serial.startswith('emulator-') and shell('getprop', 'ro.kernel.qemu') == '1', 'Use a dedicated QA emulator'
shell('am', 'force-stop', PKG)
shell('cmd', 'locale', 'set-app-locales', PKG, '--user', '0', '--locales', 'zh-CN')
launch()
tap('讲解'); find('读懂，再记住。'); screenshot('01-learn-zh')
tap('巩固'); find('知识巩固'); screenshot('02-practice-zh')
tap('我的'); find('我的学习档案')
passed('All three tabs open the expected screen')
set_nickname('ReaderQA', True); find('ReaderQA')
passed('Native keyboard nickname save')
set_nickname('Discard', False); find('ReaderQA')
passed('Cancel keeps saved nickname')
tap('皮皮')
assert 'pip' in shell('run-as', PKG, 'cat', 'shared_prefs/avatar.xml')
screenshot('03-profile-zh')
passed('Character persisted')
try:
    shell('wm', 'user-rotation', 'lock', '1')
    find('ReaderQA'); find('我的学习档案'); screenshot('04-profile-landscape-zh')
    passed('Configuration change retains profile')
finally:
    shell('wm', 'user-rotation', 'lock', '0')
shell('am', 'force-stop', PKG); launch()
find('我的学习档案'); find('ReaderQA')
assert 'pip' in shell('run-as', PKG, 'cat', 'shared_prefs/avatar.xml')
passed('Cold relaunch restores tab, nickname and character')
screenshot('05-profile-restored-zh')
tap('讲解'); tap('选择 PDF')
assert any(n.get('package') == 'com.google.android.documentsui' for n in nodes()), 'System PDF picker missing'
# A picker opened inside a folder handles Back by visiting its parent first.
# Unwind only while DocumentsUI owns the screen; do not send extra Back into our app.
for _ in range(6):
    shell('input', 'keyevent', 'KEYCODE_BACK')
    if not any(n.get('package') == 'com.google.android.documentsui' for n in nodes()):
        break
find('读懂，再记住。')
passed('PDF picker cancellation returns to Learn')
(args.output / 'result.json').write_text(json.dumps({'status': 'passed', 'checks': checks, 'serial': args.serial, 'api': shell('getprop', 'ro.build.version.sdk')}, ensure_ascii=False, indent=2))
