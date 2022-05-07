package org.fdroid.fdroid.privileged

object ClientWhitelist {
    val whitelist: HashSet<Pair<String, String>> = HashSet(
        // certificate SHA-256, signed with keys/common/org.calyxos.fdroid
        listOf(
            Pair(
                "org.fdroid.fdroid",
                "c633ff86537b7bfe7daa3e2403d95489e215e11df63cf3aca54ad26c893bdad9"
            )
        )
    )
}