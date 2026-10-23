package com.lxj.xpopup.enums

/** Controls how a popup positions its foreground content around system UI. */
enum class PopupInsetMode {
    /** Keeps the legacy behavior selected by each popup type. */
    Auto,

    /** Draws the popup background edge-to-edge while keeping foreground content in the safe area. */
    SafeArea,

    /** Leaves content edge-to-edge and lets the popup implementation consume insets itself. */
    EdgeToEdge
}
