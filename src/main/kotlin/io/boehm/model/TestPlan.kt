package io.boehm.model

data class TestPlan(
    val type: String,
    val targetUrl: String,
    val ratePerSec: Int,
    val durationSec: Int,
    val warmupSec: Int = 5,
    val timeoutSec: Int = 60
)
