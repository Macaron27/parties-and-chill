plugins {
    java
    alias(libs.plugins.shadow)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

dependencies {
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)

    // Velocity already ships gson + slf4j at runtime; only Jedis' own deps get shaded.
    implementation(libs.jedis) {
        exclude(group = "com.google.code.gson")
        exclude(group = "org.slf4j")
    }

    testImplementation(libs.velocity.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-parameters"))
    }
    test {
        useJUnitPlatform()
    }
    shadowJar {
        archiveBaseName = "PartiesAndChill-Velocity"
        archiveClassifier = ""
        val base = "dev.partiesandchill.libs"
        relocate("redis.clients", "$base.jedis")
        relocate("org.apache.commons.pool2", "$base.pool2")
        relocate("org.json", "$base.json")
        mergeServiceFiles()
    }
    jar {
        archiveClassifier = "plain" // the thin jar lacks Jedis; ship the shadow jar
    }
    build {
        dependsOn(shadowJar)
    }
}
