import sys
sys.path.insert(0,'.')
from icons import I
W,H=360,780
o=[]
def esc(s): return s.replace('&','&amp;').replace('<','&lt;')
def rect(x,y,w,h,fill,r=0,stroke=None,sw=0):
    o.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{fill}"'+(f' stroke="{stroke}" stroke-width="{sw}"' if stroke else '')+'/>')
def text(x,y,s,size,fill='#F2F2F2',weight='normal',anchor='start',font='Liberation Sans'):
    o.append(f'<text x="{x}" y="{y}" font-family="{font}" font-size="{size}" font-weight="{weight}" fill="{fill}" text-anchor="{anchor}">{esc(s)}</text>')
def icon(name,x,y,size,rot=0,alpha=1):
    g=[f'<g transform="translate({x},{y}) scale({size/48})'+(f' rotate({rot} 24 24)' if rot else '')+f'" opacity="{alpha}">']
    for l in I[name]:
        c=l[1]; op=1
        if len(c)==9: op=int(c[1:3],16)/255; c='#'+c[3:]
        if l[0]=='fill': g.append(f'<path d="{l[2]}" fill="{c}" fill-opacity="{op:.2f}"/>')
        else: g.append(f'<path d="{l[3]}" fill="none" stroke="{c}" stroke-opacity="{op:.2f}" stroke-width="{l[2]}" stroke-linecap="round" stroke-linejoin="round"/>')
    g.append('</g>'); o.append(''.join(g))
BG='#2A2B2F'; TOP='#17181B'; TOOL='#1F2023'; MUTED='#A4A9B1'
rect(0,0,W,H,BG)
# üst çubuk
rect(0,0,W,52,TOP)
text(16,33,'Kart Format',20,weight='normal')
for i,n in enumerate(['m_search','m_sort','m_menu']): icon(n,W-44*(3-i)+10,15,22)
TW=58; PW=W-TW
# panel başlığı
rect(6,58,150,28,'#1E5BB8',7); icon('phone',12,62,20); text(38,77,'Dahili depolama',13.5,'#FFFFFF')
y=92
def row(depth,ic,title,sub=None,arrow=None,cur=False,sel=False,right=None,bar=None,bold=True,counts=None,check=True,path=None):
    global y
    lines=1+(1 if (sub or counts) else 0)+(1 if path else 0)
    h=46 if lines==1 else 54 if lines==2 else 68
    if sel: rect(3,y+1,PW-6,h-2,'#2B4E73',6)
    if cur: rect(4,y+2,PW-8,h-4,'none',6,'#FF9800',2)
    x=6+depth*14
    if arrow: icon('tree_open' if arrow=='open' else 'tree_closed',x,y+h/2-9,18)
    x+=20
    icon(ic,x,y+h/2-19,38)
    tx=x+48; ty=y+(h/2+5 if lines==1 else 22)
    maxw=PW-tx-(34 if check else 8)-(88 if right else 0)
    s=title
    while len(s)*7.6>maxw and len(s)>4: s=s[:-2]
    if s!=title: s=s[:-1]+'…'
    text(tx,ty,s,15,weight='bold' if bold else 'normal')
    if counts:
        icon('meta_folder',tx,ty+7,13); text(tx+17,ty+18,str(counts[0]),12,MUTED); icon('meta_file',tx+44,ty+7,13); text(tx+60,ty+18,str(counts[1]),12,MUTED)
    elif sub: text(tx,ty+18,sub,12,MUTED)
    if path: text(tx,ty+34,path,12,MUTED)
    if right:
        rx=PW-10
        text(rx,y+19,right[0],11.5,MUTED,anchor='end'); text(rx,y+33,right[1],11.5,'#C9CDD3',anchor='end')
        if bar is not None:
            rect(rx-80,y+40,80,4,'#44474D',2); rect(rx-80,y+40,80*bar,4,'#4DB6AC',2)
    elif check:
        cx=PW-30; cy=y+h/2-10
        if sel: rect(cx,cy,20,20,'#29B6F6',4); icon('check',cx+2,cy+2,16)
        else: rect(cx,cy,20,20,'none',4,'#5F646C',1.5)
    y+=h
row(0,'phone','Dahili depolama',arrow='open',cur=False,right=('Boş','64GB/226GB'),bar=0.72,counts=(27,1),path='/storage/emulated/0',check=False)
row(1,'folder','Android',arrow='closed',sub='12.11.2026')
row(1,'folder','DCIM',arrow='open',cur=True,counts=(2,3))
row(2,'folder','Camera',arrow='closed',sub='15.11.2026')
row(2,'file_image','IMG_20261114_183355.jpg',sub='3,4 MB  •  14.11.2026 18:33',bold=False,sel=True)
row(2,'file_video','VID_20261113_101210.mp4',sub='48 MB  •  13.11.2026 10:12',bold=False,sel=True)
row(1,'folder','Download',arrow='closed',sub='15.11.2026')
row(1,'file_pdf','fatura-kasim.pdf',sub='212 KB  •  02.11.2026 09:40',bold=False)
rect(10,y+3,PW-20,1,'#3A3C41'); y+=7
row(0,'usb','USB kart',arrow='closed',right=('Boş','21GB/59GB'),bar=0.64,sub='Generic • exFAT',check=False)
row(0,'star','Sık kullanılanlar',arrow='closed',check=False)
row(0,'clock','Son dosyalar',arrow='closed',check=False)
row(0,'apps','Uygulama yöneticisi',arrow='closed',check=False)
row(0,'network','FTP sunucuları',arrow='closed',check=False)
row(0,'wifi','WiFi dosya paylaşımı',sub='Kapalı',check=False)
row(0,'vault','Kasa',sub='Şifreli dosyalar',check=False)
row(0,'tools','USB kart araçları',sub='Biçimlendir, imaj yaz…',check=False)
# sağ sütun
rect(PW,52,TW,H-52,TOOL)
# diğer panel şeridi
rect(PW,52,TW,128,'#26272B'); rect(PW,52,2,H-52,'#131416')
icon('usb',PW+16,58,26); icon('tree_closed',PW+20,88,18)
o.append(f'<text transform="translate({PW+24},112) rotate(90)" font-family="Liberation Sans" font-size="14" font-weight="bold" fill="#E0E3E8">USB kart</text>')
rect(PW+2,180,TW-2,1,'#34363B')
tools=[('t_up','Üst klasör',1),('t_newfolder','Yeni klasör',1),('t_rename','Ad değiştir',.3),('t_copy','Kopyala',1),('t_move','Taşı',1),('t_delete','Sil',1),('t_zip','ZIP yap',1),('t_extract','Arşivi aç',.3),('t_share','Paylaş',1),('t_info','Özellikler',1)]
ty=184
for ic,name,a in tools:
    icon(ic,PW+15,ty+6,28,alpha=a); text(PW+TW/2+1,ty+47,name,9.5,'#D0D3D8' if a==1 else '#6C7078',anchor='middle')
    ty+=56; rect(PW+8,ty,TW-14,1,'#2C2E32')
# alt işlem çubuğu
rect(0,H-44,W,44,TOP); text(14,H-24,'Kopyalanıyor: VID_20261113_101210.mp4',12.5); text(W-14,H-24,'İptal',13,'#FF7A7A',anchor='end')
rect(14,H-14,W-28,4,'#44474D',2); rect(14,H-14,(W-28)*0.4,4,'#4DB6AC',2)
open('mock.svg','w').write(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">'+'\n'.join(o)+'</svg>')
