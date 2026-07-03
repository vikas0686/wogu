plugins {
    java
    id("io.wogu.wogu-gradle-plugin")
}

group = "io.wogu.sample"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.temporal:temporal-sdk:1.30.1")
}
