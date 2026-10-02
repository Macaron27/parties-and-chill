plugins {
    `java-library`
}

dependencies {
    // Velocity provides these at runtime; the BungeeCord jar shades them.
    compileOnlyApi(libs.bundles.proxy.provided)
    // Gson and SLF4J come from the proxy too (see above).
    implementation(libs.jedis) {
        exclude(group = "com.google.code.gson")
        exclude(group = "org.slf4j")
    }

    testImplementation(libs.bundles.proxy.provided)
    testImplementation(libs.adventure.plain)
}

tasks {
    compileJava {
        // Velocity 4.2 needs Java 25 anyway; BungeeCord hosts only need 21 (Adventure 5's floor).
        options.release = 21
    }
}
