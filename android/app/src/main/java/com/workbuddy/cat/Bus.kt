package com.workbuddy.cat

/**
 * In-process links between the cat service (draws the cat) and the watcher (accessibility service that
 * sees which app/screen is open). Both live in the same process; either may be absent.
 */
object Bus {
    @Volatile var cat: CatService? = null
    @Volatile var watcher: WatchService? = null
    /** Top of the on-screen keyboard in screen px, or null when it's hidden. */
    @Volatile var imeTop: Int? = null
}
