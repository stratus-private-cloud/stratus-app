package dev.stratus.core.backup

/**
 * What a Quick Settings tile says about the backup (stratus-app#265).
 *
 * The strip's answer, cut to what fits under a tile's label, and with what a
 * tap does decided here rather than in the service: a tap that cannot start a
 * useful pass opens the app instead, where the reason can be read.
 */
data class BackupTile(val subtitle: String, val look: Look, val tap: Tap) {
    // No unavailable look: Android sends no click to an unavailable tile, and
    // the tiles that would be one are the ones whose tap has to open the app.
    enum class Look { Active, Inactive }
    enum class Tap { BackUpNow, OpenTheApp }
}

/** The tile for [state], or for a backup with nothing to back up to when null. */
fun backupTile(state: BackupState?): BackupTile = when (state) {
    null -> BackupTile("Not set up", BackupTile.Look.Inactive, BackupTile.Tap.OpenTheApp)

    BackupState.NeverRun -> BackupTile("Not run yet", BackupTile.Look.Inactive, BackupTile.Tap.BackUpNow)

    is BackupState.Idle -> BackupTile("Up to date", BackupTile.Look.Inactive, BackupTile.Tap.BackUpNow)

    is BackupState.Working -> BackupTile("${state.left} to go", BackupTile.Look.Active, BackupTile.Tap.BackUpNow)

    is BackupState.Waiting -> BackupTile(
        when (state.reason) {
            WaitingReason.ForWifi -> "Waiting for wifi"
            WaitingReason.InTheSystemsHands -> "${state.left} sending"
            WaitingReason.ForARetry, WaitingReason.ForTheNextPass -> "${state.left} waiting"
        },
        BackupTile.Look.Inactive,
        BackupTile.Tap.BackUpNow,
    )

    is BackupState.NeedsYou -> when (state.reason) {
        AttentionReason.TheLibraryIsNotReadable ->
            BackupTile("No photo access", BackupTile.Look.Inactive, BackupTile.Tap.OpenTheApp)
        is AttentionReason.SomethingWillNotSend ->
            BackupTile("${state.affected} won't upload", BackupTile.Look.Inactive, BackupTile.Tap.OpenTheApp)
    }
}

/** What the tile says between a tap and the pass it started being seen. */
val startingTile = BackupTile("Starting…", BackupTile.Look.Active, BackupTile.Tap.BackUpNow)
