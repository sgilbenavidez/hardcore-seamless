plugins {
    java
}

group = "com.hardcoreseamless"
version = "0.5.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    // Matches the Velocity server version actually running in this project (see VERSION_LOCK.md).
    compileOnly("com.velocitypowered:velocity-api:4.2.0")
    annotationProcessor("com.velocitypowered:velocity-api:4.2.0")

    // Guice's @Inject annotation only - not bundled in velocity-api.jar, but the actual running
    // Velocity server jar shades/bundles Guice itself (confirmed: com/google/inject/Inject.class
    // present inside proxy/velocity/velocity-4.2.0-30.jar), so this is compile-time only; runtime
    // wiring uses Velocity's own bundled Guice, not this dependency.
    compileOnly("com.google.inject:guice:7.0.0")

    // org.slf4j.Logger, injected by Velocity - not bundled in velocity-api.jar either.
    compileOnly("org.slf4j:slf4j-api:2.0.16")
    testImplementation("org.slf4j:slf4j-api:2.0.16")
    testImplementation("com.velocitypowered:velocity-api:4.2.0")

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Mocking Velocity's interfaces (Player/RegisteredServer/ProxyServer have 20-30+ abstract
    // methods each) instead of hand-implementing them - a small, standard tool here, not an
    // attempt to mock "all of Velocity" (see FASE 4 spec section 26).
    testImplementation("org.mockito:mockito-core:5.23.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("hardcore-coordinator")
    archiveVersion.set(project.version.toString())
}

tasks.compileJava {
    options.encoding = "UTF-8"
}
