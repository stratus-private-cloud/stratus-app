package dev.stratus.core.calendars

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * The server's page in Safari, which is where a configuration profile has to
 * come from: an app cannot add a CalDAV or CardDAV account itself.
 */
class IosCalendarHandoff : CalendarHandoff {

    override val toDavx5 = false

    override suspend fun open(setup: CalendarSetup) {
        if (setup !is CalendarSetup.Page) return
        val url = NSURL.URLWithString(setup.url) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }
}
