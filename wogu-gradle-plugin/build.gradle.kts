plugins {
    `java-gradle-plugin`
}

group = "io.wogu"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    // WoGu's engine, api, temporal, and report modules are Maven-built and only
    // available in the local Maven repository until they are published; the plugin
    // consumes them from there.
    mavenLocal()
    mavenCentral()
}

val woguVersion = "0.1.0-SNAPSHOT"

dependencies {
    implementation("io.wogu:wogu-api:$woguVersion")
    implementation("io.wogu:wogu-core:$woguVersion")
    implementation("io.wogu:wogu-temporal:$woguVersion")
    implementation("io.wogu:wogu-report:$woguVersion")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation(gradleTestKit())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        create("wogu") {
            id = "io.wogu.wogu-gradle-plugin"
            implementationClass = "io.wogu.gradle.WoguPlugin"
            displayName = "WoGu Workflow Guard"
            description = "Static analysis and build validation for workflow-based applications (Temporal Java SDK)."
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
