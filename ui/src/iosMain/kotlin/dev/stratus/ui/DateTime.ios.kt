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
