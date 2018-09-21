package com.mobbeel.plugin

import com.mobbeel.plugin.task.CopyDependenciesTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.JavaCompile

import org.gradle.jvm.tasks.Jar

class DependencyProcessorPlugin implements Plugin<Project> {

    Project project
    String archiveAarName
    def extension

    @Override
    void apply(Project project) {
        this.project = project
        this.extension = project.extensions.create("aarPlugin", PluginExtension)

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
        })
    }

    Task R2ClassTask(def variant, String sourceDir, String destinationDir) {
        project.mkdir(destinationDir)

        String taskName = "compileRs${variant.name.capitalize()}"
        return project.getTasks().create(taskName, JavaCompile.class, {
            it.source = sourceDir
            it.sourceCompatibility = '1.7'
            it.targetCompatibility = '1.7'
            it.classpath = project.files(project.projectDir.path + "/build/intermediates/classes/" + variant.name)
            it.destinationDir project.file(destinationDir)
        })
    }

    Task bundleRJarTask(def variant, String fromDir, String aarPath) {
        String taskName = "createRsJar${variant.name.capitalize()}"
        return project.getTasks().create(taskName, Jar.class, {
            it.from fromDir
            it.archiveName = "rs.jar"
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