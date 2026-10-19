#!/usr/bin/env python3
"""Structural regression checks for a visual-only change; does not claim Android tests."""
import hashlib, json, subprocess, xml.etree.ElementTree as ET
from pathlib import Path
from catalog import ICONS, ALIASES
ROOT=Path(__file__).resolve().parents[2]
BASE=json.loads((ROOT/'docs/paper-icons/stats.json').read_text())['baseline']
changed=subprocess.check_output(['git','diff',BASE,'--name-only'],cwd=ROOT,text=True).splitlines()
checked=0
for name in changed:
    if name.endswith('.kt') and '/ui/icons/' not in name:
        original=subprocess.check_output(['git','show',f'{BASE}:{name}'],cwd=ROOT,text=True)
        current=(ROOT/name).read_text()
        nonimports=lambda s:'\n'.join(l for l in s.splitlines() if not l.startswith('import '))
        assert nonimports(original)==nonimports(current),f'Non-import code change: {name}'
        checked+=1
# Every Icon consumer uses the adapter, including wildcard Material imports.
for p in (ROOT/'app/src/main/java').rglob('*.kt'):
    if '/ui/icons/' in str(p): continue
    if 'Icon(' in p.read_text() and ('import androidx.compose.material3.Icon\n' in p.read_text() or 'import androidx.compose.material3.*' in p.read_text()):
        assert 'import com.cleo.cleos.ui.icons.PaperIcon as Icon' in p.read_text(),p
aliases=[v for values in ALIASES.values() for v in values.split()]
assert len(aliases)==len(set(aliases)), 'Duplicate semantic alias'
for key in ICONS:
    svg=ROOT/f'docs/paper-icons/svg/{key}.svg'
    tree=ET.parse(svg); assert tree.getroot().attrib['viewBox']=='0 0 24 24'
    assert tree.findall('.//{http://www.w3.org/2000/svg}path'),key
# Existing resources (including launcher icons), manifest, business code stay byte-identical.
protected=subprocess.check_output(['git','ls-tree','-r','--name-only',BASE,'app/src/main/res','app/src/main/AndroidManifest.xml'],cwd=ROOT,text=True).splitlines()
for name in protected:
    baseline=subprocess.check_output(['git','show',f'{BASE}:{name}'],cwd=ROOT)
    assert hashlib.sha256(baseline).digest()==hashlib.sha256((ROOT/name).read_bytes()).digest(),name
# Generated assets are deterministic.
targets=[ROOT/'app/src/main/java/com/cleo/cleos/ui/icons/PaperVectors.kt',*(ROOT/'docs/paper-icons/svg').glob('*.svg')]
before={str(p):p.read_bytes() for p in targets}
subprocess.run(['python3',str(ROOT/'tools/paper-icons/generate.py')],check=True,cwd=ROOT)
assert all(p.read_bytes()==before[str(p)] for p in targets)
print(f'PASS: {checked} existing Kotlin files change imports only; {len(protected)} protected files unchanged; {len(ICONS)} XML vectors valid/deterministic; all Material Icon consumers covered.')
