package com.mobbeel.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.tasks.TaskAction

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CopyDependenciesBundle extends DefaultTask {

    DependencySet dependencies
    String variantName

    @TaskAction
    def executeBundleFatAAR() {
        if (temporaryDir.exists()) {
            temporaryDir.delete()
        }
        temporaryDir.mkdir()
        bundleResources()
        analyzeDependencies()
    }

    def bundleResources() {
        // Waiting for update to gradle android plugin 3.1.0
//        project.copy {
//            from project.projectDir.path + "/build/intermediates/packaged-classes/"
//            include "${variantName}/**"
//            into temporaryDir.path
//        }
//
//        project.copy {
//            from project.projectDir.path + "/build/intermediates/packagedAssets/"
//            include "${variantName}/**"
//            into temporaryDir.path
//        }

        project.copy {
            from project.projectDir.path + "/build/intermediates/bundles/"
            include "${variantName}/**"
            into temporaryDir.path
        }

        project.copy {
            from project.projectDir.path + "/build/intermediates/manifests/full/${variantName}"
            include "AndroidManifest.xml"
            into "${temporaryDir.path}/${variantName}"
        }
    }

    def analyzeDependencies() {
        dependencies.each { dependency ->
            if (dependency.group.equals(project.parent.name)) {
                println "Internal dependency detected -> " + dependency.name + ":" + dependency.version
                processInternalDependency(dependency)
            } else {
                println "External dependency detected -> " + dependency.group + ":" + dependency.name + ":" + dependency.version
                processExternalDependency(dependency)
            }
        }
    }

    /**
     * In this case dependency is outside from workspace, download from maven repository if file is
     * a jar directly move to lib/ folder and analyze pom file for detect another transitive dependency
     * @param dependency
     * @return
     */
    def processExternalDependency(Dependency dependency) {
        def jarLocation = project.gradle.getGradleUserHomeDir().path + "/caches/modules-2/files-2.1/"
        jarLocation += dependency.group + "/" + dependency.name + "/" + dependency.version + "/"

        getProject().fileTree(jarLocation).getFiles().each { file ->
            if (file.name.endsWith(".pom")) {
                println "POM: " + file.name
                fromPath(project, file.path)
            } else {
                println "Artifact: " + file.name
                if (file.name.endsWith(".aar")) {
                    processZipFile(file, dependency.name)
                } else {
                    copyArtifactTo(file.path)
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

        File file = new File("${temporaryDir.path}/.temp")

        project.copy {
            from "${file.path}"
            include "classes.jar"
            into "${temporaryDir.path}/${variantName}/libs"
            rename "classes.jar", "${dependencyName.toLowerCase()}.jar"
//          rename '(.*)-debug(.*)', '$1$2'
        }

        project.copy {
            from "${file.path}/jni/"
            include "**/*.so"
            into "${temporaryDir.path}/${variantName}/jni"
        }

        project.copy {
            from "${file.path}/assets"
            include "**/*"
            into "${temporaryDir.path}/${variantName}/assets"
        }

        file.deleteDir()
    }

    /**
     * In this case dependency is a internal project of workspace, then android plugin generate an
     * AAR but it's not possible embed AAR inside another AAR. Technical decision: Merge resources
     * of dependency AAR on main AAR, and move classes.jar to lib/ folder
     * @param dependency
     * @return
     */
    def processInternalDependency(Dependency dependency) {
        def buildPath = "${project.parent.projectDir.path}/${dependency.name}/build/intermediates"

        project.copy {
            from "${buildPath}/intermediate-jars/${variantName}"
            include "classes.jar"
            into "${temporaryDir.path}/${variantName}/libs"
            rename "classes.jar", "${dependency.name.toLowerCase()}.jar"
//          rename '(.*)-debug(.*)', '$1$2'
        }

        project.copy {
            from "${buildPath}/jniLibs/${variantName}"
            include "**/*.so"
            into "${temporaryDir.path}/${variantName}/jni"
        }

        project.copy {
            from "${buildPath}/bundles/${variantName}/assets"
            include "**/*"
            into "${temporaryDir.path}/${variantName}/assets"
        }
    }

    def addToZipFile(File source, ZipOutputStream zos) {
        if (source.isDirectory()) {
            String name = source.getPath().substring(pathFixed)
            if (!name.isEmpty()) {
                if (!name.endsWith("/")) {
                    name += "/"
                }
                ZipEntry entry = new ZipEntry(name)
                entry.setTime(source.lastModified())

                zos.putNextEntry(entry)
                zos.closeEntry()
            }
            for (File nestedFile: source.listFiles()) {
                addToZipFile(nestedFile, zos)
            }
        } else {
            ZipEntry entry = new ZipEntry(source.getPath().substring(pathFixed))
            entry.setTime(source.lastModified())
            zos.putNextEntry(entry)
            BufferedInputStream input = new BufferedInputStream(new FileInputStream(source))

            byte[] buffer = new byte[1024]
            while (true) {
                int count = input.read(buffer)
                if (count == -1) {
                    break
                }
                zos.write(buffer, 0, count)
            }

            zos.closeEntry()
            input.close()
        }
    }

    def copyArtifactTo(String path) {
        project.copy {
            includeEmptyDirs false
            from path
            include "**/*.jar"
            include "**/*.aar"
            into temporaryDir.path + "/${variantName}/libs"
        }
    }

    def fromPath(Project project, String pomPath) {
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

                if (it.groupId.text().contains("com.mobbeel") || it.groupId.text().contains("commons-codec")) {
                    subJarLocation += it.groupId.text() + "/" + it.artifactId.text() + "/" + version + "/"
                    project.fileTree(subJarLocation).getFiles().each { file ->

                        if (!file.name.contains("sources")) {
                            if (file.name.endsWith(".pom")) {
                                println "   /--> " + file.name
                                fromPath(project, file.path)
                            } else {
                                copyArtifactTo(file.path)
                            }
                        }
                    }
                } else {
                    println "        (Exclude for not Mobbeel dependency)"
                }
            }
        }
    }
}