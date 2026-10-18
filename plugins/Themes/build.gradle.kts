version = "1.0.5"
description = "Aliucord theme engine compatible with Themer themes, that keeps working on modern Android."
aliucord {
    changelog.set(
        """
        # 1.0.5
        * Fix some elements not being themed on some newer Android versions.

        # 1.0.4
        * Fix settings pages crashing with an animated background in Chat & Settings transparency mode.

        # 1.0.3
        * Fix the Themes settings and theme editor closing when a change was applied.

        # 1.0.2
        * Changes made in the Themes settings and theme editor now apply right away, without going back first.

        # 1.0.1
        * Fix the status bar staying hidden after restarting Discord with a theme enabled.
        * Fix icons such as the log out button becoming invisible with themes that use transparent backgrounds.
        * Fix muted channels and forum thread lines becoming invisible. Colors now change exactly where they did with Themer.

        # 1.0.0
        * Initial release.
        """.trimIndent(),
    )
}
