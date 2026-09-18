#!/usr/bin/env python3
"""
hyperscape_to_quest.py — Bake a Hyperscape capture bundle into Quest-3-ready assets
for the Meta Spatial SDK SplatSample.

Why this exists
--------------
Hyperscape .spz files are standard Niantic SPZ v2 containers, but their
spherical-harmonics (SH) coefficients use a NON-STANDARD basis convention.
Any renderer that evaluates the full view-dependent SH (Meta's closed
Spatial-SDK splat renderer included — it ships sh_degree=3 sample assets)
renders black patches / wrong colors on these files.

Fix (verified on desktop vs the Hyperscape viewer): drop the view-dependent
terms and keep only the DC (degree-0) color. DC-only rendering was confirmed
visually correct ("completely right") against the Hyperscape viewer.

Since Meta's renderer is closed-source with no SH controls, the fix is baked
into the asset: the SH coefficient chunk is neutralized to packed 128 (= coefficient 0.0;
note packed 0 would decode to -1.0) so every renderer — regardless of which
SH basis it assumes — computes color = DC.

Usage
-----
  python3 hyperscape_to_quest.py <capture_id> --bundle-dir ~/Hyspercaptures \
      --out-dir ./quest_assets

Bundle layout (as downloaded by splat_fetch.py):
  <capture_id>.spz
  <capture_id>_cluster_centroids.json
  <capture_id>_cluster_masks.bin
  <capture_id>_camera_poses.bin        (JSON text despite .bin)
  <capture_id>_scene_mesh.<ext>       (optional)
  <capture_id>_flyby.mp4              (optional, for thumbnail)
  <capture_id>_spawn_points_2d.json   (optional)

Output (drop the whole <capture_id>/ folder into
SplatSample/app/src/main/assets/captures/):
  <capture_id>/
    <capture_id>.spz          DC-baked, gzip SPZ v2, ready for Splat(Uri)
    capture.json              manifest: spawn, splat count, source info
    thumb.jpg                 thumbnail (from flyby mp4, if ffmpeg present)
"""
import argparse
import gzip
import json
import os
import struct
import subprocess
import sys

NGSP_MAGIC = 0x5053474E

# Legacy (v1-v3, gzip) SPZ chunk layout per nianticlabs/spz load-spz.cc
# deserializePackedGaussians(), for version==2 (what Hyperscape emits):
#   positions: N*9 (24-bit fixed point), alphas: N*1, colors: N*3,
#   scales: N*3, rotations: N*3 (v2; v3+ uses 4 = smallest-three),
#   sh: N * dimForDegree(shDegree) * 3
FIXED_BYTES_PER_POINT_V2 = 9 + 1 + 3 + 3 + 3  # = 19


def dim_for_degree(d):
    return d * (d + 2)  # 15 per channel at degree 3


def read_spz(path):
    with open(path, 'rb') as f:
        raw = f.read()
    if raw[:2] == b'\x1f\x8b':
        data = gzip.decompress(raw)
        compressed = True
    else:
        data = raw
        compressed = False
    magic, version, n, sh_degree, frac_bits, flags, _ = struct.unpack('<IIIBBBB', data[:16])
    if magic != NGSP_MAGIC:
        raise ValueError(f'{path}: bad magic {magic:#x}')
    if version != 2:
        raise ValueError(f'{path}: expected SPZ v2 (Hyperscape), got v{version}')
    return data, dict(version=version, n=n, sh_degree=sh_degree,
                      frac_bits=frac_bits, flags=flags, compressed=compressed)


def dc_bake(data, hdr, zero_sh=True):
    """Return new SPZ bytes with view-dependent SH neutralized.

    zero_sh=True  (default, safest): neutralize the SH coefficient chunk,
        keeping the header untouched. File stays structurally identical to
        Meta's own sample assets (v2, sh_degree=3).

        CRITICAL: "neutral" is packed byte 128, NOT 0. The SPZ codec maps
        unpacked = (packed - 128) / 128 (niantic load-spz.cc unquantizeSH),
        so packed 0 would decode to coefficient -1.0. Packed 128 decodes to
        exactly 0.0 in every spec-compliant loader, making the degree>=1
        terms contribute 0 under ANY SH basis convention -> color == DC.
    zero_sh=False: truncate the SH chunk and set sh_degree=0 (smaller file,
        spec-compliant, but untested against Meta's closed loader).
    """
    n, shd = hdr['n'], hdr['sh_degree']
    sh_bytes = n * dim_for_degree(shd) * 3
    fixed_end = 16 + n * FIXED_BYTES_PER_POINT_V2
    sh_end = fixed_end + sh_bytes
    if len(data) < sh_end:
        raise ValueError('SPZ truncated: expected %d bytes, got %d' % (sh_end, len(data)))
    trailing = data[sh_end:]  # extensions, if any — preserved verbatim
    if zero_sh:
        out = bytearray(data[:fixed_end]) + bytearray([128]) * sh_bytes + trailing
    else:
        out = bytearray(data[:fixed_end]) + trailing
        out[12] = 0  # sh_degree = 0
    return gzip.compress(bytes(out), compresslevel=9)


def load_cluster_views(centroids_path):
    """Cluster 'views' = per-cluster viewpoint positions in Hyperscape world meters (Z-up)."""
    j = json.load(open(centroids_path))
    return j['views']


def outlier_filter(data, hdr, views, margin=8.0):
    """Drop splats far outside the captured space (the 'stray floaters').

    Hyperscape hides these at runtime via per-cluster visibility culling
    (od_cluster_masks). Meta's renderer can't do that, so the bake removes
    them: keep splats inside bbox(cluster views) expanded by `margin` meters.
    Falls back to a percentile box when no centroids file is available.
    Returns (filtered_data, kept_count).
    """
    import numpy as np
    n = hdr['n']
    pos = np.frombuffer(data[16:16 + n * 9], dtype=np.uint8).reshape(n, 9)
    xyz = np.zeros((n, 3), np.float64)
    for a in range(3):
        v = (pos[:, 3 * a].astype(np.int64) | (pos[:, 3 * a + 1].astype(np.int64) << 8) |
             (pos[:, 3 * a + 2].astype(np.int64) << 16))
        xyz[:, a] = np.where(v >= 1 << 23, v - (1 << 24), v) / float(1 << hdr['frac_bits'])
    if views:
        v = np.array(views, dtype=np.float64)
        lo, hi = v.min(0) - margin, v.max(0) + margin
    else:
        lo = np.percentile(xyz, 0.5, axis=0)
        hi = np.percentile(xyz, 99.5, axis=0)
    keep = np.flatnonzero(((xyz >= lo) & (xyz <= hi)).all(axis=1))
    print(f'outlier filter: bbox lo={lo.round(2)} hi={hi.round(2)} -> kept {len(keep)}/{n}')
    if len(keep) == n:
        return data, n
    return _gather(data, hdr, keep)


def _gather(data, hdr, keep):
    """Rebuild the legacy SPZ stream keeping only `keep` indices."""
    import numpy as np
    n, shd = hdr['n'], hdr['sh_degree']
    sh_dim3 = dim_for_degree(shd) * 3
    sizes = [9, 1, 3, 3, 3, sh_dim3]
    offs = [16]
    for s in sizes:
        offs.append(offs[-1] + n * s)
    parts = [bytearray(struct.pack('<IIIBBBB', NGSP_MAGIC, 2, len(keep),
                                   shd, hdr['frac_bits'], hdr['flags'], 0))]
    for ci, s in enumerate(sizes):
        arr = np.frombuffer(data[offs[ci]:offs[ci + 1]], dtype=np.uint8).reshape(n, s)
        parts.append(arr[keep].tobytes())
    return b''.join(parts), len(keep)


def visibility_filter(data, hdr, masks_path, min_visible):
    """Drop splats visible from fewer than min_visible of the 64 clusters.

    cluster_masks.bin is N uint64s; bit i = splat visible from cluster i.
    (Kept as an option; on the garage scan every splat is visible from >=17
    clusters, so this is a no-op there — outlier_filter does the real work.)
    """
    import numpy as np
    n = hdr['n']
    masks = np.frombuffer(open(masks_path, 'rb').read(), dtype=np.uint64, count=n)
    if len(masks) != n:
        raise ValueError('mask count %d != splat count %d' % (len(masks), n))
    pop = np.unpackbits(masks.view(np.uint8)).reshape(-1, 64).sum(axis=1)
    keep = np.flatnonzero(pop >= min_visible)
    print(f'visibility filter >= {min_visible}: kept {len(keep)}/{n}')
    if len(keep) == n:
        return data, n
    return _gather(data, hdr, keep)


def decimate_uniform(data, hdr, target):
    """Uniform random decimation to target splat count (keeps header valid)."""
    import numpy as np
    n = hdr['n']
    if n <= target:
        return data, n
    rng = np.random.default_rng(0)
    keep = np.sort(rng.choice(n, target, replace=False))
    print(f'decimated {n} -> {target} splats (uniform)')
    return _gather(data, hdr, keep)


def spawn_from_camera_poses(poses_path):
    """Derive a spawn pose from the capture's camera poses.

    input_poses are camera-to-world 4x4s in the Hyperscape world frame
    (meters, Z-up — verified: pose translations coincide with the cluster
    'views' at ~0.26m mean distance, z ~= height).

    The app renders with the sample's -90deg X rotation, which maps
    (x, y, z)_world -> (x, z, -y)_sdk (Z-up -> Y-up). Spawn is emitted in
    that SDK frame so the app can pass it straight to setViewOrigin().

    Returns dict(position=[x,y,z], yaw_deg, forward=[x,y,z]).
    yaw_deg convention: 0 = facing +Z in SDK space, positive toward +X
    (verify in-headset; flip sign in capture.json if mirrored).
    """
    import numpy as np
    j = json.loads(open(poses_path, 'rb').read())
    poses = j['input_poses']
    if not poses:
        raise ValueError('no input_poses in camera poses file')
    p = np.array(poses[0], dtype=np.float64)  # 4x4 camera-to-world
    center_w = p[:3, 3]
    fwd_w = p[:3, :3] @ np.array([0.0, 0.0, 1.0])  # COLMAP camera looks down +z
    # Hyperscape world (Z-up) -> SDK (Y-up) via R_x(-90): (x,y,z)->(x,z,-y)
    pos = [float(center_w[0]), float(center_w[2]), float(-center_w[1])]
    fwd = np.array([fwd_w[0], fwd_w[2], -fwd_w[1]])
    fwd[1] = 0.0
    nrm = float(np.linalg.norm(fwd))
    if nrm < 1e-6:
        fwd = np.array([0.0, 0.0, 1.0])
        nrm = 1.0
    fwd = fwd / nrm
    yaw = float(np.degrees(np.arctan2(fwd[0], fwd[2])))
    return {'position': pos, 'yaw_deg': yaw,
            'forward': [float(fwd[0]), float(fwd[1]), float(fwd[2])],
            'source': 'input_poses[0]', 'num_poses': len(poses),
            'frame': 'sdk-y-up (hyperscape z-up rotated -90deg about X)',
            'yaw_convention': '0deg faces +Z, positive toward +X (assumed; '
                              'negate yaw_deg here if the initial view is mirrored)'}


def make_thumbnail(flyby_path, out_path):
    if not (flyby_path and os.path.exists(flyby_path)):
        return False
    try:
        subprocess.run(['ffmpeg', '-y', '-v', 'error', '-i', flyby_path,
                        '-vframes', '1', '-q:v', '4', out_path],
                       check=True, timeout=60)
        return os.path.exists(out_path)
    except (FileNotFoundError, subprocess.SubprocessError):
        return False


def main():
    ap = argparse.ArgumentParser(description='Bake Hyperscape capture -> Quest 3 assets')
    ap.add_argument('capture_id')
    ap.add_argument('--bundle-dir', default='.', help='dir with <id>.* bundle files')
    ap.add_argument('--out-dir', default='./quest_assets')
    ap.add_argument('--strip-sh-degree', action='store_true',
                    help='truncate SH chunk + set sh_degree=0 instead of neutralizing coeffs')
    ap.add_argument('--min-visibility', type=int, default=0,
                    help='drop splats visible from fewer than K of 64 clusters (0=keep all)')
    ap.add_argument('--max-splats', type=int, default=0,
                    help='uniform-decimate to N splats (0=no decimation)')
    ap.add_argument('--no-outlier-filter', action='store_true',
                    help='skip floater/outlier removal')
    ap.add_argument('--outlier-margin', type=float, default=8.0,
                    help='meters beyond cluster-view bbox to keep (default 8)')
    ap.add_argument('--name', default=None)
    args = ap.parse_args()

    cid, bdir = args.capture_id, args.bundle_dir
    spz_path = os.path.join(bdir, f'{cid}.spz')
    data, hdr = read_spz(spz_path)
    print(f'loaded {cid}.spz: v{hdr["version"]} n={hdr["n"]} '
          f'sh_degree={hdr["sh_degree"]} flags={hdr["flags"]:#x}')

    n = hdr['n']
    if args.min_visibility > 0:
        data, n = visibility_filter(
            data, hdr, os.path.join(bdir, f'{cid}_cluster_masks.bin'),
            args.min_visibility)
        hdr = dict(hdr, n=n)
    if not args.no_outlier_filter:
        cpath = os.path.join(bdir, f'{cid}_cluster_centroids.json')
        views = load_cluster_views(cpath) if os.path.exists(cpath) else None
        data, n = outlier_filter(data, hdr, views, margin=args.outlier_margin)
        hdr = dict(hdr, n=n)
    if args.max_splats > 0 and n > args.max_splats:
        data, n = decimate_uniform(data, hdr, args.max_splats)
        hdr = dict(hdr, n=n)

    baked = dc_bake(data, hdr, zero_sh=not args.strip_sh_degree)
    print(f'DC-bake done (zero_sh={not args.strip_sh_degree}), '
          f'{len(baked)} bytes gzipped')

    out = os.path.join(args.out_dir, cid)
    os.makedirs(out, exist_ok=True)
    spz_out = os.path.join(out, f'{cid}.spz')
    open(spz_out, 'wb').write(baked)

    poses_path = os.path.join(bdir, f'{cid}_camera_poses.bin')
    spawn = spawn_from_camera_poses(poses_path) if os.path.exists(poses_path) else None

    thumb = os.path.join(out, 'thumb.jpg')
    has_thumb = make_thumbnail(os.path.join(bdir, f'{cid}_flyby.mp4'), thumb)

    manifest = {
        'id': cid,
        'name': args.name or cid,
        'splat_file': f'{cid}.spz',
        'splat_count': n,
        'dc_baked': True,
        'sh_mode': 'stripped' if args.strip_sh_degree else 'neutral-128',
        'spawn': spawn,
        'has_thumbnail': has_thumb,
        'source': {'sh_degree': hdr['sh_degree'], 'note': 'Hyperscape SPZ: non-standard SH basis; baked to DC-only'},
    }
    json.dump(manifest, open(os.path.join(out, 'capture.json'), 'w'), indent=2)
    print(f'wrote {out}/capture.json, spawn={spawn}')

    # Maintain captures/index.json alongside the per-capture folders so the
    # app can enumerate captures from APK assets.
    index_path = os.path.join(args.out_dir, 'index.json')
    index = []
    if os.path.exists(index_path):
        try:
            loaded = json.load(open(index_path))
            # Written as {"captures": [...]}; accept a bare array for legacy.
            index = loaded.get('captures', []) if isinstance(loaded, dict) else loaded
            if not isinstance(index, list):
                index = []
        except ValueError:
            index = []
    index = [e for e in index if e.get('id') != cid]
    index.append({'id': cid, 'name': manifest['name'],
                  'dir': cid, 'splat_count': n,
                  'has_thumbnail': has_thumb})
    json.dump({'captures': index}, open(index_path, 'w'), indent=2)
    print(f'updated {index_path} ({len(index)} captures)')
    print('OK ->', out)


if __name__ == '__main__':
    sys.exit(main())
