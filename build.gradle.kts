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

    implementation("org.slf4j:slf4j-api:2.0.9")

    api("com.google.guava:guava:33.2.1-jre")
    api("org.apache.bcel:bcel:6.10.0")
    api("org.apache.commons:commons-lang3:3.15.0")

    val springBootVer = "2.7.18"
    implementation("org.springframework.boot:spring-boot:$springBootVer")
    implementation("org.springframework.boot:spring-boot-autoconfigure:$springBootVer")

    compileOnly("org.springframework:spring-web:5.3.31")
    compileOnly("org.springframework:spring-webmvc:5.3.31")
    compileOnly("org.springframework:spring-websocket:5.3.32")
    compileOnly("org.springframework:spring-jms:5.3.32")

    compileOnly("io.github.openfeign:feign-core:12.5")

    compileOnly("org.springframework.cloud:spring-cloud-openfeign-core:3.1.9")
    compileOnly("org.springframework.cloud:spring-cloud-context:3.1.8")
    compileOnly("org.springframework.boot:spring-boot-starter-test:$springBootVer")

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
