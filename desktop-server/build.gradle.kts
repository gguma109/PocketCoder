plugins { kotlin("jvm") version "2.0.21" }
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
kotlin.sourceSets.main { kotlin.srcDir("../shared/src/main/kotlin") }
tasks.register<Jar>("serverJar") {
    dependsOn(tasks.classes)
    archiveFileName.set("pocketcoder-server.jar")
    destinationDirectory.set(layout.projectDirectory)
    manifest { attributes["Main-Class"] = "RemoteServerKt" }
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
tasks.register<JavaExec>("selfTest") {
    dependsOn("serverJar")
    classpath = files(layout.projectDirectory.file("pocketcoder-server.jar"))
    mainClass.set("RemoteServerKt")
    args("--self-test")
}
