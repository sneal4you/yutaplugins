version = "1.0.1"
description = "Aliucord theme engine compatible with Themer themes, that keeps working on modern Android."
aliucord {
    changelog.set(
        """
        # 1.0.1
        * Fix the status bar staying hidden after restarting Discord with a theme enabled.
        * Fix icons such as the log out button becoming invisible with themes that use transparent backgrounds.
        * Fix muted channels and forum thread lines becoming invisible. Colors now change exactly where they did with Themer.

        # 1.0.0
        * Initial release.
        """.trimIndent(),
    )
}
