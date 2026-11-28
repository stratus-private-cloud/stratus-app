package dev.stratus.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals

class BackupTileTest {

    @Test
    fun withNothingToBackUpToATapOpensTheApp() {
        assertEquals(
            BackupTile("Not set up", BackupTile.Look.Inactive, BackupTile.Tap.OpenTheApp),
            backupTile(null),
        )
    }

    @Test
    fun aPassUnderWayIsTheOnlyActiveLook() {
        assertEquals(BackupTile("12 to go", BackupTile.Look.Active, BackupTile.Tap.BackUpNow), backupTile(BackupState.Working(12, "a.jpg")))
        assertEquals(BackupTile.Look.Inactive, backupTile(BackupState.Idle(0, 3)).look)
        assertEquals(BackupTile.Look.Inactive, backupTile(BackupState.NeverRun).look)
    }

    @Test
    fun waitingNamesWifiAndCountsTheRest() {
        fun waiting(reason: WaitingReason) = backupTile(BackupState.Waiting(reason, 4, 1L, null, 0)).subtitle
        assertEquals("Waiting for wifi", waiting(WaitingReason.ForWifi))
        assertEquals("4 sending", waiting(WaitingReason.InTheSystemsHands))
        assertEquals("4 waiting", waiting(WaitingReason.ForARetry))
        assertEquals("4 waiting", waiting(WaitingReason.ForTheNextPass))
    }

    @Test
    fun whatAPassCannotFixOpensTheAppInstead() {
        val unreadable = backupTile(BackupState.NeedsYou(AttentionReason.TheLibraryIsNotReadable, 0))
        assertEquals(BackupTile("No photo access", BackupTile.Look.Inactive, BackupTile.Tap.OpenTheApp), unreadable)

        val stuck = backupTile(BackupState.NeedsYou(AttentionReason.SomethingWillNotSend("413"), 3))
        assertEquals(BackupTile("3 won't upload", BackupTile.Look.Inactive, BackupTile.Tap.OpenTheApp), stuck)
    }
}
