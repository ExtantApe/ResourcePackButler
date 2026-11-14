plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.extantape"
version = "0.2.1"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

// Built against the oldest supported version, 26.1, so nothing newer slips in. Tested on 26.1, 26.2 and 26.3.
val paperApi = "io.papermc.paper:paper-api:26.1.2.build.74-stable"

dependencies {
    compileOnly(paperApi)
    implementation("com.github.ExtantApe:Signalbox:v0.3.0")

    testImplementation(paperApi)
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("Packmule")
    archiveClassifier.set("plain")
    from("LICENSE")
}

// Signalbox, for the format-1 commands tools such as Leaf Juice use, goes inside the jar, relocated, so it can never
// clash with another plugin's copy.
tasks.shadowJar {
    archiveBaseName.set("Packmule")
    archiveClassifier.set("")
    from("LICENSE")
    relocate("com.extantape.signalbox", "com.extantape.packmule.libs.signalbox")
}
tasks.build { dependsOn(tasks.shadowJar) }
