package org.telegram.messenger.forkgram

class SubtitleSegment(
    @JvmField val startMs: Long,
    @JvmField val endMs: Long,
    @JvmField val text: String
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is SubtitleSegment) {
            return false
        }
        return startMs == other.startMs && endMs == other.endMs && text == other.text
    }

    override fun hashCode(): Int {
        var result = startMs.hashCode()
        result = 31 * result + endMs.hashCode()
        result = 31 * result + text.hashCode()
        return result
    }
}
