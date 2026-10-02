plugins {
    java
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":core"))
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)

    testImplementation(libs.velocity.api)
}

tasks {
    compileJava {
        options.compilerArgs.add("-parameters")
    }
    test {
        val version = project.version.toString() // read at configuration time (configuration-cache safe)
        systemProperty("pnc.version", version)
    }
    shadowJar {
        archiveFileName = "PartiesAndChill-velocity.jar"
        destinationDirectory = rootProject.layout.projectDirectory.dir("builds")
        // Velocity already ships everything else core uses; only Jedis and its own deps get shaded.
        val base = "dev.partiesandchill.libs"
        relocate("redis.clients", "$base.jedis")
        relocate("org.apache.commons.pool2", "$base.pool2")
        relocate("org.json", "$base.json")
        mergeServiceFiles()
    }
    build {
        dependsOn(shadowJar)
    }
}
