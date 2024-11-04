plugins {
    `java-library`
    `maven-publish`
    id("org.asciidoctor.jvm.convert") version "4.0.1"
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
    testCompileOnly("org.projectlombok:lombok:1.18.34")

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
    compileOnly("org.springframework.data:spring-data-commons:2.7.18")
    compileOnly("org.springframework.data:spring-data-jpa:2.7.18")
    compileOnly("org.springframework.data:spring-data-mongodb:3.4.18")
    compileOnly("javax.persistence:javax.persistence-api:2.2")
    compileOnly("org.hibernate:hibernate-core:5.6.15.Final")

    compileOnly("jakarta.jms:jakarta.jms-api:2.0.3")
    testImplementation("jakarta.jms:jakarta.jms-api:2.0.3")

    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

tasks.asciidoctor {
    dependsOn(project(":test:service1").tasks.build)
    baseDirFollowsSourceFile()
    outputOptions {
        backends("docbook")
    }
}

tasks.create<Exec>("pandoc") {
    dependsOn("asciidoctor")
    group = "documentation"
    commandLine = "pandoc -f docbook -t gfm $buildDir/docs/asciidoc/readme.xml -o $rootDir/README.md".split(" ")
}

tasks.build {
    if (properties["no-pandoc"] == null) {
        dependsOn("pandoc")
    }
}

//tasks.withType<JavaCompile> {
//    this.options.compilerArgs = listOf("-verbose")
//}

java {
    withSourcesJar()
    withJavadocJar()
    targetCompatibility = JavaVersion.VERSION_1_8
    sourceCompatibility = JavaVersion.VERSION_1_8
    modularity.inferModulePath.set(true)
}

publishing {
    publications {
        create<MavenPublication>("java") {
            pom {
                description.set("todo")
                url.set("https://github.com/m4gshm/spring-connections-visualizer")
                properties.put("maven.compiler.target", "${java.targetCompatibility}")
                properties.put("maven.compiler.source", "${java.sourceCompatibility}")
                developers {
                    developer {
                        id.set("m4gshm")
                        name.set("Bulgakov Alexander")
                        email.set("mfourgeneralsherman@gmail.com")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/m4gshm/spring-connections-visualizer.git")
                    developerConnection.set("scm:git:https://github.com/m4gshm/spring-connections-visualizer.git")
                    url.set("https://github.com/m4gshm/spring-connections-visualizer")
                }
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://github.com/m4gshm/spring-connections-visualizer?tab=MIT-1-ov-file#readme")
                    }
                }
            }
            from(components["java"])
        }
    }
    repositories {
        maven("file://$rootDir/../m4gshm.github.io/maven2") {
            name = "GithubMavenRepo"
        }
    }
}