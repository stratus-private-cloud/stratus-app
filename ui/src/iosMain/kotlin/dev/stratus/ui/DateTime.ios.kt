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

internal actual fun wallClock(taken: String): String? {
    val utc = platform.Foundation.NSTimeZone.timeZoneWithName("UTC")
    val parsed = NSDateFormatter().apply {
        dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        locale = platform.Foundation.NSLocale.localeWithLocaleIdentifier("en_US_POSIX")
        timeZone = utc!!
    }.dateFromString(taken) ?: return null
    return NSDateFormatter().apply {
        dateStyle = NSDateFormatterMediumStyle
        timeStyle = NSDateFormatterShortStyle
        timeZone = utc!!
    }.stringFromDate(parsed)
}
