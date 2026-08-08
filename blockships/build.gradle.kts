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
}

// --- defCoreLib source selection ---------------------------------------------------------------
// Mechanism engine. compileOnly + runtime `depend:` — NEVER shaded (mirrors ProtocolLib/WorldGuard;
// do not relocate anon.def9a2a4.corelib).
//
// Always a jar on disk — there is no published artifact to resolve, by design. Two ways in:
//   -PdefCoreLibDir=/path/to/checkout   newest <dir>/bin/defCoreLib-*.jar  (default: the sibling)
//   -PdefCoreLibJar=/abs/path/to.jar    that exact jar; wins over defCoreLibDir
//
// `make build` rebuilds the sibling checkout first and passes -PdefCoreLibDir, so the compile
// classpath and the runtime plugin jar it copies into plugins/ are the same build — you cannot
// compile against one engine and run against another by accident. CI has no sibling on disk: it
// clones defCoreLib at the ref pinned in gradle.properties, builds it, and passes -PdefCoreLibJar.
val defCoreLibDir = (findProperty("defCoreLibDir") as String?)?.takeIf { it.isNotBlank() }
    ?: "../../defCoreLib"

val defCoreLibJarProp = (findProperty("defCoreLibJar") as String?)?.takeIf { it.isNotBlank() }

val defCoreLibJar = if (defCoreLibJarProp != null) {
    file(defCoreLibJarProp)
} else {
    // shadow jar only — `-plain` is the thin jar and carries none of the shaded deps
    fileTree("$defCoreLibDir/bin") {
        include("defCoreLib-*.jar")
        exclude("*-plain.jar")
    }.files.maxByOrNull { it.lastModified() }
        ?: error("no defCoreLib-*.jar in $defCoreLibDir/bin — run `make build` in the defCoreLib " +
                 "checkout first, or pass -PdefCoreLibJar=/abs/path/to.jar")
}
require(defCoreLibJar.isFile) { "defCoreLib jar does not exist: $defCoreLibJar" }
logger.lifecycle("defCoreLib: $defCoreLibJar")

dependencies {
    // compileOnly("io.papermc.paper:paper-api:26.1.2.build.60-stable")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
    // Optional soft-dependency: region protection integration (never shaded).
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.17")
    // defCoreLib Mechanism engine — see the source selection block above.
    compileOnly(files(defCoreLibJar))
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
