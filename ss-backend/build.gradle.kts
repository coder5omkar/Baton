plugins {
    java
    id("org.springframework.boot") version "4.1.1" apply false
}

allprojects {
    group = "com.acme"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    dependencies {
        add("testImplementation", platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.named<Test>("test") {
        useJUnitPlatform {
            excludeTags("integration")
        }
    }

    tasks.register<Test>("integrationTest") {
        description = "Runs integration tests. Requires Docker."
        group = "verification"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        dependsOn(tasks.named("testClasses"))
        useJUnitPlatform {
            includeTags("integration")
        }
        shouldRunAfter(tasks.named("test"))
    }
}
