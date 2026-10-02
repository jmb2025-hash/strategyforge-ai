package app.strategyforge.engine.research

/**
 * What the owner's own AI wrote around the strategy JSON (D-043): a plain-English readback of every
 * rule, the points it had to research further and what it found, and anything it still could not
 * find. It is kept with the imported strategy as untrusted text, shown to the owner and never run.
 */
data class ImportNotes(
    val readback: List<String>,
    val furtherResearch: List<String>,
    val stillMissing: List<String>,
    /** Text outside the three sections, or the whole reply when the AI used no headings. */
    val other: String?,
) {
    companion object {
        const val MAX_CHARS = 30_000

        private enum class Section { READBACK, RESEARCH, MISSING }

        private val heading =
            Regex("""^[\s#*_>]*(RULE\s+READBACK|FURTHER\s+RESEARCH|STILL\s+MISSING)[\s*_]*(?::[\s*_]*(.*))?$""", RegexOption.IGNORE_CASE)
        private val bullet = Regex("""^\s*(?:[-*•]|\d+[.)])\s+""")
        private val none = Regex("""^(none|nothing|n/?a)\b[.!]?\s*(\(.*\))?$""", RegexOption.IGNORE_CASE)

        /** Removes control characters (other than line breaks and tabs) and caps the length. */
        fun clean(text: String?): String? =
            text
                ?.replace("\r\n", "\n")
                ?.filter { it == '\n' || it == '\t' || !it.isISOControl() }
                ?.trim()
                ?.take(MAX_CHARS)
                ?.takeIf { it.isNotBlank() }

        fun parse(text: String?): ImportNotes? {
            val t = clean(text) ?: return null
            val lines = mutableMapOf<Section, MutableList<String>>()
            val other = StringBuilder()
            var current: Section? = null
            var gap = false
            t.lineSequence().forEach { line ->
                val h = heading.find(line)
                if (h != null) {
                    current =
                        when (h.groupValues[1].uppercase().replace(Regex("\\s+"), " ")) {
                            "RULE READBACK" -> Section.READBACK
                            "FURTHER RESEARCH" -> Section.RESEARCH
                            else -> Section.MISSING
                        }
                    gap = false
                    lines.getOrPut(current!!) { mutableListOf() }
                    h.groupValues[2]
                        .trim()
                        .trimEnd('*', '_')
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?.let { lines.getValue(current!!) += it }
                    return@forEach
                }
                val s = current
                if (s == null) {
                    other.append(line).append('\n')
                    return@forEach
                }
                if (line.isBlank()) {
                    gap = true
                    return@forEach
                }
                val items = lines.getValue(s)
                val isBullet = bullet.containsMatchIn(line)
                if (gap && !isBullet && items.isNotEmpty()) {
                    // Plain text after a blank line ends the section (for example a closing remark).
                    current = null
                    other.append(line).append('\n')
                    return@forEach
                }
                gap = false
                if (isBullet || items.isEmpty()) {
                    items += line.replace(bullet, "").trim()
                } else {
                    items[items.lastIndex] = items.last() + " " + line.trim()
                }
            }

            fun of(s: Section) = lines[s].orEmpty().map { it.trim() }.filter { it.isNotBlank() && !none.matches(it) }
            return ImportNotes(of(Section.READBACK), of(Section.RESEARCH), of(Section.MISSING), other.toString().trim().takeIf { it.isNotBlank() })
        }
    }
}
