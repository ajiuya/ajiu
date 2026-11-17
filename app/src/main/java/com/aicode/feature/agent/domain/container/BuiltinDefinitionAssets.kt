package com.aicode.feature.agent.domain.container

import android.content.Context

/** Only APK resources are read here, never installed user definitions or credentials. */
internal object BuiltinDefinitionAssets {
    fun agents(context: Context): Map<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
        context.assets.list("agents").orEmpty().sorted().forEach { name ->
            BuiltinDefinitionPaths.component(name)
            put(name, context.assets.open("agents/$name").use { it.readBytes() })
        }
        BuiltinDefinitionPaths.validate(this, emptyMap())
    }

    fun skills(context: Context): Map<String, Map<String, ByteArray>> = skillManifest(
        listAssets = { context.assets.list(it).orEmpty().toList() },
        readAsset = { context.assets.open(it).use { stream -> stream.readBytes() } }
    )

    internal fun skillManifest(
        listAssets: (String) -> List<String>,
        readAsset: (String) -> ByteArray
    ): Map<String, Map<String, ByteArray>> = linkedMapOf<String, Map<String, ByteArray>>().apply {
        listAssets("skills").sorted().forEach { name ->
            BuiltinDefinitionPaths.component(name)
            val files = linkedMapOf<String, ByteArray>()
            fun visit(assetPath: String, prefix: String) {
                listAssets(assetPath).sorted().forEach { entry ->
                    BuiltinDefinitionPaths.component(entry)
                    val child = "$assetPath/$entry"
                    val relative = if (prefix.isEmpty()) entry else "$prefix/$entry"
                    if (listAssets(child).isNotEmpty()) visit(child, relative)
                    else files[relative] = readAsset(child)
                }
            }
            visit("skills/$name", "")
            put(name, files)
        }
        // A failed read must not silently offer a truncated package, including companion scripts/docs.
        BuiltinDefinitionPaths.validate(emptyMap(), this)
    }
}
