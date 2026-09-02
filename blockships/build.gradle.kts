plugins {
    `java`
    id("com.gradleup.shadow") version "9.3.0"
}

group = "anon.def9a2a4"
version = "0.0.18"

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

    // The thin jar is never usable: bstats is relocated in shadowJar only (below), and Metrics is
    // constructed as the FIRST statement of onEnable with no try/catch, so this one dies immediately on
    // a NoClassDefFoundError. It also carried NO classifier while shadowJar sets "", so both targeted
    // build/libs/BlockShips-<version>.jar.
    //
    // That was latent nondeterminism, not an observed break: `make build` runs shadowJar alone, and CI's
    // `gradle build` ran BOTH with shadowJar happening to land last (last green run uploaded the shadow
    // jar and all 6 matrix cells passed; Gradle 9.6.1 never warned about the overlapping output).
    // Nothing orders the two tasks, so "happening to" was the entire guarantee — hence disabling it.
    //
    // Chosen over a `-plain` classifier not because that can't work — defCoreLib does exactly that, on
    // purpose, since its subprojects compileOnly(project(":")) — but because BlockShips has no such
    // consumer, so a second jar would do nothing except match the CI upload glob AND `cp bin/*.jar`,
    // handing Paper "Ambiguous plugin name". Nothing consumes the thin jar: no test sources, no publishing.
    //
    // KEEP the manifest block. Shadow builds DefaultInheritManifest(project, jarTask.get().manifest, …),
    // so shadowJar INHERITS this task's manifest; `enabled = false` affects execution only, not config.
    // (archiveBaseName here, by contrast, is now dead — shadowJar sets its own.)
    jar {
        enabled = false
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
