package com.osint.unified.domain

enum class ToolCategory { SOCMINT, NETINT, GEOINT, FININT, FORENSICS }

/**
 * Where a tool runs:
 *  - LOCAL: ARM64 binary / embedded runtime shipped with the app (Phase 2+)
 *  - CLOUD: executed via the backend REST API (heavy or sensor-bound tools)
 */
enum class ExecutionMode { LOCAL, CLOUD }

data class Tool(
    val id: String,
    val name: String,
    val category: ToolCategory,
    val inputType: String,
    val outputFormat: String,
    val executionMode: ExecutionMode,
    val description: String,
    val repoUrl: String,
    val timeoutMs: Long = 30_000L
)
