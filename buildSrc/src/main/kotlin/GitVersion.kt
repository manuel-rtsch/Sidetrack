import org.gradle.api.Project
import org.gradle.api.provider.Provider

/**
 * The version the build reports, derived from the git checkout it is made from.
 *
 * :app stamps its Android version with it, and the Jellyfin and Plex clients report the same
 * string to their servers, so the derivation lives in one place instead of being copied into
 * three build scripts.
 */
object GitVersion {
    /** Used when the checkout has no usable tag, e.g. a source archive. */
    const val FALLBACK_NAME = "0.1.0"

    /** Used when the commit count cannot be read. */
    const val FALLBACK_CODE = 1

    /** `v0.4.2-3-gabc1234` becomes `0.4.2-3-gabc1234`; anything not starting with a version falls back. */
    fun nameFrom(project: Project): String =
        runCatching { name(project).get() }.getOrDefault(FALLBACK_NAME)

    /** The commit count, which only ever grows, so an upgrade is never refused. */
    fun codeFrom(project: Project): Int =
        runCatching { code(project).get() }.getOrDefault(FALLBACK_CODE)

    private fun name(project: Project): Provider<String> =
        git(project, "describe", "--tags", "--match", "v[0-9]*", "--dirty").map { described ->
            described.removePrefix("v").takeIf { it.firstOrNull()?.isDigit() == true } ?: FALLBACK_NAME
        }

    private fun code(project: Project): Provider<Int> =
        git(project, "rev-list", "--count", "HEAD").map { it.toIntOrNull() ?: FALLBACK_CODE }

    private fun git(project: Project, vararg args: String): Provider<String> = project.providers.exec {
        commandLine("git", *args)
        workingDir = project.rootDir
        isIgnoreExitValue = true
    }.standardOutput.asText.map(String::trim)
}
