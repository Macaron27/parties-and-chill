rootProject.name = "parties-and-chill"

include("api", "velocity", "paper-bridge")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        // Plugin APIs below publish POMs with unpublished parents (and BedWars2023 declares Java 11
        // metadata); the bridge only needs their jars on the compile classpath.
        maven("https://repo.tomkeuper.com/repository/releases/") {
            metadataSources { artifact() }
            content { includeGroup("com.tomkeuper.bedwars") }
        }
        maven("https://jitpack.io") {
            metadataSources { artifact() }
            content { includeGroupByRegex("com\\.github\\..*") }
        }
    }
}
