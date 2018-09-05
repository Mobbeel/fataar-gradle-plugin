package com.mobbeel.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Zip

class DependencyProcessorPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        def extension = project.extensions.create("aarPlugin", PluginExtension)

        project.afterEvaluate {
            project.android.libraryVariants.all { variant ->
                def copyTask = project.getTasks().create("copy${variant.name.capitalize()}Dependencies", CopyDependenciesTask.class, {
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

    static class PluginExtension {
        boolean includeAllInnerDependencies
        String[] packagesToInclude
    }
}
