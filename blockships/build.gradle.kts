plugins {
    `java`
    id("com.gradleup.shadow") version "9.3.0"
}

group = "anon.def9a2a4"
version = "0.0.17"

java {
    toolchain {
        // languageVersion.set(JavaLanguageVersion.of(25))
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "enginehub"
        url = uri("https://maven.enginehub.org/repo/")
    }
    maven {
        name = "jitpack"
        url = uri("https://jitpack.io")
    }
}

dependencies {
    // compileOnly("io.papermc.paper:paper-api:26.1.2.build.60-stable")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
    // Optional soft-dependency: region protection integration (never shaded).
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.17")
    // defCoreLib Mechanism engine. compileOnly + runtime `depend:` — NEVER shaded (mirrors
    // ProtocolLib/WorldGuard; do not relocate anon.def9a2a4.corelib). Local sibling jar during the
    // integration branch; switch to the JitPack pin `com.github.def9a2a4:defCoreLib:<sha>` at merge.
    compileOnly(files("../../defCoreLib/bin/defCoreLib-0.4.0.jar"))
    implementation("org.bstats:bstats-bukkit:3.1.0")
}

tasks {
    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    jar {
        archiveBaseName.set("BlockShips")
        manifest {
            attributes["paperweight-mappings-namespace"] = "mojang"
        }
    }

    shadowJar {
        archiveBaseName.set("BlockShips")
        archiveClassifier.set("")
        manifest {
            attributes["paperweight-mappings-namespace"] = "mojang"
        }
        relocate("org.bstats", "${project.group}.bstats")
        mergeServiceFiles()
    }
}
