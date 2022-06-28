package org.fdroid.fdroid.privileged

object ClientWhitelist {
    val whitelist: HashSet<Pair<String, String>> = HashSet(
        // certificate SHA-256 of https//f-droid.org/F-Droid.apk
        listOf(
            Pair(
                "org.fdroid.fdroid",
                "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab"
            )
        )
    )
}
