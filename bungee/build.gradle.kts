plugins {
    java
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":core"))
    compileOnly(libs.bungeecord.api)

    // BungeeCord doesn't ship what core expects from the proxy (or ships other versions): shaded and relocated.
    implementation(libs.bundles.proxy.provided)
    implementation(libs.adventure.gson) // MiniMessage -> JSON -> BungeeCord components
    runtimeOnly(libs.slf4j.nop) // silences Jedis' own logging; ours goes to the plugin logger

    testImplementation(libs.bungeecord.api)
}

configurations.runtimeClasspath {
    // Annotations only, never read at runtime.
    exclude(group = "org.jetbrains", module = "annotations")
    exclude(group = "org.jspecify")
    exclude(group = "com.google.errorprone")
}

tasks {
    compileJava {
        options.release = 21 // like core
    }
    processResources {
        val version = project.version.toString() // read at configuration time (configuration-cache safe)
        inputs.property("version", version)
        filesMatching("bungee.yml") { expand("version" to version) }
    }
    shadowJar {
        archiveFileName = "PartiesAndChill-bungee.jar"
        destinationDirectory = rootProject.layout.projectDirectory.dir("builds")
        val base = "dev.partiesandchill.libs"
        mapOf(
            "redis.clients" to "jedis", "org.apache.commons.pool2" to "pool2", "org.json" to "json",
            "net.kyori" to "kyori", "org.spongepowered.configurate" to "configurate", "io.leangen.geantyref" to "geantyref",
            "com.github.benmanes.caffeine" to "caffeine",
            "com.google.gson" to "gson", "org.slf4j" to "slf4j",
        ).forEach { (from, to) -> relocate(from, "$base.$to") }
        mergeServiceFiles()
        exclude("module-info.class", "META-INF/versions/*/module-info.class")
    }
    build {
        dependsOn(shadowJar)
    }
}
