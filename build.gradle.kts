import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("net.fabricmc.fabric-loom-remap")
    `maven-publish`
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()

repositories {
    mavenCentral()
    maven { url = uri("https://cursemaven.com") }
    maven { url = uri("https://thedarkcolour.github.io/KotlinForForge/") }
    maven { url = uri("https://maven.fabricmc.net/") }
    maven { url = uri("https://maven.architectury.dev/") }
    maven { url = uri("https://jitpack.io") }
    maven { url = uri("https://repo.maven.apache.org/maven2/") }
    maven { url = uri("https://repo.spongepowered.org/maven/") }
    maven { url = uri("https://files.minecraftforge.net/maven/") }
    maven { url = uri("https://papermc.io/repo/repository/maven-public/") }
    maven { url = uri("https://repo.extendedclip.com/content/repositories/placeholderapi/") }
    maven { url = uri("https://maven.impactdev.net/repository/development") }
    maven { url = uri("https://repo.essentialsx.net/releases/") }
    maven { url = uri("https://gitlab.com/cable-mc/cobblemon") }
    mavenLocal()
    maven {
        name = "griefdefender"
        url = uri("https://repo.glaremasters.me/repository/bloodshot")
    }
}

loom {
    splitEnvironmentSourceSets()

    mods {
        register("cobblespawnregions") {
            sourceSet(sourceSets.main.get())
            sourceSet(sourceSets.getByName("client"))
        }
    }
}

dependencies {

    minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
    mappings("net.fabricmc:yarn:${providers.gradleProperty("yarn_mappings").get()}:v2")
    modImplementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")


    modImplementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${providers.gradleProperty("fabric_kotlin_version").get()}")


    modImplementation("com.cobblemon:fabric:1.7.3+1.21.1")
    modCompileOnly(files("libs/everlastingutils-1.1.6.jar"))
}

tasks.processResources {
    inputs.property("version", version)

    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.jar {
    inputs.property("projectName", project.name)

    from("LICENSE") {
        rename { "${it}_${project.name}" }
    }
}

publishing {
    publications {
        register<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
    repositories {

    }
}