plugins {
    `java-library`
    `maven-publish`
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    // Same floor as the bridge that ships these classes: one API for 1.8.8 -> 26.x backends.
    compileOnly(libs.spigot.api) { isTransitive = false }
    compileOnlyApi("org.jetbrains:annotations:26.0.2")

    testImplementation(libs.spigot.api) { isTransitive = false }
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release = 8 // loaded by Java 8 servers, like paper-bridge
        options.compilerArgs.addAll(listOf("-Xlint:all,-options,-processing"))
    }
    compileTestJava {
        options.release = 25
    }
    javadoc {
        (options as StandardJavadocDocletOptions).apply {
            encoding = "UTF-8"
            addStringOption("-release", "8")
            addBooleanOption("Xdoclint:all,-missing", true) // event boilerplate (constructors, getHandlerList) needs no prose
        }
    }
    test {
        useJUnitPlatform()
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "parties-and-chill-api"
            from(components["java"])
        }
    }
}
