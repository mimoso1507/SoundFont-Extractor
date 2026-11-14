package com.ilmer.sf2tool.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.UIManager
import kotlin.math.max
import kotlin.math.min

/** Smallest window allowed when not in full screen. It is landscape on purpose. */
private const val MIN_W = 960
private const val MIN_H = 560

fun main() {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val vm = DesktopViewModel()

    application {
        val icon = remember { BitmapPainter(useResource("icon.png", ::loadImageBitmap)) }
        // The app always starts in full screen. ESC leaves it, F11 toggles it.
        var fullscreen by remember { mutableStateOf(true) }
        var screen by remember {
            mutableStateOf(GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds)
        }

        // Full screen is a borderless window covering the whole monitor (taskbar included).
        // Undecorated can't change on a live window, so the window is recreated when toggling.
        key(fullscreen) {
            val windowed = remember { landscapeWindowSize(screen) }
            val state = rememberWindowState(
                placement = WindowPlacement.Floating,
                position = if (fullscreen) WindowPosition(screen.x.dp, screen.y.dp)
                else WindowPosition((screen.x + (screen.width - windowed.width) / 2).dp, (screen.y + (screen.height - windowed.height) / 2).dp),
                size = if (fullscreen) DpSize(screen.width.dp, screen.height.dp) else DpSize(windowed.width.dp, windowed.height.dp),
            )
            // onPreviewKeyEvent runs outside the window scope, so keep a handle to the AWT window here.
            val awtWindow = remember { arrayOfNulls<ComposeWindow>(1) }
            Window(
                onCloseRequest = ::exitApplication,
                state = state,
                title = "SF2 Extractor",
                icon = icon,
                undecorated = fullscreen,
                resizable = !fullscreen,
                onPreviewKeyEvent = { e ->
                    when {
                        e.type != KeyEventType.KeyDown -> false
                        e.key == Key.Escape && fullscreen -> { fullscreen = false; true }
                        e.key == Key.F11 -> {
                            if (!fullscreen) awtWindow[0]?.let { screen = it.graphicsConfiguration.bounds }
                            fullscreen = !fullscreen
                            true
                        }
                        else -> false
                    }
                },
            ) {
                awtWindow[0] = window
                DisposableEffect(window) {
                    if (fullscreen) {
                        window.bounds = Rectangle(screen)
                        window.toFront()
                        window.requestFocus()
                        onDispose { }
                    } else {
                        window.minimumSize = Dimension(MIN_W, MIN_H)
                        // Keep the window horizontal: never let it get taller than it is wide.
                        val keepLandscape = object : ComponentAdapter() {
                            override fun componentResized(e: ComponentEvent) {
                                if (window.extendedState and Frame.MAXIMIZED_BOTH != 0) return
                                val w = window.width
                                val h = window.height
                                if (h > w * 3 / 4) window.setSize(w, max(MIN_H, w * 3 / 4))
                            }
                        }
                        window.addComponentListener(keepLandscape)
                        onDispose { window.removeComponentListener(keepLandscape) }
                    }
                }

                val dark = isSystemInDarkTheme()
                val colors = if (dark) darkColorScheme(primary = Color(0xFF81C784), secondary = Color(0xFFA5D6A7))
                else lightColorScheme(primary = Color(0xFF2E7D32), secondary = Color(0xFF558B2F))
                MaterialTheme(colorScheme = colors) {
                    DesktopScreen(
                        vm = vm,
                        window = window,
                        fullscreen = fullscreen,
                        onToggleFullscreen = {
                            if (!fullscreen) screen = window.graphicsConfiguration.bounds
                            fullscreen = !fullscreen
                        },
                        onExit = ::exitApplication,
                    )
                }
            }
        }
    }
}

/** A 16:10 window that fits in 85 % of the screen, never smaller than the minimum size. */
private fun landscapeWindowSize(screen: Rectangle): Dimension {
    var w = min(1400, (screen.width * 0.85).toInt())
    var h = w * 10 / 16
    if (h > screen.height * 0.85) {
        h = (screen.height * 0.85).toInt()
        w = h * 16 / 10
    }
    return Dimension(max(MIN_W, w), max(MIN_H, h))
}
