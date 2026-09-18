/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.spatial.samples.splatsample

import android.content.Context
import android.net.Uri
import android.util.Log
import com.meta.spatial.core.Vector3
import java.io.File
import org.json.JSONObject

/**
 * Spawn pose for a Hyperscape capture, in Spatial SDK scene coordinates (Y-up, meters).
 *
 * Produced by tools/hyperscape_to_quest.py from the capture's camera_poses:
 * position = first capture camera's position mapped Z-up -> Y-up through the
 * sample's standard -90deg X splat rotation (y ~= eye height); yawDeg = its heading.
 *
 * Note: scene.setViewOrigin() takes the tracking-space origin (floor level),
 * so the app passes y=0 and lets the headset's eye height land on the
 * camera's height — it does NOT pass position.y directly.
 *
 * yawDeg convention (assumed, verify in-headset): 0 = facing +Z, positive
 * toward +X. If the initial view looks backwards, negate yawDeg in
 * capture.json — no re-bake needed.
 */
data class CaptureSpawn(
    val x: Float,
    val y: Float,
    val z: Float,
    val yawDeg: Float,
    /** Unit forward vector (y=0) in scene coordinates, from capture.json. */
    val forward: Vector3?,
)

/**
 * One baked Hyperscape capture.
 *
 * Two sources are supported:
 * - APK assets: app/src/main/assets/captures/<dir>/ (deviceDir == null)
 * - Device storage: <root>/HyperscapeCaptures/<dir>/ where root is the
 *   app-specific external files dir (no permission needed) or
 *   Documents/ (needs READ_EXTERNAL_STORAGE on API <= 32).
 *
 * The .spz was pre-processed by tools/hyperscape_to_quest.py:
 * - SH view-dependent coefficients neutralized to packed 128 (= 0.0), because
 *   Hyperscape SPZs use a non-standard SH basis that renders as black patches
 *   under standard 3DGS SH evaluation (which Meta's closed splat renderer
 *   performs — its own sample assets are sh_degree=3). DC-only color was
 *   verified correct against the Hyperscape viewer.
 * - Far outlier splats ("floaters") removed; Hyperscape hides these at runtime
 *   with per-cluster visibility culling (od_cluster_masks), which has no
 *   equivalent in the public Splat API, so the bake applies the conservative
 *   subset (bbox of cluster views + margin) instead.
 */
data class HyperscapeCapture(
    val id: String,
    val name: String,
    val assetDir: String,
    val splatFile: String,
    val splatCount: Int,
    val spawn: CaptureSpawn?,
    val hasThumbnail: Boolean,
    /**
     * Capture directory on device storage, or null for APK-asset captures.
     * Expected contents: capture.json, `<id>.spz` files, optional
     * `<id>_thumb.jpg` / `<id>_flyby0.mp4` sidecars.
     */
    val deviceDir: File? = null,
    /**
     * True for a `<id>.spz` the folder scan enumerated (Hyperscape splat
     * enumeration) that no import has baked yet — e.g. a raw bundle
     * adb-pushed straight into HyperscapeCaptures/. Tapping its tile bakes
     * it on device first; it is never auto-loaded raw (the non-standard SH
     * basis would render with black patches).
     */
    val needsBake: Boolean = false,
) {
  /** URI for the Splat component: "apk:///captures/..." or "file:///sdcard/...". */
  val splatUri: String
    get() =
        if (deviceDir != null) Uri.fromFile(File(deviceDir, splatFile)).toString()
        else "apk:///captures/$assetDir/$splatFile"

  /** AssetManager path of the optional thumbnail, e.g. "captures/garage/thumb.jpg". */
  val thumbnailAssetPath: String
    get() = "captures/$assetDir/thumb.jpg"

  /** Thumbnail file for device-storage captures, if it exists. */
  val thumbnailFile: File?
    get() =
        deviceDir?.let { dir ->
          // Per-capture thumbs from the multi-spz bake, then the legacy
          // single-capture thumb.jpg.
          File(dir, "${id}_thumb.jpg").takeIf { it.isFile }
              ?: File(dir, "thumb.jpg").takeIf { it.isFile }
        }

  /**
   * `<id>_flyby0.mp4` (or legacy `<id>_flyby.mp4`) next to the .spz: the
   * hover-to-play video thumbnail for this tile, when the sidecar exists.
   */
  val videoThumbnailFile: File?
    get() =
        deviceDir?.let { dir ->
          File(dir, "${id}_flyby0.mp4").takeIf { it.isFile }
              ?: File(dir, "${id}_flyby.mp4").takeIf { it.isFile }
        }
}

private const val TAG = "HyperscapeCapture"

/**
 * Splits a capture.json into its capture entries: the multi-capture
 * `{"captures": [...]}` shape written by the on-device bake, or the legacy
 * single-capture shape (the whole object is the entry).
 */
fun manifestEntries(manifestJson: String): List<JSONObject> {
  val root = JSONObject(manifestJson)
  val arr = root.optJSONArray("captures")
  if (arr != null) return (0 until arr.length()).map { arr.getJSONObject(it) }
  return listOf(root)
}

/**
 * Parses one capture entry (a JSONObject with id/splat_file/...) as written
 * by tools/hyperscape_to_quest.py or the on-device bake. Shared by the
 * APK-asset and device-storage loaders; returns null when the entry is
 * unreadable.
 *
 * @param dirName directory name of the capture (asset subdir or device folder name)
 * @param entry one capture entry from the manifest
 * @param deviceDir device-storage capture dir, or null for APK-asset captures
 * @param assetThumbExists checks the thumbnail in APK assets (ignored for device captures)
 * @param needsBake marks an enumerated-but-unbaked <id>.spz (see HyperscapeCapture)
 */
fun parseCaptureEntry(
    dirName: String,
    entry: JSONObject,
    deviceDir: File? = null,
    assetThumbExists: () -> Boolean = { false },
    needsBake: Boolean = false,
): HyperscapeCapture? {
  return try {
    val spawnObj = entry.optJSONObject("spawn")
    val spawn =
        spawnObj?.let {
          val p = it.getJSONArray("position")
          val f = it.optJSONArray("forward")
          CaptureSpawn(
              x = p.getDouble(0).toFloat(),
              y = p.getDouble(1).toFloat(),
              z = p.getDouble(2).toFloat(),
              yawDeg = it.optDouble("yaw_deg", 0.0).toFloat(),
              forward =
                  f?.let { arr ->
                    Vector3(
                        arr.getDouble(0).toFloat(),
                        arr.getDouble(1).toFloat(),
                        arr.getDouble(2).toFloat())
                  },
          )
        }
    val hasThumb =
        if (deviceDir != null) {
          // Per-capture thumb from the multi-spz bake, or the legacy thumb.jpg.
          val id = entry.optString("id")
          File(deviceDir, "${id}_thumb.jpg").isFile || File(deviceDir, "thumb.jpg").isFile
        } else entry.optBoolean("has_thumbnail", false) && assetThumbExists()
    HyperscapeCapture(
        id = entry.getString("id"),
        name = entry.optString("name", dirName),
        assetDir = dirName,
        splatFile = entry.getString("splat_file"),
        splatCount = entry.optInt("splat_count", 0),
        spawn = spawn,
        hasThumbnail = hasThumb,
        deviceDir = deviceDir,
        needsBake = needsBake,
    )
  } catch (e: Exception) {
    Log.w(TAG, "Skipping capture entry in '$dirName': ${e.message}")
    null
  }
}

/**
 * Parses one capture manifest (capture.json as written by
 * tools/hyperscape_to_quest.py). Legacy single-capture shape; see
 * [parseCaptureEntry] and [manifestEntries] for the multi shape.
 */
fun parseCaptureManifest(
    dirName: String,
    manifestJson: String,
    deviceDir: File? = null,
    assetThumbExists: () -> Boolean = { false },
): HyperscapeCapture? {
  return try {
    parseCaptureEntry(dirName, JSONObject(manifestJson), deviceDir, assetThumbExists)
  } catch (e: Exception) {
    Log.w(TAG, "Skipping capture dir '$dirName': ${e.message}")
    null
  }
}

/**
 * Loads baked Hyperscape captures from APK assets.
 *
 * Expected layout (written by tools/hyperscape_to_quest.py):
 *   assets/captures/index.json                 -> {"captures": [{id, name, dir, ...}]}
 *   assets/captures/<dir>/capture.json         -> full manifest incl. spawn
 *   assets/captures/<dir>/<id>.spz             -> DC-baked splat
 *   assets/captures/<dir>/thumb.jpg            -> optional thumbnail
 *
 * Returns an empty list when no captures are bundled; the sample then falls
 * back to its built-in demo splats (or device-storage captures).
 */
fun loadHyperscapeCaptures(context: Context): List<HyperscapeCapture> {
  val am = context.assets
  val indexJson =
      try {
        am.open("captures/index.json").bufferedReader().use { it.readText() }
      } catch (e: Exception) {
        Log.i(TAG, "No captures/index.json in assets (${e.message}); using demo splats")
        return emptyList()
      }
  val captures = mutableListOf<HyperscapeCapture>()
  try {
    val index = JSONObject(indexJson).optJSONArray("captures") ?: return emptyList()
    for (i in 0 until index.length()) {
      val entry = index.getJSONObject(i)
      val dir = entry.getString("dir")
      val manifestJson =
          try {
            am.open("captures/$dir/capture.json").bufferedReader().use { it.readText() }
          } catch (e: Exception) {
            Log.w(TAG, "Skipping capture dir '$dir': ${e.message}")
            continue
          }
      // Confirm the thumbnail is actually there; don't trust the manifest blindly.
      val thumbExists = { am.open("captures/$dir/thumb.jpg").use { true } }
      parseCaptureManifest(dir, manifestJson, assetThumbExists = thumbExists)?.let {
        // Prefer the index entry's display name when the manifest has none.
        captures.add(
            if (it.name == dir) it.copy(name = entry.optString("name", dir)) else it)
      }
    }
  } catch (e: Exception) {
    Log.w(TAG, "Failed to parse captures/index.json: ${e.message}")
    return emptyList()
  }
  Log.i(TAG, "Loaded ${captures.size} Hyperscape capture(s) from assets")
  return captures
}

/**
 * Loads baked Hyperscape captures from device storage, so new captures can be
 * tried without rebuilding the APK. Scans each root for subdirectories shaped
 * like the bake output:
 *
 *   <root>/HyperscapeCaptures/<dir>/capture.json   ({"captures": [...]} or legacy single)
 *   <root>/HyperscapeCaptures/<dir>/<id>.spz        (one tile per .spz)
 *   <root>/HyperscapeCaptures/<dir>/<id>_thumb.jpg  (optional)
 *   <root>/HyperscapeCaptures/<dir>/<id>_flyby0.mp4 (optional video thumbnail)
 *
 * Hyperscape splat enumeration: every `<id>.spz` in the folder becomes its
 * own picker tile — not just the first one. A .spz with no manifest entry
 * (e.g. a raw bundle adb-pushed straight in, never imported) is listed with
 * needsBake=true; tapping its tile bakes it on device first.
 *
 * Typical roots: the app-specific external files dir (no permission needed,
 * adb-pushable) and Documents/HyperscapeCaptures (needs READ_EXTERNAL_STORAGE
 * on API <= 32). Unreadable roots are silently skipped.
 */
fun loadDeviceCaptures(roots: List<File>): List<HyperscapeCapture> {
  val captures = mutableListOf<HyperscapeCapture>()
  for (root in roots) {
    val base = File(root, "HyperscapeCaptures")
    val dirs =
        try {
          base.listFiles { f -> f.isDirectory }
        } catch (e: Exception) {
          Log.i(TAG, "Cannot list $base (${e.message})")
          null
        } ?: continue
    for (dir in dirs) {
      val covered = mutableSetOf<String>()
      val manifestFile = File(dir, "capture.json")
      if (manifestFile.isFile) {
        try {
          for (entry in manifestEntries(manifestFile.readText())) {
            parseCaptureEntry(dir.name, entry, deviceDir = dir)?.let {
              captures.add(it)
              covered.add(it.splatFile)
            }
          }
        } catch (e: Exception) {
          Log.w(TAG, "Skipping manifest in '${dir.name}': ${e.message}")
        }
      }
      // Hyperscape splat enumeration: any <id>.spz not described by the
      // manifest gets its own tile and bakes on demand when tapped.
      val orphans =
          dir.listFiles { f -> f.isFile && f.name.endsWith(".spz", ignoreCase = true) }
              ?.sortedBy { it.name }
              .orEmpty()
              .filter { it.name !in covered }
      for (spz in orphans) {
        val id = spz.nameWithoutExtension
        captures.add(
            HyperscapeCapture(
                id = id,
                name = id,
                assetDir = dir.name,
                splatFile = spz.name,
                splatCount = 0,
                spawn = null,
                hasThumbnail = false,
                deviceDir = dir,
                needsBake = true,
            ))
      }
      if (orphans.isNotEmpty()) Log.i(TAG, "Enumerated ${orphans.size} unbaked .spz in '${dir.name}'")
    }
  }
  if (captures.isNotEmpty()) Log.i(TAG, "Loaded ${captures.size} capture(s) from device storage")
  return captures
}
