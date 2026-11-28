// SPDX-License-Identifier: GPL-3.0-or-later
package org.kontho.pdfreader

import kotlin.math.abs

/**
 * Cuts the silence a voice leaves at the start and end of each spoken piece. Every piece comes
 * back with a different amount of it, which made some full stops feel much longer than others.
 * With the edges trimmed, the only pause left is the fixed one added after punctuation.
 */
object AudioTrim {
    fun trim(pcm: ShortArray, sampleRate: Int, leadMs: Int = 15, tailMs: Int = 30): ShortArray {
        if (pcm.isEmpty() || sampleRate <= 0) return pcm
        var peak = 0
        for (s in pcm) peak = maxOf(peak, abs(s.toInt()))
        val threshold = maxOf(250, peak / 40)
        if (peak <= threshold) return pcm // nothing but quiet noise: leave it alone

        var first = 0
        while (first < pcm.size && abs(pcm[first].toInt()) <= threshold) first++
        var last = pcm.size - 1
        while (last > first && abs(pcm[last].toInt()) <= threshold) last--

        val from = maxOf(0, first - sampleRate * leadMs / 1000)
        val to = minOf(pcm.size, last + 1 + sampleRate * tailMs / 1000)
        return if (from == 0 && to == pcm.size) pcm else pcm.copyOfRange(from, to)
    }
}
