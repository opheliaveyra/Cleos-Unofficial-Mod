"""Conservative, repeatable refinement of Aster's four original PNGs. No redraw."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter
from scipy import ndimage
import numpy as np
import base64, csv, hashlib, io, json, zipfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'assets/paper-aster'
NAMES = {'home':'主页', 'chat':'聊天', 'diary':'日记', 'todo':'待办'}
report = {}
with zipfile.ZipFile(OUT/'reference/cleos_paper_icons_batch01.zip') as z:
    for name in NAMES:
        raw = z.read(f'cleos_paper_icons_batch01/{name}.png')
        a = np.array(Image.open(io.BytesIO(raw)).convert('RGBA'))
        # Eliminate only disconnected alpha noise; retain original translucent watercolor.
        labels, count = ndimage.label(a[:,:,3] > 8)
        sizes = np.bincount(labels.ravel())
        keep = sizes >= max(24, sizes[1:].max() * 0.01)
        keep[0] = False
        mask = ndimage.binary_dilation(keep[labels], iterations=2)
        removed = int(np.count_nonzero((a[:,:,3] > 0) & ~mask))
        a[~mask] = 0
        a[a[:,:,3] <= 2] = 0
        im = Image.fromarray(a)
        bbox = im.getbbox()
        crop = im.crop(bbox)
        scale = 416 / max(crop.size)
        crop = crop.resize((round(crop.width*scale),round(crop.height*scale)), Image.Resampling.LANCZOS)
        refined = Image.new('RGBA',(512,512))
        refined.alpha_composite(crop,((512-crop.width)//2,(512-crop.height)//2))
        refined.save(OUT/f'png/{name}.png', optimize=True)
        # SVG is explicitly a raster-preserving editable container, never claimed as traced vector.
        b = io.BytesIO();refined.save(b,format='PNG')
        encoded = base64.b64encode(b.getvalue()).decode()
        (OUT/f'source/{name}.svg').write_text(f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 512 512" width="512" height="512"><title>Aster {name}: raster-preserving source</title><desc>Embedded RGBA watercolor artwork. Not a pure vector tracing.</desc><image id="original-watercolor" width="512" height="512" xlink:href="data:image/png;base64,{encoded}"/></svg>')
        pixels = np.array(refined)
        assert refined.mode == 'RGBA' and refined.size == (512,512)
        assert np.all(pixels[:40,:,3] == 0) and np.all(pixels[-40:,:,3] == 0)
        assert np.all(pixels[:,:40,3] == 0) and np.all(pixels[:,-40:,3] == 0)
        report[name] = dict(original_sha256=hashlib.sha256(raw).hexdigest(),output_sha256=hashlib.sha256(b.getvalue()).hexdigest(),original_bbox=bbox,output_bbox=refined.getbbox(),removed_disconnected_pixels=removed,transparent_fraction=float(np.mean(pixels[:,:,3]==0)),alpha_levels=len(np.unique(pixels[:,:,3])))

with (OUT/'icon-manifest.csv').open('w',newline='',encoding='utf-8-sig') as f:
    w=csv.writer(f);w.writerow(['name','function','png_path','source_path','usage_location','status'])
    for name,label in NAMES.items():w.writerow([name,label,f'assets/paper-aster/png/{name}.png',f'assets/paper-aster/source/{name}.svg','app/src/main/java/com/cleo/cleos/ui/MainScreen.kt:GlassTab','batch01-refined; integration-and-device-review-pending'])

sheet = Image.new('RGB',(1000,650),'#faf7f2'); d=ImageDraw.Draw(sheet)
for i,name in enumerate(NAMES):
    x=i*250;im=Image.open(OUT/f'png/{name}.png')
    sheet.paste(im.resize((230,230)),(x+10,25),im.resize((230,230)))
    d.text((x+85,265),name,fill='#514960')
    for j,bg in enumerate(['#ffffff','#252334','#aaa0bd']):
        y=310+j*105;d.rectangle((x,y,x+249,y+100),fill=bg)
        for k,s in enumerate([16,24,32,48]):
            small=im.resize((s,s),Image.Resampling.LANCZOS);sheet.paste(small,(x+12+k*60,y+25),small)
            d.text((x+12+k*60,y+78),str(s),fill='#887e96')
sheet.save(OUT/'previews/batch01-scale-review.png')
(OUT/'TEST_REPORT.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
