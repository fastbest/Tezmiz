# 48x48 tuvalde basit şekil yardımcıları; çıktı SVG yol verisi (VectorDrawable ile uyumlu)
import math
def f(v): return ('%.2f'%v).rstrip('0').rstrip('.')
def rect(x,y,w,h,r=0):
    if r<=0: return f"M{f(x)},{f(y)}h{f(w)}v{f(h)}h{f(-w)}z"
    r=min(r,w/2,h/2)
    return (f"M{f(x+r)},{f(y)}h{f(w-2*r)}a{f(r)},{f(r)} 0 0 1 {f(r)},{f(r)}v{f(h-2*r)}"
            f"a{f(r)},{f(r)} 0 0 1 {f(-r)},{f(r)}h{f(-(w-2*r))}a{f(r)},{f(r)} 0 0 1 {f(-r)},{f(-r)}"
            f"v{f(-(h-2*r))}a{f(r)},{f(r)} 0 0 1 {f(r)},{f(-r)}z")
def circle(cx,cy,r):
    return f"M{f(cx-r)},{f(cy)}a{f(r)},{f(r)} 0 1 0 {f(2*r)},0a{f(r)},{f(r)} 0 1 0 {f(-2*r)},0z"
def poly(*pts):
    s="M"+" L".join(f"{f(x)},{f(y)}" for x,y in pts)
    return s+"z"
def line(*pts):
    return "M"+" L".join(f"{f(x)},{f(y)}" for x,y in pts)
def arc(cx,cy,r,a0,a1):
    # açı derece, saat yönü (y aşağı)
    x0=cx+r*math.cos(math.radians(a0)); y0=cy+r*math.sin(math.radians(a0))
    x1=cx+r*math.cos(math.radians(a1)); y1=cy+r*math.sin(math.radians(a1))
    large=1 if (a1-a0)%360>180 else 0
    return f"M{f(x0)},{f(y0)}A{f(r)},{f(r)} 0 {large} 1 {f(x1)},{f(y1)}"
def pie(cx,cy,r,a0,a1):
    x0=cx+r*math.cos(math.radians(a0)); y0=cy+r*math.sin(math.radians(a0))
    x1=cx+r*math.cos(math.radians(a1)); y1=cy+r*math.sin(math.radians(a1))
    large=1 if (a1-a0)%360>180 else 0
    return f"M{f(cx)},{f(cy)}L{f(x0)},{f(y0)}A{f(r)},{f(r)} 0 {large} 1 {f(x1)},{f(y1)}z"
# Katman: (dolgu, yol) ya da ('stroke', renk, kalınlık, yol)
def F(c,d): return ('fill',c,d)
def S(c,w,d): return ('stroke',c,w,d)
