package com.samford.doctor.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samford.doctor.AppContainer
import com.samford.doctor.core.Hashing
import com.samford.doctor.data.db.SamfordDb
import com.samford.doctor.data.db.VisitEntity
import com.samford.doctor.data.db.VisitStatus
import com.samford.doctor.data.remote.ApiJson
import com.samford.doctor.data.remote.CompleteOutcome
import com.samford.doctor.data.remote.CompleteVisitRequest
import com.samford.doctor.data.remote.CompleteVisitResponse
import com.samford.doctor.data.remote.PageRefDto
import com.samford.doctor.data.repo.HeaderFactory
import com.samford.doctor.data.repo.OpenResult
import com.samford.doctor.data.repo.ServerStatus
import com.samford.doctor.editor.StampFactory
import com.samford.doctor.ink.InkFormat
import com.samford.doctor.ink.InkPage
import com.samford.doctor.ink.PageDelta
import com.samford.doctor.ink.StampCatalog
import com.samford.doctor.ink.SyntheticInk
import com.samford.doctor.model.DeliveryState
import com.samford.doctor.model.DocType
import com.samford.doctor.model.Doctor
import com.samford.doctor.model.Patient
import com.samford.doctor.sync.Freezer
import com.samford.doctor.sync.OutboxState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Runs against the real Samford mock backend (shivam098/samford-web-app, isolated mode).
 * Enabled only when SAMFORD_IT_API_BASE is set, e.g. http://127.0.0.1:4320/api/mobile/v1.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackendIntegrationTest {
    private val base: String? = System.getProperty("samford.it.apiBase")
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private fun root() = base!!.removeSuffix("/").removeSuffix("/api/mobile/v1")

    private fun post(url: String, body: JsonObject): JsonObject {
        val req = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val text = r.body.string()
            check(r.isSuccessful) { "POST $url -> ${r.code}: $text" }
            return json.parseToJsonElement(text).jsonObject
        }
    }

    private suspend fun authedGet(c: AppContainer, path: String): Pair<Int, ByteArray> {
        val req = Request.Builder().url(base!!.trimEnd('/') + path)
            .header("Authorization", "Bearer ${c.session.token}")
            .header("X-Device-Id", c.settings.deviceId())
            .build()
        http.newCall(req).execute().use { r -> return r.code to r.body.bytes() }
    }

    private fun container() = AppContainer(
        context = context,
        db = SamfordDb.inMemory(context),
        apiOverride = null,
        discoveryUrl = "",
        kickSync = {},
        settingsName = "it-" + UUID.randomUUID(),
    )

    private suspend fun signedIn(): AppContainer {
        val c = container()
        c.settings.setManualUrl(base)
        c.restore()
        assertTrue(c.server.refresh() is ServerStatus.Online)
        val persona = c.session.personas().doctors.first { it.personaId == "doctor-a" }
        assertNull(c.session.signIn(persona))
        c.settings.setSignature(SyntheticInk.signature())
        return c
    }

    @Test
    fun completeVisitAgainstTheRealBackend() = runBlocking {
        assumeTrue("set SAMFORD_IT_API_BASE to run", base != null)
        val c = signedIn()
        val stamp = System.currentTimeMillis() % 100000
        val reg = post(
            "${root()}/api/test-support/visits",
            buildJsonObject {
                put("doctorId", "doctor-a")
                put("patient", buildJsonObject { put("name", "IT Patient $stamp (Synthetic)"); put("age", 44); put("sex", "Female"); put("mobile", "90000%05d".format(stamp)) })
            },
        )
        val visitId = reg["visitId"]!!.jsonPrimitive.content

        c.queue.refresh()
        val entry = c.queue.entries.first().first { it.queueEntryId == visitId }
        val localVisitId = (c.visits.openVisit(entry) as OpenResult.Opened).visitId
        assertEquals(visitId, c.db.visitDao().visit(localVisitId)!!.serverVisitId)

        val docs = c.db.visitDao().documentsForVisit(localVisitId)
        for (doc in docs) {
            val pageId = c.journal.pagesForDoc(doc.docId).first().first
            when (doc.type) {
                "VISIT_SUMMARY" -> SyntheticInk.page(1, 0, 4).strokes.forEach { c.journal.append(pageId, PageDelta.addStroke(it)) }
                "PRESCRIPTION" -> {
                    SyntheticInk.page(2, 0, 2).strokes.forEach { c.journal.append(pageId, PageDelta.addStroke(it)) }
                    c.journal.append(pageId, PageDelta.addStamp(StampFactory.create(StampCatalog.medicines[0], 12000, 60000, "m1")))
                    c.journal.append(pageId, PageDelta.addStamp(StampFactory.create(StampCatalog.dosage[0], 45000, 60000, "d1")))
                }
                "INVESTIGATIONS" -> {
                    SyntheticInk.page(3, 0, 1).strokes.forEach { c.journal.append(pageId, PageDelta.addStroke(it)) }
                    c.visits.setLabs(doc.docId, listOf("central-lab"))
                }
            }
        }
        assertNull(c.freezer.lockForCompletion(localVisitId))
        c.freezer.freezeVisit(localVisitId)
        c.syncEngine.runOnce()
        val bundle = c.db.outboxDao().forVisit(localVisitId).single()
        assertEquals(bundle.lastErrorMessage ?: "", OutboxState.RECEIVED.name, bundle.state)

        // The hospital holds exactly the pages this tablet rendered, byte for byte.
        val response = ApiJson.json.decodeFromString(CompleteVisitResponse.serializer(), bundle.responseJson!!)
        val rxLocal = c.db.visitDao().documentsForVisit(localVisitId).single { it.type == "PRESCRIPTION" }
        val rxRef = response.documents.single { it.clientDocId == rxLocal.docId }
        val (code, body) = authedGet(c, "/doctor/documents/${rxRef.documentId}")
        assertEquals(200, code)
        val doc = json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
        val serverPages = doc["versions"]!!.jsonArray[0].jsonObject["pages"]!!.jsonArray.map { it.jsonObject["pngSha256"]!!.jsonPrimitive.content }
        val localPages = ApiJson.json.decodeFromString(ListSerializer(PageRefDto.serializer()), rxLocal.pageRefsJson!!).map { it.pngSha256 }
        assertEquals(localPages, serverPages)
        val (blobCode, blob) = authedGet(c, "/blobs/${localPages.first()}")
        assertEquals(200, blobCode)
        assertEquals(localPages.first(), Hashing.sha256Hex(blob))

        // Retrying the same bundle is an idempotent replay, never a duplicate.
        val replay = c.api.completeVisit(visitId, bundle.bundleId, ApiJson.json.decodeFromString(CompleteVisitRequest.serializer(), bundle.payloadJson))
        assertTrue(replay is CompleteOutcome.Accepted)
        assertEquals(response.documents.map { it.documentId }, (replay as CompleteOutcome.Accepted).response.documents.map { it.documentId })

        // Pharmacy dispenses on the web; the tablet sees it.
        post("${root()}/api/test-support/deliveries", buildJsonObject { put("documentId", rxRef.documentId); put("recipient", "PHARMACY"); put("state", "DISPENSED") })
        c.syncEngine.pollStatus()
        val deliveries = c.db.deliveryDao().observeForVisit(localVisitId).first()
        assertTrue(deliveries.toString(), deliveries.any { it.recipientKind == "PHARMACY" && it.state == DeliveryState.DISPENSED.name })
        assertTrue(deliveries.any { it.recipientKind == "LAB" && it.labId == "central-lab" })

        // Addendum: version 2 keeps every original page hash and adds the correction page.
        val addendumId = c.visits.createAddendumDraft(rxLocal.docId)!!
        val pageId = c.journal.pagesForDoc(addendumId).first().first
        SyntheticInk.page(9, 0, 1).strokes.forEach { c.journal.append(pageId, PageDelta.addStroke(it)) }
        assertNull(c.freezer.freezeAddendum(addendumId, "Dose correction"))
        c.syncEngine.runOnce()
        val addBundle = c.db.outboxDao().forVisit(localVisitId).single { it.docId == addendumId }
        assertEquals(addBundle.lastErrorMessage ?: "", OutboxState.RECEIVED.name, addBundle.state)
        val (code2, body2) = authedGet(c, "/doctor/documents/${rxRef.documentId}")
        assertEquals(200, code2)
        val versions = json.parseToJsonElement(body2.toString(Charsets.UTF_8)).jsonObject["versions"]!!.jsonArray
        assertEquals(2, versions.size)
        val v2Pages = versions[1].jsonObject["pages"]!!.jsonArray.map { it.jsonObject["pngSha256"]!!.jsonPrimitive.content }
        assertEquals(localPages, v2Pages.take(localPages.size))
        assertEquals(localPages.size + 1, v2Pages.size)
        assertEquals("Dose correction", versions[1].jsonObject["reason"]!!.jsonPrimitive.content)

        // The returning-patient record now includes this visit's handwritten pages.
        val history = c.records.history(entry.patient.patientId, null, null)
        assertTrue(history.message ?: "", history.visits.any { v -> v.documents.any { d -> d.pageShas.containsAll(localPages) } })
    }

    /**
     * Cross-app E2E, producer side (the patient app repo verifies): completes a visit for a fresh
     * patient through the real tablet pipeline and writes what the patient app must show.
     * Enabled with SAMFORD_IT_API_BASE and SAMFORD_E2E_OUT=<file>.
     */
    @Test
    fun crossAppE2EProducer() = runBlocking {
        val outPath = System.getProperty("samford.e2eOut")
        assumeTrue("set SAMFORD_IT_API_BASE and SAMFORD_E2E_OUT to run", base != null && outPath != null)
        val c = signedIn()
        val mobile = "9" + (100_000_000 + (System.currentTimeMillis() % 899_999_999)).toString().takeLast(9)
        val reg = post(
            "${root()}/api/test-support/visits",
            buildJsonObject {
                put("doctorId", "doctor-a")
                put("patient", buildJsonObject { put("name", "E2E Patient (Synthetic)"); put("age", 39); put("sex", "Female"); put("mobile", mobile) })
            },
        )
        val visitId = reg["visitId"]!!.jsonPrimitive.content
        c.queue.refresh()
        val entry = c.queue.entries.first().first { it.queueEntryId == visitId }
        val localVisitId = (c.visits.openVisit(entry) as OpenResult.Opened).visitId
        for (doc in c.db.visitDao().documentsForVisit(localVisitId)) {
            val pageId = c.journal.pagesForDoc(doc.docId).first().first
            when (doc.type) {
                "VISIT_SUMMARY" -> SyntheticInk.page(21, 0, 5).strokes.forEach { c.journal.append(pageId, PageDelta.addStroke(it)) }
                "PRESCRIPTION" -> {
                    SyntheticInk.page(22, 0, 2).strokes.forEach { c.journal.append(pageId, PageDelta.addStroke(it)) }
                    c.journal.append(pageId, PageDelta.addStamp(StampFactory.create(StampCatalog.medicines[0], 12000, 60000, "e2e-m1")))
                }
            }
        }
        assertNull(c.freezer.lockForCompletion(localVisitId))
        c.freezer.freezeVisit(localVisitId)
        c.syncEngine.runOnce()
        val bundle = c.db.outboxDao().forVisit(localVisitId).single()
        assertEquals(bundle.lastErrorMessage ?: "", OutboxState.RECEIVED.name, bundle.state)
        val docs = c.db.visitDao().documentsForVisit(localVisitId).filter { it.serverDocumentId != null }
        val result = buildJsonObject {
            put("itBase", base)
            put("mobile", mobile)
            put("visitId", visitId)
            put("patientId", entry.patient.patientId)
            put(
                "documents",
                buildJsonArray {
                    docs.forEach { d ->
                        add(
                            buildJsonObject {
                                put("documentId", d.serverDocumentId)
                                put("type", d.type)
                                put("version", d.version)
                                put("pngSha256", buildJsonArray { ApiJson.json.decodeFromString(ListSerializer(PageRefDto.serializer()), d.pageRefsJson!!).forEach { add(kotlinx.serialization.json.JsonPrimitive(it.pngSha256)) } })
                                put("pdfSha256", d.pdfSha256)
                                put("inkSha256", d.inkSha256)
                            },
                        )
                    }
                },
            )
        }
        File(outPath).apply { parentFile?.mkdirs() }.writeText(result.toString())
        assertEquals(2, docs.size)
    }

    /**
     * Writes synthetic samford-ink/1 history bundles (ink JSON, page PNGs, PDF, manifest) for the
     * backend seed, using the isolated server's own seed patients and doctor-c as the author.
     * Enabled with SAMFORD_IT_API_BASE and SAMFORD_SEED_OUT.
     */
    @Test
    fun generateSeedFixtures() = runBlocking {
        val out = System.getProperty("samford.seed.out")
        assumeTrue("set SAMFORD_IT_API_BASE and SAMFORD_SEED_OUT to run", base != null && out != null)
        val c = signedIn()
        c.queue.refresh()
        val seedPatients = c.queue.entries.first().filter { it.queueEntryId == "visit-a" || it.queueEntryId == "visit-b" }
        assertEquals(2, seedPatients.size)
        val personas = c.api.personas()
        val author = personas.first { it.personaId == "doctor-c" }.let { Doctor(it.personaId, it.name, it.qualification, it.registrationNo, it.department) }
        val dir = File(out).apply { mkdirs() }
        val blobDir = File(dir, "blobs").apply { mkdirs() }
        val fixtures = buildJsonArray {
            seedPatients.forEachIndexed { i, entry ->
                val daysAgo = 14L + i * 16
                val started = Instant.parse("2026-11-21T04:30:00Z").minusSeconds(daysAgo * 86_400)
                val visit = VisitEntity(
                    visitId = "seed-history-${entry.patient.patientId}",
                    serverVisitId = null, queueEntryId = null, serverQueueEntry = false,
                    patientId = entry.patient.patientId,
                    patientJson = InkFormat.canonicalJson.encodeToString(Patient.serializer(), entry.patient),
                    doctorJson = InkFormat.canonicalJson.encodeToString(Doctor.serializer(), author),
                    vitalsJson = null, token = "${i + 1}", returning = false, isDemo = true, isSeedHistory = true,
                    status = VisitStatus.COMPLETED, startedAtMs = started.toEpochMilli(), completedAtMs = started.toEpochMilli() + 600_000,
                )
                val header = HeaderFactory.build(visit, entry.patient, author, entry.vitals)
                val signedAt = Instant.ofEpochMilli(visit.completedAtMs!!)
                val signature = c.freezer.signatureBlock(SyntheticInk.signature(seed = 31L + i), author, signedAt)
                val documents = listOf(
                    DocType.VISIT_SUMMARY to listOf(SyntheticInk.page(500L + i, 0, 9), SyntheticInk.page(600L + i, 1, 4)),
                    DocType.PRESCRIPTION to listOf(prescription(700L + i)),
                    DocType.INVESTIGATIONS to listOf(SyntheticInk.page(800L + i, 0, 2)),
                )
                var rxFrozen: com.samford.doctor.sync.FrozenDocument? = null
                suspend fun save(frozen: com.samford.doctor.sync.FrozenDocument) = frozen.blobs.forEach { sha ->
                    val ext = when (sha) {
                        frozen.pdfSha256 -> "pdf"
                        frozen.inkSha256 -> "json"
                        else -> "png"
                    }
                    File(blobDir, "$sha.$ext").writeBytes(c.blobs.read(sha)!!)
                }
                fun pagesJson(frozen: com.samford.doctor.sync.FrozenDocument) = buildJsonArray {
                    frozen.pages.forEach { p -> add(buildJsonObject { put("index", p.index); put("pngSha256", p.pngSha256); put("widthPx", p.widthPx); put("heightPx", p.heightPx) }) }
                }
                val documentsJson = buildJsonArray {
                    documents.forEach { (type, pages) ->
                        val clientDocId = "seed-${entry.patient.patientId}-${type.shortCode.lowercase()}"
                        val frozen = c.freezer.freezeDocument(clientDocId, type, type.pageTitle, Freezer.docRef(visit, type, 1), pages, header, signature, null, emptyList())
                        if (type == DocType.PRESCRIPTION) rxFrozen = frozen
                        save(frozen)
                        add(
                            buildJsonObject {
                                put("clientDocId", clientDocId)
                                put("type", type.name)
                                put("title", type.pageTitle)
                                put("inkFormat", InkFormat.FORMAT)
                                put("inkSha256", frozen.inkSha256)
                                put("pdfSha256", frozen.pdfSha256)
                                put("pages", pagesJson(frozen))
                                put("routing", ApiJson.json.encodeToJsonElement(com.samford.doctor.model.Routing.serializer(), Freezer.routingFor(type, if (type == DocType.INVESTIGATIONS) listOf("central-lab") else emptyList())))
                                frozen.hints?.let { put("hints", ApiJson.json.encodeToJsonElement(com.samford.doctor.data.remote.HintsDto.serializer(), it)) }
                            },
                        )
                    }
                }
                // patient-b: a signed v2 prescription addendum (earlier page unchanged + addendum page).
                val addendaJson = if (i != 1) null else {
                    val v1 = rxFrozen!!
                    val rxId = "seed-${entry.patient.patientId}-rx"
                    val reason = "Corrected dose and duration (synthetic sample)"
                    val addSignedAt = Instant.parse("2026-10-22T05:10:00Z")
                    val meta = com.samford.doctor.ink.AddendumMeta(rxId, 1, v1.inkSha256, reason, DocType.PRESCRIPTION.pageTitle)
                    val v2 = c.freezer.freezeDocument(
                        rxId, DocType.PRESCRIPTION, "Addendum", Freezer.docRef(visit, DocType.PRESCRIPTION, 2), listOf(addendumPage(900L)),
                        header, c.freezer.signatureBlock(SyntheticInk.signature(seed = 32L), author, addSignedAt), meta, v1.pages,
                    )
                    save(v2)
                    // Structured helper for desk software: the full v2 medicine list (corrected entry replaces v1's).
                    val hints = v2.hints?.let { add ->
                        val base = v1.hints ?: com.samford.doctor.data.remote.HintsDto()
                        base.copy(medicines = base.medicines.filter { m -> add.medicines.none { it.name == m.name } } + add.medicines)
                    }
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("clientDocId", rxId)
                                put("reason", reason)
                                put("signedAt", addSignedAt.toString())
                                put("pages", pagesJson(v2))
                                put("pdfSha256", v2.pdfSha256)
                                put("inkSha256", v2.inkSha256)
                                put("inkFormat", InkFormat.FORMAT)
                                hints?.let { put("hints", ApiJson.json.encodeToJsonElement(com.samford.doctor.data.remote.HintsDto.serializer(), it)) }
                            },
                        )
                    }
                }
                add(
                    buildJsonObject {
                        put("fixtureId", visit.visitId)
                        put("patientId", entry.patient.patientId)
                        put("uhid", entry.patient.uhid)
                        put("doctorId", author.personaId)
                        put("visitDate", started.toString().take(10))
                        put("signedAt", signedAt.toString())
                        put("documents", documentsJson)
                        addendaJson?.let { put("addenda", it) }
                    },
                )
            }
        }
        File(dir, "manifest.json").writeText(Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), fixtures))
        assertTrue(File(dir, "manifest.json").length() > 0)
        assertTrue((blobDir.listFiles()?.size ?: 0) >= 12)
        fixtures.forEach { f -> f.jsonObject["documents"]!!.jsonArray.forEach { d -> d.jsonObject["pages"]!!.jsonArray.forEach { p -> assertEquals(1500, p.jsonObject["widthPx"]!!.jsonPrimitive.int) } } }
    }

    private fun addendumPage(seed: Long): InkPage {
        val med = StampFactory.create(StampCatalog.medicines[4], 12000, 34000, "seed-$seed-m")
        var x = med.x + med.w + 2500
        val chips = listOf(StampCatalog.dosage[0], StampCatalog.food[0], StampCatalog.duration[4]).mapIndexed { j, chip ->
            StampFactory.create(chip, x, 34000, "seed-$seed-c$j").also { x = it.x + it.w + 1500 }
        }
        val note = SyntheticInk.page(seed, 0, 1).strokes.map { com.samford.doctor.ink.InkGeometry.transform(it, com.samford.doctor.ink.Affine.translate(0f, 8000f)) }
        return InkPage(0, strokes = note, stamps = listOf(med) + chips)
    }

    private fun prescription(seed: Long): InkPage {
        val meds = listOf(StampCatalog.medicines[0], StampCatalog.medicines[4])
        val stamps = meds.flatMapIndexed { i, med ->
            val y = 36000 + i * 8000
            val name = StampFactory.create(med, 12000, y, "seed-$seed-m$i")
            var x = name.x + name.w + 2500
            listOf(name) + listOf(StampCatalog.dosage[i], StampCatalog.food[0], StampCatalog.duration[i + 1]).mapIndexed { j, chip ->
                StampFactory.create(chip, x, y, "seed-$seed-c$i-$j").also { x = it.x + it.w + 1500 }
            }
        }
        return InkPage(0, strokes = SyntheticInk.page(seed, 0, 2).strokes.map { com.samford.doctor.ink.InkGeometry.transform(it, com.samford.doctor.ink.Affine.translate(0f, 20000f)) }, stamps = stamps)
    }
}
