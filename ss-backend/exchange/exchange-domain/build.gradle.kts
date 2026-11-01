plugins {
    `java-library`
}

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    api(project(":platform:platform-kernel"))
    api("tools.jackson.core:jackson-databind")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
}
