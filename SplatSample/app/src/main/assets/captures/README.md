# `assets/captures/` — baked Hyperscape captures

This directory holds the output of `SplatSample/tools/hyperscape_to_quest.py`:

```
captures/
  index.json                  {"captures": [{id, name, dir, splat_count, has_thumbnail}]}
  <capture-id>/
    <capture-id>.spz          DC-baked SPZ v2 (Hyperscape SH neutralized)
    capture.json              manifest: splat count, spawn pose, source notes
    thumb.jpg                 optional thumbnail (from the capture flyby video)
```

It is **empty in git** (see `.gitignore`): personal scan data is never
committed. Bake locally and copy the output here, then build:

```powershell
cd SplatSample
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Full workflow: `SplatSample/HYPERSCAPE.md`. With no captures present the app
uses its built-in demo splats.
