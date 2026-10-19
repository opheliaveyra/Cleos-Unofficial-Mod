package com.cleo.cleos.data

/** Separate namespace: never repurpose glass, bubble, wallpaper or launcher preferences. */
object VisualTheme {
    const val NATIVE = "native_default"
    const val LAVENDER = "lavender_scrapbook"
    fun normalize(id: String?): String = when (id) {
        LAVENDER -> LAVENDER
        else -> NATIVE
    }
}
