package io.github.configdrift.cli

import io.github.configdrift.HeadlessAnalyzer
import io.github.configdrift.engine.OverlayOverrides
import io.github.configdrift.model.DriftReport
import io.github.configdrift.model.Severity
import io.github.configdrift.report.JsonReportRenderer
import io.github.configdrift.report.MarkdownReportRenderer
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
        val root = options.path.toAbsolutePath().normalize()
        if (!root.toFile().isDirectory) {
            System.err.println("Not a directory: $root")
            return EXIT_USAGE
        }

        val report = HeadlessAnalyzer.analyze(
            root = root,
            overlayOverrides = OverlayOverrides(
                manualComplete = options.completeProfiles,
                manualOverlay = options.overlayProfiles,
            ),
        )

        val rendered = when (options.format) {
            Format.JSON -> JsonReportRenderer().render(report)
            Format.MARKDOWN -> MarkdownReportRenderer().render(report)
        }
        print(rendered)
        options.output?.writeText(rendered)

        return if (shouldFail(report, options.failOn)) EXIT_FINDINGS else EXIT_OK
    }

    private fun shouldFail(report: DriftReport, failOn: FailOn): Boolean {
        val counts = report.findingsBySeverity()
        return when (failOn) {
            FailOn.NEVER -> false
            FailOn.ERROR -> counts.getValue(Severity.ERROR).isNotEmpty()
            FailOn.WARNING ->
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
        var format = Format.JSON
        var output: Path? = null
        var failOn = FailOn.ERROR
        val complete = mutableSetOf<String>()
        val overlay = mutableSetOf<String>()

        var i = 1
        while (i < args.size) {
            when (val arg = args[i]) {
                "--path" -> {
                    path = Path.of(requireValue(args, ++i, "--path"))
                }
                "--format" -> {
                    format = Format.parse(requireValue(args, ++i, "--format"))
                }
                "-o", "--output" -> {
                    output = Path.of(requireValue(args, ++i, arg))
                }
                "--fail-on" -> {
                    failOn = FailOn.parse(requireValue(args, ++i, "--fail-on"))
                }
                "--complete-profile" -> {
                    complete += requireValue(args, ++i, "--complete-profile")
                }
                "--overlay-profile" -> {
                    overlay += requireValue(args, ++i, "--overlay-profile")
                }
                "--help", "-h" -> {
                    command = "help"
                }
                else -> throw IllegalArgumentException("Unknown option: $arg")
            }
            i++
        }

        return Options(
            command = command,
            path = path,
            format = format,
            output = output,
            failOn = failOn,
            completeProfiles = complete,
            overlayProfiles = overlay,
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
              --format json|markdown     Report format (default: json)
              -o, --output FILE          Also write the report to FILE
              --fail-on error|warning|never
                                         Exit 1 when findings meet this severity (default: error)
              --complete-profile NAME    Treat profile as a complete environment
              --overlay-profile NAME     Treat profile as a partial overlay
              -h, --help                 Show this help

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
        val format: Format = Format.JSON,
        val output: Path? = null,
        val failOn: FailOn = FailOn.ERROR,
        val completeProfiles: Set<String> = emptySet(),
        val overlayProfiles: Set<String> = emptySet(),
    )

    enum class Format {
        JSON, MARKDOWN;

        companion object {
            fun parse(raw: String): Format = when (raw.lowercase()) {
                "json" -> JSON
                "markdown", "md" -> MARKDOWN
                else -> throw IllegalArgumentException("Unknown format: $raw (expected json|markdown)")
            }
        }
    }

    enum class FailOn {
        ERROR, WARNING, NEVER;

        companion object {
            fun parse(raw: String): FailOn = when (raw.lowercase()) {
                "error" -> ERROR
                "warning" -> WARNING
                "never" -> NEVER
                else -> throw IllegalArgumentException("Unknown fail-on: $raw (expected error|warning|never)")
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
