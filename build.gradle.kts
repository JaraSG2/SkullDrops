plugins {
    java
    id("com.gradleup.shadow") version "9.2.2"
}
group = "cz.jarasg2"
version = "1.1.0-beta1"
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    implementation("org.bstats:bstats-bukkit:3.1.0")
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
tasks.processResources { filteringCharset = "UTF-8" }
tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.bstats", "cz.jarasg2.skulldrops.libs.bstats")
}
tasks.jar { enabled = false }
tasks.build { dependsOn(tasks.shadowJar) }
