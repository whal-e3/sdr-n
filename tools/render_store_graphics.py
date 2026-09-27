#!/usr/bin/env python3
"""Render code-defined store layouts using the existing OrbitScope logo (PyMuPDF)."""
from pathlib import Path
import base64
import pymupdf
root = Path(__file__).resolve().parents[1]
out = root / 'docs/release/store'
logo = base64.b64encode((root / 'docs/branding/orbitscope-logo.png').read_bytes()).decode()
image = f'data:image/png;base64,{logo}'
svg_head = '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"'
icon = f'''{svg_head} width="512" height="512" viewBox="0 0 512 512">
<rect width="512" height="512" fill="#07131F"/>
<image x="24" y="24" width="464" height="464" xlink:href="{image}"/>
</svg>'''
feature = f'''{svg_head} width="1024" height="500" viewBox="0 0 1024 500">
<defs><linearGradient id="bg"><stop stop-color="#07131F"/><stop offset="1" stop-color="#112938"/></linearGradient></defs>
<rect width="1024" height="500" fill="url(#bg)"/>
<g fill="none" stroke="#315063" stroke-width="1"><circle cx="815" cy="250" r="280"/><circle cx="815" cy="250" r="225"/><circle cx="815" cy="250" r="170"/></g>
<image x="550" y="25" width="450" height="450" xlink:href="{image}"/>
<text x="64" y="195" font-family="sans-serif" font-size="62" font-weight="bold" fill="#EAF5F8">OrbitScope</text>
<text x="67" y="250" font-family="sans-serif" font-size="25" fill="#67DDD7">Follow orbits. Explore signals.</text>
<rect x="67" y="294" width="394" height="1" fill="#315063"/>
<text x="67" y="335" font-family="sans-serif" font-size="19" fill="#EAF5F8">3D globe · Pass planning · USB SDR tools</text>
<text x="67" y="390" font-family="sans-serif" font-size="17" fill="#FFC979">Experimental receive-only preview</text>
</svg>'''
for name, svg in [('play-icon',icon),('feature-graphic',feature)]:
    (out / (name+'.svg')).write_text(svg)
    document = pymupdf.open(stream=svg.encode(),filetype='svg')
    pdf = pymupdf.open('pdf',document.convert_to_pdf())
    # Play's icon is a 32-bit PNG; the feature graphic is RGB without alpha.
    pixmap=pdf[0].get_pixmap(matrix=pymupdf.Matrix(1,1),alpha=name == 'play-icon')
    pixmap.save(out / (name+'.png'))
    print(name,pixmap.width,pixmap.height)
