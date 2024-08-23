plugins {
    `java-library`
    id("org.springframework.boot") version "2.7.18"
    id("io.spring.dependency-management") version ("1.1.4")
}

repositories {
    mavenCentral()
}

configurations.annotationProcessor {
    extendsFrom(configurations.compileOnly.get())
}

dependencies {
    val springBootVer = "2.7.18"

    compileOnly("org.projectlombok:lombok:1.18.34")

    api(project(":"))

    implementation("org.springframework.boot:spring-boot-starter-web")
//    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0")
    implementation("org.springdoc:springdoc-openapi-ui:1.7.0")
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign:3.1.9")

    implementation("org.springframework.boot:spring-boot-starter-websocket:$springBootVer")
    implementation("org.springframework.boot:spring-boot-starter-artemis:$springBootVer")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa:$springBootVer")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb:$springBootVer")
    implementation("javax.persistence:javax.persistence-api:2.2")
    implementation("com.h2database:h2:2.3.230")
    implementation("net.sourceforge.plantuml:plantuml-mit:1.2024.6")

    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-starter-test:$springBootVer")
    testRuntimeOnly("org.apache.commons:commons-lang3:3.15.0")

}

tasks.test {
    useJUnitPlatform()
    environment("CONNECTIONS_VISUALIZE_PLANTUML_OUT", "$projectDir/src/schema/connections.puml")
}

java {
    targetCompatibility = JavaVersion.VERSION_11
    sourceCompatibility = JavaVersion.VERSION_11
    modularity.inferModulePath.set(true)
}
