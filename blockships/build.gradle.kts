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

// --- defCoreLib source selection ---------------------------------------------------------------
// Mechanism engine. compileOnly + runtime `depend:` — NEVER shaded (mirrors ProtocolLib/WorldGuard;
// do not relocate anon.def9a2a4.corelib).
//
// Default is the JitPack build pinned by `defCoreLibRef` in gradle.properties. The repo-root
// Makefile reads that same property to fetch the runtime plugin jar, so a clean clone and CI
// compile and run against the identical build with no sibling checkout on disk.
//
// Co-developing both repos? Opt in explicitly — never auto-detected, because a build that silently
// changes its dependency based on what happens to sit next to it on disk is how CI broke:
//   gradle shadowJar -PdefCoreLibLocal                   newest ../../defCoreLib/bin/defCoreLib-*.jar
//   gradle shadowJar -PdefCoreLibLocal=/abs/path/to.jar  a specific jar
//   make build DEFCORELIB_LOCAL=1                        flips the Makefile's runtime copy too
val defCoreLibRef = (findProperty("defCoreLibRef") as String?)?.takeIf { it.isNotBlank() }
    ?: error("defCoreLibRef is not set — expected it in blockships/gradle.properties")

val defCoreLibLocal =
    if (hasProperty("defCoreLibLocal")) property("defCoreLibLocal").toString() else null

val defCoreLibDependency: Any = if (defCoreLibLocal == null) {
    logger.lifecycle("defCoreLib: JitPack pin com.github.def9a2a4:defCoreLib:$defCoreLibRef")
    "com.github.def9a2a4:defCoreLib:$defCoreLibRef"
} else {
    val jar = if (defCoreLibLocal.isNotBlank()) {
        file(defCoreLibLocal)
    } else {
        // shadow jar only — `-plain` is the thin jar and carries none of the shaded deps
        fileTree("../../defCoreLib/bin") {
            include("defCoreLib-*.jar")
            exclude("*-plain.jar")
        }.files.maxByOrNull { it.lastModified() }
            ?: error("-PdefCoreLibLocal: no defCoreLib-*.jar in ../../defCoreLib/bin — " +
                     "run `make build` in the defCoreLib checkout first")
    }
    require(jar.isFile) { "-PdefCoreLibLocal: $jar does not exist" }
    logger.lifecycle("defCoreLib: LOCAL $jar  (NOT the pinned CI build $defCoreLibRef)")
    files(jar)
}

dependencies {
    // compileOnly("io.papermc.paper:paper-api:26.1.2.build.60-stable")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
    // Optional soft-dependency: region protection integration (never shaded).
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.17")
    // defCoreLib Mechanism engine — see the source selection block above.
    compileOnly(defCoreLibDependency)
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
