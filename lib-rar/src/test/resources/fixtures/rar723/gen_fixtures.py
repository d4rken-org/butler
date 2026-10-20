"""Builds the rar723 fixture corpus and its manifest.

    python3 -I gen_fixtures.py --rar <path/to/rar> --out <dir> [--bsdtar <instrumented bsdtar>] [--huge]

Sources are generated deterministically into a fresh temporary directory, archived with RARLAB RAR,
listed with `rar lt`, and described in <out>/manifest.json. Expected sizes and SHA-256 values come
from the source files, never from extracting the archives. `--bsdtar` points at a libarchive bsdtar
that prints `RAR5FILTER type=<n> len=<n>` for every filter it parses; it is only used to record which
filter types each compressed fixture contains. `--huge` also builds the >4 GiB dictionary fixture
(needs ~11 GB RAM, ~4.4 GB temporary disk).
"""
import argparse
import hashlib
import json
import os
import shutil
import struct
import subprocess
import tempfile
import zlib

MTIME_NS = 1_700_000_000_123_456_789  # 2023-11-14T22:13:20.123456789Z
PASSWORD = "password"
UNICODE_PASSWORD = "pässwört密码"


def stream(seed, n):
    out = bytearray()
    c = 0
    while len(out) < n:
        out += hashlib.sha256(f"{seed}:{c}".encode()).digest()
        c += 1
    return bytes(out[:n])


def x86(seed, n, e9):
    """Pseudo x86 code: E8 (and E9) rel32 calls to 64 fixed targets between filler instructions.

    The absolute targets repeat, so converting rel32 to absolute addresses pays off for the filter.
    """
    r = stream(seed, n * 2)
    out = bytearray()
    i = 0
    targets = [((r[k] << 8) | r[k + 1]) * 4 % n for k in range(0, 128, 2)]
    while len(out) < n:
        b = r[i % len(r)]
        i += 1
        if b < 30:
            op = 0xE9 if (e9 and b < 10) else 0xE8
            t = targets[r[i % len(r)] % len(targets)]
            i += 1
            out += bytes([op]) + struct.pack('<i', t - (len(out) + 5))
        else:
            out += bytes([0x48, 0x89, 0xC0 | (b & 7)]) if b < 140 else bytes([0x8B, 0x45, b & 0x38])
    return bytes(out[:n])


def delta(seed, n, channels=3):
    """Three interleaved byte channels, each a slow random walk (like 24-bit sample tables)."""
    out = bytearray()
    v = [0] * channels
    r = stream(seed, n)
    for i in range(n):
        ch = i % channels
        v[ch] = (v[ch] + (r[i] % 5) - 2) & 0xFF
        out.append(v[ch])
    return bytes(out)


def lorem(seed, n):
    words = ("lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor "
             "incididunt ut labore et dolore magna aliqua enim ad minim veniam quis nostrud").split()
    r = stream(seed, n)
    out = []
    size = 0
    line = 0
    i = 0
    while size < n:
        count = 4 + r[i % len(r)] % 9
        text = f"{line:05d} " + " ".join(words[r[(i + k + 1) % len(r)] % len(words)] for k in range(count)) + "\n"
        i += count + 1
        line += 1
        out.append(text)
        size += len(text)
    return "".join(out).encode()[:n]


def dictdata(n):
    """A 16 KiB random block repeated with a counter every KiB: tiny when packed, larger than every window."""
    block = stream("dict", 16384)
    out = bytearray()
    k = 0
    while len(out) < n:
        for off in range(0, len(block), 1024):
            out += struct.pack('<I', k) + block[off + 4:off + 1024]
            k += 1
    return bytes(out[:n])


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as f:
        f.write(data)


def make_sources(root):
    b = os.path.join(root, 'basic')
    write(f"{b}/hello.txt", b"Hello, RAR!\n")
    write(f"{b}/docs/lorem.txt", lorem("lorem", 12000))
    write(f"{b}/docs/numbers.txt", "".join(f"{i}\n" for i in range(1, 2001)).encode())
    write(f"{b}/empty.txt", b"")
    os.makedirs(f"{b}/emptydir")
    for name in ("Ελληνικά.txt", "日本語のファイル.txt", "emoji 😀🎉.txt", "русский/файл.txt", "عربى.txt"):
        write(f"{b}/unicode/{name}", f"content of {name}\n".encode())

    f = os.path.join(root, 'filters')
    write(f"{f}/x86e9.bin", x86("x86e9", 40000, True))
    write(f"{f}/delta.bin", delta("delta", 40000))

    write(os.path.join(root, 'dict', 'dictdata.bin'), dictdata(2_200_000))

    s = os.path.join(root, 'symlinks')
    write(f"{s}/target.txt", b"symlink target\n")
    write(f"{s}/sub/inner.txt", b"inner file\n")
    os.symlink("target.txt", f"{s}/to-target")
    os.symlink("sub", f"{s}/to-sub")
    os.symlink("../target.txt", f"{s}/sub/up-target")

    h = os.path.join(root, 'hardlinks')
    write(f"{h}/original.txt", b"hard linked content\n")
    os.link(f"{h}/original.txt", f"{h}/second.txt")

    i = os.path.join(root, 'identical')
    write(f"{i}/first.bin", stream("identical", 8192))
    write(f"{i}/copy.bin", stream("identical", 8192))

    p = os.path.join(root, 'perfile')
    write(f"{p}/one/a.txt", b"alpha, password one\n")
    write(f"{p}/two/b.txt", b"bravo, password two\n")
    write(f"{p}/plain/c.txt", b"charlie, no password\n")
    write(f"{p}/one/same.txt", b"first\n")
    write(f"{p}/two/same.txt", b"second\n")

    v = os.path.join(root, 'versions')
    write(f"{v}/v1/note.txt", b"version one\n")
    write(f"{v}/v2/note.txt", b"version two, longer\n")

    vol = os.path.join(root, 'volumes')
    write(f"{vol}/random.bin", stream("volume", 30000))
    write(f"{vol}/lorem.txt", lorem("volume", 30000))
    write(f"{vol}/small.txt", b"small file after the split ones\n")

    m = os.path.join(root, 'misc')
    write(f"{m}/hello.txt", b"Hello, RAR!\n")
    write(f"{m}/numbers.txt", "".join(f"{i}\n" for i in range(1, 2001)).encode())
    write(os.path.join(root, 'comment.txt'), "Fixture comment.\nSecond line: ünïcödé ✓\n".encode())

    for dirpath, dirnames, filenames in os.walk(root, topdown=False):
        for name in filenames + dirnames:
            pth = os.path.join(dirpath, name)
            if not os.path.islink(pth):
                os.chmod(pth, 0o755 if os.path.isdir(pth) else 0o644)
            os.utime(pth, ns=(MTIME_NS, MTIME_NS), follow_symlinks=False)


def rar_cmd(rar, args, cwd, log):
    log.append({"cwd": cwd, "command": "rar " + " ".join(args)})
    subprocess.run([rar] + args, cwd=cwd, check=True, stdout=subprocess.DEVNULL)


def lt(rar, archive, password, out_dir):
    args = [rar, 'lt', '-idc']
    args.append(f"-p{password}" if password else '-p-')
    args.append(os.path.basename(archive))
    res = subprocess.run(args, cwd=out_dir, check=True, capture_output=True)
    return res.stdout.decode('utf-8')


def parse_lt(text):
    blocks = []
    head = {}
    cur = None
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            if cur:
                blocks.append(cur)
            cur = None
            continue
        if ':' not in line:
            continue
        key, _, value = line.partition(':')
        key, value = key.strip(), value.strip()
        if key == 'Name':
            cur = {}
        if cur is None:
            head[key] = value
        else:
            cur[key] = value
    if cur:
        blocks.append(cur)
    return head, blocks


def filter_types(bsdtar, files):
    if not bsdtar:
        return None
    work = tempfile.mkdtemp()
    try:
        res = subprocess.run([bsdtar, '-xf', files[0], '-C', work], capture_output=True)
        types = []
        for line in res.stderr.decode('utf-8', 'replace').splitlines():
            if line.startswith('RAR5FILTER'):
                types.append(int(line.split()[1].split('=')[1]))
        return sorted(set(types)), len(types)
    finally:
        shutil.rmtree(work)


def describe(path):
    st = os.lstat(path)
    if os.path.islink(path):
        return {"type": "symlink", "linkTarget": os.readlink(path)}
    if os.path.isdir(path):
        return {"type": "directory", "size": 0}
    data = open(path, 'rb').read()
    return {"type": "file", "size": len(data), "sha256": hashlib.sha256(data).hexdigest(),
            "crc32": f"{zlib.crc32(data):08x}", "mtimeNs": st.st_mtime_ns}


def zero_description(size, mtime_ns):
    h = hashlib.sha256()
    crc = 0
    chunk = bytes(1 << 20)
    for _ in range(size // len(chunk)):
        h.update(chunk)
        crc = zlib.crc32(chunk, crc)
    return {"type": "file", "size": size, "sha256": h.hexdigest(), "crc32": f"{crc:08x}", "mtimeNs": mtime_ns}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--rar', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--bsdtar')
    ap.add_argument('--huge', action='store_true')
    a = ap.parse_args()
    rar = os.path.abspath(a.rar)
    bsdtar = os.path.abspath(a.bsdtar) if a.bsdtar else None
    out = os.path.abspath(a.out)
    os.makedirs(out, exist_ok=True)
    src = tempfile.mkdtemp()
    make_sources(src)
    os.environ['TZ'] = 'UTC'

    basic = ['hello.txt', 'docs', 'empty.txt', 'emptydir', 'unicode']
    misc = ['hello.txt', 'numbers.txt']

    # name, [(cwd under the source root, switches, files, password)]; steps after the first append.
    specs = [
        ('stored.rar', [('basic', ['-m0', '-ts+'], basic, None)]),
        ('m1.rar', [('basic', ['-m1'], basic, None)]),
        ('m3.rar', [('basic', ['-m3'], basic, None)]),
        ('m5.rar', [('basic', ['-m5'], basic, None)]),
        ('filter-e8.rar', [('filters', ['-m3', '-mce+'], ['x86e9.bin'], None)]),
        ('filter-e8e9.rar', [('filters', ['-m3'], ['x86e9.bin'], None)]),
        ('filter-delta.rar', [('filters', ['-m3', '-mc3d+'], ['delta.bin'], None)]),
        ('solid.rar', [('basic', ['-m3', '-s'], basic, None)]),
        ('dict-128k.rar', [('dict', ['-m3', '-md128k'], ['dictdata.bin'], None)]),
        ('dict-1m.rar', [('dict', ['-m3', '-md1m'], ['dictdata.bin'], None)]),
        ('dict-4m.rar', [('dict', ['-m3', '-md4m'], ['dictdata.bin'], None)]),
        ('dict-default.rar', [('dict', ['-m3'], ['dictdata.bin'], None)]),
        ('symlinks.rar', [('symlinks', ['-m3', '-ol'], ['target.txt', 'sub', 'to-target', 'to-sub'], None)]),
        ('hardlinks.rar', [('hardlinks', ['-m3', '-oh'], ['original.txt', 'second.txt'], None)]),
        ('filecopy.rar', [('identical', ['-m3', '-oi:1'], ['first.bin', 'copy.bin'], None)]),
        ('blake2.rar', [('basic', ['-m3', '-htb'], basic, None)]),
        ('encrypted-data.rar', [('basic', ['-m3', f'-p{PASSWORD}'], basic, PASSWORD)]),
        ('encrypted-stored.rar', [('misc', ['-m0', f'-p{PASSWORD}'], misc, PASSWORD)]),
        ('encrypted-headers.rar', [('basic', ['-m3', f'-hp{PASSWORD}'], basic, PASSWORD)]),
        ('solid-encrypted.rar', [('basic', ['-m3', '-s', f'-p{PASSWORD}'], basic, PASSWORD)]),
        ('encrypted-unicode-password.rar', [('misc', ['-m3', f'-p{UNICODE_PASSWORD}'], misc, UNICODE_PASSWORD)]),
        ('per-file-passwords.rar', [
            ('perfile/one', ['-m3', '-pone'], ['a.txt'], 'one'),
            ('perfile/two', ['-m3', '-ptwo'], ['b.txt'], 'two'),
            ('perfile/plain', ['-m3'], ['c.txt'], None),
            ('perfile/one', ['-m3', '-pone', '-ver'], ['same.txt'], 'one'),
            ('perfile/two', ['-m3', '-ptwo', '-ver'], ['same.txt'], 'two'),
        ]),
        ('comment.rar', [('misc', ['-m3', '-z../comment.txt'], misc, None)]),
        ('recovery.rar', [('misc', ['-m3', '-rr5%'], misc, None)]),
        ('quickopen.rar', [('misc', ['-m3', '-qo+'], misc, None)]),
        ('locked.rar', [('misc', ['-m3', '-k'], misc, None)]),
        ('versions.rar', [
            ('versions/v1', ['-m3', '-ver'], ['note.txt'], None),
            ('versions/v2', ['-m3', '-ver'], ['note.txt'], None),
        ]),
        ('vol-plain.part1.rar', [('volumes', ['-m3', '-v15k'], ['random.bin', 'lorem.txt', 'small.txt'], None)]),
        ('vol-solid.part1.rar', [('volumes', ['-m3', '-s', '-v15k'], ['random.bin', 'lorem.txt', 'small.txt'], None)]),
        ('vol-hp.part1.rar', [('volumes', ['-m3', f'-hp{PASSWORD}', '-v15k'], ['random.bin', 'lorem.txt', 'small.txt'], PASSWORD)]),
    ]
    if a.huge:
        specs.append(('dict-5g-v1.rar', [('huge', ['-m1', '-mt4', '-md5g'], ['zero.bin'], None)]))

    manifest = {"rar": subprocess.run([rar], capture_output=True).stdout.decode().splitlines()[1].strip(),
                "mtimeNs": MTIME_NS, "fixtures": {}}

    for name, steps in specs:
        log = []
        archive = os.path.join(out, name)
        stem = name[:-len('.part1.rar')] if name.endswith('.part1.rar') else None
        sources = {}  # archived name -> list of descriptions, oldest first
        passwords = {}
        for cwd, switches, files, password in steps:
            work = os.path.join(src, cwd)
            if cwd == 'huge':
                os.makedirs(work, exist_ok=True)
                with open(os.path.join(work, 'zero.bin'), 'wb') as f:
                    f.truncate(4400 * 1024 * 1024)
            target = os.path.join(out, stem + '.rar') if stem else archive
            rar_cmd(rar, ['a', '-idq'] + switches + [target] + files, work, log)
            log[-1]["command"] = log[-1]["command"].replace(target, os.path.basename(target))
            log[-1]["cwd"] = cwd
            for f in files:
                top = os.path.join(work, f)
                walk = [top]
                if os.path.isdir(top) and not os.path.islink(top):
                    for dp, dn, fn in os.walk(top):
                        walk += [os.path.join(dp, x) for x in dn + fn]
                for p in walk:
                    rel = os.path.relpath(p, work)
                    if cwd == 'huge':
                        d = zero_description(4400 * 1024 * 1024, os.lstat(p).st_mtime_ns)
                    else:
                        d = describe(p)
                    sources.setdefault(rel, []).append(d)
                    passwords.setdefault(rel, []).append(password)
            if cwd == 'huge':
                os.remove(os.path.join(work, 'zero.bin'))

        if stem:
            files = sorted((f for f in os.listdir(out) if f.startswith(stem + '.part') and f.endswith('.rar')),
                           key=lambda f: int(f[len(stem) + 5:-4]))
        else:
            files = [name]
        list_password = next((s[3] for s in steps if s[3]), None)
        texts = [lt(rar, os.path.join(out, f), list_password, out) for f in files]
        head = parse_lt(texts[0])[0]

        entries = []
        seen = {}
        ordinal = -1
        for volume, text in enumerate(texts):
            for blk in parse_lt(text)[1]:
                shown = blk['Name']
                base, version = shown, None
                if 'File version' in blk:
                    version = int(blk['File version'])
                    base = shown.rsplit(';', 1)[0]
                ratio = blk.get('Ratio', '')
                continuation = ratio in ('<--', '<->')
                if continuation:
                    idx = seen[base] - 1
                else:
                    idx = seen.get(base, 0)
                    seen[base] = idx + 1
                    ordinal += 1
                desc = dict(sources[base][idx])
                lt_type = blk.get('Type')
                if lt_type == 'Hard link':
                    desc = {"type": "hardlink", "linkTarget": blk.get('Target'), "sha256": desc.get('sha256')}
                elif lt_type == 'File reference':
                    desc = {"type": "filecopy", "linkTarget": blk.get('Target'), "sha256": desc.get('sha256')}
                if 'Size' in blk:
                    desc["size"] = int(blk['Size'])
                checksum = next(([k, v] for k, v in blk.items()
                                 if k in ('CRC32', 'CRC32 MAC', 'BLAKE2', 'BLAKE2 MAC', 'Pack-CRC32', 'Pack-BLAKE2')),
                                None)
                entries.append({
                    "volume": volume, "ordinal": ordinal, "continuation": continuation,
                    "name": base, **desc, "version": version,
                    "packedSize": int(blk['Packed size']) if 'Packed size' in blk else None,
                    "password": passwords[base][idx],
                    "encrypted": 'encrypted' in blk.get('Flags', ''),
                    "solid": 'solid' in blk.get('Flags', ''),
                    "splitAcrossVolumes": ratio in ('-->', '<--', '<->'),
                    "ltType": lt_type, "ltChecksum": checksum, "ltCompression": blk.get('Compression'),
                })

        ft = None
        # libarchive refuses dictionaries above 64 MiB, so the huge fixture cannot be scanned.
        if not list_password and not stem and steps[0][0] != 'huge' and any('-m0' not in s[1] for s in steps):
            res = filter_types(bsdtar, [archive])
            if res is not None:
                ft = {"types": res[0], "count": res[1]}
        manifest["fixtures"][name] = {
            "files": files,
            "commands": log,
            "listPassword": list_password,
            "headerEncrypted": any(sw.startswith('-hp') for s in steps for sw in s[1]),
            "solid": any('-s' in s[1] for s in steps),
            "details": head.get('Details'),
            "filters": ft,
            "entries": entries,
            "rarLt": texts,
        }

    # RAR 7.23 only writes .partN.rar names; the legacy set is a renamed byte-identical copy.
    plain = manifest["fixtures"]["vol-plain.part1.rar"]
    legacy = ["vol-legacy.rar"] + [f"vol-legacy.r{i:02d}" for i in range(len(plain["files"]) - 1)]
    for src_name, dst_name in zip(plain["files"], legacy):
        shutil.copyfile(os.path.join(out, src_name), os.path.join(out, dst_name))
    manifest["fixtures"]["vol-legacy.rar"] = dict(plain, files=legacy, copyOf="vol-plain.part1.rar",
                                                  commands=[{"command": f"cp {s} {d}"} for s, d in zip(plain["files"], legacy)])

    with open(os.path.join(out, 'manifest.json'), 'w', encoding='utf-8') as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1, sort_keys=False)
        f.write('\n')
    shutil.rmtree(src)


if __name__ == '__main__':
    main()
