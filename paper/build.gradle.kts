plugins {
    java
    alias(libs.plugins.shadow)
}

dependencies {
    // The developer API other plugins compile against; its classes ship inside the bridge jar.
    implementation(project(":api"))

    // Compiled against the oldest API so one jar loads on 1.8.8 -> 26.3 backends.
    compileOnly(libs.spigot.api) { isTransitive = false }
    compileOnly("com.google.guava:guava:17.0") // 1.8.8 ships guava 17; spigot-api is non-transitive above
    compileOnly(libs.bedwars1058.api) { isTransitive = false }
    compileOnly(libs.bedwars2023.api) { isTransitive = false }
    compileOnly(libs.bwproxy2023.api) { isTransitive = false }
    compileOnly(libs.advancedban) { isTransitive = false }
    compileOnly("org.jetbrains:annotations:26.0.2")

    // Hooks reference Bukkit/AdvancedBan/BedWars types, so tests need them at runtime.
    testImplementation(libs.spigot.api) { isTransitive = false }
    testImplementation(libs.bwproxy2023.api) { isTransitive = false }
    testImplementation(libs.advancedban) { isTransitive = false }
}

tasks {
    compileJava {
        // 1.8 servers run Java 8: the bridge must stay Java 8 bytecode.
        options.release = 8
    }
    compileTestJava {
        options.release = 25
    }
    processResources {
        val version = project.version.toString() // read at configuration time (configuration-cache safe)
        inputs.property("version", version)
        filesMatching("plugin.yml") { expand("version" to version) }
    }
    shadowJar {
        archiveFileName = "PartiesAndChill-paper.jar"
        destinationDirectory = rootProject.layout.projectDirectory.dir("builds")
    }
    build {
        dependsOn(shadowJar)
    }
}
