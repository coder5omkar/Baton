plugins {
    `java-library`
}

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    api("tools.jackson.core:jackson-databind")
    api("org.slf4j:slf4j-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
}
