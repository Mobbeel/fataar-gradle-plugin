package com.mobbeel.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.tasks.bundling.Zip

class DependencyProcessorPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        checkAndroidPluginVersion(project)

        def extension = project.extensions.create("fatAARConfig", PluginExtension)

        project.afterEvaluate {
            project.android.libraryVariants.all { variant ->
                def copyTask = project.getTasks().create("copy${variant.name.capitalize()}Dependencies", CopyDependenciesBundle.class, {
                    it.packagesToInclude = extension.packagesToInclude
                    it.includeInnerDependencies = extension.includeAllInnerDependencies
                    it.dependencies = project.configurations.api.getDependencies()
                    it.variantName = variant.name
                })

                String fileOutputName
                variant.outputs.all {
                    fileOutputName = outputFileName
                }

                def aarTask = project.getTasks().create("createZip${variant.name.capitalize()}", Zip.class, {
                    it.from copyTask.temporaryDir.path + "/${variant.name}/"
                    it.include "**"
                    it.archiveName = fileOutputName
                    it.destinationDir(project.file(project.projectDir.path + "/build/outputs/aar/"))
                })

                def assembleTask = project.tasks.findByPath("assemble${variant.name.capitalize()}")
                assembleTask.finalizedBy(copyTask)
                copyTask.finalizedBy(aarTask)
            }
        }
    }

    private static void checkAndroidPluginVersion(Project project) {
//        println "Android plugin version: " + project.android.version
    }

    private static void checkAndroidPlugin(Project project) {
        if (!project.plugins.hasPlugin('com.android.library')) {
            throw new ProjectConfigurationException('fataar plugin must be applied in project that' +
                    ' has android library plugin!', null)
        }
    }

    static class PluginExtension {
        boolean includeAllInnerDependencies
        String[] packagesToInclude
    }
}
