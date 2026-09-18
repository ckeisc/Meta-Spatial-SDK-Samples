/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.spatial.samples.splatsample

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.SpatialSDKInternalTestingAPI
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.okhttp3.OkHttpAssetFetcher
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.NetworkedAssetLoader
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.splat.SpatialSDKExperimentalSplatAPI
import com.meta.spatial.splat.Splat
import com.meta.spatial.splat.SplatFeature
import com.meta.spatial.splat.SplatLoadEventArgs
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.GLXFInfo
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.SupportsLocomotion
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.VRFeature
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SharedPreferences key for the last splat the user picked in the panel. */
private const val KEY_SELECTED_SPLAT = "selected_splat_index"

@OptIn(SpatialSDKExperimentalSplatAPI::class)
class SplatSampleActivity : AppSystemActivity() {
  private var gltfxEntity: Entity? = null
  private val activityScope = CoroutineScope(Dispatchers.Main)

  private lateinit var environmentEntity: Entity
  private lateinit var skyboxEntity: Entity
  private lateinit var panelEntity: Entity
  private lateinit var floorEntity: Entity
  // Entity that holds the Splat component for rendering Gaussian Splats
  private lateinit var splatEntity: Entity

  // Built-in demo splats, shown when no Hyperscape captures are available.
  private val demoSplats: List<String> = listOf("apk://Menlo Park.spz", "apk://Los Angeles.spz")
  // All known Hyperscape captures: device storage first, then APK assets.
  // State so the panel picker recomposes when the list refreshes (e.g. after
  // the Documents permission is granted).
  private var capturesState = mutableStateOf<List<HyperscapeCapture>>(emptyList())

  /** Effective picker list: capture URIs, or the demo splats when none exist. */
  private fun effectiveSplatList(): List<String> =
      capturesState.value.map { it.splatUri }.ifEmpty { demoSplats }

  private lateinit var defaultSplatPath: Uri
  private var selectedIndex = mutableStateOf(0)
  /**
   * Controls whether the control panel UI is interactive.
   *
   * When loading a new Splat, we disable panel interaction to prevent users from triggering
   * multiple concurrent load operations, which could cause race conditions or confusing visual
   * states. The panel is re-enabled once the Splat finishes loading.
   *
   * This state is passed to the ControlPanel composable, which uses it to:
   * - Disable click handlers on the preview images
   * - Apply a visual "greyed out" effect to indicate the disabled state
   */
  private var isPanelInteractive = mutableStateOf(true)
  private val delayVisibilityMS = 2000L
  /**
   * True while a capture folder picked through the system folder picker is
   * being copied into the app's HyperscapeCaptures dir. The panel shows an
   * "Importing…" state and disables the picker meanwhile.
   */
  private var isImportingCapture = mutableStateOf(false)

  // Remembers which splat the user picked so the next launch opens on it
  // instead of always defaulting to the first entry.
  private val prefs by lazy { getSharedPreferences("splat_sample_prefs", MODE_PRIVATE) }

  // Asks for read access to Documents/ so captures can be sideloaded there.
  // On grant the capture list is rescanned and the picker updates.
  private val documentsPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Log.i("SplatSample", "Documents permission granted=$granted")
        if (granted) refreshCaptures()
      }

  // System folder picker for importing a baked capture without adb. The
  // picked tree is COPIED into the app's own HyperscapeCaptures dir (not
  // referenced in place), because Splat() takes file:// / apk:// URIs and
  // may not understand the picker's content:// URIs.
  private val folderPickerLauncher =
      registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) importCaptureFolder(treeUri)
      }

  // Rotation applied to the Splat to align it with the scene coordinate system
  // -90 degrees on X axis converts from original Splat coordinate space to Spatial SDK space.
  // Hyperscape captures are Z-up like the sample's own assets, so the same rotation applies.
  private val eulerRotation = Vector3(-90f, 0f, 0f)
  private val panelHeight = 1.5f
  private val panelOffset = 2.5f
  private val laxZ = 4f
  private val mpkZ = 2.5f

  private val headQuery =
      Query.where { has(AvatarAttachment.id) }
          .filter { isLocal() and by(AvatarAttachment.typeData).isEqualTo("head") }

  // Register all features your app needs. Features add capabilities to the Spatial SDK.
  override fun registerFeatures(): List<SpatialFeature> {
    return listOf(
        VRFeature(this), // Enable VR rendering
        // SplatFeature: REQUIRED for rendering Gaussian Splats
        // This feature handles loading, decoding, and rendering .spz Splat files
        // Must be registered before creating any entities with Splat components
        SplatFeature(this.spatialContext, systemManager),
        ComposeFeature(), // Enable Compose UI panels
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Captures come from device storage (sideloaded, no rebuild needed) and
    // APK assets; device captures win the top spots in the picker.
    refreshCaptures()
    val list = effectiveSplatList()
    // Restore the user's last pick (clamped in case the set changed);
    // fall back to the first splat on a fresh install.
    selectedIndex.value = prefs.getInt(KEY_SELECTED_SPLAT, 0).coerceIn(list.indices)
    defaultSplatPath = list[selectedIndex.value].toUri()
    NetworkedAssetLoader.init(
        File(applicationContext.getCacheDir().canonicalPath),
        OkHttpAssetFetcher(),
    )
    skyboxEntity =
        Entity.create(
            listOf(
                Mesh(Uri.parse("mesh://skybox"), hittable = MeshCollision.NoCollision),
                Material().apply {
                  baseTextureAndroidResourceId = R.drawable.skydome
                  unlit = true
                },
                Transform(Pose(Vector3(x = 0f, y = 0f, z = 0f))),
            ),
        )
    panelEntity =
        Entity.createPanelEntity(
            R.id.control_panel,
            Transform(Pose(Vector3(0f, panelHeight, 0f), Quaternion(0f, 180f, 0f))),
            Grabbable(type = GrabbableType.PIVOT_Y, minHeight = 0.75f, maxHeight = 2.5f),
        )
    loadGLXF { composition ->
      environmentEntity = composition.getNodeByName("Environment").entity
      val environmentMesh = environmentEntity.getComponent<Mesh>()
      environmentMesh.defaultShaderOverride = SceneMaterial.UNLIT_SHADER
      environmentEntity.setComponent(environmentMesh)
      floorEntity = composition.getNodeByName("Floor").entity
      initializeSplat(defaultSplatPath)
      setSplatVisibility(false)
    }
    // Ask for Documents access so captures can be sideloaded to
    // Documents/HyperscapeCaptures without rebuilding the APK. The
    // app-specific external files dir works without this permission.
    if (!hasDocumentsAccess() && Build.VERSION.SDK_INT <= 32) {
      documentsPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
  }

  /**
   * (Re)scans for Hyperscape captures: device storage first, then APK assets.
   * Called at startup and again if the Documents permission is granted later.
   */
  private fun refreshCaptures() {
    val assetCaptures = loadHyperscapeCaptures(this)
    val deviceCaptures = loadDeviceCaptures(deviceCaptureRoots())
    val merged = deviceCaptures + assetCaptures
    if (merged.map { it.splatUri } != capturesState.value.map { it.splatUri }) {
      capturesState.value = merged
      selectedIndex.value = selectedIndex.value.coerceIn(effectiveSplatList().indices)
      Log.i(
          "SplatSample",
          "Captures: ${deviceCaptures.size} on device, ${assetCaptures.size} in assets")
    }
  }

  /**
   * Copies a picked capture folder (capture.json + .spz + optional thumb.jpg)
   * into the app-specific HyperscapeCaptures dir, then rescans and selects
   * the new capture. The copy runs off the main thread; the panel shows an
   * "Importing…" state meanwhile. Shows a Toast on success or failure.
   */
  private fun importCaptureFolder(treeUri: Uri) {
    isImportingCapture.value = true
    activityScope.launch(Dispatchers.IO) {
      var importedDir: String? = null
      try {
        contentResolver.takePersistableUriPermission(
            treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val tree =
            DocumentFile.fromTreeUri(this@SplatSampleActivity, treeUri)
                ?: throw IllegalArgumentException("cannot open picked folder")
        val files = tree.listFiles().filter { it.isFile }
        if (files.none { it.name == "capture.json" }) {
          throw IllegalArgumentException("picked folder has no capture.json")
        }
        if (files.none { it.name?.endsWith(".spz", ignoreCase = true) == true }) {
          throw IllegalArgumentException("picked folder has no .spz")
        }
        val filesRoot =
            getExternalFilesDir(null) ?: throw IllegalStateException("no external files dir")
        val dirName = (tree.name ?: "capture").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = File(filesRoot, "HyperscapeCaptures/$dirName").apply { mkdirs() }
        for (f in files) {
          val name = f.name ?: continue
          // Only the bake outputs; skip stray files like .DS_Store.
          if (name != "capture.json" &&
              !name.endsWith(".spz", ignoreCase = true) &&
              name != "thumb.jpg") {
            continue
          }
          contentResolver.openInputStream(f.uri)?.use { input ->
            File(dest, name).outputStream().use { input.copyTo(it) }
          } ?: throw IOException("cannot read $name")
        }
        importedDir = dirName
        Log.i("SplatSample", "Imported capture folder '$dirName' from picker")
      } catch (e: Exception) {
        Log.w("SplatSample", "Capture import failed", e)
      }
      withContext(Dispatchers.Main) {
        isImportingCapture.value = false
        val dirName = importedDir
        if (dirName != null) {
          refreshCaptures()
          val idx = capturesState.value.indexOfFirst { it.deviceDir?.name == dirName }
          if (idx >= 0) {
            loadSplat(effectiveSplatList()[idx])
            Toast.makeText(
                    this@SplatSampleActivity,
                    "Imported '${capturesState.value[idx].name}'",
                    Toast.LENGTH_SHORT)
                .show()
          }
        } else {
          Toast.makeText(
                  this@SplatSampleActivity,
                  "Import failed — pick a baked capture folder (capture.json + .spz)",
                  Toast.LENGTH_LONG)
              .show()
        }
      }
    }
  }

  /**
   * Roots scanned for sideloaded captures (each gets a "HyperscapeCaptures"
   * subfolder; see loadDeviceCaptures).
   * - App-specific external files dir: no permission needed, adb-pushable.
   * - Documents/: the shared folder the user asked for; needs
   *   READ_EXTERNAL_STORAGE on API <= 32, requested at startup.
   */
  private fun deviceCaptureRoots(): List<File> {
    val roots = mutableListOf<File>()
    getExternalFilesDir(null)?.let { roots.add(it) }
    if (hasDocumentsAccess()) {
      @Suppress("DEPRECATION")
      val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
      roots.add(docs)
    }
    return roots
  }

  /** True when the app may read Documents/HyperscapeCaptures. */
  private fun hasDocumentsAccess(): Boolean {
    if (Build.VERSION.SDK_INT > 32) return false // scoped storage: app dir only
    return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED
  }

  @OptIn(SpatialSDKInternalTestingAPI::class)
  override fun onSceneReady() {
    super.onSceneReady()
    registerTestingIntentReceivers()
    scene.setLightingEnvironment(
        ambientColor = Vector3(0f),
        sunColor = Vector3(7.0f, 7.0f, 7.0f),
        sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
        environmentIntensity = 0.3f,
    )
    scene.updateIBLEnvironment("environment.env")
    scene.setViewOrigin(0.0f, 0.0f, 2.5f, 90.0f)
    systemManager.registerSystem(ControllerListenerSystem())
  }

  /**
   * Creates an entity with a Splat component.
   *
   * Gaussian Splats are a 3D representation technique that uses millions of small 3D Gaussians to
   * represent real-world captured scenes with photorealistic quality.
   *
   * To create a Splat entity, you need:
   * 1. Splat component: Points to the .spz or .ply file containing the Gaussian Splat data
   * 2. Transform component: Positions and rotates the Splat in 3D space
   * 3. Scale component: Adjusts the size of the Splat
   *
   * Splat files (.ply or .spz) can be loaded from:
   * - Application assets: "apk://filename.spz"
   * - Network URLs: "https://example.com/splat.spz"
   * - Local files: "file:///path/to/splat.spz"
   */
  private fun initializeSplat(splatPath: Uri) {
    // Disable panel interaction while Splat is loading to prevent concurrent load requests
    // The panel will be re-enabled in onSplatLoaded() once the Splat finishes loading
    setPanelInteractive(false)
    splatEntity =
        Entity.create(
            listOf(
                Splat(splatPath),
                Transform(
                    Pose(
                        Vector3(0.0f, 0.0f, 0.0f),
                        Quaternion(eulerRotation.x, eulerRotation.y, eulerRotation.z),
                    ),
                ),
                Scale(Vector3(1f)),
                SupportsLocomotion(),
            ),
        )
    splatEntity?.registerEventListener<SplatLoadEventArgs>(SplatLoadEventArgs.EVENT_NAME) { _, _ ->
      Log.d("SplatManager", "Splat loaded EVENT!")
      onSplatLoaded()
    }
  }

  private fun onSplatLoaded() {
    // Smooth out the transition from loading to showing the splat
    // This delay ensures the Splat is fully ready before revealing it to the user
    activityScope.launch {
      delay(500)
      recenterScene()
      setSplatVisibility(true)
      // Re-enable panel interaction now that the Splat has finished loading
      // Users can now select a different Splat without causing race conditions
      setPanelInteractive(true)
    }
  }

  /**
   * Enables or disables interaction with the control panel.
   *
   * This is used to prevent users from selecting a new splat while the current one is still
   * loading. When disabled, the panel images are not clickable.
   *
   * @param isInteractive true to enable panel interaction, false to disable it
   */
  private fun setPanelInteractive(isInteractive: Boolean) {
    isPanelInteractive.value = isInteractive
  }

  /**
   * Loads a new Splat asset into the scene.
   *
   * This function demonstrates how to dynamically change which Splat is displayed. You can call
   * this function to switch between different Splat assets at runtime.
   *
   * @param newSplatPath Path to the .spz Splat file (e.g., "apk://MySplat.spz" or a URL)
   */
  fun loadSplat(newSplatPath: String) {
    // Remember the pick so the next launch restores it (single source of
    // truth for selectedIndex; the panel also sets it on tap).
    val newIndex = effectiveSplatList().indexOf(newSplatPath)
    if (newIndex >= 0) {
      selectedIndex.value = newIndex
      prefs.edit().putInt(KEY_SELECTED_SPLAT, newIndex).apply()
    }

    if (splatEntity.hasComponent<Splat>()) {
      // Entity exists with a Splat component
      var splatComponent = splatEntity.getComponent<Splat>()

      if (splatComponent.path.toString() == newSplatPath) {
        // Optimization: Already showing this Splat, no need to reload
        // This prevents unnecessary file reloading and memory operations
      } else {
        // Disable panel interaction during loading to prevent concurrent load requests
        // This provides a better user experience by preventing rapid-fire selections
        // The panel will be re-enabled in onSplatLoaded() once loading completes
        setPanelInteractive(false)
        // Replace the existing Splat component with a new one pointing to a different file
        // setComponent() automatically unloads the old Splat from memory and loads the new one
        splatEntity.setComponent(Splat(newSplatPath.toUri()))
        recenterScene()
        setSplatVisibility(false)
      }
    } else {
      // No Splat Component exists yet, create one
      splatEntity.setComponent(Splat(newSplatPath.toUri()))
    }
  }

  /**
   * Controls the visibility of the Splat in the scene.
   *
   * Splats respect the Visible component like other rendered entities. Setting Visible(false) hides
   * the Splat without unloading it from memory, allowing for fast show/hide toggling.
   *
   * @param isSplatVisible true to show the Splat, false to hide it
   */
  fun setSplatVisibility(isSplatVisible: Boolean) {

    // Update the Visible Component on the Entity with a Splat Component
    splatEntity.setComponent(Visible(isSplatVisible))
    // Show environment when the Splat is hidden, hide when the Splat is visible
    setEnvironmentVisiblity(!isSplatVisible)
  }

  fun setEnvironmentVisiblity(isVisible: Boolean) {
    environmentEntity.setComponent(Visible(isVisible))
    skyboxEntity.setComponent(Visible(isVisible))
  }

  fun recenterScene() {
    val captureSpawn = currentCapture()?.spawn
    if (captureSpawn != null) {
      // Hyperscape capture: drop the user at the capture camera's start pose,
      // facing the way it faced. setViewOrigin takes the *tracking-space*
      // origin (floor level, like the sample's y=0) — not the eye height —
      // so the headset's own eye height lands where the capture camera was.
      // The panel goes 1.5 m ahead of the spawn point along the capture
      // forward vector, turned to face the user.
      scene.setViewOrigin(captureSpawn.x, 0f, captureSpawn.z, captureSpawn.yawDeg)
      val fwd = captureSpawn.forward
      val px = if (fwd != null) captureSpawn.x + fwd.x * 1.5f else captureSpawn.x
      val pz = if (fwd != null) captureSpawn.z + fwd.z * 1.5f else captureSpawn.z
      panelEntity.setComponent(
          Transform(
              Pose(
                  Vector3(px, panelHeight, pz),
                  Quaternion(0f, captureSpawn.yawDeg + 180f, 0f),
              )),
      )
      return
    }
    var z = laxZ
    if (splatEntity.getComponent<Splat>().path.toString() == defaultSplatPath.toString()) {
      z = mpkZ
    }
    scene.setViewOrigin(0f, 0f, z, 0f)
    panelEntity.setComponent(
        Transform(Pose(Vector3(0f, panelHeight, z - panelOffset), Quaternion(0f, 180f, 0f))),
    )
  }

  /**
   * Returns the Hyperscape capture backing the currently loaded splat, or null
   * for the built-in demo splats.
   */
  private fun currentCapture(): HyperscapeCapture? {
    if (!::splatEntity.isInitialized || capturesState.value.isEmpty()) return null
    val path = splatEntity.getComponent<Splat>().path.toString()
    return capturesState.value.firstOrNull { it.splatUri == path }
  }

  /**
   * Positions the panel 2 meters in front of the user's current head position.
   *
   * This function:
   * 1. Queries for the user's head entity using AvatarAttachment
   * 2. Gets the head's transform to determine forward direction
   * 3. Projects the forward vector onto the horizontal plane (y = 0)
   * 4. Positions the panel 2 meters forward from the head
   * 5. Rotates the panel to face the user
   */
  private fun positionPanelInFrontOfUser(distance: Float) {
    // Find the user's head entity
    val head = headQuery.eval().firstOrNull()

    if (head != null) {
      // Get the head's current pose (position and rotation)
      val headPose = head.getComponent<Transform>().transform
      // Get the forward direction vector from the head pose
      val forward = headPose.forward()
      // Flatten to horizontal plane by zeroing out the y component
      forward.y = 0f
      val forwardNormalized = forward.normalize()
      // Calculate new position 2 meters in front of the head
      var newPosition = headPose.t + (forwardNormalized * distance)
      newPosition.y = panelHeight // Set y position to panel height
      // Create rotation to make panel face the user
      val lookRotation = Quaternion.lookRotation(forwardNormalized)
      // Update the panel's transform
      panelEntity.setComponent(Transform(Pose(newPosition, lookRotation)))
    }
  }

  /**
   * System that listens for controller button presses and performs actions.
   *
   * This demonstrates how to:
   * - Query for controller entities in the scene
   * - Filter for local and active controllers
   * - Detect button press events (ButtonA and ButtonB)
   * - Access head tracking data to reposition UI panels
   *
   * Button mappings:
   * - A Button: Repositions the UI panel 2 meters in front of the user's current view direction
   * - B Button: Resets the view origin and positions the panel in front of the user
   *
   * This is a useful starting point for implementing controller-based interactions in your Spatial
   * SDK application.
   */
  inner class ControllerListenerSystem : SystemBase() {
    override fun execute() {
      // Query for all entities with Controller component and filter for local controllers
      val controllers = Query.where { has(Controller.id) }.eval().filter { it.isLocal() }

      for (controllerEntity in controllers) {
        val controller = controllerEntity.getComponent<Controller>()
        // Skip inactive controllers
        if (!controller.isActive) continue

        // Filter for right controller only
        val attachment = controllerEntity.tryGetComponent<AvatarAttachment>()
        if (attachment?.type != "right_controller") continue

        // Check if Button A was just pressed (button state changed and is now pressed)
        if (
            (controller.changedButtons and ButtonBits.ButtonA) != 0 &&
                (controller.buttonState and ButtonBits.ButtonA) != 0
        ) {
          positionPanelInFrontOfUser(panelOffset)
        }

        // Check if Button B was just pressed
        if (
            (controller.changedButtons and ButtonBits.ButtonB) != 0 &&
                (controller.buttonState and ButtonBits.ButtonB) != 0
        ) {
          recenterScene()
        }
      }
    }
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  override fun registerPanels(): List<PanelRegistration> {
    return listOf(
        createSimpleComposePanel(
            R.id.control_panel,
            ANIMATION_PANEL_WIDTH,
            ANIMATION_PANEL_HEIGHT,
        ) {
          // Pass the loadSplat function to the UI panel
          // This allows users to select different Splat assets from the UI
          // Pass isPanelInteractive state to control UI interaction during Splat loading
          // When false, the panel images become non-clickable and visually greyed out
          // This prevents users from selecting a new Splat while one is still loading
          // Reading capturesState here makes the picker recompose when the
          // capture list refreshes (e.g. after the Documents permission grant).
          ControlPanel(
              effectiveSplatList(),
              capturesState.value,
              selectedIndex,
              isPanelInteractive,
              isImportingCapture,
              ::loadSplat,
              onOpenFolder = { folderPickerLauncher.launch(null) })
        },
    )
  }

  private fun loadGLXF(onLoaded: ((GLXFInfo) -> Unit) = {}): Job {
    gltfxEntity = Entity.create()
    return activityScope.launch {
      glXFManager.inflateGLXF(
          Uri.parse("apk:///scenes/Composition.glxf"),
          rootEntity = gltfxEntity!!,
          keyName = "example_key_name",
          onLoaded = onLoaded,
      )
    }
  }

  private fun createSimpleComposePanel(
      panelId: Int,
      width: Float,
      height: Float,
      content: @Composable () -> Unit,
  ): ComposeViewPanelRegistration {
    return ComposeViewPanelRegistration(
        panelId,
        composeViewCreator = { _, ctx -> ComposeView(ctx).apply { setContent { content() } } },
        settingsCreator = {
          UIPanelSettings(
              shape = QuadShapeOptions(width = width, height = height),
              style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
              display = DpPerMeterDisplayOptions(),
          )
        },
    )
  }
}
