package dev.kairo.myra.brain.net

import dev.kairo.myra.brain.BrainEnv
import dev.kairo.myra.brain.BrainTool
import dev.kairo.myra.brain.ToolModule

/**
 * The brain's links to the outside (the paired PC, mail, social posting, YouTube upload, research).
 * Not ported into the app yet: this module only reports that, so the app's status line stays honest.
 * Web search already works through the app's own `web_search` and `deep_research` tools.
 */
class NetModule(private val env: BrainEnv) : ToolModule {
    fun start() {}
    fun stop() {}
    fun pcStatus(): String = "not paired"
    override fun tools(): List<BrainTool> = emptyList()
}
