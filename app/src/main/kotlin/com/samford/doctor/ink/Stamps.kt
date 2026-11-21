package com.samford.doctor.ink

/**
 * Tap-to-place stamps. Chips are short clinical shorthand; favourites are full medicine or test
 * names that also carry a structured hint for pharmacy and lab software. The handwriting (and
 * the rendered stamp on the page) stays the authoritative record.
 */
data class StampTemplate(
    val key: String,
    val kind: String,
    val text: String,
    val category: StampCategory,
    val hint: StampHint? = null,
    val asset: String? = null,
)

enum class StampCategory { DOSAGE, FOOD, DURATION, ADVICE, MEDICINE, TEST, DIAGRAM }

object StampCatalog {
    private fun chip(key: String, text: String, cat: StampCategory) =
        StampTemplate(key, InkStamp.KIND_CHIP, text, cat, StampHint(cat.name.lowercase(), key, text))

    private fun fav(key: String, text: String, cat: StampCategory, code: String) =
        StampTemplate(key, InkStamp.KIND_TEXT, text, cat, StampHint(cat.name.lowercase(), code, text))

    val dosage = listOf(
        chip("1-0-1", "1-0-1", StampCategory.DOSAGE),
        chip("1-0-0", "1-0-0", StampCategory.DOSAGE),
        chip("0-0-1", "0-0-1", StampCategory.DOSAGE),
        chip("1-1-1", "1-1-1", StampCategory.DOSAGE),
        chip("0-1-0", "0-1-0", StampCategory.DOSAGE),
        chip("sos", "SOS", StampCategory.DOSAGE),
        chip("hs", "HS (bedtime)", StampCategory.DOSAGE),
        chip("stat", "STAT", StampCategory.DOSAGE),
    )

    val food = listOf(
        chip("after-food", "After food", StampCategory.FOOD),
        chip("before-food", "Before food", StampCategory.FOOD),
        chip("empty-stomach", "Empty stomach", StampCategory.FOOD),
        chip("with-milk", "With milk", StampCategory.FOOD),
    )

    val duration = listOf(
        chip("3d", "× 3 days", StampCategory.DURATION),
        chip("5d", "× 5 days", StampCategory.DURATION),
        chip("7d", "× 7 days", StampCategory.DURATION),
        chip("10d", "× 10 days", StampCategory.DURATION),
        chip("1m", "× 1 month", StampCategory.DURATION),
        chip("continue", "Continue", StampCategory.DURATION),
    )

    val advice = listOf(
        chip("fluids", "Plenty of oral fluids", StampCategory.ADVICE),
        chip("rest", "Rest for 2 days", StampCategory.ADVICE),
        chip("review-1w", "Review after 1 week", StampCategory.ADVICE),
        chip("review-sos", "Review SOS if worse", StampCategory.ADVICE),
        chip("low-salt", "Low-salt diet", StampCategory.ADVICE),
        chip("steam", "Steam inhalation", StampCategory.ADVICE),
        chip("bring-reports", "Bring previous reports", StampCategory.ADVICE),
    )

    val medicines = listOf(
        fav("paracetamol-650", "Tab Paracetamol 650 mg", StampCategory.MEDICINE, "PARACETAMOL_650_TAB"),
        fav("pantoprazole-40", "Tab Pantoprazole 40 mg", StampCategory.MEDICINE, "PANTOPRAZOLE_40_TAB"),
        fav("amoxicillin-500", "Cap Amoxicillin 500 mg", StampCategory.MEDICINE, "AMOXICILLIN_500_CAP"),
        fav("cetirizine-10", "Tab Cetirizine 10 mg", StampCategory.MEDICINE, "CETIRIZINE_10_TAB"),
        fav("metformin-500", "Tab Metformin 500 mg", StampCategory.MEDICINE, "METFORMIN_500_TAB"),
        fav("amlodipine-5", "Tab Amlodipine 5 mg", StampCategory.MEDICINE, "AMLODIPINE_5_TAB"),
        fav("ors", "ORS sachet in 1 L water", StampCategory.MEDICINE, "ORS_SACHET"),
        fav("ambroxol-syp", "Syp Ambroxol 10 ml", StampCategory.MEDICINE, "AMBROXOL_SYP"),
    )

    val tests = listOf(
        fav("cbc", "CBC", StampCategory.TEST, "CBC"),
        fav("lft", "LFT", StampCategory.TEST, "LFT"),
        fav("kft", "KFT", StampCategory.TEST, "KFT"),
        fav("hba1c", "HbA1c", StampCategory.TEST, "HBA1C"),
        fav("lipid", "Lipid profile", StampCategory.TEST, "LIPID"),
        fav("tsh", "TSH", StampCategory.TEST, "TSH"),
        fav("urine-rm", "Urine R/M", StampCategory.TEST, "URINE_RM"),
        fav("xray-chest", "X-ray chest PA", StampCategory.TEST, "XRAY_CHEST_PA"),
        fav("usg-abdomen", "USG abdomen", StampCategory.TEST, "USG_ABDOMEN"),
        fav("ecg", "ECG", StampCategory.TEST, "ECG"),
    )

    val diagrams = listOf(
        StampTemplate("body-front", InkStamp.KIND_DIAGRAM, "Body (front)", StampCategory.DIAGRAM, asset = Diagrams.BODY_FRONT),
        StampTemplate("chest", InkStamp.KIND_DIAGRAM, "Chest / lungs", StampCategory.DIAGRAM, asset = Diagrams.CHEST),
        StampTemplate("abdomen", InkStamp.KIND_DIAGRAM, "Abdomen quadrants", StampCategory.DIAGRAM, asset = Diagrams.ABDOMEN),
    )

    val all: List<StampTemplate> = dosage + food + duration + advice + medicines + tests + diagrams

    fun byKey(key: String): StampTemplate? = all.firstOrNull { it.key == key }

    /** Default stamp font size in cU (18 units). */
    const val CHIP_SIZE_CU = 1800
    const val TEXT_SIZE_CU = 2000
    const val DIAGRAM_WIDTH_CU = 30000
}

/**
 * Vector outlines for annotation templates. Coordinates are in a 0..1000 x 0..1000 box that is
 * scaled into the stamp's w x h on the page. Each path is a polyline (x,y pairs); the renderer
 * draws them with a fixed grey line so the doctor's annotations stand out.
 */
object Diagrams {
    const val BODY_FRONT = "body-front/1"
    const val CHEST = "chest/1"
    const val ABDOMEN = "abdomen/1"

    /** Height / width ratio for each diagram. */
    fun aspect(asset: String): Float = when (asset) {
        BODY_FRONT -> 2.0f
        CHEST -> 0.9f
        ABDOMEN -> 1.0f
        else -> 1f
    }

    fun polylines(asset: String): List<IntArray> = when (asset) {
        BODY_FRONT -> bodyFront
        CHEST -> chest
        ABDOMEN -> abdomen
        else -> emptyList()
    }

    private fun circle(cx: Int, cy: Int, r: Int, steps: Int = 24): IntArray {
        val out = IntArray((steps + 1) * 2)
        for (i in 0..steps) {
            val a = 2 * Math.PI * i / steps
            out[2 * i] = (cx + r * Math.cos(a)).toInt()
            out[2 * i + 1] = (cy + r * Math.sin(a)).toInt()
        }
        return out
    }

    private val bodyFront: List<IntArray> = listOf(
        circle(500, 90, 70),
        intArrayOf(470, 160, 470, 190, 330, 220, 250, 470, 290, 480, 360, 300, 370, 520, 350, 980, 450, 980, 500, 600),
        intArrayOf(530, 160, 530, 190, 670, 220, 750, 470, 710, 480, 640, 300, 630, 520, 650, 980, 550, 980, 500, 600),
    )

    private val chest: List<IntArray> = listOf(
        // Left and right lung outlines with the trachea.
        intArrayOf(470, 60, 470, 200, 380, 250, 260, 380, 200, 600, 190, 820, 260, 880, 420, 840, 460, 600, 470, 260),
        intArrayOf(530, 60, 530, 200, 620, 250, 740, 380, 800, 600, 810, 820, 740, 880, 580, 840, 540, 600, 530, 260),
        intArrayOf(240, 520, 450, 470),
        intArrayOf(560, 470, 780, 520),
    )

    private val abdomen: List<IntArray> = listOf(
        circle(500, 500, 430, 36),
        intArrayOf(500, 70, 500, 930),
        intArrayOf(70, 500, 930, 500),
        circle(500, 500, 30, 12),
    )
}
