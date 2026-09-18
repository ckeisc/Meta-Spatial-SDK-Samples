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
    val noOutlierFilter: Boolean = false,
    val outlierMargin: Double = 8.0,
)

/** What bakeHyperscapeBundle produced. */
data class BakeResult(
    val id: String,
    val name: String,
    val splatCount: Int,
    val spawn: CaptureSpawn?,
)

/**
 * Bakes a raw Hyperscape capture bundle into Quest-3-ready assets, on device.
 *
 * This is a port of SplatSample/tools/hyperscape_to_quest.py. The bundle dir
 * is expected to hold `<id>.spz` plus the optional `<id>_camera_poses.bin`
 * (JSON text), `<id>_cluster_centroids.json`, `<id>_cluster_masks.bin` and
 * `<id>_flyby.mp4` files, as downloaded by splat_fetch.py.
 *
 * Steps: parse SPZ v2 -> optional visibility filter -> outlier (floater)
 * filter -> optional decimation -> DC-bake (neutralize the non-standard SH
 * coefficients to packed 128 = 0.0 so Meta's closed renderer shows correct
 * DC-only colors) -> gzip. Writes the baked `<id>.spz` over the raw file,
 * plus `capture.json` and `thumb.jpg` (via MediaMetadataRetriever, no
 * ffmpeg on device).
 *
 * @throws IllegalArgumentException when the bundle is unusable (no .spz,
 *   bad magic, wrong SPZ version, truncated data).
 */
fun bakeHyperscapeBundle(
    bundleDir: File,
    name: String? = null,
    opts: BakeOptions = BakeOptions(),
): BakeResult {
  val spzFiles =
      bundleDir
          .listFiles { f -> f.isFile && f.name.endsWith(".spz", ignoreCase = true) }
          ?.sortedBy { it.name }
          .orEmpty()
  require(spzFiles.isNotEmpty()) { "no .spz in ${bundleDir.name}" }
  val spzFile = spzFiles[0]
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
  // Outlier filter: drop splats far outside the captured space (the stray
  // floaters Hyperscape hides via cluster culling, which the public Splat
  // API cannot express).
  if (!opts.noOutlierFilter) {
    val centroidsFile = File(bundleDir, "${id}_cluster_centroids.json")
    val views = if (centroidsFile.isFile) loadClusterViews(centroidsFile) else null
    outlierFilter(data, n, hdr.fracBits, views, opts.outlierMargin)?.let { keep ->
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

  // DC-bake + gzip, overwriting the raw .spz with the baked one (same name
  // the manifest points at).
  spzFile.writeBytes(dcBake(data, hdr, n))
  Log.i(TAG, "DC-bake done for $id ($n splats)")

  val posesFile = File(bundleDir, "${id}_camera_poses.bin")
  val spawn = if (posesFile.isFile) spawnFromCameraPoses(posesFile) else null

  val thumbFile = File(bundleDir, "thumb.jpg")
  val hasThumb = makeThumbnail(File(bundleDir, "${id}_flyby.mp4"), thumbFile)

  val manifest =
      JSONObject()
          .put("id", id)
          .put("name", name ?: id)
          .put("splat_file", "$id.spz")
          .put("splat_count", n)
          .put("dc_baked", true)
          .put("sh_mode", "neutral-128")
          .put("has_thumbnail", hasThumb)
          .put(
              "source",
              JSONObject()
                  .put("sh_degree", hdr.shDegree)
                  .put(
                      "note",
                      "Hyperscape SPZ: non-standard SH basis; baked to DC-only on device"))
  if (spawn != null) {
    manifest.put(
        "spawn",
        JSONObject()
            .put("position", JSONArray(listOf(spawn.x, spawn.y, spawn.z)))
            .put("yaw_deg", spawn.yawDeg)
            .put(
                "forward",
                spawn.forward?.let { f -> JSONArray(listOf(f.x, f.y, f.z)) }
                    ?: JSONObject.NULL))
  }
  File(bundleDir, "capture.json").writeText(manifest.toString(2))
  Log.i(TAG, "wrote ${bundleDir.name}/capture.json, spawn=$spawn")

  return BakeResult(id, name ?: id, n, spawn)
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
 * Drops splats far outside the captured space. Keeps splats inside
 * bbox(cluster views) expanded by [margin] meters, or a percentile box when
 * no centroids file is available. Returns null when everything is kept.
 */
private fun outlierFilter(
    data: ByteArray,
    n: Int,
    fracBits: Int,
    views: List<DoubleArray>?,
    margin: Double,
): IntArray? {
  val inv = 1.0 / (1L shl fracBits).toDouble()
  val xs = DoubleArray(n)
  val ys = DoubleArray(n)
  val zs = DoubleArray(n)
  for (i in 0 until n) {
    val b = 16 + i * 9
    val c = IntArray(3)
    for (a in 0..2) {
      val v =
          (data[b + 3 * a].toInt() and 0xFF) or
              ((data[b + 3 * a + 1].toInt() and 0xFF) shl 8) or
              ((data[b + 3 * a + 2].toInt() and 0xFF) shl 16)
      // 24-bit fixed point, sign-extended.
      c[a] = if (v >= (1 shl 23)) v - (1 shl 24) else v
    }
    xs[i] = c[0] * inv
    ys[i] = c[1] * inv
    zs[i] = c[2] * inv
  }
  val lo: DoubleArray
  val hi: DoubleArray
  if (!views.isNullOrEmpty()) {
    lo = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
    hi = doubleArrayOf(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
    for (v in views) {
      for (a in 0..2) {
        lo[a] = minOf(lo[a], v[a])
        hi[a] = maxOf(hi[a], v[a])
      }
    }
    for (a in 0..2) {
      lo[a] -= margin
      hi[a] += margin
    }
  } else {
    // numpy.percentile default (method='linear'): rank = p/100*(n-1),
    // lerp between the bracketing sorted values.
    fun pct(arr: DoubleArray, p: Double): Double {
      val s = arr.sortedArray()
      if (s.size == 1) return s[0]
      val rank = p / 100.0 * (s.size - 1)
      val j = rank.toInt().coerceIn(0, s.size - 2)
      val g = rank - j
      return s[j] + g * (s[j + 1] - s[j])
    }
    lo = doubleArrayOf(pct(xs, 0.5), pct(ys, 0.5), pct(zs, 0.5))
    hi = doubleArrayOf(pct(xs, 99.5), pct(ys, 99.5), pct(zs, 99.5))
  }
  val keep = ArrayList<Int>(n)
  for (i in 0 until n) {
    if (xs[i] in lo[0]..hi[0] && ys[i] in lo[1]..hi[1] && zs[i] in lo[2]..hi[2]) {
      keep.add(i)
    }
  }
  Log.i(TAG, "outlier filter: kept ${keep.size}/$n")
  return if (keep.size == n) null else keep.toIntArray()
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

/** Cluster 'views' = per-cluster viewpoint positions in Hyperscape world meters (Z-up). */
private fun loadClusterViews(f: File): List<DoubleArray>? {
  return try {
    val arr = JSONObject(f.readText()).optJSONArray("views") ?: return null
    List(arr.length()) { i ->
      val v = arr.getJSONArray(i)
      doubleArrayOf(v.getDouble(0), v.getDouble(1), v.getDouble(2))
    }
  } catch (e: Exception) {
    Log.w(TAG, "cannot parse cluster views", e)
    null
  }
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
