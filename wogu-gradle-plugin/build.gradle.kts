import javax.xml.parsers.DocumentBuilderFactory

plugins {
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "1.3.1"
}

group = "dev.wogu"
// wogu-gradle-plugin is a separate Gradle build (not a Maven module), but it's still
// released in lockstep with the Maven reactor (see VERSIONING.md), so it reads the same
// single source of truth instead of duplicating the version here: the <revision>
// property in the root pom.xml. Bump that one line to release a new version everywhere.
version = readRevisionFromRootPom()

fun readRevisionFromRootPom(): String {
    val rootPom = rootDir.resolveSibling("pom.xml")
    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rootPom)
    return document.getElementsByTagName("revision").item(0).textContent.trim()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    // The dev.wogu:* artifacts this build depends on (below) are resolved from
    // the local Maven repository first: CI and CONTRIBUTING.md's build steps run
    // 'mvn install' right before this build, and a not-yet-released version (e.g. while
    // preparing a release, or any commit between releases) only ever exists there, never
    // on Maven Central. mavenCentral() remains for every other, actually-published
    // dependency (JUnit, AssertJ, etc.) and as a fallback once a version is released.
    mavenLocal()
    mavenCentral()
}

val woguVersion = version.toString()

dependencies {
    implementation("dev.wogu:wogu-api:$woguVersion")
    implementation("dev.wogu:wogu-core:$woguVersion")
    implementation("dev.wogu:wogu-temporal:$woguVersion")
    implementation("dev.wogu:wogu-report:$woguVersion")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation(gradleTestKit())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {

    website = "https://github.com/vikas0686/wogu"
    vcsUrl = "https://github.com/vikas0686/wogu"

    plugins {
        create("wogu") {
            id = "dev.wogu"
            implementationClass = "dev.wogu.gradle.WoguPlugin"

            displayName = "WoGu Workflow Guard"
            description = "Static analysis and workflow quality gates."

            tags.set(listOf(
                "temporal",
                "workflow",
                "static-analysis",
                "quality",
                "java"
            ))
        }
    }
}


tasks.test {
    useJUnitPlatform()
}
