plugins {
    `java-library`
    `maven-publish`
}

group = "db.commentcheck"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencies {
    // Liquibase parser + offline SQL generation. Match the version your services already use.
    api("org.liquibase:liquibase-core:4.31.1")

    // The TestEngine SPI — needed at test-compile/runtime in consumers, so expose it transitively.
    api("org.junit.platform:junit-platform-engine:1.11.4")

    // For the bundled SqlCommentAnalyzer / engine unit tests only.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("org.junit.platform:junit-platform-testkit:1.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // Don't let THIS library's own build trigger its own engine against a (non-existent) changelog.
    systemProperty("liquibase.commentcheck.enabled", "false")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
