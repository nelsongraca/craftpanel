package craftpanel

import com.github.jknack.handlebars.Helper
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import se.bjurr.gitchangelog.api.GitChangelogApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

/**
 * Local release task. Computes the next version from Conventional Commits (minor bump only),
 * prepends the new release section to [changelogFile], then commits/tags/pushes with the native
 * git CLI so the developer's signing configuration (e.g. SSH-signed commits) is honoured.
 *
 * Two commits, gitflow style:
 *  - release commit (`chore(release): <v>`) carrying CHANGELOG.md + `gradle.properties = <v>`,
 *    tagged `<v>`;
 *  - prepare-next commit (`gradle.properties = <next-minor>-SNAPSHOT`) on master afterwards.
 *
 * The tag therefore never sits on the master tip. The prepare-next push supersedes the release
 * commit's master-branch CI runs (master uses `cancel-in-progress`), so tag releases must not rely
 * on them: publish.yml re-runs CI + System Tests on the tag commit itself before publishing.
 */
abstract class ReleaseTask @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {

    @get:Input
    abstract val repoPath: Property<String>

    @get:Input
    abstract val changelogTemplate: Property<String>

    /** Explicit version (e.g. `-PreleaseVersion=1.0.0`); absent means compute from commits. */
    @get:Input
    @get:Optional
    abstract val releaseVersion: Property<String>

    /** Preview only: compute version + changelog but do not commit, tag, or push. */
    @get:Input
    abstract val dryRun: Property<Boolean>

    /** Also push a follow-up commit setting `craftpanel_version=<next-minor>.0-SNAPSHOT`. */
    @get:Input
    abstract val prepareNext: Property<Boolean>

    @get:Internal
    abstract val changelogFile: RegularFileProperty

    @TaskAction
    fun release() {
        val repo = File(repoPath.get())
        val lastTag = gitOutput(repo, "describe", "--tags", "--abbrev=0")
        val version = resolveVersion(repo)
        check(!tagExists(repo, version)) { "Tag '$version' already exists." }

        if (lastTag.isEmpty()) {
            logger.lifecycle("No previous tag found - keeping the existing CHANGELOG.md as the initial release entry.")
        } else {
            generateChangelog(repo, lastTag, version)
        }

        if (dryRun.get()) {
            logger.lifecycle("Dry run complete: version=$version (no commit, tag, or push).")
            return
        }

        setProjectVersion(version)
        commit(repo, "chore(release): $version", listOf("CHANGELOG.md", "gradle.properties"))
        git(repo, "tag", "-a", version, "-m", "Release $version")
        git(repo, "push", "origin", "HEAD")
        git(repo, "push", "origin", version)

        if (prepareNext.get()) {
            val next = nextMinorSnapshot(version)
            setProjectVersion(next)
            logger.lifecycle("Next development version: $next")
            commit(repo, "chore(release): prepare for next development iteration", listOf("gradle.properties"))
            git(repo, "push", "origin", "HEAD")
        }
        logger.lifecycle("Released $version.")
    }

    private fun resolveVersion(repo: File): String {
        val override = releaseVersion.orNull?.trim().orEmpty()
        val raw = if (override.isNotEmpty()) {
            override
        } else {
            GitChangelogApi.gitChangelogApiBuilder()
                .withFromRepo(repo.absolutePath)
                .withSemanticMajorVersionPattern(NEVER)
                .withSemanticMinorVersionPattern(ALWAYS)
                .withSemanticPatchVersionPattern(NEVER)
                .getNextSemanticVersion()
                .version
        }
        val version = raw.removePrefix("v")
        require(SEMVER.matches(version)) { "Not a valid semver release version: '$raw'" }
        return version
    }

    private fun generateChangelog(repo: File, fromRevision: String, version: String) {
        GitChangelogApi.gitChangelogApiBuilder()
            .withFromRepo(repo.absolutePath)
            .withFromRevision(fromRevision)
            .withUntaggedName(version)
            .withHandlebarsHelper("releaseDate", Helper<Any> { _, _ -> LocalDate.now().toString() })
            .withTemplateContent(changelogTemplate.get())
            .prependToFile(changelogFile.get().asFile)
    }

    private fun nextMinorSnapshot(version: String): String {
        val (major, minor, _) = SEMVER.matchEntire(version)!!.destructured
        return "$major.${minor.toInt() + 1}.0-SNAPSHOT"
    }

    private fun setProjectVersion(value: String) {
        val properties = File(changelogFile.get().asFile.parentFile, "gradle.properties")
        properties.writeText(
            properties.readText().replace(Regex("(?m)^craftpanel_version=.*$"), "craftpanel_version=$value")
        )
    }

    private fun commit(repo: File, message: String, paths: List<String>) {
        val pathArgs = paths.toTypedArray()
        git(repo, "add", *pathArgs)
        if (hasStagedChanges(repo, paths)) {
            git(repo, "commit", "-m", message, *pathArgs)
        } else {
            logger.lifecycle("No changes to commit for: ${paths.joinToString()}")
        }
    }

    private fun tagExists(repo: File, version: String): Boolean =
        gitOutput(repo, "rev-parse", "-q", "--verify", "refs/tags/$version").isNotEmpty()

    private fun hasStagedChanges(repo: File, paths: List<String>): Boolean {
        val result = execOps.exec {
            workingDir = repo
            commandLine(listOf("git", "diff", "--cached", "--quiet", "--") + paths)
            isIgnoreExitValue = true
        }
        return result.exitValue != 0
    }

    /** Runs git, failing the task on a non-zero exit. */
    private fun git(repo: File, vararg args: String) {
        execOps.exec {
            workingDir = repo
            commandLine(listOf("git") + args)
        }
    }

    /** Runs git and returns trimmed stdout, tolerating a non-zero exit (empty result). */
    private fun gitOutput(repo: File, vararg args: String): String {
        val out = ByteArrayOutputStream()
        execOps.exec {
            workingDir = repo
            commandLine(listOf("git") + args)
            standardOutput = out
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        return out.toString(Charsets.UTF_8).trim()
    }

    private companion object {
        const val NEVER = "^$"
        const val ALWAYS = ".*"
        val SEMVER = Regex("""(\d+)\.(\d+)\.(\d+)""")
    }
}
