package dev.stratus.ui

/**
 * The marks the app draws, as characters of a Material Symbols subset
 * (stratus-app#195) drawn by [Symbol].
 *
 * They were emoji until then: free, and drawn by the system's own font, but a
 * coloured picture whose look changed with the phone's maker, beside controls
 * that wanted to be drawn in the theme's colour. Material Symbols is that font
 * cut down to these thirty-odd glyphs -- a few kilobytes, the same on both
 * platforms -- and still a character, so a mark is still a string.
 * `scripts/symbols.sh` makes the font from `ui/symbols/names.txt` and prints
 * the codepoint each name gets here.
 *
 * Public because the end-to-end suite presses these buttons and has to name
 * them. A constant shared with the test is one that cannot drift from what is
 * on screen.
 */
object Glyph {
    const val SEARCH = "\uEF7A"
    const val SETTINGS = "\uE8B8"
    const val SERVER = "\uF15C"
    const val BACKUP = "\uE864"
    const val FOLDERS = "\uE2C7"
    const val WIFI = "\uE63E"
    const val REPORTS = "\uE868"
    const val EDIT = "\uF097"
    const val SIGN_OUT = "\uE9BA"
    const val ADDRESS = "\uE250"
    const val USERNAME = "\uF0D3"
    const val BUILD = "\uE893"
    const val SCREEN = "\uE307"

    // A row's kind and what can be done to it.
    const val ACTIONS = "\uE5D4"
    const val FOLDER = FOLDERS
    const val PHOTO = "\uE3F4"
    const val FILM = "\uE404"
    const val TRACK = "\uE405"
    const val PDF = "\uE415"
    const val ARCHIVE = "\uEB2C"
    const val SHEET = "\uE265"
    const val EVENT = "\uE878"
    const val CONTACT = "\uF22E"
    const val DOCUMENT = "\uE873"
    const val OPEN = "\uE8F4"
    const val DOWNLOAD = "\uF090"
    const val SHARE = "\uE80D"
    const val RENAME = EDIT
    const val DELETE = "\uE92E"

    // The sections a Stratus is split into: its tree, its two generated
    // libraries, and the trash, which lives in the menu.
    const val FILES = FOLDER
    const val MUSIC = TRACK
    const val VIDEOS = FILM
    const val TRASH = DELETE

    const val PLAY = "\uE037"
    const val PAUSE = "\uE034"
    const val NEXT = "\uE044"
    const val PREVIOUS = "\uE045"
    const val STOP = "\uE047"

    const val BACK = "\uE5C4"
    const val MORE = "\uE5CC"
}
