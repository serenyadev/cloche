package earth.terrarium.cloche

import earth.terrarium.cloche.target.*
import earth.terrarium.cloche.target.fabric.FabricTargetImpl
import net.msrandom.minecraftcodev.accesswidener.accessWidenersConfigurationName
import net.msrandom.minecraftcodev.core.utils.lowerCamelCaseGradleName
import net.msrandom.minecraftcodev.mixins.mixinsConfigurationName
import net.msrandom.stubs.GenerateStubApi
import org.gradle.api.Project
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.JavaCompile

private const val GENERATE_JAVA_EXPECT_STUBS_OPTION = "generateExpectStubs"

internal class CommonInfo(
    val dependants: Set<MinecraftTargetInternal<*>>,
    val type: String?,
    val version: String?,
)

context(Project) internal fun createCommonTarget(
    commonTarget: CommonTargetInternal,
    commonInfo: Provider<CommonInfo>,
    onlyCommonOfType: Provider<Boolean>,
) {
    fun intersection(
        compilationName: String,
        compilations: Provider<List<TargetCompilation>>,
    ): FileCollection {
        val compilationName = compilationName.takeUnless { it == SourceSet.MAIN_SOURCE_SET_NAME }

        val name = lowerCamelCaseGradleName("create", commonTarget.name, compilationName, "api-stub")

        val generateStub = tasks.register(name, GenerateStubApi::class.java) {
            it.group = "minecraft-stubs"

            val jarName = if (compilationName == null) {
                commonTarget.classifierName
            } else {
                "${commonTarget.classifierName}-$compilationName"
            }

            it.apiFileName.set("$jarName-api-stub.jar")

            val configurations = project.configurations
            val objects = project.objects

            it.classpaths.set(compilations.map {
                it.map {
                    val classpath = objects.newInstance(GenerateStubApi.Classpath::class.java)

                    classpath.artifacts.set(
                        configurations.named(it.sourceSet.compileClasspathConfigurationName)
                            .map { it.incoming.artifacts })

                    classpath.extraFiles.from(it.finalMinecraftFile)
                    classpath.extraFiles.from(it.extraClasspathFiles)

                    classpath
                }
            })

            it.dependsOn(files(compilations.map {
                it.map {
                    configurations.named(it.sourceSet.compileClasspathConfigurationName)
                }
            }))
        }

        return fileTree(generateStub.flatMap(GenerateStubApi::outputDirectory)).builtBy(generateStub)
    }

    fun dependencyHolder(compilation: CommonCompilation) = project.configurations.maybeCreate(
        lowerCamelCaseGradleName(
            commonTarget.featureName,
            compilation.collapsedName,
            "intersectionDependencies",
        )
    ).apply {
        isCanBeConsumed = false
        isCanBeResolved = false
    }

    fun addCompilation(
        compilation: CommonCompilation,
        variant: PublicationSide,
        intersection: FileCollection,
    ) {
        val sourceSet = with(commonTarget) {
            compilation.sourceSet
        }

        project.createCompilationVariants(
            compilation,
            sourceSet,
            commonTarget.name == COMMON || commonTarget.publish
        )

        configureSourceSet(sourceSet, commonTarget, compilation, false)

        project.components.named("java") { java ->
            java as AdhocComponentWithVariants

            java.addVariantsFromConfiguration(project.configurations.getByName(sourceSet.runtimeElementsConfigurationName)) { variant ->
                // Common compilations are not runnable.
                variant.skip()
            }
        }

        val dependencyHolder = dependencyHolder(compilation)

        project.dependencies.add(dependencyHolder.name, intersection)

        compilation.attributes {
            it.attribute(SIDE_ATTRIBUTE, variant)

            // afterEvaluate needed as the attributes existing(not just their values) depend on configurable info
            afterEvaluate { project ->
                val commonInfo = commonInfo.get()

                if (commonInfo.type != null) {
                    it.attribute(CommonTargetAttributes.TYPE, commonInfo.type)
                }

                if (commonInfo.version != null) {
                    it.attribute(TargetAttributes.MINECRAFT_VERSION, commonInfo.version)
                }

                if (!onlyCommonOfType.get() && commonTarget.name != COMMON && !commonTarget.publish) {
                    it.attribute(CommonTargetAttributes.NAME, commonTarget.name)
                }
            }
        }

        project.configurations.named(sourceSet.compileClasspathConfigurationName) {
            it.extendsFrom(dependencyHolder)

            it.attributes(compilation::attributes)
        }

        for (name in listOf(
            sourceSet.apiElementsConfigurationName,
            sourceSet.runtimeElementsConfigurationName,
            sourceSet.javadocElementsConfigurationName,
            sourceSet.sourcesElementsConfigurationName
        )) {
            project.configurations.findByName(name)?.attributes(compilation::attributes)
        }

        project.dependencies.add(
            sourceSet.compileOnlyConfigurationName,
            "net.msrandom:java-expect-actual-annotations:1.0.0"
        )

        project.dependencies.add(
            sourceSet.annotationProcessorConfigurationName,
            JAVA_EXPECT_ACTUAL_ANNOTATION_PROCESSOR
        )
        project.dependencies.add(sourceSet.accessWidenersConfigurationName, compilation.accessWideners)
        project.dependencies.add(sourceSet.mixinsConfigurationName, compilation.mixins)

        tasks.named(sourceSet.compileJavaTaskName, JavaCompile::class.java) {
            it.options.compilerArgs.add("-A$GENERATE_JAVA_EXPECT_STUBS_OPTION")
        }

        plugins.withId("org.jetbrains.kotlin.jvm") {
            project.dependencies.add(
                sourceSet.compileOnlyConfigurationName,
                "net.msrandom:kmp-stub-annotations:1.0.0",
            )
        }
    }

    fun add(
        compilation: CommonTopLevelCompilation,
        dataGetter: (MinecraftTargetInternal<*>) -> TargetCompilation,
        testGetter: (MinecraftTargetInternal<*>) -> TargetCompilation,
        variant: PublicationSide,
        intersection: FileCollection,
    ) {
        addCompilation(compilation, variant, intersection)

        if (compilation.name != SourceSet.MAIN_SOURCE_SET_NAME) {
            compilation.addClasspathDependency(commonTarget.main)
        }

        compilation.data.onConfigured {
            addCompilation(
                it,
                variant,
                intersection(
                    it.name,
                    commonInfo.map {
                        it.dependants.map(dataGetter)
                    }
                ),
            )

            it.addClasspathDependency(compilation)
            it.addClasspathDependency(commonTarget.main)

            if (compilation.name != SourceSet.MAIN_SOURCE_SET_NAME) {
                commonTarget.main.data.onConfigured { data ->
                    it.addClasspathDependency(data)
                }
            }
        }

        compilation.test.onConfigured {
            addCompilation(
                it,
                variant,
                intersection(
                    it.name,
                    commonInfo.map {
                        it.dependants.map(testGetter)
                    }
                ),
            )

            it.addClasspathDependency(compilation)
            it.addClasspathDependency(commonTarget.main)

            if (compilation.name != SourceSet.MAIN_SOURCE_SET_NAME) {
                commonTarget.main.test.onConfigured { test ->
                    it.addClasspathDependency(test)
                }
            }
        }
    }

    add(
        commonTarget.main,
        { it.data.internalValue ?: it.main },
        { it.test.internalValue ?: it.main },
        PublicationSide.Common,
        intersection(
            commonTarget.main.name,
            commonInfo.map { it.dependants.map(MinecraftTargetInternal<*>::main) },
        ),
    )

    commonTarget.client.onConfigured {
        add(
            it,
            {
                (it as? FabricTargetImpl)?.client?.internalValue?.let {
                    it.data.internalValue ?: it
                }
                    ?: it.data.internalValue
                    ?: it.main
            },
            {
                (it as? FabricTargetImpl)?.client?.internalValue?.let {
                    it.test.internalValue ?: it
                }
                    ?: it.test.internalValue
                    ?: it.main
            },
            PublicationSide.Client,
            intersection(it.name, commonInfo.map {
                it.dependants.map {
                    (it as? FabricTargetImpl)?.client?.internalValue as? TargetCompilation ?: it.main
                }
            }),
        )
    }
}
