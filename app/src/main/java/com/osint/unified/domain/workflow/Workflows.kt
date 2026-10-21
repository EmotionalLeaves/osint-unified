package com.osint.unified.domain.workflow

import com.osint.unified.domain.Observable
import com.osint.unified.domain.ResultFormatter

/**
 * Chained tool pipelines. The headline chain (the design's "Workflow A") is
 * domain recon: discover subdomains with Subfinder, then probe the discovered
 * hosts with httpx — piping the output of step 1 into step 2.
 */
object Workflows {

    /** A single step. [fromPreviousHosts] = feed the prior step's hosts as input. */
    data class Step(val toolId: String, val fromPreviousHosts: Boolean)

    data class Workflow(val id: String, val name: String, val steps: List<Step>)

    val DOMAIN_RECON = Workflow(
        id = "domain_recon",
        name = "Domain recon",
        steps = listOf(
            Step("subfinder", fromPreviousHosts = false),
            Step("httpx", fromPreviousHosts = true)
        )
    )

    fun forObservable(observable: Observable): Workflow? = when (observable) {
        is Observable.Domain -> DOMAIN_RECON
        else -> null
    }

    /** Pulls host-like lines out of a step's raw output to feed the next step. */
    fun extractHosts(toolId: String, raw: String): List<String> = when (toolId) {
        "subfinder" -> ResultFormatter.format("subfinder", raw).lines.take(30)
        else -> emptyList()
    }
}
