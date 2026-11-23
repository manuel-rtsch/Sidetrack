import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.inject.Inject

/**
 * Installs the pinned PMD distribution under `tools/pmd` and leaves a marker once it is usable.
 *
 * PMD is installed rather than resolved from Maven so the scanner is the exact build the recorded
 * reports were produced with: a rule or lexer change in a newer PMD would silently move every
 * number in the duplication section of `TODO.md`. Only the marker is a declared output, so Gradle
 * does not hash the 142 MB distribution on every build.
 */
abstract class InstallPmd : DefaultTask() {

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    /** Where the distribution is unpacked: `tools/pmd/pmd-bin-<version>`. */
    @get:Internal
    abstract val home: DirectoryProperty

    @get:Internal
    abstract val archive: RegularFileProperty

    /** Records the checksum that was installed, so a version or checksum bump reinstalls. */
    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun install() {
        val homeDir = home.get().asFile
        val markerFile = marker.get().asFile
        val launcher = File(homeDir, "bin/pmd")
        if (markerFile.isFile && markerFile.readText().trim() == sha256.get() && launcher.isFile) return

        val zip = archive.get().asFile
        if (!zip.isFile || digestOf(zip) != sha256.get()) {
            logger.lifecycle("Downloading PMD ${version.get()} into ${zip.parentFile}")
            zip.parentFile.mkdirs()
            zip.delete()
            val url = "https://github.com/pmd/pmd/releases/download/pmd_releases/" +
                "${version.get()}/pmd-dist-${version.get()}-bin.zip"
            try {
                URI(url).toURL().openStream().use { input -> zip.outputStream().use(input::copyTo) }
            } catch (failure: Exception) {
                throw GradleException(
                    "Could not download PMD ${version.get()} from $url. Install it by hand: unpack " +
                        "pmd-dist-${version.get()}-bin.zip into tools/pmd/ so that " +
                        "tools/pmd/${homeDir.name}/bin/pmd exists, then re-run.",
                    failure,
                )
            }
            val actual = digestOf(zip)
            if (actual != sha256.get()) {
                zip.delete()
                throw GradleException("PMD archive checksum mismatch: expected ${sha256.get()}, got $actual")
            }
        }

        // Unpack beside the destination first so an interrupted run cannot leave a half-populated
        // home that a later run would trust. The archive holds one top-level directory.
        val staging = File(homeDir.parentFile, "${homeDir.name}.staging")
        staging.deleteRecursively()
        staging.mkdirs()
        val root = staging.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { entries ->
            while (true) {
                val entry = entries.nextEntry ?: break
                val target = File(staging, entry.name)
                if (!target.canonicalPath.startsWith(root)) {
                    throw GradleException("PMD archive contains an unsafe path: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile.mkdirs()
                    target.outputStream().use(entries::copyTo)
                }
                entries.closeEntry()
            }
        }
        val unpacked = staging.listFiles()?.toList().orEmpty()
        val payload = unpacked.singleOrNull()?.takeIf { it.isDirectory } ?: staging
        homeDir.deleteRecursively()
        homeDir.parentFile.mkdirs()
        if (!payload.renameTo(homeDir)) throw GradleException("Could not move $payload to $homeDir")
        staging.deleteRecursively()
        if (!launcher.isFile) throw GradleException("PMD ${version.get()} unpacked without bin/pmd in $homeDir")
        // ZipInputStream drops the Unix mode bits, and a non-executable tools/pmd/.../bin/pmd is a
        // confusing thing to hand someone who wants to run the scanner by hand.
        File(homeDir, "bin").listFiles()?.forEach { it.setExecutable(true) }
        markerFile.parentFile.mkdirs()
        markerFile.writeText(sha256.get())
    }

    private fun digestOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * One CPD pass over one language.
 *
 * Identifiers and literals are both ignored, which makes every hit a structural clone rather than
 * two functions that happen to share a shape. The cost is that two deliberately separate
 * serialisation formats can still look identical, so the reports are for reading, not just
 * counting -- see the duplication section of `TODO.md`.
 */
abstract class CpdAnalysis : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val language: Property<String>

    @get:Input
    abstract val minimumTokens: Property<Int>

    /** Every path in the report is relativised against this, so the reports are checkout-portable. */
    @get:Input
    abstract val relativizeRoot: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val tool: ConfigurableFileCollection

    @get:OutputFile
    abstract val fileList: RegularFileProperty

    @get:OutputFile
    abstract val csvReport: RegularFileProperty

    @get:OutputFile
    abstract val markdownReport: RegularFileProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun analyze() {
        val root = File(relativizeRoot.get())
        val list = fileList.get().asFile
        list.parentFile.mkdirs()
        list.writeText(
            sources.files
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .sorted()
                .joinToString(separator = "\n", postfix = "\n"),
        )
        render(list, "csv", csvReport.get().asFile)
        render(list, "markdown", markdownReport.get().asFile)
    }

    private fun render(list: File, format: String, output: File) {
        output.parentFile.mkdirs()
        exec.javaexec {
            mainClass.set("net.sourceforge.pmd.cli.PmdCli")
            classpath = tool
            workingDir = File(relativizeRoot.get())
            args = listOf(
                "cpd",
                "-l", language.get(),
                "--minimum-tokens", minimumTokens.get().toString(),
                "--ignore-identifiers",
                "--ignore-literals",
                "--file-list", list.absolutePath,
                "-f", format,
                "-r", output.absolutePath,
                // A plain `cpd` run reports; cpdCheck is the one that fails the build.
                "--no-fail-on-violation",
                "--no-fail-on-error",
            )
            isIgnoreExitValue = true
        }
        // CPD resolves the file list against the working directory and the renderers print those
        // absolute paths even with -z, so relativise afterwards: the reports get committed to
        // docs/ and must not carry somebody's checkout path.
        val prefix = relativizeRoot.get() + File.separator
        val text = output.readText()
        if (text.contains(prefix)) output.writeText(text.replace(prefix, ""))
    }
}

/**
 * Fails when the duplicated line count in the reports exceeds the budget in `gradle.properties`.
 *
 * The budget only ever goes down: every cleanup lowers it in the same commit, so the scanner
 * catches regressions instead of re-reporting the backlog forever.
 */
abstract class CpdBudget : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val reports: ConfigurableFileCollection

    @get:Input
    abstract val maximumDuplicateLines: Property<Int>

    @TaskAction
    fun verify() {
        var total = 0
        val rows = mutableListOf<String>()
        reports.files.filter { it.isFile }.sortedBy { it.name }.forEach { report ->
            var lines = 0
            var groups = 0
            report.readLines().drop(1).filter { it.isNotBlank() }.forEach { row ->
                // The CSV renderer is `lines,tokens,occurrences`, then one line/file pair per copy.
                lines += row.substringBefore(',').toIntOrNull() ?: 0
                groups += 1
            }
            total += lines
            rows += "  ${report.nameWithoutExtension}: $groups groups, $lines duplicated lines"
        }
        logger.lifecycle("Copy/paste duplication\n" + rows.joinToString("\n"))
        val budget = maximumDuplicateLines.get()
        if (total > budget) {
            throw GradleException(
                "Duplicated lines rose to $total, over the budget of $budget. See " +
                    "build/reports/cpd/ for the blocks, then either remove the duplication or " +
                    "raise sidetrack.cpd.maxDuplicateLines in gradle.properties on purpose.",
            )
        }
        logger.lifecycle("Duplicated lines: $total of a $budget budget")
    }
}
