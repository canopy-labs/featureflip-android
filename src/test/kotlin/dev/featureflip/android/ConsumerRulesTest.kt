package dev.featureflip.android

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The jar's R8/ProGuard rules must keep everything the SDK reaches by name. A miss
 * fails silently in a minified app (no lifecycle events, no flags, empty event batches)
 * and in nothing else, since no test here is minified.
 */
class ConsumerRulesTest {

    private val rules: String = requireNotNull(
        javaClass.classLoader.getResource("META-INF/proguard/featureflip-android.pro"),
    ) { "the consumer rules must ship in the jar" }.readText()

    @Test
    fun `every androidx class and method LifecycleObserver reflects on is kept`() {
        val source = File("src/main/kotlin/dev/featureflip/android/LifecycleObserver.kt").readText()
        val classes = Regex("""Class\.forName\("(androidx\.[^"]+)"\)""")
            .findAll(source).map { it.groupValues[1] }.toSet()
        // android.os.Looper and Handler are framework classes, which R8 never shrinks.
        val frameworkMethods = setOf("getMainLooper", "myLooper", "post")
        val methods = Regex("""getMethod\("([^"]+)"""")
            .findAll(source).map { it.groupValues[1] }.toSet() - frameworkMethods

        assertThat(classes).contains(
            "androidx.lifecycle.ProcessLifecycleOwner",
            "androidx.lifecycle.DefaultLifecycleObserver",
        )
        assertThat(methods).contains("get", "getLifecycle", "addObserver", "removeObserver")
        classes.forEach { assertThat(rules).`as`("rules keep %s", it).contains(it) }
        methods.forEach { assertThat(rules).`as`("rules keep %s()", it).contains(" $it(") }
    }

    @Test
    fun `Jackson's models and generic signatures are kept`() {
        assertThat(rules).contains("-keep class dev.featureflip.android.** { *; }")
        assertThat(rules).contains("Signature")
        assertThat(rules).contains("class * extends com.fasterxml.jackson.core.type.TypeReference")
    }
}
