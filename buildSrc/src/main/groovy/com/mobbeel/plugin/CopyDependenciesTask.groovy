package com.mobbeel.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.SelfResolvingDependency
import org.gradle.api.tasks.TaskAction

class CopyDependenciesTask extends DefaultTask {

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

        boolean oldStructureUsed = true
        project.getParent().buildscript.getConfigurations().getByName("classpath").getDependencies().each { Dependency dep ->
            if (dep.name == "gradle" && dep.version.contains("3.1")) {
                oldStructureUsed = false
            }
        }

        copyProjectBundles(oldStructureUsed)
        analyzeDependencies()
    }

    def copyProjectBundles(boolean oldStructure) {
        if (oldStructure) {
            project.copy {
                from "${project.projectDir.path}/build/intermediates/bundles/"
                from "${project.projectDir.path}/build/intermediates/manifests/full/"
                include "${variantName}/**"
                exclude "output.json"
                into temporaryDir.path
            }
        } else {
            project.copy {
                from("${project.projectDir.path}/build/intermediates/packaged-classes/") {
                    include "${variantName}/**"
                }
                from("${project.projectDir.path}/build/intermediates/manifests/full/") {
                    include "${variantName}/**"
                    exclude "**/output.json"
                }
                from("${project.projectDir.path}/build/intermediates/attr/") {
                    include "*.txt"
                }
                into temporaryDir.path
            }
            project.copy {
                from "${project.projectDir.path}/build/intermediates/packaged-aidl/${variantName}"
                include "**"
                into "${temporaryDir.path}/${variantName}/aidl"
            }
            project.copy {
                from "${project.projectDir.path}/build/intermediates/packaged_res/${variantName}"
                include "**"
                into "${temporaryDir.path}/${variantName}/res"
            }
            project.copy {
                from "${project.projectDir.path}/build/intermediates/packagedAssets/${variantName}"
                include "**"
                into "${temporaryDir.path}/${variantName}/assets"
            }
        }
    }

    def analyzeDependencies() {
        dependencies.each { dependency ->
            def dependencyPath
            def archiveName

            if (dependency instanceof ProjectDependency) {
                Project dependencyProject = project.parent.findProject(dependency.name)
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

                processDependency(dependency, archiveName, dependencyPath)
            } else if (dependency instanceof ExternalModuleDependency) {
                println "External dependency detected -> " + dependency.group + ":" + dependency.name + ":" + dependency.version
                dependencyPath = project.gradle.getGradleUserHomeDir().path + "/caches/modules-2/files-2.1/"
                dependencyPath += dependency.group + "/" + dependency.name + "/" + dependency.version + "/"

                processDependency(dependency, archiveName, dependencyPath)
            } else if (dependency instanceof SelfResolvingDependency) {
                SelfResolvingDependency resolvingDependency = (SelfResolvingDependency) dependency
                println "File tree: " + resolvingDependency.properties.buildDependencies
//                copyArtifactFrom("${project.projectDir}/libs")
                println()
            } else {
                println "Not recognize type of dependency for " + dependency
                println()
            }
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
                            copyArtifactFrom(file.path)
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
            into "${temporaryDir.path}/temp_zip"
        }

        File tempFolder = new File("${temporaryDir.path}/temp_zip")

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

        project.copy {
            from "${tempFolder.path}/"
            include "**/*.txt"
            into "${temporaryDir.path}/${variantName}/"
        }

        project.copy {
            from "${tempFolder.path}/"
            include "annotations.zip"
            into "${temporaryDir.path}/${variantName}/"
        }

        tempFolder.deleteDir()
    }

    def copyArtifactFrom(String path) {
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
                                copyArtifactFrom(file.path)
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