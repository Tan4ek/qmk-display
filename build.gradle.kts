plugins {
    kotlin("jvm") version "1.9.23"
    id("com.github.johnrengelman.shadow") version "7.1.2"

    id("org.graalvm.buildtools.native") version "0.10.2"
}

group = "com.tan4ek"
version = "1.1.0" // change it also in the code

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.hid4java:hid4java:0.8.0")
    implementation("com.github.oshi:oshi-core-java11:6.6.1")
    implementation("org.slf4j:slf4j-nop:2.0.13")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}
tasks {
    shadowJar {
        archiveClassifier.set("")
        manifest {
            attributes["Main-Class"] = "com.tan4ek.MainKt"
        }
    }
}


graalvmNative {
    binaries {
        named("main") {
            mainClass.set("com.tan4ek.MainKt")
            buildArgs.add("--install-exit-handlers")
            // manual memory management
            buildArgs.add("--gc=serial")
            buildArgs.add("-R:MaxHeapSize=10m")
        }
    }
    toolchainDetection.set(true)
}
