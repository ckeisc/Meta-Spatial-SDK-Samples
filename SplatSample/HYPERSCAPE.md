# Hyperscape captures in SplatSample (branch `hyperscape-splat-vr`)

This branch teaches the Spatial SDK `SplatSample` to render **Meta Hyperscape
capture bundles** (`.spz` + `_cluster_centroids.json` + `_cluster_masks.bin` +
`_camera_poses` + …) in VR on Quest 3, applying what was learned
reverse-engineering Hyperscape's renderer in `ckeisc/vkraygs` (`spz-support`).

## Why a pre-bake step exists

Meta's splat renderer is closed-source (`SplatFeature` native library). The
public `Splat` component exposes only a URI — no SH controls, no culling, no
LOD knobs — and it evaluates the full view-dependent spherical harmonics
(the stock sample assets ship `sh_degree=3`).

Hyperscape's `.spz` files are standard Niantic SPZ v2 containers, but their
**SH rest coefficients use a non-standard basis**. Any renderer doing standard
3DGS SH evaluation renders black patches / wrong colors on them. Desktop
testing against the Hyperscape viewer proved **DC-only rendering is color-
correct** ("completely right"). Since the Quest renderer can't be told to skip
the rest terms, the fix is baked into the asset: the SH coefficient chunk is
neutralized so every basis evaluates the degree≥1 terms to zero.

Neutral value = **packed byte 128, not 0**: the SPZ codec maps
`unpacked = (packed − 128) / 128`, so 0 would decode to coefficient −1.0.
128 decodes to exactly 0.0 in every spec-compliant loader.

A second bake step removes **far outlier splats ("floaters")**. Hyperscape
hides these at runtime with per-cluster visibility culling
(`od_cluster_masks`, 64 uint64 bitmasks); the public Splat API has no
equivalent, so the bake keeps the conservative subset: splats inside
`bbox(cluster views) + 8 m margin`. On the garage scan this removed 4.1% of
splats (372,938 → 357,584).

## Bake a capture (PC, Python 3 + numpy)

```bash
# <bundle-dir> holds <id>.spz, <id>_cluster_centroids.json,
# <id>_cluster_masks.bin, <id>_camera_poses.bin (JSON despite .bin)
python3 SplatSample/tools/hyperscape_to_quest.py <capture_id> \
    --bundle-dir <bundle-dir> \
    --out-dir <somewhere>/quest_assets \
    --name "My Garage"
```

Output per capture:

```
quest_assets/<capture_id>/
  <capture_id>.spz   DC-baked SPZ v2 (header preserved, SH neutralized)
  capture.json      manifest: splat count, spawn pose, source notes
  thumb.jpg         optional thumbnail (from <id>_flyby.mp4 via ffmpeg)
quest_assets/index.json   {"captures": [...]}  — regenerated/merged each run
```

Useful flags: `--strip-sh-degree` (truncate SH + set degree 0 instead of
neutral-128; smaller but untested against Meta's loader), `--no-outlier-filter`,
`--outlier-margin M`, `--min-visibility K`, `--max-splats N`.

Then copy into the app:

```bash
cp -r quest_assets/* SplatSample/app/src/main/assets/captures/
```

Baked captures are **git-ignored by design** (personal scan data is never
committed) — they live only in your local checkout for building.

## What the app does differently

- `Capture.kt` (new): loads `assets/captures/index.json` + per-capture
  `capture.json` via AssetManager. Empty/missing → the sample falls back to
  its built-in Menlo Park / Los Angeles demos, unchanged.
- `SplatSampleActivity.kt`:
  - `splatList` is built from bundled captures when present.
  - `recenterScene()` (B button, and after each load) spawns the user at the
    capture camera's start pose from `camera_poses` (position + heading) and
    puts the control panel 1.5 m ahead of it. `setViewOrigin` takes the
    tracking-space origin, so the app passes y=0 (floor) and the headset's own
    eye height lands where the capture camera was. The splat keeps the sample's
    standard −90° X rotation — Hyperscape captures are Z-up, same as the
    sample's own assets, so no extra axis fixups.
  - **Yaw convention is assumed, not verified**: `yaw_deg` in capture.json
    means 0 = facing +Z, positive toward +X. If the initial view is mirrored,
    negate `yaw_deg` in `capture.json` (or the `forward` vector is stored too
    for a convention-free fix) — no re-bake needed.
- `SplatControlPanel.kt`: shows capture names (+ splat counts) and loads
  `thumb.jpg` from assets when the bake produced one.

## Known gaps (not fixable through the public API)

- **Opacity**: Hyperscape's splat opacity handling is not reverse-engineered
  (garage-door transparency issue). The bake preserves alpha bytes verbatim;
  expect minor differences vs the Hyperscape viewer on translucent surfaces.
- **View-dependent color**: the true Hyperscape SH basis is unknown, so
  captures render DC-only (no specular/view shift). Correct colors, flat
  lighting response.
- **Runtime cluster culling**: `cluster_masks.bin` can't be consumed by the
  closed renderer (single Splat entity per scene anyway). The bake's outlier
  filter is the static equivalent; per-view culling granularity is lost.
- **Scene mesh**: `MeshCollision` exposes only `NoCollision`, so the capture's
  scene mesh can't become a collider through the public API. Locomotion works
  through the splat itself (`SupportsLocomotion`, per the 0.9.0 changelog).

## Build & install (Windows + Quest 3)

```powershell
cd SplatSample
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Press **B** on the right controller to recenter/spawn; **A** snaps the panel
in front of you. First load takes a few seconds (357k splats decode).

## Sideload captures without rebuilding (Quest 3)

Two ways to get baked captures onto the device:

**Option 0 — in-headset (no adb):** tap **"Open capture folder…"** on the
control panel. The system folder picker opens; choose a capture folder. Two
kinds are accepted:
- **Baked** (`capture.json` + `<id>.spz`, as the PC script writes): used as-is.
- **Raw Hyperscape bundle** (`<id>.spz` + `<id>_camera_poses.bin` + optional
  cluster files / flyby mp4, as `splat_fetch.py` downloads): the app bakes it
  on device — DC-bake, outlier filter, spawn pose, manifest — so no PC step
  is needed. Thumbnail comes from the flyby mp4 via MediaMetadataRetriever.

The app copies the folder into its own `HyperscapeCaptures` dir, rescans,
and loads it — a Toast confirms the import, or explains why it failed.

**adb push** still works too — the app scans device storage for baked
captures at startup, so new scans can be tried without another
`assembleDebug` cycle. Bake on the PC as usual, then push the capture folder
to either location:

```powershell
# Option A — Documents (shared folder; the app asks for read access on first launch)
adb push <baked-capture-dir> /sdcard/Documents/HyperscapeCaptures/

# Option B — app-specific dir (no permission needed)
adb push <baked-capture-dir> /sdcard/Android/data/com.meta.spatial.samples.splatsample/files/HyperscapeCaptures/
```

`<baked-capture-dir>` is the folder the bake script wrote
(`capture.json`, `<id>.spz`, optional `thumb.jpg`). Device captures appear at
the top of the panel picker and use `file://` URIs, so they load exactly like
bundled ones — spawn pose, thumbnails, and all. Granting the Documents
permission rescans and the picker updates live; the app-specific dir is picked
up on the next launch.

## File map

| Path | What |
|---|---|
| `SplatSample/tools/hyperscape_to_quest.py` | PC bake script (SPZ v2 parser, DC-bake, outlier filter, spawn, manifest) |
| `SplatSample/app/src/main/assets/captures/` | Baked captures bundled in the APK (git-ignored; personal scan data) |
| `.../splatsample/Capture.kt` | Capture loading: APK assets + device storage (`Documents/HyperscapeCaptures`, app files dir) |
| `.../splatsample/SplatSampleActivity.kt` | Capture list, spawn/recenter, Documents permission, folder-picker import |
| `.../splatsample/SplatControlPanel.kt` | Scrollable capture picker + thumbnails + "Open capture folder…" button |
| `.../splatsample/HyperscapeBake.kt` | On-device port of the bake script: SPZ v2 parse, DC-bake, outlier/visibility filters, decimation, spawn from camera poses, thumbnail via MediaMetadataRetriever, `capture.json` manifest |
