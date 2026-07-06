import javax.xml.parsers.DocumentBuilderFactory

plugins {
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "1.3.1"
}

group = "io.github.vikas0686"
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
    mavenCentral()
}

val woguVersion = version.toString()

dependencies {
    implementation("io.github.vikas0686:wogu-api:$woguVersion")
    implementation("io.github.vikas0686:wogu-core:$woguVersion")
    implementation("io.github.vikas0686:wogu-temporal:$woguVersion")
    implementation("io.github.vikas0686:wogu-report:$woguVersion")

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
            id = "io.github.vikas0686.wogu"
            implementationClass = "io.wogu.gradle.WoguPlugin"

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
