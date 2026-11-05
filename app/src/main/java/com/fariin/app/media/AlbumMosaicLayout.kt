package com.fariin.app.media

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The album mosaic, a port of iOS `MediaGroupLayout.solve` plus the scale-up in
 * `MessageRowLayout.albumPlan`. Pure, in dp, no Compose.
 *
 * The layout is chosen by SHAPE, not by count: each tile keeps its real proportions. Tall phone
 * screenshots sit side by side as columns, wide shots stack as strips. 2, 3 and 4 items have
 * hand-tuned arrangements; 5 or more (or any panorama) go to a general row solver.
 */
object AlbumMosaicLayout {

    /** One tile, in dp, relative to the album's top-left. [index] points into the item list. */
    data class Tile(val index: Int, val x: Float, val y: Float, val w: Float, val h: Float,
                    val top: Boolean, val bottom: Boolean, val left: Boolean, val right: Boolean)

    data class Result(val tiles: List<Tile>, val width: Float, val height: Float)

    /** Same as iOS: 2 so the seams read on a wallpaper. */
    const val SPACING = 2f
    private const val MIN_WIDTH = 68f
    private const val MIN_HEIGHT = 81f

    /**
     * iOS albumPlan: solve in a square box [albumWidth] wide, then scale a narrow result (a group of
     * tall screenshots) up to the full width, height capped at 1.5 widths.
     * [aspects] = width / height per item; unknown -> 1.
     */
    fun plan(aspects: List<Float>, albumWidth: Float): Result {
        val solved = solve(aspects.map { max(0.01f, it) }, albumWidth, albumWidth)
        // The row solver rounds every tile up, so a row can run a point or two past the width; pull
        // it back so the album never pokes out of its bubble.
        val raw = if (solved.width > albumWidth + 0.5f) {
            val k = albumWidth / solved.width
            Result(solved.tiles.map { it.copy(x = it.x * k, y = it.y * k, w = it.w * k, h = it.h * k) },
                albumWidth, solved.height * k)
        } else solved
        if (raw.width <= 1f || raw.height <= 1f || raw.width >= albumWidth - 1f) return raw
        val s = min(albumWidth / raw.width, albumWidth * 1.5f / raw.height)
        if (s <= 1.01f) return raw
        fun r(v: Float) = (v * s).roundToInt().toFloat()
        return Result(raw.tiles.map { it.copy(x = r(it.x), y = r(it.y), w = r(it.w), h = r(it.h)) },
            r(raw.width), r(raw.height))
    }

    fun solve(ratios: List<Float>, maxW: Float, maxH: Float, spacing: Float = SPACING): Result {
        if (ratios.isEmpty()) return Result(emptyList(), 0f, 0f)
        val shapes = ratios.map { if (it > 1.2f) 'w' else if (it < 0.8f) 'n' else 'q' }
        val forceGeneral = ratios.any { it > 2f }
        val avg = (1f + ratios.sum()) / ratios.size
        if (!forceGeneral) when (ratios.size) {
            1 -> {
                val h = min(maxH, maxW / max(ratios[0], 0.01f))
                return finish(listOf(t(0, 0f, 0f, maxW, h, top = true, bottom = true, left = true, right = true)))
            }
            2 -> return two(ratios, shapes, maxW, maxH, spacing, avg)
            3 -> return three(ratios, shapes, maxW, maxH, spacing)
            4 -> return four(ratios, shapes, maxW, maxH, spacing)
        }
        return general(ratios, maxW, spacing, avg)
    }

    private fun rnd(v: Float) = Math.round(v).toFloat()

    private fun two(r: List<Float>, s: List<Char>, maxW: Float, maxH: Float, sp: Float, avg: Float): Result {
        val maxRatio = maxW / maxH
        val both = s.joinToString("")
        if (both == "ww" && avg > 1.4f * maxRatio && r[1] - r[0] < 0.2f) {
            val h = floor(min(maxW / r[0], min(maxW / r[1], (maxH - sp) / 2)))
            return finish(listOf(
                t(0, 0f, 0f, maxW, h, top = true, left = true, right = true),
                t(1, 0f, h + sp, maxW, h, bottom = true, left = true, right = true)))
        }
        if (both == "ww" || both == "qq") {
            val w = (maxW - sp) / 2
            val h = floor(min(w / r[0], min(w / r[1], maxH)))
            return finish(listOf(
                t(0, 0f, 0f, w, h, top = true, left = true, bottom = true),
                t(1, w + sp, 0f, w, h, top = true, right = true, bottom = true)))
        }
        val avail = maxW - sp
        val second = floor(min(0.5f * avail, rnd(avail / r[0] / (1 / r[0] + 1 / r[1]))))
        val first = maxW - second - sp
        val h = floor(min(maxH, rnd(min(first / r[0], second / r[1]))))
        return finish(listOf(
            t(0, 0f, 0f, first, h, top = true, left = true, bottom = true),
            t(1, first + sp, 0f, second, h, top = true, right = true, bottom = true)))
    }

    private fun three(r: List<Float>, s: List<Char>, maxW: Float, maxH: Float, sp: Float): Result {
        if (s[0] == 'n') {
            val thirdH = min((maxH - sp) * 0.5f, rnd(r[1] * (maxW - sp) / (r[2] + r[1])))
            val secondH = maxH - thirdH - sp
            val rightW = max(MIN_WIDTH, min((maxW - sp) * 0.5f, rnd(min(thirdH * r[2], secondH * r[1]))))
            val leftW = rnd(min(maxH * r[0], maxW - sp - rightW))
            return finish(listOf(
                t(0, 0f, 0f, leftW, maxH, top = true, left = true, bottom = true),
                t(1, leftW + sp, 0f, rightW, secondH, right = true, top = true),
                t(2, leftW + sp, secondH + sp, rightW, thirdH, right = true, bottom = true)))
        }
        val firstH = floor(min(maxW / r[0], (maxH - sp) * 0.66f))
        val half = (maxW - sp) / 2
        val secondH = min(maxH - firstH - sp, rnd(min(half / r[1], half / r[2])))
        return finish(listOf(
            t(0, 0f, 0f, maxW, firstH, top = true, left = true, right = true),
            t(1, 0f, firstH + sp, half, secondH, left = true, bottom = true),
            t(2, half + sp, firstH + sp, half, secondH, right = true, bottom = true)))
    }

    private fun four(r: List<Float>, s: List<Char>, maxW: Float, maxH: Float, sp: Float): Result {
        if (s[0] == 'w') {
            val heroH = rnd(min(maxW / r[0], (maxH - sp) * 0.66f))
            val avail = maxW - 2 * sp
            var rowH = rnd(avail / (r[1] + r[2] + r[3]))
            val w0 = max(MIN_WIDTH, min(avail * 0.4f, rowH * r[1]))
            val w2 = max(max(MIN_WIDTH, avail * 0.33f), rowH * r[3])
            val w1 = maxW - w0 - w2 - 2 * sp
            rowH = max(MIN_HEIGHT, min(maxH - heroH - sp, rowH))
            val y = heroH + sp
            return finish(listOf(
                t(0, 0f, 0f, maxW, heroH, top = true, left = true, right = true),
                t(1, 0f, y, w0, rowH, left = true, bottom = true),
                t(2, w0 + sp, y, w1, rowH, bottom = true),
                t(3, w0 + w1 + 2 * sp, y, w2, rowH, right = true, bottom = true)))
        }
        val h = maxH
        val heroW = rnd(min(h * r[0], (maxW - sp) * 0.6f))
        var colW = rnd((maxH - 2 * sp) / (1 / r[1] + 1 / r[2] + 1 / r[3]))
        val h0 = floor(colW / r[1])
        val h1 = floor(colW / r[2])
        val h2 = h - h0 - h1 - 2 * sp
        colW = max(MIN_WIDTH, min(maxW - heroW - sp, colW))
        val x = heroW + sp
        return finish(listOf(
            t(0, 0f, 0f, heroW, h, top = true, left = true, bottom = true),
            t(1, x, 0f, colW, h0, right = true, top = true),
            t(2, x, h0 + sp, colW, h1, right = true),
            t(3, x, h0 + h1 + 2 * sp, colW, h2, right = true, bottom = true)))
    }

    private fun general(ratios: List<Float>, maxW: Float, sp: Float, avg: Float): Result {
        val cropped = ratios.map { r ->
            val pulled = if (avg > 1.1f) max(1f, r) else min(1f, r)
            max(0.66667f, min(1.7f, pulled))
        }
        val n = cropped.size
        fun rowH(from: Int, count: Int): Float {
            val sum = (from until from + count).sumOf { cropped[it].toDouble() }.toFloat()
            return if (sum > 0) (maxW - (count - 1) * sp) / sum else 0f
        }
        val attempts = mutableListOf<Pair<List<Int>, List<Float>>>()
        fun add(counts: List<Int>) {
            if (counts.any { it <= 0 }) return
            var start = 0
            val hs = counts.map { c -> rowH(start, c).also { start += c } }
            attempts += counts to hs
        }
        for (a in 1 until n) if (a <= 3 && n - a <= 3) add(listOf(a, n - a))
        val midCap = if (avg < 0.85f) 4 else 3
        if (n >= 3) for (a in 1 until n - 1) for (b in 1 until n - a) {
            val c = n - a - b
            if (a <= 3 && b <= midCap && c <= 3) add(listOf(a, b, c))
        }
        if (n >= 4) for (a in 1 until n - 2) for (b in 1 until n - a - 1) for (c in 1 until n - a - b) {
            val d = n - a - b - c
            if (a <= 3 && b <= 3 && c <= 3 && d <= 3) add(listOf(a, b, c, d))
        }
        val target = floor(maxW / 3 * 4)
        var best: Pair<List<Int>, List<Float>>? = null
        var bestDiff = 0f
        for (at in attempts) {
            var total = sp * (at.second.size - 1)
            var runningMin = Float.MAX_VALUE
            // iOS compares the RUNNING total here, not the row height; kept so the output matches.
            for (h in at.second) { total += floor(h); runningMin = min(runningMin, total) }
            var diff = abs(total - target)
            if (at.first.zipWithNext().any { (x, y) -> x > y }) diff *= 1.5f
            if (runningMin < MIN_WIDTH) diff *= 1.5f
            if (best == null || diff < bestDiff) { best = at; bestDiff = diff }
        }
        val opt = best ?: return Result(emptyList(), 0f, 0f)
        val tiles = mutableListOf<Tile>()
        var index = 0
        var y = 0f
        opt.first.forEachIndexed { row, count ->
            val h = ceil(opt.second[row])
            var x = 0f
            for (k in 0 until count) {
                val w = ceil(cropped[index] * h)
                tiles += t(index, x, y, w, h, top = row == 0, bottom = row == opt.first.size - 1,
                    left = k == 0, right = k == count - 1)
                x += w + sp
                index++
            }
            y += h + sp
        }
        // Stretch each row's last tile to the widest row's edge so the block has one flush right side.
        val widest = tiles.maxOf { it.x + it.w }
        return finish(tiles.map { if (it.right && it.x + it.w < widest) it.copy(w = widest - it.x) else it })
    }

    private fun t(i: Int, x: Float, y: Float, w: Float, h: Float, top: Boolean = false, bottom: Boolean = false,
                  left: Boolean = false, right: Boolean = false) = Tile(i, x, y, w, h, top, bottom, left, right)

    private fun finish(tiles: List<Tile>): Result =
        Result(tiles, tiles.maxOf { rnd(it.x + it.w) }, tiles.maxOf { rnd(it.y + it.h) })
}
