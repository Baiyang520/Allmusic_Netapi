plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(files("libs/server-4.0.0-all.jar"))
    compileOnly("com.google.code.gson:gson:2.14.0")
    compileOnly("net.kyori:adventure-text-minimessage:4.26.1")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    jar {
        archiveFileName.set("netapi-${project.version}.jar")
        manifest {
            attributes["Implementation-Title"] = "AllMusic Bilibili NetApi"
            attributes["Implementation-Version"] = project.version
        }
    }

    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}
