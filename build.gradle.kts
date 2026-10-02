plugins {
    base
    alias(libs.plugins.shadow) apply false
}

subprojects {
    group = "dev.partiesandchill"
    version = "1.2.0"

    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion = JavaLanguageVersion.of(25)
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.compilerArgs.add("-Xlint:all,-options,-processing") // -options: release 8 is "obsolete"
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
        dependencies {
            "testImplementation"(platform(libs.junit.bom))
            "testImplementation"(libs.junit.jupiter)
            "testRuntimeOnly"(libs.junit.launcher)
        }
    }
}

// Every module ships its jar to builds/ (PartiesAndChill-<platform>.jar).
tasks.clean {
    delete(layout.projectDirectory.dir("builds"))
}
