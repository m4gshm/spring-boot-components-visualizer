plugins {
    `java-library`
}

group = "github.m4gshm"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

configurations.annotationProcessor {
    extendsFrom(configurations.compileOnly.get())
}

dependencies {
    compileOnly("org.projectlombok:lombok:1.18.34")

//    implementation("net.sourceforge.plantuml:plantuml-mit:1.2024.6")

    implementation("org.slf4j:slf4j-api:2.0.9")

    val springBootVer = "2.7.18"
    implementation("org.springframework.boot:spring-boot:$springBootVer")
    implementation("org.springframework.boot:spring-boot-autoconfigure:$springBootVer")
    implementation("org.springframework:spring-web:5.3.31")

    implementation("org.springframework:spring-jms:5.3.32")
    implementation("io.github.openfeign:feign-core:12.5")
    implementation("org.springframework.cloud:spring-cloud-openfeign-core:3.1.9")
    implementation("org.springframework.cloud:spring-cloud-context:3.1.8")

    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

java {
    withSourcesJar()
    targetCompatibility = JavaVersion.VERSION_11
    sourceCompatibility = JavaVersion.VERSION_11
    modularity.inferModulePath.set(true)
}
