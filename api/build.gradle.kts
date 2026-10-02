plugins {
    `java-library`
    `maven-publish`
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    // Same floor as the bridge that ships these classes: one API for 1.8.8 -> 26.x backends.
    compileOnly(libs.spigot.api) { isTransitive = false }
    compileOnlyApi("org.jetbrains:annotations:26.0.2")

    testImplementation(libs.spigot.api) { isTransitive = false }
}

tasks {
    compileJava {
        options.release = 8 // loaded by Java 8 servers, like the paper bridge
    }
    compileTestJava {
        options.release = 25
    }
    jar {
        // For developers compiling against it (compileOnly); servers run the paper jar, which already contains it.
        archiveFileName = "PartiesAndChill-api.jar"
        destinationDirectory = rootProject.layout.projectDirectory.dir("builds")
    }
    javadoc {
        (options as StandardJavadocDocletOptions).apply {
            encoding = "UTF-8"
            addStringOption("-release", "8")
            addBooleanOption("Xdoclint:all,-missing", true) // event boilerplate (constructors, getHandlerList) needs no prose
        }
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
