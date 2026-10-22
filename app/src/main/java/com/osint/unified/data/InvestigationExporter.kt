package com.osint.unified.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.EntityExtractor
import com.osint.unified.domain.Findings
import com.osint.unified.domain.IntelSummary
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
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat(val ext: String, val mime: String) {
    JSON("json", "application/json"),
    CSV("csv", "text/csv"),
    DOSSIER("txt", "text/plain"),
    PDF("pdf", "application/pdf")
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
        when (format) {
            ExportFormat.JSON -> file.writeText(toJson(observable, results))
            ExportFormat.CSV -> file.writeText(toCsv(results))
            ExportFormat.DOSSIER -> file.writeText(toDossier(observable, results))
            ExportFormat.PDF -> writePdf(file, observable, results)
        }
        file
    }

    /** Renders the dossier text into a paginated A4 PDF. */
    private fun writePdf(file: File, observable: Observable, results: List<ResultRow>) {
        val text = toDossier(observable, results)
        val document = PdfDocument()
        val paint = Paint().apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            color = Color.BLACK
        }
        val pageWidth = 595
        val pageHeight = 842
        val margin = 40f
        val lineHeight = 14f
        val maxWidth = pageWidth - 2 * margin

        var pageNum = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        var canvas = page.canvas
        var y = margin + lineHeight

        fun newPage() {
            document.finishPage(page)
            pageNum++
            page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
            canvas = page.canvas
            y = margin + lineHeight
        }

        text.split("\n").forEach { rawLine ->
            wrapLine(rawLine, paint, maxWidth).forEach { line ->
                if (y > pageHeight - margin) newPage()
                canvas.drawText(line, margin, y, paint)
                y += lineHeight
            }
        }

        document.finishPage(page)
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
    }

    /** Hard-wraps a line to fit the page width (monospace, char-based). */
    private fun wrapLine(line: String, paint: Paint, maxWidth: Float): List<String> {
        if (line.isEmpty()) return listOf("")
        val out = mutableListOf<String>()
        var current = StringBuilder()
        for (ch in line) {
            current.append(ch)
            if (paint.measureText(current.toString()) > maxWidth) {
                current.deleteCharAt(current.length - 1)
                out.add(current.toString())
                current = StringBuilder().append(ch)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
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

    /**
     * The report someone actually reads.
     *
     * A flat dump of every tool in the order they finished buries the two
     * or three sources that found something among the forty that did not,
     * and gives no account of why half the entries are about an identifier
     * nobody typed. So the report leads with the synthesis, then what was
     * found, then what the second pass chased and why, and only then the
     * roll call of sources that answered no — which belongs in a dossier,
     * as a record of where someone has already looked, but not at the top.
     */
    private fun toDossier(observable: Observable, results: List<ResultRow>): String = buildString {
        val seed = observable.raw.trim().lowercase()
        val sorted = Findings.sort(results)
        val facts = IntelSummary.synthesize(results)
        val pivots = EntityExtractor.extract(
            results,
            observable.raw,
            seedIsPlace = observable is Observable.Coordinate
        )
        val (direct, derived) = sorted.hits.partition { it.input.trim().lowercase() == seed }

        append("OSINT INVESTIGATION DOSSIER\n")
        append("===========================\n")
        append("Observable : ${observable.raw}\n")
        append("Type       : ${observable.typeLabel}\n")
        append("Generated  : ${Date()}\n")
        append("Sources    : ${results.size} queried — ${sorted.hits.size} returned intel, ")
        append("${sorted.empty.size} found nothing, ${sorted.failed.size} could not be reached\n")

        if (facts.isNotEmpty()) {
            append("\nSUMMARY\n-------\n")
            facts.forEach { append("${it.label.padEnd(12)} ${it.value}\n") }
        }

        if (pivots.isNotEmpty()) {
            append("\nIDENTIFIERS DISCOVERED\n----------------------\n")
            pivots.groupBy { it.kind }.forEach { (kind, items) ->
                append("${kind.padEnd(8)} ${items.joinToString(", ") { it.value }}\n")
            }
        }

        if (direct.isNotEmpty()) {
            append("\nFINDINGS\n--------\n")
            direct.forEach { appendResult(it) }
        }

        if (derived.isNotEmpty()) {
            append("\nFOLLOWED UP\n-----------\n")
            append("Looked up because the first pass named them, not because they ")
            append("were the target.\n")
            derived.groupBy { it.input }.forEach { (via, rows) ->
                append("\n◂ $via\n")
                rows.forEach { appendResult(it) }
            }
        }

        if (sorted.empty.isNotEmpty()) {
            append("\nSOURCES THAT FOUND NOTHING (${sorted.empty.size})\n")
            append("--------------------------\n")
            append(sorted.empty.joinToString(", ") { it.toolName })
            append("\n")
        }

        if (sorted.failed.isNotEmpty()) {
            append("\nSOURCES THAT COULD NOT BE REACHED (${sorted.failed.size})\n")
            append("---------------------------------\n")
            append("These said nothing either way; absence here is not evidence.\n")
            sorted.failed.forEach {
                append("${it.toolName}: ${it.errorMessage ?: it.status}\n")
            }
        }
    }

    private fun StringBuilder.appendResult(r: ResultRow) {
        append("\n--- ${r.toolName} ---\n")
        append(r.rawOutput.ifBlank { "(no output)" })
        append("\n")
    }

    private fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
