package com.osint.unified.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.EntityExtractor
import com.osint.unified.domain.Corroboration
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
    TEXT("txt", "text/plain"),
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
        format: ExportFormat,
        /** What the investigator wrote. The one part of a case no source
         *  produced, and for a long time the one part no export carried. */
        notes: String = ""
    ): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.cacheDir, "exports").apply { mkdirs() }
        // Old reports used to accumulate here for ever, each named after the
        // target — so the share sheet, and every app that received one,
        // displayed the email address or phone number being investigated.
        purgeOldExports(dir)
        val file = File(dir, "osint-case-${stamp.format(Date())}.${format.ext}")
        when (format) {
            ExportFormat.JSON -> file.writeText(toJson(observable, results, notes))
            ExportFormat.CSV -> file.writeText(toCsv(results))
            ExportFormat.TEXT -> file.writeText(toDossier(observable, results, notes))
            ExportFormat.PDF -> writePdf(file, observable, results, notes)
        }
        file
    }

    /** Reports older than a day are not worth the exposure of keeping. */
    private fun purgeOldExports(dir: File) {
        val cutoff = System.currentTimeMillis() - 24 * 3600_000L
        runCatching {
            dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        }
    }

    /** Renders the dossier text into a paginated A4 PDF. */
    private fun writePdf(
        file: File,
        observable: Observable,
        results: List<ResultRow>,
        notes: String
    ) {
        val text = toDossier(observable, results, notes)
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

    private fun toJson(
        observable: Observable,
        results: List<ResultRow>,
        notes: String
    ): String {
        val doc = buildJsonObject {
            put("observable", observable.raw)
            put("type", observable.typeLabel)
            if (notes.isNotBlank()) put("notes", notes)
            put("generatedAt", java.time.Instant.now().toString())
            put("appBuild", com.osint.unified.BuildConfig.VERSION_NAME)
            putJsonArray("results") {
                results.forEach { r ->
                    addJsonObject {
                        put("tool", r.toolName)
                        put("toolId", r.toolId)
                        put("status", r.status)
                        put("verdict", verdict(r))
                        put("input", r.input)
                        put("startedAt", iso(r.startedAt))
                        r.finishedAt?.let { put("finishedAt", iso(it)) }
                        put("sha256", sha256(r.rawOutput))
                        if (r.status == "error") put("error", r.errorMessage ?: "")
                        else put("output", r.rawOutput)
                    }
                }
            }
        }
        return prettyJson.encodeToString(JsonElement.serializer(), doc)
    }

    private fun toCsv(results: List<ResultRow>): String {
        val sb = StringBuilder(
            "tool,tool_id,verdict,input,started_utc,finished_utc,sha256,output_or_error\n"
        )
        results.forEach { r ->
            val payload = if (r.status == "error") (r.errorMessage ?: "") else r.rawOutput
            sb.append(
                listOf(
                    r.toolName, r.toolId, verdict(r), r.input,
                    iso(r.startedAt), r.finishedAt?.let { iso(it) }.orEmpty(),
                    sha256(r.rawOutput), payload
                ).joinToString(",") { csv(it) }
            ).append("\n")
        }
        return sb.toString()
    }

    /** Epoch milliseconds are not a time anyone can read or compare. */
    private fun iso(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).toString()

    private fun verdict(r: ResultRow): String = when (Findings.kindOf(r)) {
        Findings.Kind.HIT -> "found"
        Findings.Kind.ABSENT -> "nothing found"
        Findings.Kind.UNREACHABLE -> "could not be reached"
    }

    /**
     * A fingerprint of exactly what the source returned.
     *
     * Without it an export is a text file anyone could have typed. With it,
     * two people can confirm they are looking at the same answer, and a
     * later re-run can be compared to this one.
     */
    private fun sha256(text: String): String = runCatching {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(Locale.US, it) }
    }.getOrDefault("")

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
    private fun toDossier(
        observable: Observable,
        results: List<ResultRow>,
        notes: String
    ): String = buildString {
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
        append("Generated  : ${java.time.Instant.now()}\n")
        append("App build  : ${com.osint.unified.BuildConfig.VERSION_NAME}\n")
        append("Sources    : ${results.size} queried — ${sorted.hits.size} returned intel, ")
        append("${sorted.empty.size} found nothing, ${sorted.failed.size} could not be reached\n")

        // An assessment, not a transcript. A report that hands over sixty
        // tool outputs and no judgement has moved the work rather than
        // done it, and leaves the reader to discover for themselves that
        // half of it is a CDN.
        val entities = Corroboration.merge(
            results, observable.raw, seedIsPlace = observable is Observable.Coordinate
        )
        append("\nASSESSMENT\n----------\n")
        append(Corroboration.assessment(entities, results)).append('\n')
        append(
            "Graded on the Admiralty scale: a letter for the source " +
                "(A, a record of the thing itself, down to E, a heuristic), " +
                "a number for this particular claim (1, corroborated by " +
                "another independent source, down to 6, unjudgeable). The two " +
                "are rated separately. No single source produces a 1, however " +
                "good it is.\n"
        )

        val leads = entities.filter { it.caveat == null }
        if (leads.isNotEmpty()) {
            append("\nWHAT WAS FOUND\n--------------\n")
            leads.groupBy { it.kind }.forEach { (kind, group) ->
                append("\n$kind\n")
                group.take(30).forEach { e ->
                    append("  ${e.grade}  ${e.value}\n")
                    append("        ${e.credibility.label} · ")
                    append(e.sourceNames.joinToString(", "))
                    if (e.independent > 1) append(" (${e.independent} independent)")
                    append('\n')
                }
            }
        }

        val machinery = entities.filter { it.caveat != null }
        if (machinery.isNotEmpty()) {
            append("\nSET ASIDE AS MACHINERY\n----------------------\n")
            append(
                "Real records that are not leads: role mailboxes, registrar " +
                    "privacy proxies, content delivery hosts.\n"
            )
            machinery.take(30).forEach { e ->
                append("  ${e.value} — ${e.caveat}\n")
            }
        }

        // What was not done is part of the report. A reader cannot tell a
        // question that was asked and answered "no" from one that was
        // never asked, unless the report says.
        append("\nLIMITATIONS\n-----------\n")
        if (sorted.failed.isNotEmpty()) {
            append("Could not be reached, so they said nothing either way: ")
            append(sorted.failed.joinToString(", ") { it.toolName })
            append("\n")
        }
        val active = results.filter {
            com.osint.unified.domain.ToolRegistry.byId(it.toolId)?.touchesTarget == true
        }
        append(
            if (active.isEmpty())
                "Nothing here contacted the target's own infrastructure; every " +
                    "source is a third party reporting what it already held.\n"
            else
                "${active.size} of these sources connected to the target's own " +
                    "infrastructure, so this enquiry appears in their logs.\n"
        )
        append(
            "Absence of a finding is not evidence of absence. A name missing " +
                "from a sanctions list is missing from one list; an account not " +
                "found is not found by the rules this build carries.\n"
        )

        // First, because it is the only part of the document a person
        // wrote, and the rest is there to support it.
        if (notes.isNotBlank()) {
            append("\nANALYST NOTES\n-------------\n")
            notes.trim().lineSequence().forEach { append(it.trimEnd()).append('\n') }
        }

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

    /**
     * One result, with enough around it to be checkable later.
     *
     * A line of text in a report is not evidence of anything. When it ran,
     * what it was asked about and the hash of exactly what came back are
     * what let someone else confirm they are reading the same answer.
     */
    private fun StringBuilder.appendResult(r: ResultRow) {
        append("\n--- ${r.toolName} [${r.toolId}] ---\n")
        append("asked      : ${r.input}\n")
        append("ran        : ${iso(r.startedAt)}")
        r.finishedAt?.let { append(" → ${iso(it)}") }
        append("\n")
        append("sha256     : ${sha256(r.rawOutput)}\n\n")
        append(r.rawOutput.ifBlank { "(no output)" })
        append("\n")
    }

    /**
     * A CSV cell, escaped against the spreadsheet as well as the parser.
     *
     * Third-party text beginning with =, +, - or @ is executed as a formula
     * by Excel and Sheets. Since every cell here holds text written by
     * someone else — the people being investigated among them — that is a
     * way to run code on the machine of whoever opens the report.
     */
    private fun csv(value: String) = csvCell(value)

    companion object {
        /**
         * One CSV cell, quoted and defused.
         *
         * Every cell in these exports is text somebody else wrote, the
         * people being investigated among them, and Excel and Sheets
         * execute a cell that begins with =, +, - or @. Without the
         * leading apostrophe an export is a way to run code on the machine
         * of whoever opens it.
         *
         * Visible so the test can call this rather than keep a copy of it
         * and check the copy against itself.
         */
        fun csvCell(value: String): String {
            val safe = if (value.firstOrNull() in setOf('=', '+', '-', '@', '\t', '\r')) {
                "'" + value
            } else {
                value
            }
            return "\"" + safe.replace("\"", "\"\"") + "\""
        }
    }
}
