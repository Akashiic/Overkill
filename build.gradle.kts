plugins {
    java
}

group = "com.akashic.overkill"
version = System.getenv("VERSION") ?: "0.1.0-dev"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/groups/public/") {
        mavenContent { includeGroup("org.spigotmc"); includeGroup("org.bukkit"); includeGroup("net.md-5") }
    }
    mavenLocal()
}

/*
 * kunftig-api, compile-only (never shaded: at runtime the classes come from the Kunftig mod in mods/). Taken, in
 * order, from:
 *   -PkunftigApiSrc=<dir>   the API's sources (api/src/main/java of a Kunftig checkout), compiled here: what CI does
 *   ../Kunftig/api/src/main/java, when the two repositories sit side by side
 *   mavenLocal()            com.akashic.kunftig:kunftig-api:<kunftigApiVersion> (./gradlew :api:publishToMavenLocal)
 */
val kunftigApiSrc: File? = (providers.gradleProperty("kunftigApiSrc").orNull?.let { file(it) }
    ?: file("../Kunftig/api/src/main/java").takeIf { it.isDirectory })

val kunftigApi: SourceSet? = kunftigApiSrc?.let { src ->
    sourceSets.create("kunftigApi") { java.setSrcDirs(listOf(src)) }
}

dependencies {
    // The oldest Bukkit API on a public repository; checkLegacyBukkitApi verifies against the 1.7.10 one.
    compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT") { isTransitive = false }
    if (kunftigApi != null) {
        "kunftigApiCompileOnly"("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT") { isTransitive = false }
        compileOnly(kunftigApi.output)
        testImplementation(kunftigApi.output)
    } else {
        val v = providers.gradleProperty("kunftigApiVersion").getOrElse("0.9.0-dev")
        compileOnly("com.akashic.kunftig:kunftig-api:$v")
        testImplementation("com.akashic.kunftig:kunftig-api:$v")
    }

    testImplementation("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT") { isTransitive = false }
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.withType<JavaCompile>().configureEach {
    // Thermos and Crucible run on Java 8.
    options.release.set(8)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-options", "-Xlint:-processing", "-Werror"))
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.jar {
    archiveBaseName.set("Overkill")
}

tasks.test {
    useJUnitPlatform()
}

/*
 * The plugin compiles against spigot-api 1.8.8; this gate recompiles it against the Bukkit 1.7.10 API that
 * Thermos/Crucible ship, so anything that only exists in 1.8+ fails the build instead of the server:
 *   ./gradlew checkLegacyBukkitApi -PlegacyBukkitApi=<bukkit-1.7.10.jar>
 * (Kunftig's scripts/build-legacy-bukkit-api.sh builds that jar from Crucible's sources.)
 */
val legacyBukkitApi: File? = providers.gradleProperty("legacyBukkitApi").orNull?.let { rootProject.file(it) }
val checkLegacyBukkitApi by tasks.registering(JavaCompile::class) {
    description = "Recompiles the plugin against the Bukkit 1.7.10 API (-PlegacyBukkitApi=<jar>)."
    group = "verification"
    val jar = legacyBukkitApi
    onlyIf("-PlegacyBukkitApi is set") { jar != null }
    doFirst {
        if (jar == null || !jar.isFile) {
            throw GradleException("Bukkit 1.7.10 API jar not found: $jar")
        }
    }
    val main = sourceSets.main.get()
    source = main.java
    classpath = files(listOfNotNull(jar)) + main.compileClasspath.filter { !it.name.startsWith("spigot-api-") }
    destinationDirectory.set(layout.buildDirectory.dir("legacyApiCheck"))
    options.release.set(8)
}

/** The resource pack, generated from the plugin's own combo and blade definitions (see tools/ and docs/PACK.md). */
val generatePack by tasks.registering(JavaExec::class) {
    description = "Generates the resource pack into resourcepack/ (models, animations, manifests, textures)."
    group = "build"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.akashic.overkill.pack.PackGenerator")
    args(rootProject.file("resourcepack").absolutePath)
}
