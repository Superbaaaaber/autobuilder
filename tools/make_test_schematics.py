"""生成测试用的 .litematic 和 .schem（Sponge v2/v3）文件，验证 mod 的读取逻辑。"""
import gzip, struct, sys, os

def tag_payload(t, v):
    if t == 1: return struct.pack('>b', v)
    if t == 2: return struct.pack('>h', v)
    if t == 3: return struct.pack('>i', v)
    if t == 4: return struct.pack('>q', v)
    if t == 7: return struct.pack('>i', len(v)) + bytes(v)
    if t == 8:
        b = v.encode('utf-8'); return struct.pack('>H', len(b)) + b
    if t == 9:
        et, items = v
        return struct.pack('>bi', et if items else 0, len(items)) + b''.join(tag_payload(et, x) for x in items)
    if t == 10:
        out = b''
        for k, (tt, vv) in v.items():
            kb = k.encode('utf-8')
            out += struct.pack('>bH', tt, len(kb)) + kb + tag_payload(tt, vv)
        return out + b'\x00'
    if t == 12: return struct.pack('>i', len(v)) + b''.join(struct.pack('>q', x) for x in v)
    raise ValueError(t)

def write(path, root):
    data = struct.pack('>bH', 10, 0) + tag_payload(10, root)
    with gzip.open(path, 'wb') as f: f.write(data)

def S(v): return (8, v)
def I(v): return (3, v)
def C(d): return (10, d)

def state(name, props=None):
    d = {'Name': S(name)}
    if props: d['Properties'] = C({k: S(v) for k, v in props.items()})
    return d

# 测试结构 3(x) × 2(y) × 2(z)：
# y=0: 石砖×3(z=0)，橡木原木 axis=x ×3 (z=1)
# y=1: 东向楼梯 / 上半石台阶 / 空气 (z=0)；空气×3 (z=1)
blocks = {}
for x in range(3):
    blocks[(x,0,0)] = ('minecraft:stone_bricks', None)
    blocks[(x,0,1)] = ('minecraft:oak_log', {'axis': 'x'})
    blocks[(x,1,1)] = ('minecraft:air', None)
blocks[(0,1,0)] = ('minecraft:oak_stairs', {'facing': 'east', 'half': 'bottom', 'shape': 'straight', 'waterlogged': 'false'})
blocks[(1,1,0)] = ('minecraft:stone_brick_slab', {'type': 'top', 'waterlogged': 'false'})
blocks[(2,1,0)] = ('minecraft:air', None)
SX, SY, SZ = 3, 2, 2
DV = 3955  # 1.21.1

def key(n, p):
    return n + ('[' + ','.join(f'{k}={v}' for k, v in sorted(p.items())) + ']' if p else '')

out = sys.argv[1]
os.makedirs(out, exist_ok=True)

# ---- litematic ----
pal = [('minecraft:air', None)]
idx = {}
for v in blocks.values():
    k = key(*v)
    if k not in idx and v[0] != 'minecraft:air':
        idx[k] = len(pal); pal.append(v)
idx[key('minecraft:air', None)] = 0
bits = max(2, (len(pal) - 1).bit_length())
vol = SX * SY * SZ
total_bits = vol * bits
longs = [0] * ((total_bits + 63) // 64)
for (x, y, z), v in blocks.items():
    i = (y * SZ + z) * SX + x
    val = idx[key(*v)]
    start = i * bits
    li, off = start >> 6, start & 63
    longs[li] |= (val << off) & 0xFFFFFFFFFFFFFFFF
    if off + bits > 64:
        longs[li + 1] |= val >> (64 - off)
longs = [x - (1 << 64) if x >= (1 << 63) else x for x in longs]
region = {
    'Position': C({'x': I(0), 'y': I(0), 'z': I(0)}),
    'Size': C({'x': I(SX), 'y': I(SY), 'z': I(SZ)}),
    'BlockStatePalette': (9, (10, [state(n, p) for n, p in pal])),
    'BlockStates': (12, longs),
    'TileEntities': (9, (10, [])), 'Entities': (9, (10, [])),
}
write(os.path.join(out, 'test_litematic.litematic'), {
    'MinecraftDataVersion': I(DV), 'Version': I(6),
    'Metadata': C({'Name': S('test'), 'Author': S('autotest')}),
    'Regions': C({'main': C(region)}),
})

# ---- sponge v2 / v3 ----
spal = {}
for v in [('minecraft:air', None)] + list(blocks.values()):
    k = key(*v)
    if k not in spal: spal[k] = len(spal)
def varints():
    b = bytearray()
    for y in range(SY):
        for z in range(SZ):
            for x in range(SX):
                v = spal[key(*blocks[(x, y, z)])]
                while True:
                    byte = v & 0x7F; v >>= 7
                    if v: b.append(byte | 0x80)
                    else: b.append(byte); break
    return list(struct.unpack(f'{len(b)}b', bytes(b)))
pal_tag = C({k: I(v) for k, v in spal.items()})
write(os.path.join(out, 'test_sponge_v2.schem'), {
    'Version': I(2), 'DataVersion': I(DV), 'Width': (2, SX), 'Height': (2, SY), 'Length': (2, SZ),
    'PaletteMax': I(len(spal)), 'Palette': pal_tag, 'BlockData': (7, varints()),
})
write(os.path.join(out, 'test_sponge_v3.schem'), {
    'Schematic': C({'Version': I(3), 'DataVersion': I(DV), 'Width': (2, SX), 'Height': (2, SY), 'Length': (2, SZ),
                    'Blocks': C({'Palette': pal_tag, 'Data': (7, varints())})})
})
print('ok', os.listdir(out))
