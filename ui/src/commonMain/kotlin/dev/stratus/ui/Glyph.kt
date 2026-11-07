package dev.stratus.ui

/**
 * The marks on the rows, as emoji.
 *
 * Compose Multiplatform stopped shipping the Material icon set, and the
 * alternatives were a dependency per glyph or path data pasted into this
 * repository -- against a principle that counts every library added to a mobile
 * app. An emoji is a character: it costs nothing, it is drawn by the system's
 * own font on both platforms, and it is the one kind of picture that is already
 * everywhere.
 *
 * [BACK] and [MORE] are deliberately *not* emoji. An emoji arrow is a coloured
 * picture of an arrow and reads as one; a navigation bar wants the plain glyph
 * the system font draws, in the colour the theme is using.
 *
 * Public because the end-to-end suite presses these buttons and has to name
 * them. A constant shared with the test is one that cannot drift from what is
 * on screen.
 */
object Glyph {
    const val SEARCH = "🔍"
    const val SETTINGS = "⚙️"
    const val SERVER = "☁️"
    const val BACKUP = "📷"
    const val FOLDERS = "📁"
    const val WIFI = "📶"
    const val REPORTS = "🐞"
    const val EDIT = "✏️"
    const val SIGN_OUT = "🚪"
    const val ADDRESS = "🔗"
    const val USERNAME = "👤"
    const val BUILD = "🏷️"
    const val SCREEN = "📺"

    // A row's kind and what can be done to it: the web UI's listing, mark for
    // mark, so the two read as one thing.
    const val ACTIONS = SETTINGS
    const val FOLDER = "📁"
    const val PHOTO = "🖼️"
    const val FILM = "🎬"
    const val TRACK = "🎵"
    const val PDF = "📕"
    const val ARCHIVE = "📦"
    const val SHEET = "📊"
    const val EVENT = "📅"
    const val CONTACT = "👤"
    const val DOCUMENT = "📄"
    const val OPEN = "👁️"
    const val DOWNLOAD = "📥"
    const val SHARE = "🔗"
    const val RENAME = EDIT
    const val DELETE = "🗑️"

    // The sections a Stratus is split into: its tree, its two generated
    // libraries, and the trash, which lives in the menu.
    const val FILES = FOLDER
    const val MUSIC = TRACK
    const val VIDEOS = FILM
    const val TRASH = DELETE

    // The player's buttons.
    const val PLAY = "▶️"
    const val PAUSE = "⏸️"
    const val NEXT = "⏭️"
    const val PREVIOUS = "⏮️"
    const val STOP = "⏹️"

    const val BACK = "←"
    const val MORE = "›"
}
