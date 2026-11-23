plugins {
    kotlin("jvm") version "2.0.21"
}

group = "com.neuromate"
version = "0.1.0"

repositories { mavenCentral() }

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(17) }
tasks.test { useJUnitPlatform() }
