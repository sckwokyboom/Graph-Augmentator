plugins {
    application
    `java-library`
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories { mavenCentral() }

dependencies {
    implementation("info.picocli:picocli:4.7.6")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.1")
    implementation("org.slf4j:slf4j-simple:2.0.16")
    implementation("com.github.javaparser:javaparser-core:3.27.0")
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.27.0")
    implementation("org.jgrapht:jgrapht-core:1.5.2")
    implementation("org.jgrapht:jgrapht-io:1.5.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("com.networknt:json-schema-validator:1.5.5")
}

application {
    mainClass.set("com.graphtipper.cli.Main")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
