/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

/**
 * SplatControlPanel.kt
 *
 * OVERVIEW: This file defines the UI control panel for the Splat demo app using Jetpack Compose.
 * The panel provides interactive buttons to manipulate splats in real-time.
 *
 * KEY CONCEPTS:
 * - Jetpack Compose: Modern declarative UI framework for Android
 * - @Composable functions: UI building blocks that can be composed together
 * - SpatialTheme: Meta's design system for spatial computing UIs
 *
 * USAGE IN YOUR APP:
 * 1. Import this ControlPanel composable
 * 2. Pass your SplatManager instance to it
 * 3. Register it as a panel in your Activity (see SplatSampleActivity.kt)
 * 4. The panel will appear in your 3D scene as an interactive surface
 */
package com.meta.spatial.samples.splatsample

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.SpatialColorScheme
import com.meta.spatial.uiset.theme.SpatialTheme
import com.meta.spatial.uiset.theme.darkSpatialColorScheme
import com.meta.spatial.uiset.theme.lightSpatialColorScheme

/**
 * Physical dimensions of the panel in 3D space (in meters).
 *
 * PANEL SIZING: These constants define the panel's size when rendered in the 3D scene.
 * - Width: 2.048 meters (~6.7 feet)
 * - Height: 1.254 meters (~4.1 feet)
 *
 * ASPECT RATIO: Width/Height ≈ 1.63:1 (close to golden ratio for pleasant visuals)
 *
 * CUSTOMIZATION: Adjust these values to make the panel larger/smaller in your scene. Maintain
 * aspect ratio to prevent UI distortion.
 */
const val ANIMATION_PANEL_WIDTH = 2.048f
const val ANIMATION_PANEL_HEIGHT = 1.254f

private val panelHeadingText = "Splat Sample"
private val panelInstructionText = buildAnnotatedString {
  append("Press ")
  withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) { append("A") }
  append(" to show/hide the panel in front of you. \nPress ")
  withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) { append("B") }
  append(" to recenter the view. \nTap ")
  withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) { append("Open capture folder…") }
  append(" to import a capture — baked, or a raw Hyperscape bundle (baked on device).")
}

@Composable
fun ControlPanel(
    splatList: List<String>,
    captures: List<HyperscapeCapture>,
    selectedIndex: MutableState<Int>,
    isPanelInteractive: State<Boolean>,
    isImporting: State<Boolean>,
    loadSplatFunction: (String) -> Unit,
    onOpenFolder: () -> Unit,
) {
  // Apply SpatialTheme to ensure consistent design across the panel
  SpatialTheme(colorScheme = getPanelTheme()) {
    // Main container column with styling
    Column(
        modifier =
            Modifier.fillMaxSize() // Fill the panel's allocated space
                .clip(SpatialTheme.shapes.large) // Rounded corners
                .background(brush = LocalColorScheme.current.panel) // Themed background
                .padding(36.dp), // Inner padding for content
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // Content column with consistent spacing between elements
      Column(
          verticalArrangement = Arrangement.spacedBy(20.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        // Panel title
        Text(
            text = panelHeadingText,
            style = SpatialTheme.typography.headline1Strong,
            color = LocalColorScheme.current.primaryAlphaBackground,
        )
        // Instructions for panel controls
        Text(
            text = panelInstructionText,
            style = SpatialTheme.typography.body1,
            color = LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 8.dp),
        )

        // Capture picker: one tappable tile per splat. A LazyRow (not a fixed
        // Row) so the list scrolls horizontally as the capture library grows
        // instead of squeezing every tile. Tapping a tile swaps the splat at
        // runtime; the activity persists the choice for the next launch.
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
          itemsIndexed(splatList) { index, option ->
            // Each splat option is displayed as a column with image above button
            val isSelected = (index == selectedIndex.value)
            // When bundled Hyperscape captures back the list, index aligns with captures.
            val capture = captures.getOrNull(index)
            Column(
                modifier = Modifier.width(240.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              // Large clickable preview image
              val previewResource = getSplatPreviewResource(option)
              // Hyperscape flyby video sidecar: hover-to-play video tile.
              val videoFile = capture?.videoThumbnailFile
              // Visual feedback for loading state:
              // When panel is not interactive (Splat is loading), reduce opacity to 40%
              // This provides a clear visual cue that the panel is temporarily disabled
              val imageAlpha = if (isPanelInteractive.value) 1f else 0.4f
              val imageModifier =
                  Modifier.fillMaxWidth()
                      .height(200.dp)
                      // Apply alpha modifier for visual loading state feedback
                      // 1.0 = fully visible (interactive), 0.4 = greyed out (loading)
                      .alpha(imageAlpha)
                      .clip(RoundedCornerShape(12.dp))
                      .border(
                          width = if (isSelected) 4.dp else 2.dp,
                          color =
                              if (isSelected) Color(0xFF1877F2)
                              else LocalColorScheme.current.primaryAlphaBackground,
                          shape = RoundedCornerShape(12.dp),
                      )
                      // Disable click handling while Splat is loading
                      // This prevents race conditions from concurrent load requests
                      .clickable(enabled = isPanelInteractive.value) {
                        loadSplatFunction(option)
                        selectedIndex.value = index
                      }
              // Prefer the flyby video tile; then the capture thumbnail from
              // assets; then the built-in drawable previews used by the demo
              // splats.
              if (videoFile != null) {
                VideoThumbnailTile(
                    videoFile = videoFile,
                    contentDescription = "Flyby preview of $option",
                    modifier = imageModifier,
                )
              } else {
                // Static thumbnail (or placeholder) for non-video tiles.
                val thumbnailBitmap = captureThumbnail(capture)
                if (thumbnailBitmap != null) {
                  Image(
                      bitmap = thumbnailBitmap,
                      contentDescription = "Preview of $option",
                      modifier = imageModifier,
                      contentScale = ContentScale.Crop,
                  )
                } else if (previewResource != null) {
                  Image(
                      painter = painterResource(id = previewResource),
                      contentDescription = "Preview of $option",
                      modifier = imageModifier,
                      contentScale = ContentScale.Crop,
                  )
                } else {
                  // No preview image available (typical for baked captures without
                  // a flyby thumbnail): tappable placeholder tile so the capture
                  // stays selectable. imageModifier already carries the click handler.
                  Box(
                      modifier =
                          imageModifier.background(
                              LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.12f),
                              RoundedCornerShape(12.dp),
                          ),
                      contentAlignment = Alignment.Center,
                  ) {
                    Text(
                        text = "No preview",
                        style = SpatialTheme.typography.body1,
                        color = LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.7f),
                    )
                  }
                }
              }

              // Label below image
              Text(
                  text = getSplatDisplayName(option, capture),
                  style = SpatialTheme.typography.headline2Strong,
                  color =
                      if (isSelected) Color(0xFF1877F2)
                      else LocalColorScheme.current.primaryAlphaBackground,
              )
            }
          }
        }

        // Folder import: system picker for a baked capture folder (capture.json
        // + .spz). The activity copies it into the app's HyperscapeCaptures
        // dir and rescans; the picker then shows the new capture. Disabled
        // while a splat is loading or an import is in progress.
        Button(
            onClick = onOpenFolder,
            enabled = isPanelInteractive.value && !isImporting.value,
        ) {
          Text(
              text = if (isImporting.value) "Importing capture…" else "Open capture folder…",
              style = SpatialTheme.typography.body1,
          )
        }
      } // End content column
    } // End main container
  } // End SpatialTheme
} // End ControlPanel composable

/**
 * Hover-to-play video thumbnail for a Hyperscape `<id>_flyby0.mp4` sidecar.
 *
 * The tile shows the video's first frame while idle; it auto-plays (muted,
 * looping) only while the cursor hovers the tile, and pauses when the cursor
 * leaves. The ExoPlayer is released when the tile leaves the composition.
 */
@Composable
fun VideoThumbnailTile(
    videoFile: File,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val exoPlayer = remember(videoFile.absolutePath) {
    ExoPlayer.Builder(context).build().apply {
      setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
      repeatMode = Player.REPEAT_MODE_ALL
      volume = 0f
      playWhenReady = false
      prepare()
    }
  }
  DisposableEffect(videoFile.absolutePath) { onDispose { exoPlayer.release() } }

  val interactionSource = remember { MutableInteractionSource() }
  val isHovered by interactionSource.collectIsHoveredAsState()
  // Auto-play only while hovered; pause (freezing on the current frame)
  // as soon as the cursor leaves the tile.
  LaunchedEffect(isHovered) {
    if (isHovered) exoPlayer.play() else exoPlayer.pause()
  }

  AndroidView(
      factory = { ctx ->
        PlayerView(ctx).apply {
          setPlayer(exoPlayer)
          useController = false
          // Crop to fill the tile like the image previews (ContentScale.Crop).
          resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
      },
      modifier = modifier.hoverable(interactionSource = interactionSource),
  )
}

/**
 * Determines the appropriate color scheme based on system theme.
 *
 * THEMING IN SPATIAL UIS: Spatial apps should respect the user's system theme preference. This
 * function checks if dark mode is enabled and returns the matching theme.
 *
 * @return SpatialColorScheme - Either dark or light color scheme
 */
@Composable
fun getPanelTheme(): SpatialColorScheme =
    if (isSystemInDarkTheme()) darkSpatialColorScheme() else lightSpatialColorScheme()

/**
 * Maps splat file names to their corresponding drawable resource IDs. Used to display preview
 * images for each splat option.
 */
fun getSplatPreviewResource(splatPath: String): Int? {
  return when {
    splatPath.contains("Menlo Park", ignoreCase = true) -> R.drawable.mpk_room
    splatPath.contains("Los Angeles", ignoreCase = true) -> R.drawable.lax_room
    else -> null
  }
}

/**
 * Extracts a clean display name from the splat file path. Removes "apk://" prefix and ".spz"
 * extension. Hyperscape captures use their baked name (and splat count) instead.
 */
fun getSplatDisplayName(splatPath: String, capture: HyperscapeCapture? = null): String {
  if (capture != null) {
    // An enumerated-but-unbaked raw bundle: tapping the tile bakes it first.
    if (capture.needsBake) return "${capture.name} (tap to bake)"
    val count = capture.splatCount
    return if (count > 0) "${capture.name} (${count / 1000}k)" else capture.name
  }
  return splatPath.replace("apk://", "").replace(".spz", "")
}

/**
 * Loads a Hyperscape capture's thumbnail (thumb.jpg), if the bake produced
 * one. Device-storage captures read from their folder; APK-asset captures
 * read from assets. Returns null when there is none — the caller falls back
 * to the built-in drawable previews.
 */
@Composable
fun captureThumbnail(capture: HyperscapeCapture?): ImageBitmap? {
  if (capture == null) return null
  val context = LocalContext.current
  return remember(capture.id, capture.deviceDir?.absolutePath) {
    try {
      val stream =
          capture.thumbnailFile?.inputStream()
              ?: if (capture.hasThumbnail) {
                context.assets.open(capture.thumbnailAssetPath)
              } else {
                null
              }
      stream?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
    } catch (e: Exception) {
      null
    }
  }
}
