plugins {
    kotlin("jvm") version "1.9.22"
    id("com.gradleup.shadow") version "8.3.0"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "com.hacklab"
version = "1.2.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
    maven("https://oss.sonatype.org/content/groups/public/") {
        name = "sonatype"
    }
    maven("https://maven.enginehub.org/repo/") {
        name = "enginehub-repo"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.5-R0.1-SNAPSHOT")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.10")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
}

tasks {
    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("1.21.5")
    }
}

val targetJavaVersion = 21
kotlin {
    jvmToolchain(targetJavaVersion)
}

tasks.build {
    dependsOn("shadowJar")
}

tasks.shadowJar {
    archiveBaseName.set("EasyCTF")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    // 出力ファイル名を明示的に設定
    archiveFileName.set("EasyCTF-${project.version}.jar")
    
    // Relocate Kotlin stdlib to avoid conflicts
    relocate("kotlin", "com.hacklab.ctf.libs.kotlin")
    relocate("org.jetbrains", "com.hacklab.ctf.libs.jetbrains")
    
    // Minimize JAR size by removing unused classes
    minimize()
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Archive task for distribution
tasks.register<Zip>("createDistribution") {
    group = "distribution"
    description = "Creates a distribution archive with plugin and documentation"
    
    from(tasks.shadowJar.get().outputs)
    from("README.md") { into("docs") }
    from("CLAUDE.md") { into("docs") }
    from("src/main/resources/config.yml") { into("config") }
    from("src/main/resources/lang_ja.yml") { into("config") }
    from("src/main/resources/lang_en.yml") { into("config") }
    
    archiveBaseName.set("EasyCTF-Distribution")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(file("$buildDir/distributions"))
}
