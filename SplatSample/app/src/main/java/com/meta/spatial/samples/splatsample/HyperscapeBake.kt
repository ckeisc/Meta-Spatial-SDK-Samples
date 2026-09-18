/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.spatial.samples.splatsample

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.meta.spatial.core.Vector3
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.nameWithoutExtension
import kotlin.math.atan2
import kotlin.math.ln
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "HyperscapeBake"
private const val NGSP_MAGIC = 0x5053474E
/** Fixed bytes per splat in legacy SPZ v2: positions 9 + alpha 1 + color 3 + scale 3 + rot 3. */
private const val FIXED_BYTES_PER_POINT_V2 = 9 + 1 + 3 + 3 + 3

/** Parsed SPZ v2 header. `n` is tracked separately as filters shrink it. */
private data class SpzHeader(val shDegree: Int, val fracBits: Int, val flags: Int)

/** Options mirroring hyperscape_to_quest.py's flags. */
data class BakeOptions(
    val minVisibility: Int = 0,
    val maxSplats: Int = 0,
    /** Hole-filling: multiply each Gaussian's 3D sigma by this (1.0 = off). */
    val inflate: Float = 1.3f,
)

/** What bakeHyperscapeBundle produced, per .spz. */
data class BakeResult(
    val id: String,
    val name: String,
    val splatCount: Int,
    val spawn: CaptureSpawn?,
    val shDegree: Int,
    val inflate: Float,
)

/**
 * Bakes a raw Hyperscape capture bundle into Quest-3-ready assets, on device.
 *
 * This is a port of SplatSample/tools/hyperscape_to_quest.py. The bundle dir
 * is expected to hold one or more `<id>.spz` files plus each capture's
 * optional `<id>_camera_poses` (JSON text, no extension),
 * `<id>_cluster_centroids.json`, `<id>_cluster_masks.bin` and
 * `<id>_flyby0.mp4` sidecars, as downloaded by splat_fetch.py. (Older
 * downloads named the flyby `<id>_flyby.mp4` and the poses
 * `<id>_camera_poses.bin`; both are accepted.)
 *
 * Every .spz in the folder is baked (Hyperscape splat enumeration): each
 * becomes its own selectable capture tile. Steps per .spz: parse SPZ v2 ->
 * optional visibility filter -> optional decimation -> inflate 3D sigma
 * (hole-filling, vkraygs#2) -> DC-bake (neutralize
 * the non-standard SH coefficients to packed 128 = 0.0 so Meta's closed
 * renderer shows correct DC-only colors) -> gzip. Writes the baked `<id>.spz` over the raw file, `<id>_thumb.jpg`
 * (via MediaMetadataRetriever, no ffmpeg on device), and a single
 * `capture.json` holding a "captures" list with one entry per .spz.
 *
 * @throws IllegalArgumentException when the bundle is unusable (no .spz,
 *   bad magic, wrong SPZ version, truncated data).
 */
fun bakeHyperscapeBundle(
    bundleDir: File,
    name: String? = null,
    opts: BakeOptions = BakeOptions(),
): List<BakeResult> {
  val spzFiles =
      bundleDir
          .listFiles { f -> f.isFile && f.name.endsWith(".spz", ignoreCase = true) }
          ?.sortedBy { it.name }
          .orEmpty()
  require(spzFiles.isNotEmpty()) { "no .spz in ${bundleDir.name}" }
  // A lone .spz keeps the friendlier folder name; with several, each tile is
  // labeled by its own <file id>.
  val results =
      spzFiles.map { spzFile ->
        val id = spzFile.nameWithoutExtension
        bakeOneSpz(bundleDir, spzFile, if (spzFiles.size == 1) (name ?: id) else id, opts)
      }
  val entries = JSONArray()
  results.forEach { r -> entries.put(manifestEntry(r, File(bundleDir, "${r.id}_thumb.jpg").isFile)) }
  File(bundleDir, "capture.json").writeText(JSONObject().put("captures", entries).toString(2))
  Log.i(TAG, "wrote ${bundleDir.name}/capture.json with ${results.size} capture(s)")
  return results
}

/**
 * Bakes a single `<id>.spz` inside an already-imported folder and merges its
 * entry into `capture.json`'s "captures" list (preserving the other entries).
 * This is the lazy path for .spz files the folder scan enumerated but no
 * import baked yet — e.g. a raw Hyperscape bundle adb-pushed straight into
 * HyperscapeCaptures/. Must only be called on raw (unbaked) files: the
 * inflate step is not idempotent, so re-baking would scale sigma twice.
 */
fun bakeSingleSpz(
    bundleDir: File,
    spzFileName: String,
    opts: BakeOptions = BakeOptions(),
): BakeResult {
  val spzFile = File(bundleDir, spzFileName)
  require(spzFile.isFile) { "no such .spz in ${bundleDir.name}: $spzFileName" }
  val result = bakeOneSpz(bundleDir, spzFile, spzFile.nameWithoutExtension, opts)

  val entries = JSONArray()
  var replaced = false
  val manifestFile = File(bundleDir, "capture.json")
  try {
    if (manifestFile.isFile) {
      val root = JSONObject(manifestFile.readText())
      val existing = root.optJSONArray("captures")
      if (existing != null) {
        for (i in 0 until existing.length()) {
          val e = existing.getJSONObject(i)
          if (e.optString("id") == result.id) {
            entries.put(manifestEntry(result, File(bundleDir, "${result.id}_thumb.jpg").isFile))
            replaced = true
          } else {
            entries.put(e)
          }
        }
      } else if (root.optString("splat_file").isNotEmpty()) {
        // Legacy single-capture manifest: keep its entry, append the new one.
        entries.put(root)
      }
    }
  } catch (e: Exception) {
    Log.w(TAG, "could not read existing manifest, rewriting", e)
  }
  if (!replaced) {
    entries.put(manifestEntry(result, File(bundleDir, "${result.id}_thumb.jpg").isFile))
  }
  manifestFile.writeText(JSONObject().put("captures", entries).toString(2))
  Log.i(TAG, "baked on demand '${result.id}' (${result.splatCount} splats)")
  return result
}

/**
 * Locates a capture's flyby video. splat_fetch.py names it
 * `<id>_flyby0.mp4`; older downloads used `<id>_flyby.mp4`.
 */
private fun findFlyby(bundleDir: File, id: String): File? =
    listOf("${id}_flyby0.mp4", "${id}_flyby.mp4")
        .map { File(bundleDir, it) }
        .firstOrNull { it.isFile }

/** One "captures" entry for capture.json, from a BakeResult. */
private fun manifestEntry(r: BakeResult, hasThumb: Boolean): JSONObject {
  val manifest =
      JSONObject()
          .put("id", r.id)
          .put("name", r.name)
          .put("splat_file", "${r.id}.spz")
          .put("splat_count", r.splatCount)
          .put("dc_baked", true)
          .put("sh_mode", "neutral-128")
          .put("inflate", r.inflate)
          .put("has_thumbnail", hasThumb)
          .put(
              "source",
              JSONObject()
                  .put("sh_degree", r.shDegree)
                  .put(
                      "note",
                      "Hyperscape SPZ: non-standard SH basis; baked to DC-only on device"))
  if (r.spawn != null) {
    manifest.put(
        "spawn",
        JSONObject()
            .put("position", JSONArray(listOf(r.spawn.x, r.spawn.y, r.spawn.z)))
            .put("yaw_deg", r.spawn.yawDeg)
            .put(
                "forward",
                r.spawn.forward?.let { f -> JSONArray(listOf(f.x, f.y, f.z)) }
                    ?: JSONObject.NULL))
  }
  return manifest
}

/**
 * Bakes one raw `<id>.spz`: filters -> inflate -> DC-bake (overwrites the
 * file in place) -> `<id>_thumb.jpg` from the flyby video when present.
 */
private fun bakeOneSpz(
    bundleDir: File,
    spzFile: File,
    displayName: String,
    opts: BakeOptions,
): BakeResult {
  val id = spzFile.nameWithoutExtension

  var data = spzFile.readBytes()
  if (data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
    data = GZIPInputStream(data.inputStream()).use { it.readBytes() }
  }
  val hdr = parseSpzHeader(data)
  var n = parseSpzCount(data)
  Log.i(TAG, "loaded $id.spz: v2 n=$n sh_degree=${hdr.shDegree} flags=${hdr.flags}")

  // Optional cluster-visibility filter (off by default, like the script).
  val masksFile = File(bundleDir, "${id}_cluster_masks.bin")
  if (opts.minVisibility > 0 && masksFile.isFile) {
    visibilityFilter(n, masksFile.readBytes(), opts.minVisibility)?.let { keep ->
      data = gather(data, hdr, n, keep)
      n = keep.size
    }
  }
  // Optional uniform decimation.
  if (opts.maxSplats > 0 && n > opts.maxSplats) {
    val keep = decimate(n, opts.maxSplats)
    data = gather(data, hdr, n, keep)
    n = keep.size
  }

  // Hole-filling: inflate 3D sigma (mirrors vkraygs PR ckeisc/vkraygs#2,
  // final inflate-only state). Runs on the raw bytes before dcBake gzips.
  // NOT idempotent: re-baking re-applies the scaling, so baked files must
  // not be re-baked (see bakeSingleSpz).
  if (opts.inflate != 1.0f) {
    data = inflateScales(data, n, opts.inflate)
  }

  // DC-bake + gzip, overwriting the raw .spz with the baked one (same name
  // the manifest points at).
  spzFile.writeBytes(dcBake(data, hdr, n))
  Log.i(TAG, "DC-bake done for $id ($n splats)")

  // Camera poses are extensionless in current splat_fetch.py downloads;
  // older ones used a .bin suffix.
  val posesFile =
      listOf("${id}_camera_poses", "${id}_camera_poses.bin")
          .map { File(bundleDir, it) }
          .firstOrNull { it.isFile }
  val spawn = posesFile?.let { spawnFromCameraPoses(it) }

  val thumbFile = File(bundleDir, "${id}_thumb.jpg")
  val flyby = findFlyby(bundleDir, id)
  if (flyby != null) makeThumbnail(flyby, thumbFile)

  return BakeResult(id, displayName, n, spawn, hdr.shDegree, opts.inflate)
}

/**
 * Hole-filling: multiply each Gaussian's 3D sigma by [factor], applied as
 * += ln(factor) on the SPZ log-scales (sigma = exp(byte/16 - 10)).
 * Mirrors vkraygs PR ckeisc/vkraygs#2 (final inflate-only state), where the
 * same scaling is a GPU shader uniform defaulting to 1.3. factor=1.0 is a
 * no-op. NOT idempotent: re-baking an inflated file inflates it again.
 */
private fun inflateScales(data: ByteArray, n: Int, factor: Float): ByteArray {
  require(factor > 0) { "inflate factor must be positive, got $factor" }
  if (factor == 1.0f) return data
  val delta = Math.round(16 * ln(factor.toDouble())).toInt()
  if (delta == 0) return data
  val out = data.copyOf()
  val base = 16 + n * (9 + 1 + 3) // header + positions + alphas + colors
  for (i in 0 until 3 * n) {
    out[base + i] = ((out[base + i].toInt() and 0xFF) + delta).coerceIn(0, 255).toByte()
  }
  Log.i(TAG, "inflate x$factor: sigma scaled (+$delta on log-scale bytes)")
  return out
}

/** Parses and validates the 16-byte SPZ header (little-endian). */
private fun parseSpzHeader(data: ByteArray): SpzHeader {
  require(data.size >= 16) { "SPZ too small for header" }
  val bb = ByteBuffer.wrap(data, 0, 16).order(ByteOrder.LITTLE_ENDIAN)
  val magic = bb.int
  val version = bb.int
  bb.int // n; read separately so the message below can name it
  val shDegree = bb.get().toInt() and 0xFF
  val fracBits = bb.get().toInt() and 0xFF
  val flags = bb.get().toInt() and 0xFF
  require(magic == NGSP_MAGIC) { "bad SPZ magic ${magic.toString(16)}" }
  require(version == 2) { "expected SPZ v2 (Hyperscape), got v$version" }
  return SpzHeader(shDegree, fracBits, flags)
}

private fun parseSpzCount(data: ByteArray): Int =
    ByteBuffer.wrap(data, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int

/** Neutralizes the SH coefficient chunk to packed 128 (= 0.0) and gzips. */
private fun dcBake(data: ByteArray, hdr: SpzHeader, n: Int): ByteArray {
  // CRITICAL: "neutral" is packed 128, NOT 0. The SPZ codec maps
  // unpacked = (packed - 128) / 128, so packed 0 would decode to -1.0.
  // Packed 128 decodes to exactly 0.0, making the degree>=1 terms
  // contribute 0 under ANY SH basis convention -> color == DC.
  val shDim = hdr.shDegree * (hdr.shDegree + 2) // 15 per channel at degree 3
  val shBytes = n * shDim * 3
  val fixedEnd = 16 + n * FIXED_BYTES_PER_POINT_V2
  val shEnd = fixedEnd + shBytes
  require(data.size >= shEnd) { "SPZ truncated: expected $shEnd bytes, got ${data.size}" }
  val raw = ByteArrayOutputStream(data.size)
  raw.write(data, 0, fixedEnd)
  // Write the neutral chunk in slices to avoid one huge intermediate array.
  val slice = ByteArray(1 shl 20) { 0x80.toByte() }
  var remaining = shBytes
  while (remaining > 0) {
    val w = minOf(remaining, slice.size)
    raw.write(slice, 0, w)
    remaining -= w
  }
  raw.write(data, shEnd, data.size - shEnd) // extensions, if any — preserved
  val gz = ByteArrayOutputStream()
  object : GZIPOutputStream(gz) {
        init {
          def.setLevel(Deflater.BEST_COMPRESSION)
        }
      }
      .use { it.write(raw.toByteArray()) }
  return gz.toByteArray()
}

/**
 * Rebuilds the SPZ stream keeping only `keep` indices. Mirrors the script's
 * _gather(); used by the filters below.
 */
private fun gather(data: ByteArray, hdr: SpzHeader, n: Int, keep: IntArray): ByteArray {
  val shDim3 = hdr.shDegree * (hdr.shDegree + 2) * 3
  val sizes = intArrayOf(9, 1, 3, 3, 3, shDim3)
  val offs = IntArray(sizes.size + 1)
  offs[0] = 16
  for (i in sizes.indices) offs[i + 1] = offs[i] + n * sizes[i]
  val out = ByteArrayOutputStream(16 + keep.size * (FIXED_BYTES_PER_POINT_V2 + shDim3))
  val hbb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
  hbb.putInt(NGSP_MAGIC)
  hbb.putInt(2)
  hbb.putInt(keep.size)
  hbb.put(hdr.shDegree.toByte())
  hbb.put(hdr.fracBits.toByte())
  hbb.put(hdr.flags.toByte())
  hbb.put(0)
  out.write(hbb.array())
  for (ci in sizes.indices) {
    val s = sizes[ci]
    val base = offs[ci]
    for (k in keep) out.write(data, base + k * s, s)
  }
  return out.toByteArray()
}

/**
 * Drops splats visible from fewer than [minVisible] of the 64 clusters.
 * cluster_masks.bin holds N little-endian uint64s; bit i = splat visible
 * from cluster i. Returns null when everything is kept.
 */
private fun visibilityFilter(n: Int, masks: ByteArray, minVisible: Int): IntArray? {
  require(masks.size >= n * 8) { "mask count ${masks.size / 8} != splat count $n" }
  val bb = ByteBuffer.wrap(masks).order(ByteOrder.LITTLE_ENDIAN)
  val keep = ArrayList<Int>(n)
  for (i in 0 until n) {
    if (java.lang.Long.bitCount(bb.getLong(i * 8)) >= minVisible) keep.add(i)
  }
  Log.i(TAG, "visibility filter >= $minVisible: kept ${keep.size}/$n")
  return if (keep.size == n) null else keep.toIntArray()
}

/** Uniform random decimation to [target] splats (seeded, like the script). */
private fun decimate(n: Int, target: Int): IntArray {
  val rng = java.util.Random(0)
  val idx = (0 until n).toMutableList()
  idx.shuffle(rng)
  Log.i(TAG, "decimated $n -> $target splats (uniform)")
  return idx.take(target).sorted().toIntArray()
}

/**
 * Derives a spawn pose from the capture's camera poses. input_poses are
 * camera-to-world 4x4s in the Hyperscape world frame (meters, Z-up). The app
 * renders with the sample's -90deg X rotation, which maps (x,y,z)_world ->
 * (x,z,-y)_sdk (Z-up -> Y-up); the spawn is emitted in that SDK frame.
 *
 * View direction = camera -z axis (verified on the garage capture: -z
 * aligns with the direction of travel and points into the scene bulk).
 */
private fun spawnFromCameraPoses(posesFile: File): CaptureSpawn? {
  return try {
    val poses = JSONObject(posesFile.readText()).optJSONArray("input_poses")
        ?: return null
    if (poses.length() == 0) return null
    val p = poses.getJSONArray(0)
    fun at(r: Int, c: Int) = p.getJSONArray(r).getDouble(c)
    // Position mapped Z-up -> Y-up: (x, y, z) -> (x, z, -y).
    val px = at(0, 3)
    val py = at(2, 3)
    val pz = -at(1, 3)
    // Forward = camera -z column, mapped the same way, flattened to y=0.
    var fx = -at(0, 2)
    var fz = at(1, 2)
    val nrm = kotlin.math.sqrt(fx * fx + fz * fz)
    if (nrm < 1e-6) {
      fx = 0.0
      fz = 1.0
    } else {
      fx /= nrm
      fz /= nrm
    }
    val yaw = Math.toDegrees(atan2(fx, fz))
    CaptureSpawn(
        px.toFloat(), py.toFloat(), pz.toFloat(), yaw.toFloat(),
        Vector3(fx.toFloat(), 0f, fz.toFloat()))
  } catch (e: Exception) {
    Log.w(TAG, "cannot derive spawn pose", e)
    null
  }
}

/**
 * Grabs a thumbnail frame from the flyby mp4. The PC script uses ffmpeg;
 * on device MediaMetadataRetriever does the same job with no extra
 * dependency. Returns false (not fatal) when there is no usable video.
 */
private fun makeThumbnail(flyby: File, out: File): Boolean {
  if (!flyby.isFile) return false
  return try {
    val mmr = MediaMetadataRetriever()
    mmr.setDataSource(flyby.absolutePath)
    val frame = mmr.getFrameAtTime(1_000_000) // 1s in
    mmr.release()
    if (frame == null) return false
    FileOutputStream(out).use { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    out.isFile
  } catch (e: Exception) {
    Log.w(TAG, "thumbnail failed", e)
    false
  }
}
