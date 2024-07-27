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

    compileOnly("org.projectlombok:lombok:1.18.30")

    implementation(project(":"))

    implementation("org.springframework.boot:spring-boot-starter-web")
//    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0")
    implementation("org.springdoc:springdoc-openapi-ui:1.7.0")
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign:3.1.9")

    implementation("org.springframework.boot:spring-boot-starter-websocket:$springBootVer")
    implementation("org.springframework.boot:spring-boot-starter-artemis:$springBootVer")

    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-starter-test:$springBootVer")

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
