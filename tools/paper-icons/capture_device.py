#!/usr/bin/env python3
"""Capture REAL app pages and test navigation, only on a disposable Android emulator.
No API/model account or test-only production screen is needed. Fail if UI not reachable.
"""
from pathlib import Path
import re, subprocess, time, xml.etree.ElementTree as ET
OUT=Path('artifacts/paper-icons'); OUT.mkdir(parents=True,exist_ok=True)
def adb(*args): return subprocess.check_output(['adb',*args])
assert adb('shell','getprop','ro.kernel.qemu').strip()==b'1', 'Use a disposable emulator, not a personal phone.'
def tap(label):
    for attempt in range(12):
        adb('shell','uiautomator','dump','/sdcard/paper-ui.xml')
        raw=adb('shell','cat','/sdcard/paper-ui.xml')
        nodes=ET.fromstring(raw).iter('node')
        for n in nodes:
            if label in (n.get('text'),n.get('content-desc')):
                coords=list(map(int,re.findall(r'\d+',n.get('bounds',''))))
                if len(coords)==4:
                    x1,y1,x2,y2=coords
                    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
                    time.sleep(2);return
        time.sleep(1)
    (OUT/'unreachable-ui.xml').write_bytes(raw)
    raise RuntimeError(f'Actual app navigation target not found: {label}')
def capture(name):
    time.sleep(2)
    (OUT/f'{name}.png').write_bytes(adb('exec-out','screencap','-p'))
    adb('shell','uiautomator','dump','/sdcard/paper-ui.xml')
    (OUT/f'{name}.xml').write_bytes(adb('shell','cat','/sdcard/paper-ui.xml'))
for theme in ('no','yes'):
    adb('shell','cmd','uimode','night',theme)
    adb('shell','am','force-stop','com.cleo.cleos')
    adb('shell','am','start','-W','-n','com.cleo.cleos/.MainActivity')
    time.sleep(5)
    for label in ('日记','待办','聊天','主页'):
        tap(label)
        capture(f'{theme}-{label}')
    tap('设置');capture(f'{theme}-settings')
(OUT/'capture-result.txt').write_text('Actual APK navigation and screenshots completed for light/dark system modes. Inspect glass auto mode and contrast manually.\n')
