/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.spatial.samples.splatsample

import android.content.Context
import android.util.Log
import com.meta.spatial.core.Vector3
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
 * One baked Hyperscape capture living under app/src/main/assets/captures/<id>/.
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
) {
  /** URI for the Splat component, e.g. "apk:///captures/garage/garage.spz". */
  val splatUri: String
    get() = "apk:///captures/$assetDir/$splatFile"

  /** AssetManager path of the optional thumbnail, e.g. "captures/garage/thumb.jpg". */
  val thumbnailAssetPath: String
    get() = "captures/$assetDir/thumb.jpg"
}

private const val TAG = "HyperscapeCapture"

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
 * back to its built-in demo splats.
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
      try {
        val manifest =
            JSONObject(am.open("captures/$dir/capture.json").bufferedReader().use { it.readText() })
        val spawnObj = manifest.optJSONObject("spawn")
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
        var hasThumb = manifest.optBoolean("has_thumbnail", false)
        if (hasThumb) {
          // Confirm the file is actually there; don't trust the manifest blindly.
          try {
            am.open("captures/$dir/thumb.jpg").close()
          } catch (e: Exception) {
            hasThumb = false
          }
        }
        captures.add(
            HyperscapeCapture(
                id = manifest.getString("id"),
                name = manifest.optString("name", entry.optString("name", dir)),
                assetDir = dir,
                splatFile = manifest.getString("splat_file"),
                splatCount = manifest.optInt("splat_count", 0),
                spawn = spawn,
                hasThumbnail = hasThumb,
            ))
      } catch (e: Exception) {
        Log.w(TAG, "Skipping capture dir '$dir': ${e.message}")
      }
    }
  } catch (e: Exception) {
    Log.w(TAG, "Failed to parse captures/index.json: ${e.message}")
    return emptyList()
  }
  Log.i(TAG, "Loaded ${captures.size} Hyperscape capture(s) from assets")
  return captures
}
