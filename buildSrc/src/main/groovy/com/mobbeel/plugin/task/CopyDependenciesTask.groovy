package com.mobbeel.plugin.task

import groovy.xml.XmlUtil
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
    String gradleVersion
    String[] packagesToInclude = [""]

    @TaskAction
    def executeTask() {
        if (temporaryDir.exists()) {
            temporaryDir.deleteDir()
        }
        temporaryDir.mkdir()

        copyProjectBundles()
        analyzeDependencies()
    }

    def copyProjectBundles() {
        if (gradleVersion.contains("3.2")) { // Version 3.2.x
            project.copy {
                from "${project.projectDir.path}/build/intermediates/packaged-classes/"
                include "${variantName}/**"
                into temporaryDir.path
            }

            project.copy {
                from("${project.projectDir.path}/build/intermediates/res/symbol-table-with-package/${variantName}") {
                    include "package-aware-r.txt"
                    rename '(.*)', 'R.txt'
                }

                from("${project.projectDir.path}/build/intermediates/aapt_friendly_merged_manifests/" +
                        "${variantName}/process${variantName.capitalize()}Manifest/aapt/") {
                    include "AndroidManifest.xml"
                }

                into "${temporaryDir.path}/${variantName}"
            }
            processRsAwareFile(new File("${temporaryDir.path}/${variantName}/R.txt"))

            project.copy {
                from "${project.projectDir.path}/build/intermediates/packaged_res/${variantName}"
                include "**"
                into "${temporaryDir.path}/${variantName}/res"
            }

            project.copy {
                from "${project.projectDir.path}/build/intermediates/library_assets/${variantName}/packageDebugAssets/out/"
                include "**"
                into "${temporaryDir.path}/${variantName}/assets"
            }
            project.copy {
                def proguardDir = "/merge${variantName.capitalize()}ConsumerProguardFiles"
                def proguardPath = "${project.projectDir.path}/build/intermediates/consumer_proguard_file/"
                def path = proguardPath + "${variantName}" + proguardDir
                from path
                include "**"
                into "${temporaryDir.path}/${variantName}/"
            }
        } else if (gradleVersion.contains("3.1")) { // Version 3.1.x
            project.copy {
                from("${project.projectDir.path}/build/intermediates/packaged-classes/") {
                    include "${variantName}/**"
                }
                from("${project.projectDir.path}/build/intermediates/manifests/full/") {
                    include "${variantName}/**"
                    exclude "**/output.json"
                }
                into temporaryDir.path
            }

            project.copy {
                from"${project.projectDir.path}/build/intermediates/res/symbol-table-with-package/${variantName}"
                include "package-aware-r.txt"
                rename '(.*)', 'R.txt'
                into "${temporaryDir.path}/${variantName}"
            }
            processRsAwareFile(new File("${temporaryDir.path}/${variantName}/R.txt"))
            
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
            project.copy {
                from "${project.projectDir.path}/build/intermediates/publish-proguard/${variantName}"
                include "**"
                into "${temporaryDir.path}/${variantName}/"
            }
        } else { // Version 3.0.x
            project.copy {
                from "${project.projectDir.path}/build/intermediates/bundles/"
                from "${project.projectDir.path}/build/intermediates/manifests/full/"
                include "${variantName}/**"
                exclude "**/output.json"
                into temporaryDir.path
            }
        }
    }

    def analyzeDependencies() {
        dependencies.each { dependency ->
            def dependencyPath
            def archiveName

            if (dependency instanceof ProjectDependency) {
                String group = dependency.group
                Project dependencyProject
                if (group.indexOf(".") > 1) {
                    String subdirectory = group.substring(group.indexOf(".") + 1)
                    dependencyProject = project.parent.findProject(subdirectory + ":" + dependency.name)
                } else {
                    dependencyProject = project.parent.findProject(dependency.name)
                }

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
                processPomFile(file.path)
            } else {
                if (archiveName == null || file.name == archiveName) {
                    println "Artifact: " + file.name
                    if (file.name.endsWith(".aar")) {
                        processZipFile(file, dependency)
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

    def processZipFile(File aarFile, Dependency dependency) {
        String tempDirPath = "${temporaryDir.path}/${dependency.name}_zip"

        project.copy {
            from project.zipTree(aarFile.path)
            include "**/*"
            into tempDirPath
        }

        File tempFolder = new File(tempDirPath)

        project.copy {
            from "${tempFolder.path}"
            include "classes.jar"
            into "${temporaryDir.path}/${variantName}/libs"
            def jarName = getJarNameFromDependency(dependency)
            rename "classes.jar", jarName
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
            from "${tempFolder.path}/res"
            include "**/*"
            exclude "values/**"
            into "${temporaryDir.path}/${variantName}/res"
        }

        processValuesResource(tempFolder.path)
        processRsFile(tempFolder)

        tempFolder.deleteDir()
    }

    def getJarNameFromDependency(Dependency dependency) {
        def jarName = ""
        if (null != dependency.group) {
            jarName += dependency.group.toLowerCase() + "-"
        }
        jarName += dependency.name.toLowerCase()
        if(null != dependency.version && !dependency.version.equalsIgnoreCase('unspecified')) {
            jarName += "-" + dependency.version
        }
        jarName += ".jar"

        return jarName
    }
    
    def processRsAwareFile(File resAwareFile) {
        RandomAccessFile raf = new RandomAccessFile(resAwareFile, "rw")

        long writePosition = raf.getFilePointer()
        raf.readLine() // Move pointer to second line of file
        long readPosition = raf.getFilePointer()

        byte[] buffer = new byte[1024]
        int bytesInBuffer

        while (-1 != (bytesInBuffer = raf.read(buffer))) {
            raf.seek(writePosition)

            raf.write(buffer, 0, bytesInBuffer)
            readPosition += bytesInBuffer
            writePosition += bytesInBuffer

            raf.seek(readPosition)
        }
        raf.setLength(writePosition)

        if (gradleVersion.contains("3.2")) {
            String filePath = "${project.projectDir.path}/build/intermediates/symbols/${variantName}/R.txt"
            Scanner resourcesOriginal = new Scanner(new File(filePath))

            raf.seek(0) // Move pointer to first line

            String queryLine
            int offset = 0
            while ((queryLine = raf.readLine()) != null) {
                boolean match = false

                String line
                while (!match && resourcesOriginal.hasNextLine()) {
                    line = resourcesOriginal.nextLine()
                    if (line.contains(queryLine)) {
                        match = true
                    }
                }

                if (match && line != null) {
                    line += "\n"

                    println line
                    println line.getBytes()
                    println line.getBytes().length
                    println offset

                    byte[] data = line.getBytes()

                    raf.seek(offset)
                    raf.write(data, 0, data.length)
                    offset += data.length

                    raf.seek(offset + 1)
                } else {
                    raf.close()
                    throw new IllegalStateException("R.txt cannot generate")
                }
            }
        }

        raf.close()
    }

    def processRsFile(File tempFolder) {
        def mainManifestFile = project.android.sourceSets.main.manifest.srcFile
        def libPackageName = ""

        if (mainManifestFile.exists()) {
            libPackageName = new XmlParser().parse(mainManifestFile).@package
        }

        def manifestFile = new File("$tempFolder/AndroidManifest.xml")
        if (manifestFile.exists()) {
            def aarManifest = new XmlParser().parse(manifestFile)
            def aarPackageName = aarManifest.@package

            String packagePath = aarPackageName.replace('.', '/')

            // Generate the R.java file and map to current project's R.java
            // This will recreate the class file
            def rTxt = new File("$tempFolder/R.txt")
            def rMap = new ConfigObject()

            if (rTxt.exists()) {
                rTxt.eachLine { line ->
                    //noinspection GroovyUnusedAssignment
                    def (type, subclass, name, value) = line.tokenize(' ')
                    rMap[subclass].putAt(name, type)
                }
            }

            def sb = "package $aarPackageName;" << '\n' << '\n'
            sb << 'public final class R {' << '\n'

            rMap.each { subclass, values ->
                sb << "  public static final class $subclass {" << '\n'
                values.each { name, type ->
                    sb << "    public static $type $name = ${libPackageName}.R.${subclass}.${name};" << '\n'
                }
                sb << "    }" << '\n'
            }

            sb << '}' << '\n'

            new File("${temporaryDir.path}/rs/$packagePath").mkdirs()
            FileOutputStream outputStream = new FileOutputStream("${temporaryDir.path}/rs/$packagePath/R.java")
            outputStream.write(sb.toString().getBytes())
            outputStream.close()
        }
    }

    def processValuesResource(String tempFolder) {
        File valuesSourceFile = new File("${tempFolder}/res/values/values.xml")
        File valuesDestFile = new File("${temporaryDir.path}/${variantName}/res/values/values.xml")

        if (valuesSourceFile.exists()) {
            if (!valuesDestFile.exists()) {
                project.copy {
                    from "${tempFolder}/res"
                    include "values/*"
                    into "${temporaryDir.path}/${variantName}/res"
                }
            } else {
                def valuesSource = new XmlSlurper().parse(valuesSourceFile)
                def valuesDest = new XmlSlurper().parse(valuesDestFile)

                valuesSource.children().each {
                    valuesDest.appendNode(it)
                }

                FileOutputStream fileOutputStream = new FileOutputStream(valuesDestFile, false)
                byte[] myBytes = XmlUtil.serialize(valuesDest).getBytes("UTF-8")
                fileOutputStream.write(myBytes)
                fileOutputStream.close()
            }
        }
    }

    def copyArtifactFrom(String path) {
        project.copy {
            includeEmptyDirs false
            from path
            include "**/*.jar"
            into "${temporaryDir.path}/${variantName}/libs"
            rename '(.*)', '$1'.toLowerCase()
        }
    }

    def processPomFile(String pomPath) {
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
                            processPomFile(file.path)
                        } else {
                            if (!file.name.contains("sources") && !file.name.contains("javadoc")) {
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