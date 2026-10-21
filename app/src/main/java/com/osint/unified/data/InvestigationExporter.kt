package com.osint.unified.data

import android.content.Context
import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.Observable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat(val ext: String, val mime: String) {
    JSON("json", "application/json"),
    CSV("csv", "text/csv"),
    DOSSIER("txt", "text/plain")
}

/**
 * Serializes an investigation's results to a file in the app cache for sharing.
 */
class InvestigationExporter(private val appContext: Context) {

    private val prettyJson = Json { prettyPrint = true }
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    suspend fun export(
        observable: Observable,
        results: List<ResultRow>,
        format: ExportFormat
    ): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.cacheDir, "exports").apply { mkdirs() }
        val base = observable.raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40).ifBlank { "investigation" }
        val file = File(dir, "osint_${base}_${stamp.format(Date())}.${format.ext}")
        val content = when (format) {
            ExportFormat.JSON -> toJson(observable, results)
            ExportFormat.CSV -> toCsv(results)
            ExportFormat.DOSSIER -> toDossier(observable, results)
        }
        file.writeText(content)
        file
    }

    private fun toJson(observable: Observable, results: List<ResultRow>): String {
        val doc = buildJsonObject {
            put("observable", observable.raw)
            put("type", observable.typeLabel)
            put("generatedAt", System.currentTimeMillis())
            putJsonArray("results") {
                results.forEach { r ->
                    addJsonObject {
                        put("tool", r.toolName)
                        put("toolId", r.toolId)
                        put("status", r.status)
                        put("input", r.input)
                        put("startedAt", r.startedAt)
                        r.finishedAt?.let { put("finishedAt", it) }
                        if (r.status == "error") put("error", r.errorMessage ?: "")
                        else put("output", r.rawOutput)
                    }
                }
            }
        }
        return prettyJson.encodeToString(JsonElement.serializer(), doc)
    }

    private fun toCsv(results: List<ResultRow>): String {
        val sb = StringBuilder("tool,status,input,started,finished,output_or_error\n")
        results.forEach { r ->
            val payload = if (r.status == "error") (r.errorMessage ?: "") else r.rawOutput
            sb.append(
                listOf(
                    r.toolName, r.status, r.input,
                    r.startedAt.toString(), (r.finishedAt ?: 0L).toString(), payload
                ).joinToString(",") { csv(it) }
            ).append("\n")
        }
        return sb.toString()
    }

    private fun toDossier(observable: Observable, results: List<ResultRow>): String = buildString {
        append("OSINT INVESTIGATION DOSSIER\n")
        append("===========================\n")
        append("Observable : ${observable.raw}\n")
        append("Type       : ${observable.typeLabel}\n")
        append("Generated  : ${Date()}\n")
        append("Tools run  : ${results.size}\n\n")
        results.forEach { r ->
            append("--- ${r.toolName} [${r.status}] ---\n")
            if (r.status == "error") append("Error: ${r.errorMessage}\n")
            else append(r.rawOutput.ifBlank { "(no output)" }).append("\n")
            append("\n")
        }
    }

    private fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
