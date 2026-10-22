package com.osint.unified.domain

enum class ToolCategory { SOCMINT, NETINT, GEOINT, FININT, FORENSICS }

/**
 * Where a tool runs:
 *  - LOCAL:  ARM64 binary / embedded runtime shipped with the app (Phase 2+)
 *  - CLOUD:  executed via the backend REST API (heavy or credentialed tools)
 *  - DIRECT: a public API the app calls itself — no backend needed
 */
enum class ExecutionMode { LOCAL, CLOUD, DIRECT }

data class Tool(
    val id: String,
    val name: String,
    val category: ToolCategory,
    val inputType: String,
    val outputFormat: String,
    val executionMode: ExecutionMode,
    val description: String,
    val repoUrl: String,
    val timeoutMs: Long = 30_000L,
    /**
     * Whether running this tool connects to the target's own infrastructure.
     *
     * The distinction an investigator cares about most: a passive lookup
     * asks a third party what it already knows, while an active one
     * announces your interest to the person you are investigating, from
     * your own address, in their logs. The app used to fire both the
     * moment you pressed search.
     *
     * Asking a registry about a domain is passive. Resolving its hosts,
     * fetching its files or probing its ports is not.
     */
    val touchesTarget: Boolean = false
)
