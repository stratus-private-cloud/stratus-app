package dev.stratus.ui

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.dateWithTimeIntervalSince1970

internal actual fun dateTime(epochMs: Long): String =
    NSDateFormatter().apply {
        dateStyle = NSDateFormatterMediumStyle
        timeStyle = NSDateFormatterShortStyle
    }.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0))

// Parsed and written in the same zone, the phone's, which hands the wall
// clock back unchanged -- the one hour a year that zone skips aside.
internal actual fun wallClock(taken: String): String? {
    val parsed = NSDateFormatter().apply {
        dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        locale = platform.Foundation.NSLocale(localeIdentifier = "en_US_POSIX")
    }.dateFromString(taken) ?: return null
    return NSDateFormatter().apply {
        dateStyle = NSDateFormatterMediumStyle
        timeStyle = NSDateFormatterShortStyle
    }.stringFromDate(parsed)
}
