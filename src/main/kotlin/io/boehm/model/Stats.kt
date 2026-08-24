package io.boehm.model

import kotlin.math.sqrt

/** Descriptive statistics shared by all output parsers. */
object Stats {
    fun mean(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else values.average()

    /** Population standard deviation. */
    fun stdev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val m = values.average()
        return sqrt(values.sumOf { (it - m) * (it - m) } / values.size)
    }
}
