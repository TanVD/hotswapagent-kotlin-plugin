import groovy.lang.GroovyObject

group = "org.hotswapagent"
version = "1.3.0"

plugins {
    java apply true
    `maven-publish` apply true
    idea apply true
}

repositories {
    jcenter()
}

val sourceJar = task<Jar>("sourceJar") {
    classifier = "sources"
    from(sourceSets["main"].allSource.sourceDirectories)
}

dependencies {
    compile("org.hotswapagent", "hotswap-agent-core", "1.3.0")
}

publishing {
    publications.create("mavenJava", MavenPublication::class) {
        artifactId = rootProject.name
        version = rootProject.version.toString()
        group = rootProject.group

        from(components["java"])
        artifact(sourceJar)
    }
}

idea {
    module {
        inheritOutputDirs = true
        excludeDirs
        excludeDirs = files(".idea", "gradle", "out", ".gradle").files
    }
}

task<Wrapper>("wrapper") {
    gradleVersion = "4.10"
    distributionUrl = "https://cache-redirector.jetbrains.com/services.gradle.org/distributions/gradle-$gradleVersion-all.zip"
}