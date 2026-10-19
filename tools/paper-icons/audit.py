#!/usr/bin/env python3
"""Exhaustive lexical inventory, with explicit dynamic/semantic limits; no sampling.
Baseline comes from a commit, not a moving working tree. Re-run after source changes.
"""
import csv, json, re, subprocess
from collections import Counter, defaultdict
from pathlib import Path
from catalog import ALIASES, ICONS, LABELS
ROOT=Path(__file__).resolve().parents[2]
BASE='4ae9bcd855c7767332b02049ebba68fec468617d'
OUT=ROOT/'docs/paper-icons'; OUT.mkdir(exist_ok=True)
files=subprocess.check_output(['git','ls-tree','-r','--name-only',BASE],cwd=ROOT,text=True).splitlines()
def read(p): return subprocess.check_output(['git','show',f'{BASE}:{p}'],cwd=ROOT).decode('utf-8',errors='replace')
def stripped(s):
    # Remove comments without changing line numbers, preserving quoted text.
    pat=r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/'
    return re.sub(pat,lambda m: re.sub(r'[^\n]',' ',m[0]) if m[0].startswith(('//','/*')) else m[0],s)
alias={a:k for k,v in ALIASES.items() for a in v.split()}
core={'role','add_role','group','game','letter','unread_letter','memory','bookmark','settings'}
ops={'attachment','image','add_image','camera','mic','send','copy','edit','favorite','favorite_outline','read_chat','voice'}
status={'Warning','WarningAmber','Info','Check','Lock','LockOpen','MicOff','Stop','Pause','PlayArrow','PlayCircle','Call','CallEnd'}
def category(name,key):
    if key in {'chat','diary','todo','home'}: return '主导航/同义入口'
    if key in core: return '核心功能'
    if key in ops or (not key and name not in status): return '通用操作'
    if not key: return '系统状态/媒体控制'
    return '二级功能'
rows=[]; uses=[]; source_inventory=[]; aggregate=defaultdict(list)
iconpat=re.compile(r'\bIcons\.(?:(AutoMirrored)\.)?(Rounded|Outlined|Filled|Default|Sharp|TwoTone)\.(\w+)')
for p in files:
    if not p.endswith(('.kt','.java','.xml','.html','.css','.js','.json','.kts')): continue
    raw=read(p); clean=stripped(raw) if not p.endswith('.xml') else raw
    source_inventory.append({'file':p,'bytes':len(raw.encode()),'material_references':len(list(iconpat.finditer(clean)))})
    for m in iconpat.finditer(clean):
        line=clean.count('\n',0,m.start())+1; name=m[3]; key=alias.get(name)
        enabled=p.startswith('app/src/main/')
        replacement=f'app/src/main/java/com/cleo/cleos/ui/icons/PaperVectors.kt#{key}' if key and enabled else ''
        row={'name':m[0],'meaning':LABELS.get(key,name),'category':category(name,key),'file':p,'line':line,'reference':raw.splitlines()[line-1].strip(),'decision':'替换（语义红/绿着色时保留原图）' if key and enabled else '保留','resource':replacement}
        rows.append(row); aggregate[key or name].append(row)
    # Catch ALL renderers, resource uses, vector/painter definitions, dynamic images, Canvas.
    for i,line in enumerate(clean.splitlines(),1):
        for typ,pat in [('resource',r'\b(?:R\.(?:drawable|mipmap)\.\w+|@(?:drawable|mipmap)/[\w.]+)'),('glyph',r'[\U0001F000-\U0001FAFF\u2600-\u27BF]'),('renderer/definition',r'\b(?:Icon|Image|AsyncImage|SubcomposeAsyncImage|Canvas|painterResource|rememberAsyncImagePainter|ImageVector|Painter|BitmapPainter|drawPath|drawImage|drawCircle|drawLine|drawRoundRect|drawRect)\b')]:
            for m in re.finditer(pat,line): uses.append({'kind':typ,'symbol':m[0],'file':p,'line':i,'reference':raw.splitlines()[i-1].strip(),'decision':'保留：系统/启动器/用户内容/绘制点，见报告分类'})
def write_csv(name,data,fields):
    with (OUT/name).open('w',newline='',encoding='utf-8-sig') as f:
        w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(data)
write_csv('material-references.csv', rows, ['name','meaning','category','file','line','reference','decision','resource'])
write_csv('renderers-and-resources.csv',uses,['kind','symbol','file','line','reference','decision'])
resources=[]
for p in files:
    if '/src/' not in p or '/res/' not in p: continue
    name=Path(p).stem
    cat='桌面启动器' if ('appicon' in name or 'launcher' in name or '/mipmap' in p) else '系统通知' if name=='ic_notify' else '图片/内容装饰' if p.endswith(('.png','.jpg','.webp','.gif')) else '非图标资源'
    refs=[f"{x['file']}:{x['line']}" for x in uses if x['symbol'].endswith('.'+name) or x['symbol'].endswith('/'+name)]
    resources.append({'resource':p,'category':cat,'decision':'保留','references':'; '.join(refs) or '无直接字面引用；可能由 XML/Manifest/动态资源查找引用','replacement':''})
write_csv('all-resources.csv',resources,['resource','category','decision','references','replacement'])
write_csv('scanned-files.csv',source_inventory,['file','bytes','material_references'])
summary=[]
for key, refs in sorted(aggregate.items()):
    mapped=key in ICONS
    summary.append({'semantic':key,'meaning':LABELS.get(key,key),'category':refs[0]['category'],'material_variants':'; '.join(sorted({r['name'] for r in refs})),'references':len(refs),'decision':'替换，语义色回退' if mapped else '保留','resource':f'PaperVectors.kt#{key}; docs/paper-icons/svg/{key}.svg' if mapped else '原 Material vector'})
write_csv('final-icons.csv',summary,['semantic','meaning','category','material_variants','references','decision','resource'])
stats={'baseline':BASE,'tracked_files':len(files),'scanned_text_files':len(source_inventory),'kotlin_files':sum(p.endswith('.kt') for p in files),'resource_files':len(resources),'material_reference_sites':len(rows),'material_variants':len({r['name'] for r in rows}),'material_names':len({r['name'].split('.')[-1] for r in rows}),'mapped_reference_sites':sum(bool(r['resource']) for r in rows),'retained_reference_sites':sum(not r['resource'] for r in rows),'new_drawings':len(ICONS),'used_paper_drawings':sum(r['semantic'] in ICONS for r in summary),'semantic_groups':len(summary),'renderer_and_resource_mentions':len(uses),'launcher_resources':sum(r['category']=='桌面启动器' for r in resources)}
(OUT/'stats.json').write_text(json.dumps(stats,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(stats,ensure_ascii=False,indent=2))
