package dev.stratus.core.files

import kotlinx.io.Sink

/**
 * Where a file goes once it leaves the app.
 *
 * The split is the usual one: this decides *where*, and the caller decides
 * *what*. It takes a function that writes the bytes rather than the bytes
 * themselves, which is the whole point -- somebody's four-gigabyte video has to
 * stream from the server into the destination without ever being a `ByteArray`
 * in the middle.
 */
interface FileHandoff {
    /** Hands the file to whatever the system uses to view it. */
    suspend fun open(name: String, contentType: String?, body: suspend (Sink) -> Unit)

    /** Keeps a copy somewhere the person can find it again. */
    suspend fun save(name: String, contentType: String?, body: suspend (Sink) -> Unit)

    /**
     * Sends the file itself to another app -- a chat, an email -- which is
     * the other half of sharing: a link needs a Stratus and somebody to open
     * it, and this needs neither.
     *
     * [link] is a signed link to the same file where there is one, offered
     * beside it in the sheet (stratus-app#268); it is asked once the bytes
     * are in, so its check runs while they download.
     */
    suspend fun send(name: String, contentType: String?, body: suspend (Sink) -> Unit, link: suspend () -> String?)
}
