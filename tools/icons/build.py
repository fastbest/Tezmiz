import sys, os
sys.path.insert(0,'.')
from icons import I
def svg(layers, size=48):
    out=[f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 48 48">']
    for l in layers:
        if l[0]=='fill':
            c=l[1]; op=1
            if len(c)==9: op=int(c[1:3],16)/255; c='#'+c[3:]
            out.append(f'<path d="{l[2]}" fill="{c}" fill-opacity="{op:.3f}"/>')
        else:
            c=l[1]; op=1
            if len(c)==9: op=int(c[1:3],16)/255; c='#'+c[3:]
            out.append(f'<path d="{l[3]}" fill="none" stroke="{c}" stroke-opacity="{op:.3f}" stroke-width="{l[2]}" stroke-linecap="round" stroke-linejoin="round"/>')
    out.append('</svg>')
    return '\n'.join(out)
def vd(layers):
    out=['<?xml version="1.0" encoding="utf-8"?>',
         '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
         '    android:width="48dp" android:height="48dp"',
         '    android:viewportWidth="48" android:viewportHeight="48">']
    for l in layers:
        c=l[1]
        if len(c)==7: c='#FF'+c[1:]
        if l[0]=='fill':
            out.append(f'    <path android:fillColor="{c}" android:pathData="{l[2]}" />')
        else:
            out.append(f'    <path android:strokeColor="{c}" android:strokeWidth="{l[2]}" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{l[3]}" />')
    out.append('</vector>')
    return '\n'.join(out)
mode=sys.argv[1]
if mode=='preview':
    os.makedirs('prev',exist_ok=True)
    names=list(I)
    for n in names: open(f'prev/{n}.svg','w').write(svg(I[n],96))
    print(len(names))
else:
    outdir=sys.argv[2]; os.makedirs(outdir,exist_ok=True)
    for n,l in I.items(): open(f'{outdir}/ic_{n}.xml','w').write(vd(l))
    print('wrote',len(I))
