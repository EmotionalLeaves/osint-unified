package com.osint.unified.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.EntityExtractor
import com.osint.unified.domain.CaseLinks
import com.osint.unified.domain.Corroboration
import com.osint.unified.domain.Findings
import com.osint.unified.domain.IntelSummary
import com.osint.unified.domain.Observable
import com.osint.unified.domain.Timeline
import com.osint.unified.domain.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
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
        notes: String = "",
        /** Findings the investigator marked wrong, kind to lowercased value.
         *  A report that reprints a lead its own author struck is worse
         *  than one that never found it. */
        dismissals: Map<Pair<String, String>, String> = emptyMap()
    ): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.cacheDir, "exports").apply { mkdirs() }
        // Old reports used to accumulate here for ever, each named after the
        // target — so the share sheet, and every app that received one,
        // displayed the email address or phone number being investigated.
        purgeOldExports(dir)
        val file = File(dir, "osint-case-${stamp.format(Date())}.${format.ext}")
        when (format) {
            ExportFormat.JSON ->
                file.writeText(toJson(observable, results, notes, dismissals))
            ExportFormat.CSV -> file.writeText(toCsv(results))
            ExportFormat.TEXT ->
                file.writeText(toDossier(observable, results, notes, dismissals))
            ExportFormat.PDF ->
                writePdf(file, toDossier(observable, results, notes, dismissals))
        }
        file
    }

    /**
     * One target of a case, with everything needed to report on it.
     */
    data class CaseTarget(
        /** The investigation row this came from. Two investigations of
         *  the same seed in one case are ordinary, so nothing else
         *  here is unique. */
        val id: Long,
        val name: String,
        val type: String,
        val observable: Observable,
        val results: List<ResultRow>,
        val notes: String = "",
        val tags: List<String> = emptyList(),
        val dismissals: Map<Pair<String, String>, String> = emptyMap()
    )

    /**
     * A case, rather than a target.
     *
     * Exporting target by target hands the reader the one piece of
     * analysis the app can do and they cannot: which identifier turns up
     * under more than one of them. That goes at the top, before any
     * dossier, because it is the only reason the targets are in the same
     * file.
     */
    suspend fun exportCase(
        caseName: String,
        summary: String,
        targets: List<CaseTarget>,
        format: ExportFormat
    ): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.cacheDir, "exports").apply { mkdirs() }
        purgeOldExports(dir)
        val file = File(dir, "osint-case-${stamp.format(Date())}.${format.ext}")
        when (format) {
            ExportFormat.CSV -> file.writeText(toCaseCsv(targets))
            ExportFormat.JSON -> file.writeText(toCaseJson(caseName, summary, targets))
            ExportFormat.TEXT -> file.writeText(toCaseReport(caseName, summary, targets))
            ExportFormat.PDF -> writePdf(file, toCaseReport(caseName, summary, targets))
        }
        file
    }

    private fun entitiesOf(t: CaseTarget): List<Corroboration.Entity> = Corroboration.merge(
        t.results,
        t.observable.raw,
        seedIsPlace = t.observable is Observable.Coordinate,
        dismissed = t.dismissals
    )

    private fun toCaseReport(
        caseName: String,
        summary: String,
        targets: List<CaseTarget>
    ): String = buildString {
        // Keyed on the investigation, not on what it was looking for.
        //
        // `associate` keeps the last value for a duplicate key, and
        // two investigations of the same seed in one case is ordinary
        // — nothing deduplicates on seed and a case holds whatever is
        // filed into it. So both "TARGET: example.com" sections
        // printed the second investigation's findings, grades and
        // ruled-out list, and the first investigation's work was
        // absent from a report whose heading claimed to carry it.
        val byId = targets.associate { it.id to entitiesOf(it) }
        // Pooled by seed for link-finding, because two investigations
        // of one target are one target — but pooled rather than
        // overwritten, which is what `associate` was doing.
        val byTarget = targets.groupBy { it.observable.raw }
            .mapValues { (_, group) -> group.flatMap { byId[it.id].orEmpty() } }
        val links = CaseLinks.find(byTarget)

        append("OSINT CASE REPORT\n")
        append("=================\n")
        append("Case      : ").append(caseName).append('\n')
        append("Targets   : ").append(targets.size).append('\n')
        append("Generated : ").append(java.time.Instant.now()).append('\n')
        append("App build : ").append(com.osint.unified.BuildConfig.VERSION_NAME).append('\n')

        if (summary.isNotBlank()) {
            append("\nWHAT THE INVESTIGATOR CONCLUDED\n")
            append("-------------------------------\n")
            append(summary.trim()).append('\n')
        }

        append("\nWHAT HOLDS THIS CASE TOGETHER\n")
        append("-----------------------------\n")
        append(CaseLinks.summary(links, targets.size)).append('\n')
        if (links.isEmpty()) {
            append(
                "\nNothing was found under more than one target. That is a " +
                    "result, not a gap: these targets may simply not be " +
                    "connected by anything either of them published.\n"
            )
        } else {
            append(
                "\nContent delivery hosts, role mailboxes and anything ruled " +
                    "out have been excluded — nearly every site on Earth sits " +
                    "behind the same few CDNs, and counting those would bury " +
                    "the real connection under twenty false ones.\n\n"
            )
            links.take(60).forEach { link ->
                append("  ${link.grade}  ${link.kind}  ${link.value}")
                if (link.isTarget) append("   ← itself a target of this case")
                append('\n')
                append("        shared by: ").append(link.targets.joinToString(", "))
                append('\n')
                append("        asserted by: ").append(link.sourceNames.joinToString(", "))
                if (link.independent > 1) append(" (${link.independent} independent)")
                append('\n')
            }
        }

        val allTags = CaseLinks.tagsOf(targets.map { it.tags.joinToString(",") })
        if (allTags.isNotEmpty()) {
            append("\nTAGS\n----\n")
            append(allTags.joinToString(", ") { "${it.first} (${it.second})" }).append('\n')
        }

        targets.forEach { t ->
            val entities = byId[t.id].orEmpty()
            append("\n\n")
            append("TARGET: ").append(t.observable.raw).append('\n')
            append("-".repeat(8 + t.observable.raw.length)).append('\n')
            append(t.type).append(" · ")
            append(Corroboration.assessment(entities, t.results)).append('\n')
            if (t.tags.isNotEmpty()) {
                append("tags: ").append(t.tags.joinToString(", ")).append('\n')
            }
            if (t.notes.isNotBlank()) {
                append("\nNotes\n").append(t.notes.trim()).append('\n')
            }
            val leads = entities.filter { it.caveat == null }
            if (leads.isEmpty()) {
                append("\nNothing corroborated for this target.\n")
            } else {
                leads.groupBy { it.kind }.forEach { (kind, group) ->
                    append("\n  ").append(kind).append('\n')
                    group.take(20).forEach { e ->
                        append("    ${e.grade}  ${e.value}")
                        if (e.independent > 1) append("  (${e.independent} independent)")
                        append('\n')
                    }
                }
            }
            val struck = entities.filter { it.dismissed }
            if (struck.isNotEmpty()) {
                append("\n  Ruled out by the investigator\n")
                struck.forEach { e ->
                    append("    ${e.value}")
                    if (e.dismissalReason.isNotBlank()) append(" — ${e.dismissalReason}")
                    append('\n')
                }
            }
        }

        append("\n\nWHAT THIS REPORT IS NOT\n")
        append("-----------------------\n")
        append(
            "A summary. Each target's full dossier — every source queried, " +
                "every raw answer, every date — exports separately and is " +
                "where the evidence lives. This says what the targets have " +
                "in common and what was concluded about each; it is not a " +
                "substitute for reading them.\n"
        )
    }

    private fun toCaseJson(
        caseName: String,
        summary: String,
        targets: List<CaseTarget>
    ): String {
        // Keyed on the investigation, not on what it was looking for.
        //
        // `associate` keeps the last value for a duplicate key, and
        // two investigations of the same seed in one case is ordinary
        // — nothing deduplicates on seed and a case holds whatever is
        // filed into it. So both "TARGET: example.com" sections
        // printed the second investigation's findings, grades and
        // ruled-out list, and the first investigation's work was
        // absent from a report whose heading claimed to carry it.
        val byId = targets.associate { it.id to entitiesOf(it) }
        // Pooled by seed for link-finding, because two investigations
        // of one target are one target — but pooled rather than
        // overwritten, which is what `associate` was doing.
        val byTarget = targets.groupBy { it.observable.raw }
            .mapValues { (_, group) -> group.flatMap { byId[it.id].orEmpty() } }
        val links = CaseLinks.find(byTarget)
        val doc = buildJsonObject {
            put("case", caseName)
            if (summary.isNotBlank()) put("summary", summary)
            put("generatedAt", java.time.Instant.now().toString())
            put("appBuild", com.osint.unified.BuildConfig.VERSION_NAME)
            putJsonArray("links") {
                links.forEach { link ->
                    addJsonObject {
                        put("kind", link.kind)
                        put("value", link.value)
                        put("grade", link.grade)
                        put("isTarget", link.isTarget)
                        putJsonArray("targets") { link.targets.forEach { add(it) } }
                        putJsonArray("sources") { link.sourceNames.forEach { add(it) } }
                    }
                }
            }
            putJsonArray("targets") {
                targets.forEach { t ->
                    addJsonObject {
                        put("observable", t.observable.raw)
                        put("type", t.type)
                        if (t.notes.isNotBlank()) put("notes", t.notes)
                        putJsonArray("tags") { t.tags.forEach { add(it) } }
                        put("sourcesQueried", t.results.size)
                        putJsonArray("findings") {
                            byId[t.id].orEmpty()
                                .filter { it.caveat == null }
                                .take(60)
                                .forEach { e ->
                                    addJsonObject {
                                        put("kind", e.kind)
                                        put("value", e.value)
                                        put("grade", e.grade)
                                        put("independentSources", e.independent)
                                    }
                                }
                        }
                        putJsonArray("ruledOut") {
                            byId[t.id].orEmpty()
                                .filter { it.dismissed }
                                .forEach { e ->
                                    addJsonObject {
                                        put("kind", e.kind)
                                        put("value", e.value)
                                        if (e.dismissalReason.isNotBlank()) {
                                            put("reason", e.dismissalReason)
                                        }
                                    }
                                }
                        }
                    }
                }
            }
        }
        return prettyJson.encodeToString(JsonElement.serializer(), doc)
    }

    /** Every target's results in one sheet, with a column saying whose. */
    private fun toCaseCsv(targets: List<CaseTarget>): String {
        val sb = StringBuilder(
            "target,tool,toolId,status,verdict,input,startedAt,finishedAt,sha256,bytes,output\n"
        )
        targets.forEach { t ->
            t.results.forEach { r ->
                sb.append(csvCell(t.observable.raw)).append(',')
                sb.append(csvCell(r.toolName)).append(',')
                sb.append(csvCell(r.toolId)).append(',')
                sb.append(csvCell(r.status)).append(',')
                sb.append(csvCell(verdict(r))).append(',')
                sb.append(csvCell(r.input)).append(',')
                sb.append(csvCell(iso(r.startedAt))).append(',')
                sb.append(csvCell(r.finishedAt?.let { iso(it) }.orEmpty())).append(',')
                sb.append(csvCell(r.outputSha256.ifBlank { sha256(r.rawOutput) })).append(',')
                sb.append(r.outputBytes).append(',')
                sb.append(csvCell(r.rawOutput)).append('\n')
            }
        }
        return sb.toString()
    }

    /** Reports older than a day are not worth the exposure of keeping. */
    private fun purgeOldExports(dir: File) {
        val cutoff = System.currentTimeMillis() - 24 * 3600_000L
        runCatching {
            dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        }
    }

    /** Renders the dossier text into a paginated A4 PDF. */
    /** Any report as a PDF. The text is built by whoever calls this. */
    private fun writePdf(file: File, text: String) {
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
        notes: String,
        dismissals: Map<Pair<String, String>, String> = emptyMap()
    ): String {
        val doc = buildJsonObject {
            put("observable", observable.raw)
            put("type", observable.typeLabel)
            if (notes.isNotBlank()) put("notes", notes)
            put("generatedAt", java.time.Instant.now().toString())
            put("appBuild", com.osint.unified.BuildConfig.VERSION_NAME)
            // What the investigator ruled out. A machine-readable case
            // that carries only what the tools said, and not what the
            // person decided about it, is half a case.
            if (dismissals.isNotEmpty()) {
                putJsonArray("ruledOut") {
                    dismissals.forEach { (key, reason) ->
                        addJsonObject {
                            put("kind", key.first)
                            put("value", key.second)
                            if (reason.isNotBlank()) put("reason", reason)
                        }
                    }
                }
            }
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
                        put("sha256", r.outputSha256.ifBlank { sha256(r.rawOutput) })
                        put("bytes", r.outputBytes)
                        put("truncated", r.truncated)
                        ToolRegistry.byId(r.toolId)?.reliability?.let {
                            put("sourceReliability", it.code.toString())
                        }
                        if (r.status == "error") {
                            put("error", r.errorMessage ?: "")
                        } else {
                            // Carried on a successful row too: a
                            // re-run that failed keeps the previous
                            // answer and records why here, and a
                            // consumer reading only "status" would
                            // take this for a fresh result.
                            r.errorMessage?.takeIf { it.isNotBlank() }
                                ?.let { put("warning", it) }
                            put("output", r.rawOutput)
                        }
                    }
                }
            }
        }
        return prettyJson.encodeToString(JsonElement.serializer(), doc)
    }

    private fun toCsv(results: List<ResultRow>): String {
        val sb = StringBuilder(
            "tool,tool_id,source_grade,verdict,input,started_utc,finished_utc," +
                "sha256,warning,output_or_error\n"
        )
        results.forEach { r ->
            val payload = if (r.status == "error") (r.errorMessage ?: "") else r.rawOutput
            // A column of its own, because a row whose refresh failed
            // is a successful row carrying a caveat, and a spreadsheet
            // with no space for the caveat drops it.
            val warning = if (r.status == "error") "" else r.errorMessage.orEmpty()
            sb.append(
                listOf(
                    r.toolName, r.toolId,
                    ToolRegistry.byId(r.toolId)?.reliability?.code?.toString().orEmpty(),
                    verdict(r), r.input,
                    iso(r.startedAt), r.finishedAt?.let { iso(it) }.orEmpty(),
                    r.outputSha256.ifBlank { sha256(r.rawOutput) }, warning, payload
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
        notes: String,
        dismissals: Map<Pair<String, String>, String> = emptyMap()
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
            results,
            observable.raw,
            seedIsPlace = observable is Observable.Coordinate,
            dismissed = dismissals
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

        // Struck by the investigator. Printed rather than deleted: a
        // report that silently drops what was considered and rejected
        // invites the next reader to find it again and chase it, and
        // "we looked at this and it was not him" is a finding.
        val struck = entities.filter { it.dismissed }
        if (struck.isNotEmpty()) {
            append("\nRULED OUT BY THE INVESTIGATOR\n")
            append("-----------------------------\n")
            struck.forEach { e ->
                append("  ${e.kind}  ${e.value}")
                if (e.dismissalReason.isNotBlank()) append(" — ${e.dismissalReason}")
                append("\n        asserted by ")
                append(e.sourceNames.joinToString(", ")).append('\n')
            }
        }

        val demotedSources = entities.filter { it.demoted }
            .flatMap { it.sourceNames }.distinct().sorted()
        if (demotedSources.isNotEmpty()) {
            append("\nSOURCES DOWNGRADED FOR THIS CASE\n")
            append("--------------------------------\n")
            append(
                "More of what these returned was ruled out than was kept, " +
                    "so their findings here are read one grade below their " +
                    "catalogue reliability: "
            )
            append(demotedSources.joinToString(", ")).append('\n')
        }

        val machinery = entities.filter { it.caveat != null && !it.dismissed }
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

        // The dates the sources already gave, in the order they
        // happened. Scattered across sixty results nobody sees them;
        // end to end they are frequently the whole story.
        val timeline = Timeline.build(results)
        if (timeline.isNotEmpty()) {
            append("\nTIMELINE\n--------\n")
            var year = 0
            timeline.forEach { event ->
                if (event.date.year != year) {
                    year = event.date.year
                    append("\n$year\n")
                }
                append("  ${event.date.format(Timeline.FORMAT)}  ${event.what}\n")
                append("      ${event.reliability.code} · ${event.toolName}\n")
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
        append(
            "Every line of source output below is prefixed with | so that " +
                "nothing a source wrote can be mistaken for part of this " +
                "report.\n"
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
        val grade = ToolRegistry.byId(r.toolId)?.reliability
        grade?.let { append("source     : ${it.code} — ${it.label}, ${it.why}\n") }
        if (r.status != "error") {
            r.errorMessage?.takeIf { it.isNotBlank() }
                ?.let { append("warning    : $it\n") }
        }
        // Taken when the answer arrived, from the whole of it. The old
        // hash was computed here, from the stored copy, which this
        // exporter had already truncated at 256 KB — so a fingerprint
        // advertised as "exactly what came back" did not match the
        // source's own output for any large answer.
        if (r.outputSha256.isNotBlank()) {
            append("sha256     : ${r.outputSha256}  (${r.outputBytes} bytes as received)\n")
            if (r.truncated) {
                append(
                    "             the text below is the first " +
                        "${r.rawOutput.length} characters of it\n"
                )
            }
        } else {
            append("sha256     : not recorded (captured before this build)\n")
        }
        append("\n")
        // Every line of somebody else's text is prefixed. Without that, a
        // source could put "--- Something [trusted] ---" and a sha256 line
        // in its own output and forge a section of this report.
        r.rawOutput.ifBlank { "(no output)" }.lineSequence().forEach {
            append("| ").append(it).append('\n')
        }
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
