"""打印 .litematic 的区域、调色板和方块统计（只读）。"""
import gzip, struct, sys, collections

def read(f):
    data = gzip.decompress(open(f, 'rb').read())
    pos = [0]
    def rd(fmt):
        v = struct.unpack_from('>' + fmt, data, pos[0]); pos[0] += struct.calcsize('>' + fmt); return v[0]
    def payload(t):
        if t == 1: return rd('b')
        if t == 2: return rd('h')
        if t == 3: return rd('i')
        if t == 4: return rd('q')
        if t == 5: return rd('f')
        if t == 6: return rd('d')
        if t == 7:
            n = rd('i'); v = data[pos[0]:pos[0]+n]; pos[0] += n; return v
        if t == 8:
            n = rd('H'); v = data[pos[0]:pos[0]+n].decode('utf-8', 'replace'); pos[0] += n; return v
        if t == 9:
            et = rd('b'); n = rd('i'); return [payload(et) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                tt = rd('b')
                if tt == 0: return d
                n = rd('H'); k = data[pos[0]:pos[0]+n].decode('utf-8', 'replace'); pos[0] += n
                d[k] = payload(tt)
        if t == 11:
            n = rd('i'); return [rd('i') for _ in range(n)]
        if t == 12:
            n = rd('i'); return [rd('q') for _ in range(n)]
        raise ValueError(t)
    t = rd('b'); rd('H') if False else None
    n = rd('H'); pos[0] += n
    return payload(10)

root = read(sys.argv[1])
print('DataVersion', root.get('MinecraftDataVersion'), 'Version', root.get('Version'))
md = root.get('Metadata', {})
print('Meta', {k: v for k, v in md.items() if k != 'PreviewImageData'})
total = collections.Counter()
for name, r in root['Regions'].items():
    pal = r['BlockStatePalette']
    size = (r['Size']['x'], r['Size']['y'], r['Size']['z'])
    ax, ay, az = (abs(v) for v in size)
    bits = max(2, (len(pal) - 1).bit_length())
    arr = [x & 0xFFFFFFFFFFFFFFFF for x in r['BlockStates']]
    mask = (1 << bits) - 1
    cnt = collections.Counter()
    for i in range(ax * ay * az):
        s = i * bits; li = s >> 6; off = s & 63
        v = arr[li] >> off
        if off + bits > 64: v |= arr[li + 1] << (64 - off)
        v &= mask
        cnt[v] += 1
    print('Region', name, 'pos', r['Position'], 'size', size, 'palette', len(pal), 'tileEntities', len(r.get('TileEntities', [])), 'entities', len(r.get('Entities', [])))
    for idx, c in sorted(cnt.items(), key=lambda kv: -kv[1]):
        p = pal[idx]
        props = p.get('Properties', {})
        key = p['Name'] + ('[' + ','.join(f'{k}={v}' for k, v in sorted(props.items())) + ']' if props else '')
        total[key] += c
for k, c in sorted(total.items(), key=lambda kv: -kv[1]):
    print(f'{c:6d}  {k}')

# ---- 分层打印 ----
ABBR = {'air': '.', 'cobblestone': 'C', 'white_stained_glass': 'G', 'smooth_stone': 'S', 'oak_leaves': 'L', 'lava': 'l', 'water': 'w',
        'hopper': 'H', 'observer': 'O', 'repeater': 'R', 'chest': 'c', 'crying_obsidian': 'X', 'iron_trapdoor': 'T', 'stone_brick_wall': 'W',
        'redstone_wall_torch': 't', 'lever': 'V', 'note_block': 'N', 'redstone_wire': 'r', 'piston': 'P', 'tnt': '!'}
for name, r in root['Regions'].items():
    pal = r['BlockStatePalette']
    ax, ay, az = (abs(r['Size'][k]) for k in 'xyz')
    bits = max(2, (len(pal) - 1).bit_length()); mask = (1 << bits) - 1
    arr = [x & 0xFFFFFFFFFFFFFFFF for x in r['BlockStates']]
    def at(x, y, z):
        i = (y * az + z) * ax + x; s = i * bits; li = s >> 6; off = s & 63
        v = arr[li] >> off
        if off + bits > 64: v |= arr[li + 1] << (64 - off)
        return pal[v & mask]
    for y in range(ay):
        print(f'--- y={y} (行=z 从北到南, 列=x 从西到东)')
        for z in range(az):
            row = ''
            notes = []
            for x in range(ax):
                p = at(x, y, z); n = p['Name'].split(':')[1]; ch = ABBR.get(n, '?')
                row += ch
                if ch not in '.CGSL':
                    pr = p.get('Properties', {})
                    keep = {k: v for k, v in pr.items() if k in ('facing', 'level', 'delay', 'face', 'powered', 'type', 'half', 'waterlogged', 'instrument', 'extended')}
                    notes.append(f'({x},{y},{z}){n}{keep}')
                elif ch == 'L' and p.get('Properties', {}).get('waterlogged') == 'true':
                    pass
            print('  ' + row + '   ' + '  '.join(notes))
