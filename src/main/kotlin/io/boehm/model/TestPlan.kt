package io.boehm.model

data class TestPlan(
    val type: String,
    val profile: String = "http-get",
    val targetUrl: String = "",
    val ratePerSec: Int = 50,
    val durationSec: Int = 30,
    val warmupSec: Int = 5,
    val timeoutSec: Int = 60,
    val parameters: Map<String, String> = emptyMap()
)
