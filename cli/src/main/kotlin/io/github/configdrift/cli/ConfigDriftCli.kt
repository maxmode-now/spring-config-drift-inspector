package io.github.configdrift.cli

import io.github.configdrift.HeadlessAnalyzer
import io.github.configdrift.config.ConfigDriftSettingsResolver
import io.github.configdrift.config.FailOnThreshold
import io.github.configdrift.engine.OverlayOverrides
import io.github.configdrift.model.DriftReport
import io.github.configdrift.model.Severity
import io.github.configdrift.report.JsonReportRenderer
import io.github.configdrift.report.MarkdownReportRenderer
import io.github.configdrift.report.SarifReportRenderer
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * `config-drift check` — headless analysis with CI-friendly exit codes.
 *
 * Exit codes:
 * - 0: no findings at/above the fail-on threshold
 * - 1: findings at/above the fail-on threshold
 * - 2: usage error or unexpected failure
 */
fun main(args: Array<String>) {
    exitProcess(ConfigDriftCli().run(args))
}

class ConfigDriftCli {

    fun run(args: Array<String>): Int {
        return try {
            val options = parseArgs(args) ?: return EXIT_USAGE
            when (options.command) {
                "check" -> runCheck(options)
                "help", "--help", "-h" -> {
                    printUsage()
                    EXIT_OK
                }
                else -> {
                    System.err.println("Unknown command: ${options.command}")
                    printUsage()
                    EXIT_USAGE
                }
            }
        } catch (e: IllegalArgumentException) {
            System.err.println(e.message)
            printUsage()
            EXIT_USAGE
        } catch (e: Exception) {
            System.err.println("config-drift failed: ${e.message}")
            e.printStackTrace(System.err)
            EXIT_FAILURE
        }
    }

    private fun runCheck(options: Options): Int {
        val resolved = ConfigDriftSettingsResolver.resolve(
            initialPath = options.path,
            pathExplicit = options.pathExplicit,
            failOnFromCli = options.failOn.takeIf { options.failOnExplicit },
            completeFromCli = options.completeProfiles.takeIf { options.completeExplicit },
            overlayFromCli = options.overlayProfiles.takeIf { options.overlayExplicit },
            configFile = options.configFile,
            useConfig = !options.noConfig,
        )

        val root = resolved.analysisRoot
        if (!root.toFile().isDirectory) {
            System.err.println("Not a directory: $root")
            return EXIT_USAGE
        }

        val report = HeadlessAnalyzer.analyze(
            root = root,
            overlayOverrides = OverlayOverrides(
                manualComplete = resolved.completeProfiles,
                manualOverlay = resolved.overlayProfiles,
            ),
        )

        val rendered = when (options.format) {
            Format.JSON -> JsonReportRenderer().render(report)
            Format.MARKDOWN -> MarkdownReportRenderer(includeMatrix = !options.noMatrix).render(report)
            Format.SARIF -> SarifReportRenderer().render(report)
        }
        print(rendered)
        options.output?.writeText(rendered)

        return if (shouldFail(report, resolved.failOn)) EXIT_FINDINGS else EXIT_OK
    }

    private fun shouldFail(report: DriftReport, failOn: FailOnThreshold): Boolean {
        val counts = report.findingsBySeverity()
        return when (failOn) {
            FailOnThreshold.NEVER -> false
            FailOnThreshold.ERROR -> counts.getValue(Severity.ERROR).isNotEmpty()
            FailOnThreshold.WARNING ->
                counts.getValue(Severity.ERROR).isNotEmpty() ||
                    counts.getValue(Severity.WARNING).isNotEmpty()
        }
    }

    private fun parseArgs(args: Array<String>): Options? {
        if (args.isEmpty()) {
            printUsage()
            return null
        }
        var command = args[0]
        if (command == "--help" || command == "-h") {
            return Options(command = "help")
        }

        var path = Path.of(".")
        var pathExplicit = false
        var format = Format.JSON
        var output: Path? = null
        var failOn = FailOnThreshold.ERROR
        var failOnExplicit = false
        val complete = mutableSetOf<String>()
        var completeExplicit = false
        val overlay = mutableSetOf<String>()
        var overlayExplicit = false
        var configFile: Path? = null
        var noConfig = false
        var noMatrix = false

        var i = 1
        while (i < args.size) {
            when (val arg = args[i]) {
                "--path" -> {
                    path = Path.of(requireValue(args, ++i, "--path"))
                    pathExplicit = true
                }
                "--format" -> {
                    format = Format.parse(requireValue(args, ++i, "--format"))
                }
                "-o", "--output" -> {
                    output = Path.of(requireValue(args, ++i, arg))
                }
                "--fail-on" -> {
                    failOn = FailOnThreshold.parse(requireValue(args, ++i, "--fail-on"))
                    failOnExplicit = true
                }
                "--complete-profile" -> {
                    complete += requireValue(args, ++i, "--complete-profile")
                    completeExplicit = true
                }
                "--overlay-profile" -> {
                    overlay += requireValue(args, ++i, "--overlay-profile")
                    overlayExplicit = true
                }
                "--config" -> {
                    configFile = Path.of(requireValue(args, ++i, "--config"))
                }
                "--no-config" -> {
                    noConfig = true
                }
                "--no-matrix" -> {
                    noMatrix = true
                }
                "--help", "-h" -> {
                    command = "help"
                }
                else -> throw IllegalArgumentException("Unknown option: $arg")
            }
            i++
        }

        if (noConfig && configFile != null) {
            throw IllegalArgumentException("Use either --config or --no-config, not both")
        }

        return Options(
            command = command,
            path = path,
            pathExplicit = pathExplicit,
            format = format,
            output = output,
            failOn = failOn,
            failOnExplicit = failOnExplicit,
            completeProfiles = complete,
            completeExplicit = completeExplicit,
            overlayProfiles = overlay,
            overlayExplicit = overlayExplicit,
            configFile = configFile,
            noConfig = noConfig,
            noMatrix = noMatrix,
        )
    }

    private fun requireValue(args: Array<String>, index: Int, flag: String): String {
        if (index >= args.size) throw IllegalArgumentException("$flag requires a value")
        return args[index]
    }

    private fun printUsage() {
        System.err.println(
            """
            Usage: config-drift check [options]

            Options:
              --path DIR                 Project root to analyze (default: .)
              --format json|markdown|sarif
                                         Report format (default: json)
              -o, --output FILE          Also write the report to FILE
              --fail-on error|warning|never
                                         Exit 1 when findings meet this severity (default: error)
              --complete-profile NAME    Treat profile as a complete environment
              --overlay-profile NAME     Treat profile as a partial overlay
              --config FILE              Use this .config-drift.yml instead of discovering one
              --no-config                Ignore .config-drift.yml
              --no-matrix                Omit the key matrix (markdown only; useful for PR comments)
              -h, --help                 Show this help

            Project file (optional):
              .config-drift.yml in the analysis root may set fail-on, path, and
              profiles.complete / profiles.overlay. Explicit CLI flags override the file.

            Exit codes:
              0  no findings at/above the fail-on threshold
              1  findings at/above the fail-on threshold
              2  usage error or unexpected failure
            """.trimIndent(),
        )
    }

    data class Options(
        val command: String,
        val path: Path = Path.of("."),
        val pathExplicit: Boolean = false,
        val format: Format = Format.JSON,
        val output: Path? = null,
        val failOn: FailOnThreshold = FailOnThreshold.ERROR,
        val failOnExplicit: Boolean = false,
        val completeProfiles: Set<String> = emptySet(),
        val completeExplicit: Boolean = false,
        val overlayProfiles: Set<String> = emptySet(),
        val overlayExplicit: Boolean = false,
        val configFile: Path? = null,
        val noConfig: Boolean = false,
        val noMatrix: Boolean = false,
    )

    enum class Format {
        JSON, MARKDOWN, SARIF;

        companion object {
            fun parse(raw: String): Format = when (raw.lowercase()) {
                "json" -> JSON
                "markdown", "md" -> MARKDOWN
                "sarif" -> SARIF
                else -> throw IllegalArgumentException(
                    "Unknown format: $raw (expected json|markdown|sarif)",
                )
            }
        }
    }

    companion object {
        const val EXIT_OK = 0
        const val EXIT_FINDINGS = 1
        const val EXIT_USAGE = 2
        const val EXIT_FAILURE = 2
    }
}
