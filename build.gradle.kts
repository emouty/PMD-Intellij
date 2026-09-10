import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

fun properties(key: String) = project.findProperty(key).toString()

plugins {
    id("java")
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
}

version = providers.gradleProperty("pluginVersion").get()

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(17)
}

// Configure project's dependencies
repositories {
    mavenCentral()
    intellijPlatform() {
        defaultRepositories()
    }
}

// ----------------------------------------------------------------------
// pmdbridge source set
//
// Bridge code that calls PMD APIs. Compiled against PMD as compileOnly, packaged
// SEPARATELY from the main JAR (under <plugin>/pmd/classes/ in the dist) so it is
// loaded only via the per-version ChildFirstURLClassLoader created at runtime
// by PmdProjectService — never by the main plugin classloader.
//
// Default PMD JARs are bundled under <plugin>/pmd/lib/default/ and feed the same
// classloader when no user version is configured. See pmd/PmdProjectService.java.
// ----------------------------------------------------------------------
sourceSets {
    create("pmdbridge") {
        java.srcDirs("src/pmdbridge/java")
        // See main's compile classpath (IntelliJ Platform + main output) plus pmdbridge-only PMD
        compileClasspath = sourceSets["main"].compileClasspath +
                sourceSets["main"].output +
                configurations.getByName("pmdbridgeCompileClasspath")
    }
}

// Configuration that resolves the default PMD JARs to bundle in the plugin dist.
val bundledPmd: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/platforms.html#sub:version-catalog
dependencies {
    // PMD is compileOnly for pmdbridge. It must NOT appear on the main runtime classpath
    // — main code is PMD-free and loads PMD only through the version-pinned classloader.
    "pmdbridgeCompileOnly"(libs.bundles.pmd) {
        // Prevent conflict with IntelliJ's slf4j which results in a LinkageError
        exclude("org.slf4j", module = "slf4j-api")
    }

    // Default PMD JARs to bundle inside the plugin distribution (loaded by the runtime
    // classloader when no user version is configured).
    bundledPmd(libs.bundles.pmd) {
        exclude("org.slf4j", module = "slf4j-api")
    }

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
    testImplementation(libs.assertj.core)
    // pmdbridge output + PMD itself, so a unit test can call package-private bridge logic
    // (e.g. language-version grouping) directly instead of through the runtime classloader.
    testImplementation(sourceSets["pmdbridge"].output)
    testImplementation(libs.bundles.pmd) {
        exclude("org.slf4j", module = "slf4j-api")
    }
    testRuntimeOnly(libs.junit.platform.launcher)
    // Not used by our tests (all JUnit 5): the platform test-framework jar needs JUnit 3/4
    // classes at runtime to instantiate its LauncherSessionListener service.
    testRuntimeOnly(libs.junit.legacy)

    intellijPlatform {
        // https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html#setting-up-intellij-platform
        // local("/Users/user/Applications/IntelliJ IDEA Ultimate.app")
        // for EAP
        val platformVersion = providers.gradleProperty("platformVersion").get()
        val useInstaller = !platformVersion.endsWith("-EAP-SNAPSHOT")
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"), useInstaller = useInstaller)

        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
    }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUtilBuild")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels = providers.gradleProperty("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        ides {
            // https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html#intellijPlatform-pluginVerification-ides
            // recommended() automatically tests based on the current platformVersion
            recommended()
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

// pmdbridge output is packaged as a separate JAR to be loaded via the runtime classloader.
// Version-free file name so PmdClassLoaderContainer.locatePmdbridgeJar() can find it by exact
// path inside the installed plugin directory.
val pmdbridgeJar by tasks.registering(Jar::class) {
    archiveBaseName.set("pmdbridge")
    archiveVersion.set("")
    from(sourceSets["pmdbridge"].output)
}

tasks {
    withType<JavaCompile> {
        options.compilerArgs.add("-Xlint:deprecation")
    }
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    buildPlugin {
        archiveBaseName.set("PMDPlugin")
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }

    // Place the pmdbridge JAR and the default PMD JARs into <plugin>/pmd/ inside the
    // sandbox / plugin distribution. They are intentionally outside <plugin>/lib/ so
    // the main plugin classloader does not pick them up; PmdProjectService loads them
    // via its own URLClassLoader.
    //
    // Note: prepareSandbox places the main plugin JAR under <sandbox>/plugins/<projectName>/lib/.
    // buildPlugin later renames that top-level directory to match archiveBaseName for the
    // distribution zip. So we use rootProject.name here; buildPlugin's rename carries the
    // pmd/ subdirectory along.
    val sandboxPluginDir = rootProject.name
    prepareSandbox {
        from(pmdbridgeJar) {
            into("$sandboxPluginDir/pmd/")
        }
        from(bundledPmd) {
            into("$sandboxPluginDir/pmd/lib/default/")
        }
    }
    // Platform tests (BasePlatformTestCase) load the plugin from this sandbox, so it
    // needs the same pmd/ layout runIde gets — otherwise PmdClassLoaderContainer cannot
    // locate pmdbridge.jar or the bundled PMD JARs.
    prepareTestSandbox {
        from(pmdbridgeJar) {
            into("$sandboxPluginDir/pmd/")
        }
        from(bundledPmd) {
            into("$sandboxPluginDir/pmd/lib/default/")
        }
    }

    test {
        useJUnitPlatform()
    }
}

intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=8082",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                    )
                }
            }

            plugins {
                robotServerPlugin()
            }
        }
    }
}
