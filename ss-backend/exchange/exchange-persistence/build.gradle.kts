plugins {
    `java-library`
}

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    api(project(":exchange:exchange-domain"))

    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
}
