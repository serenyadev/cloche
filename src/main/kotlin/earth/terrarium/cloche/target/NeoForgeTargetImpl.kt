package earth.terrarium.cloche.target

import earth.terrarium.cloche.NEOFORGE
import org.gradle.api.artifacts.ExternalModuleDependency
import javax.inject.Inject

internal abstract class NeoForgeTargetImpl @Inject constructor(name: String): ForgeTargetImpl(name), NeoforgeTarget {
    final override val group
        get() = "net.neoforged"

    final override val artifact
        get() = "neoforge"

    final override val loaderAttributeName get() = NEOFORGE

    override val remapNamespace: String?
        get() = if (hasMappings) {
            super<ForgeTargetImpl>.remapNamespace
        } else {
            null
        }

    final override fun ExternalModuleDependency.userdevDependency(userdev: String) {
        capabilities {
            it.requireCapability("$group:$artifact-moddev-bundle")
        }
    }

    final override fun version(minecraftVersion: String, loaderVersion: String) =
        loaderVersion
}
