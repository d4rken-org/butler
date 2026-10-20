"""Decodes libarchive's RAR test fixtures and lists them with RARLAB RAR into manifest.json.

    python3 -I import_fixtures.py --test-dir <libarchive>/libarchive/test --rar <path/to/rar> --out <dir>

Copies every test_read_format_rar5*.uu plus the RAR4 test_read_format_rar.rar.uu (uudecoded; `.Z`
payloads go through `gzip -d`). The manifest holds `rar lt` output per fixture, parsed entries and,
where `rar x` succeeds, SHA-256 values of the extracted regular files. libarchive's fixtures have no
separate sources, so those hashes come from RAR 7.23's extraction.
"""
import argparse
import binascii
import glob
import hashlib
import json
import os
import shutil
import subprocess
import tempfile

# test_read_format_rar_encryption.c:29-33: d.txt in the non-solid archive uses "password2".
PASSWORDS = {
    'test_read_format_rar5_encrypted.rar': {'d.txt': 'password2', '*': 'password'},
}
DEFAULT_PASSWORD = 'password'


def uudecode(path):
    lines = open(path, 'rb').read().splitlines()
    start = next(i for i, l in enumerate(lines) if l.startswith(b'begin '))
    name = os.path.basename(lines[start].split(b' ', 2)[2].decode().strip())
    data = bytearray()
    for line in lines[start + 1:]:
        if line.strip() == b'end':
            break
        if line in (b'`', b' ', b''):
            continue
        try:
            data += binascii.a2b_uu(line)
        except binascii.Error:
            n = (((line[0] - 32) & 63) * 4 + 5) // 3
            data += binascii.a2b_uu(line[:n + 1])
    return name, bytes(data)


def parse_lt(text):
    head, blocks, cur = {}, [], None
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


def password_for(fixture, name):
    table = PASSWORDS.get(fixture)
    if table:
        return table.get(name, table['*'])
    return DEFAULT_PASSWORD


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--test-dir', required=True)
    ap.add_argument('--rar', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    rar = os.path.abspath(a.rar)
    out = os.path.abspath(a.out)
    os.makedirs(out, exist_ok=True)

    sources = sorted(glob.glob(os.path.join(a.test_dir, 'test_read_format_rar5*.uu')))
    sources.append(os.path.join(a.test_dir, 'test_read_format_rar.rar.uu'))
    manifest = {"libarchive": "v3.8.10", "fixtures": {}}
    for uu in sources:
        # Some begin lines name the payload "-" or "<name>.uu"; the .uu file name is authoritative.
        _, data = uudecode(uu)
        name = os.path.basename(uu)[:-len('.uu')]
        if name.endswith('.Z'):
            data = subprocess.run(['gzip', '-d', '-c'], input=data, check=True, capture_output=True).stdout
            name = name[:-2]
        with open(os.path.join(out, name), 'wb') as f:
            f.write(data)

    for name in sorted(os.listdir(out)):
        if name.endswith('.py') or name.endswith('.json') or name.endswith('.md'):
            continue
        res = subprocess.run([rar, 'lt', '-idc', f'-p{DEFAULT_PASSWORD}', name], cwd=out, capture_output=True)
        text = res.stdout.decode('utf-8', 'replace')
        head, blocks = parse_lt(text)
        entries = []
        for blk in blocks:
            entries.append({
                "name": blk['Name'], "ltType": blk.get('Type'),
                "size": int(blk['Size']) if 'Size' in blk else None,
                "packedSize": int(blk['Packed size']) if 'Packed size' in blk else None,
                "ratio": blk.get('Ratio'), "flags": blk.get('Flags'), "target": blk.get('Target'),
                "version": int(blk['File version']) if 'File version' in blk else None,
                "compression": blk.get('Compression'),
                "checksum": next(([k, v] for k, v in blk.items()
                                  if k in ('CRC32', 'CRC32 MAC', 'BLAKE2', 'BLAKE2 MAC', 'Pack-CRC32')), None),
                "password": password_for(name, blk['Name']) if 'encrypted' in blk.get('Flags', '') else None,
            })

        work = tempfile.mkdtemp()
        try:
            hashes = {}
            ok = True
            pws = sorted({e['password'] for e in entries if e['password']} | {DEFAULT_PASSWORD})
            for pw in pws:
                r = subprocess.run([rar, 'x', '-idq', '-o+', '-ola', '-ai', f'-p{pw}', os.path.join(out, name), work + '/'],
                                   capture_output=True)
                ok = ok and r.returncode == 0 if pw == DEFAULT_PASSWORD else ok
            for dp, _, fn in os.walk(work):
                for f in fn:
                    p = os.path.join(dp, f)
                    if not os.path.islink(p):
                        os.chmod(p, 0o644)
                        hashes[os.path.relpath(p, work)] = hashlib.sha256(open(p, 'rb').read()).hexdigest()
            for e in entries:
                if e['ltType'] == 'File' and e['name'] in hashes:
                    e['sha256'] = hashes[e['name']]
        finally:
            shutil.rmtree(work)

        manifest["fixtures"][name] = {
            "size": os.path.getsize(os.path.join(out, name)),
            "ltExitCode": res.returncode,
            "extractExitOk": ok,
            "details": head.get('Details'),
            "entries": entries,
            "rarLt": text,
        }

    with open(os.path.join(out, 'manifest.json'), 'w', encoding='utf-8') as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
        f.write('\n')


if __name__ == '__main__':
    main()
