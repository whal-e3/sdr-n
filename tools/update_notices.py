#!/usr/bin/env python3
"""Generate license inventory from exportReleaseDependencies JSON (resolved release runtime artifacts)."""
import pathlib,json,zipfile,io,xml.etree.ElementTree as E,hashlib,os,secrets,subprocess
root=pathlib.Path(__file__).resolve().parents[1]; legal=root/'app/src/main/assets/legal'; inventory=[]; notices=[]
for a in json.loads(pathlib.Path(__import__('sys').argv[1]).read_text()):
 coordinate=':'.join(a[k] for k in ['group','name','version']); artifact=pathlib.Path(a['file']);poms=list(artifact.parents[1].glob('*/*.pom'));licenses=[]
 if poms:
  t=E.parse(poms[0]); licenses=[{'name':e.findtext('{*}name'),'url':e.findtext('{*}url')} for e in t.findall('.//{*}licenses/{*}license')]
 if not licenses and (a['group']=='org.hipparchus' or coordinate=='com.google.guava:listenablefuture:1.0'):
  licenses=[{'name':'Apache License 2.0','url':'https://www.apache.org/licenses/LICENSE-2.0'}]
 if not licenses: raise RuntimeError('Unknown license '+coordinate)
 inventory.append({'coordinate':coordinate,'sha256':hashlib.sha256(artifact.read_bytes()).hexdigest(),'licenses':licenses})
 notices.append(coordinate+'\n'+', '.join(l['name'] for l in licenses)+'\n')
 def extract(z):
  for name in sorted(z.namelist()):
   basename=pathlib.PurePosixPath(name).name.lower()
   if ('license' in basename or 'notice' in basename or basename=='copying') and not name.endswith('/') and not name.endswith('.class'):
    b=z.read(name)
    if b'\0' not in b: notices.append(name+'\n'+b.decode('utf-8',errors='replace')+'\n')
  if 'classes.jar' in z.namelist():
   with zipfile.ZipFile(io.BytesIO(z.read('classes.jar'))) as nested: extract(nested)
 with zipfile.ZipFile(artifact) as z: extract(z)
(legal/'dependencies.json').write_text(json.dumps(inventory,indent=2)+'\n')
(legal/'THIRD_PARTY_NOTICES.txt').write_text('OrbitScope third-party runtime dependencies\n\n'+'\n'.join(notices))
(legal/'GPL-3.0.txt').write_bytes((root/'LICENSE').read_bytes())
for name,src in [('LGPL-2.1.txt','app/src/main/cpp/third_party/libusb-cmake/libusb/COPYING'),('GPL-2.0.txt','app/src/main/cpp/third_party/rtl-sdr/COPYING')]:
 p=root/src
 if not p.exists():
  p=next((root/'app/src/main/cpp/third_party/libusb-cmake').rglob('COPYING'))
 (legal/name).write_bytes(p.read_bytes())
(legal/'NOTICE.txt').write_bytes((root/'NOTICE.md').read_bytes())
header=(root/'app/src/main/cpp/third_party/libhackrf/hackrf.c').read_text().split('*/',1)[0]+'*/\n'
(legal/'HackRF-BSD.txt').write_text(header)
print('Inventory:',len(inventory),'runtime artifacts; notice texts bundled')
