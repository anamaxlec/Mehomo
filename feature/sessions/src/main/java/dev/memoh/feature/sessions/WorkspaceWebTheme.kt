package dev.memoh.feature.sessions

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/** The embedded workspace uses the same reading plane as the app. */
internal class WorkspaceWebTheme(scheme: ColorScheme) {
    val background = scheme.surfaceContainerLowest
    val foreground = scheme.onSurface
    val dark = scheme.surface.luminance() < .5f
    private val colorScheme = if (dark) "dark" else "light"
    val json = "{\"background\":\"${background.css()}\",\"foreground\":\"${foreground.css()}\",\"cursor\":\"${scheme.primary.css()}\",\"colorScheme\":\"$colorScheme\"}"

    // Apply the app palette before xterm draws its first frame, not only after
    // onPageFinished. CSS also covers the space around the terminal/desktop.
    fun initialize(html: String): String = html.replace("</head>",
        "<style>html,body{background:${background.css()};color:${foreground.css()};color-scheme:$colorScheme}</style>" +
            "<script>window.initialWorkspaceTheme=$json;</script></head>")
}

private fun Color.css(): String = "#%06x".format(toArgb() and 0xffffff)
