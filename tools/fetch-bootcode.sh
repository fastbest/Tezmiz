#!/usr/bin/env bash
# Rufus projesindeki ms-sys açılış kodlarını ve FreeDOS dosyalarını indirip assets/boot altına koyar.
# Rufus ve ms-sys GPL-3.0/GPL-2.0, FreeDOS GPL lisanslıdır: https://github.com/pbatard/rufus
# Bulunamayan dosya derlemeyi durdurmaz; uygulama o özelliği kapalı gösterir.
set -uo pipefail
TAG=v4.6
OUT="${1:-$PWD/app/src/main/assets/boot}"
mkdir -p "$OUT"
work=$(mktemp -d)
if ! curl -fsSL "https://github.com/pbatard/rufus/archive/refs/tags/$TAG.tar.gz" | tar -xz -C "$work"; then
  echo "Rufus indirilemedi; BIOS açılış kodu eklenmeyecek"
  exit 0
fi
src=$(find "$work" -maxdepth 1 -type d -name 'rufus-*' | head -1)
python3 - "$src" "$OUT" <<'PY'
import os, re, sys
src, out = sys.argv[1], sys.argv[2]
want = ["mbr_win7.h", "br_fat32_0x0.h", "br_fat32nt_0x52.h", "br_fat32nt_0x3f0.h",
        "br_fat32nt_0x1800.h", "br_fat32fd_0x52.h", "br_fat32fd_0x3f0.h"]
found = {}
for root, dirs, files in os.walk(src):
    for f in files:
        if f in want and f not in found:
            found[f] = os.path.join(root, f)
for name in want:
    p = found.get(name)
    if not p:
        print("bulunamadı:", name)
        continue
    text = open(p, encoding="latin-1").read()
    m = re.search(r"\{(.*?)\}", text, re.S)
    if not m:
        print("dizi yok:", name)
        continue
    nums = re.findall(r"0[xX][0-9a-fA-F]+|\b\d+\b", m.group(1))
    data = bytes(int(n, 0) for n in nums)
    open(os.path.join(out, name[:-2] + ".bin"), "wb").write(data)
    print(name, len(data), "bayt")
for root, dirs, files in os.walk(src):
    if os.path.basename(root).lower() == "freedos":
        for f in files:
            if f.upper() in ("KERNEL.SYS", "COMMAND.COM"):
                data = open(os.path.join(root, f), "rb").read()
                open(os.path.join(out, f.upper()), "wb").write(data)
                print("FreeDOS", f, len(data), "bayt")
PY
ls -la "$OUT"
exit 0
