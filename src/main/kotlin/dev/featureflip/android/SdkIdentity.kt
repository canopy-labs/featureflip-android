package dev.featureflip.android

/**
 * The `featureflip-android/<version>` User-Agent every request carries, so the
 * Evaluation API can count SDK usage by name and version (#4141, contract in #4138).
 *
 * [SDK_VERSION] is generated at build time from the `version` in build.gradle.kts, which
 * publish-android-sdk.yml rewrites from the release tag, so it cannot drift from what
 * shipped the way a literal did (#2141). Generated source rather than a resource lookup
 * because a resource inside the jar is at the mercy of the app's packaging and shrinker.
 */
internal object SdkIdentity {
    const val NAME = "featureflip-android"

    // RFC 9110 product token: tchars.
    private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

    val DEFAULT: String = "$NAME/$SDK_VERSION"

    /**
     * The SDK token, then the wrapper's token when one is configured. A wrapper that is not
     * a valid product token is dropped with a warning rather than thrown, because identity
     * metadata must never stop the client starting.
     */
    fun userAgent(wrapperName: String?, wrapperVersion: String?): String {
        if (wrapperName.isNullOrEmpty()) return DEFAULT
        val valid = TOKEN.matches(wrapperName) && (wrapperVersion == null || TOKEN.matches(wrapperVersion))
        if (!valid) {
            System.err.println(
                "[featureflip] Ignoring wrapper '$wrapperName/$wrapperVersion': not a valid User-Agent product token.",
            )
            return DEFAULT
        }
        return if (wrapperVersion == null) "$DEFAULT $wrapperName" else "$DEFAULT $wrapperName/$wrapperVersion"
    }
}
