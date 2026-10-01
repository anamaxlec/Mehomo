package dev.memoh.core.model

/** Theme mode chosen in Settings. */
enum class ThemeMode { System, Light, Dark }

/** Accent alternatives offered in Settings. */
enum class MemohAccent(val label: String) {
    Violet("Memoh 紫"),
    Ocean("Ocean"),
    Forest("Forest"),
    Rose("Rose"),
    Amber("Amber"),
}
