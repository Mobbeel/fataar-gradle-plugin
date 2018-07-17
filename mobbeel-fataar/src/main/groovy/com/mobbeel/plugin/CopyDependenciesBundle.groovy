package com.mobbeel.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.tasks.TaskAction

class CopyDependenciesBundle extends DefaultTask {

    Boolean includeInnerDependencies
    DependencySet dependencies
    String variantName
    String[] packagesToInclude = [""]

    @TaskAction
    def executeBundleFatAAR() {
        if (temporaryDir.exists()) {
            temporaryDir.deleteDir()
        }
        temporaryDir.mkdir()

        copyProjectBundles()
        analyzeDependencies()
    }

    def copyProjectBundles() {
        project.copy {
            from "${project.projectDir.path}/build/intermediates/bundles/"
            include "${variantName}/**"
            into temporaryDir.path
        }

        project.copy {
            from "${project.projectDir.path}/build/intermediates/manifests/full/${variantName}"
            include "AndroidManifest.xml"
            into "${temporaryDir.path}/${variantName}"
        }
    }

    def analyzeDependencies() {
        dependencies.each { dependency ->
            Project dependencyProject = project.parent.findProject(dependency.name)
            def dependencyPath
            def archiveName

            if (dependencyProject != null) {
                if (dependencyProject.plugins.hasPlugin('java-library')) {
                    println "Internal java dependency detected -> " + dependency.name
                    archiveName = dependencyProject.jar.archiveName
                    dependencyPath = "${dependencyProject.buildDir}/libs/"
                } else {
                    println "Internal android dependency detected -> " + dependency.name
                    dependencyProject.android.libraryVariants.all {
                        if (it.name == variantName) {
                            it.outputs.all { archiveName = outputFileName }
                        }
                    }
                    dependencyPath = "${dependencyProject.buildDir}/outputs/aar/"
                }
            } else {
                println "External dependency detected -> " + dependency.group + ":" + dependency.name + ":" + dependency.version
                dependencyPath = project.gradle.getGradleUserHomeDir().path + "/caches/modules-2/files-2.1/"
                dependencyPath += dependency.group + "/" + dependency.name + "/" + dependency.version + "/"
            }

            processDependency(dependency, archiveName, dependencyPath)
        }
    }

    /**
     * In this case dependency is outside from workspace, download from maven repository if file is
     * a jar directly move to lib/ folder and analyze pom file for detect another transitive dependency
     * @param dependency
     * @return
     */
    def processDependency(Dependency dependency, String archiveName, String dependencyPath) {
        project.fileTree(dependencyPath).getFiles().each { file ->
            if (file.name.endsWith(".pom")) {
                println "POM: " + file.name
                fromPath(file.path)
            } else {
                if (archiveName == null || file.name == archiveName) {
                    println "Artifact: " + file.name
                    if (file.name.endsWith(".aar")) {
                        processZipFile(file, dependency.name)
                    } else if (file.name.endsWith(".jar")) {
                        if (!file.name.contains("sources")) {
                            copyArtifactTo(file.path)
                        } else {
                            println "   |--> Exclude for source jar"
                        }
                    }
                }
            }
        }
        println()
    }

    def processZipFile(File aarFile, String dependencyName) {
        project.copy {
            from project.zipTree(aarFile.path)
            include "**/*"
            into "${temporaryDir.path}/.temp"
        }

        File tempFolder = new File("${temporaryDir.path}/.temp")

        project.copy {
            from "${tempFolder.path}"
            include "classes.jar"
            into "${temporaryDir.path}/${variantName}/libs"
            rename "classes.jar", "${dependencyName.toLowerCase()}.jar"
        }

        project.copy {
            from "${tempFolder.path}/libs"
            include "**/*.jar"
            into "${temporaryDir.path}/${variantName}/libs"
        }

        project.copy {
            from "${tempFolder.path}/jni"
            include "**/*.so"
            into "${temporaryDir.path}/${variantName}/jni"
        }

        project.copy {
            from "${tempFolder.path}/assets"
            include "**/*"
            into "${temporaryDir.path}/${variantName}/assets"
        }

        tempFolder.deleteDir()
    }

    def copyArtifactTo(String path) {
        project.copy {
            includeEmptyDirs false
            from path
            include "**/*.jar"
            include "**/*.aar"
            into temporaryDir.path + "/${variantName}/libs"
            rename '(.*)', '$1'.toLowerCase()
        }
    }

    def fromPath(String pomPath) {
        def pom = new XmlSlurper().parse(new File(pomPath))
        pom.dependencies.children().each {
            def subJarLocation = project.gradle.getGradleUserHomeDir().path + "/caches/modules-2/files-2.1/"
            if (!it.scope.text().equals("test") && !it.scope.text().equals("provided")) {
                String version = it.version.text()
                if (version.startsWith("\${") && version.endsWith("}")) {
                    pom.properties.children().each {
                        if (version.contains(it.name())) {
                            version = it.text()
                        }
                    }
                }

                println "   |--> Inner dependency: " +  it.groupId.text() + ":" + it.artifactId.text() + ":" + version

                if (includeInnerDependencies || it.groupId.text() in packagesToInclude) {
                    subJarLocation += it.groupId.text() + "/" + it.artifactId.text() + "/" + version + "/"
                    project.fileTree(subJarLocation).getFiles().each { file ->
                        if (file.name.endsWith(".pom")) {
                            println "   /--> " + file.name
                            fromPath(file.path)
                        } else {
                            if (!file.name.contains("sources")) {
                                copyArtifactTo(file.path)
                            }
                        }
                    }
                } else {
                    println "        (Exclude inner dependency)"
                }
            }
        }
    }
}