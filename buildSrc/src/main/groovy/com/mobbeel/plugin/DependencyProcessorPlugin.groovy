package com.mobbeel.plugin

import com.mobbeel.plugin.task.CopyDependenciesTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Dependency
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.JavaCompile

import org.gradle.jvm.tasks.Jar

class DependencyProcessorPlugin implements Plugin<Project> {

    Project project
    String archiveAarName
    String gradleVersion
    def extension

    @Override
    void apply(Project project) {
        this.project = project
        this.extension = project.extensions.create("aarPlugin", PluginExtension)

        project.parent.buildscript.getConfigurations().getByName("classpath").getDependencies().each { Dependency dep ->
            if (dep.group == "com.android.tools.build" && dep.name == "gradle") {
                gradleVersion = dep.version
            }
        }

        project.afterEvaluate {
            project.android.libraryVariants.all { variant ->
                variant.outputs.all {
                    archiveAarName = outputFileName
                }

                def copyTask = createBundleDependenciesTask(variant)

                String rsDirPath = "${copyTask.temporaryDir.path}/rs/"
                String rsCompiledDirPath = "${copyTask.temporaryDir.path}/rs-compiled/"
                String sourceAarPath = "${copyTask.temporaryDir.path}/${variant.name}/"

                def compileRsTask = R2ClassTask(variant, rsDirPath, rsCompiledDirPath)
                def rsJarTask = bundleRJarTask(variant, rsCompiledDirPath, sourceAarPath)
                def aarTask = bundleFinalAAR(variant, sourceAarPath)

                def assembleTask = project.tasks.findByPath("assemble${variant.name.capitalize()}")

                assembleTask.finalizedBy(copyTask)
                copyTask.finalizedBy(compileRsTask)
                compileRsTask.finalizedBy(rsJarTask)
                rsJarTask.finalizedBy(aarTask)
            }
        }
    }

    Task createBundleDependenciesTask(def variant) {
        String taskName = "copy${variant.name.capitalize()}Dependencies"
        return project.getTasks().create(taskName, CopyDependenciesTask.class, {
            it.packagesToInclude = extension.packagesToInclude
            it.includeInnerDependencies = extension.includeAllInnerDependencies
            it.dependencies = project.configurations.api.getDependencies()
            it.variantName = variant.name
            it.gradleVersion = this.gradleVersion
        })
    }

    Task R2ClassTask(def variant, String sourceDir, String destinationDir) {
        project.mkdir(destinationDir)

        def classpath
        if (gradleVersion.contains("3.2")) { // Versions 3.2.x
            classpath = project.files(project.projectDir.path +
                    "/build/intermediates/javac/${variant.name}/compile${variant.name.capitalize()}JavaWithJavac/classes")
        } else { // Versions 3.0.x and 3.1.x
            classpath = project.files(project.projectDir.path + "/build/intermediates/classes/${variant.name}")
        }

        String taskName = "compileRs${variant.name.capitalize()}"
        return project.getTasks().create(taskName, JavaCompile.class, {
            it.source = sourceDir
            it.sourceCompatibility = '1.8'
            it.targetCompatibility = '1.8'
            it.classpath = classpath
            it.destinationDir project.file(destinationDir)
        })
    }

    Task bundleRJarTask(def variant, String fromDir, String aarPath) {
        String taskName = "createRsJar${variant.name.capitalize()}"
        return project.getTasks().create(taskName, Jar.class, {
            it.from fromDir
            it.archiveName = "r-classes.jar"
            it.destinationDir project.file("${aarPath}/libs")
        })
    }

    Task bundleFinalAAR(def variant, String fromPath) {
        String taskName = "createZip${variant.name.capitalize()}"
        return project.getTasks().create(taskName, Zip.class, {
            it.from fromPath
            it.include "**"
            it.archiveName = archiveAarName
            it.destinationDir(project.file(project.projectDir.path + "/build/outputs/aar/"))
        })
    }

    static class PluginExtension {
        boolean includeAllInnerDependencies
        String[] packagesToInclude
    }
}
