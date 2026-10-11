package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.SpoofApp
import java.security.SecureRandom

internal data class SubscriptionDeviceProfile(
    val id: String,
    val manufacturer: String,
    val model: String,
    val os: String,
    val osVersion: String,
) {
    val label: String get() = "$model · $os $osVersion"
}

/** Preset IDs and version ranges match tee-stream's subscription spoofing service. */
internal object SubscriptionDeviceProfiles {
    val all: List<SubscriptionDeviceProfile> = buildProfiles()
    private val random = SecureRandom()

    fun forApp(spoofApp: Int): List<SubscriptionDeviceProfile> =
        all.filter { spoofApp == SpoofApp.HAPP || it.os == "Android" }

    fun randomForApp(spoofApp: Int): SubscriptionDeviceProfile {
        val profiles = forApp(spoofApp)
        return profiles[random.nextInt(profiles.size)]
    }

    private fun buildProfiles(): List<SubscriptionDeviceProfile> = buildList {
        fun android(manufacturer: String, model: String, launch: Int, support: Int = 3) {
            for (version in launch.coerceIn(7, 16)..(launch + support).coerceIn(launch, 16)) {
                add(
                    SubscriptionDeviceProfile(
                        "android_${model.replace(Regex("[^A-Za-z0-9]"), "_")}_$version",
                        manufacturer, model, "Android", version.toString(),
                    ),
                )
            }
        }
        for (generation in 1..10) {
            val launch = (generation + 6).coerceIn(7, 16)
            val support = if (generation >= 8) 7 else 3
            android("Google", "Pixel $generation", launch, support)
            if (generation <= 4) android("Google", "Pixel $generation XL", launch, support)
            if (generation >= 6) android("Google", "Pixel $generation Pro", launch, support)
            if (generation in 3..9) android("Google", "Pixel ${generation}a", launch)
        }
        val samsung = listOf(
            6 to listOf("SM-G930F", "SM-G935F"),
            7 to listOf("SM-G950F", "SM-G955F"),
            8 to listOf("SM-G960F", "SM-G965F"),
            9 to listOf("SM-G973F", "SM-G975F"),
            10 to listOf("SM-G980F", "SM-G985F", "SM-G988B"),
            11 to listOf("SM-G991B", "SM-G996B", "SM-G998B"),
            12 to listOf("SM-S901B", "SM-S906B", "SM-S908B"),
            13 to listOf("SM-S911B", "SM-S916B", "SM-S918B"),
            14 to listOf("SM-S921B", "SM-S926B", "SM-S928B"),
            15 to listOf("SM-S931B", "SM-S936B", "SM-S938B"),
            16 to listOf("SM-S941B", "SM-S946B", "SM-S948B"),
        )
        for ((launch, models) in samsung) {
            for (model in models) android("Samsung", model, launch, 4)
        }
        for (generation in 4..15) {
            val launch = (generation + 1).coerceIn(7, 16)
            android("Xiaomi", "Redmi Note $generation", launch)
            android("Xiaomi", "Redmi Note $generation Pro", launch)
        }
        val otherAndroid = listOf(
            Triple("OnePlus", "ONEPLUS A6013", 9),
            Triple("OnePlus", "OnePlus 8 Pro", 10),
            Triple("OnePlus", "OnePlus 9", 11),
            Triple("OPPO", "CPH2423", 12),
            Triple("OPPO", "CPH2451", 13),
            Triple("OPPO", "CPH2581", 14),
            Triple("OPPO", "CPH2611", 14),
            Triple("realme", "RMX3563", 12),
            Triple("realme", "RMX3771", 13),
            Triple("realme", "RMX3851", 14),
            Triple("vivo", "V2254A", 13),
            Triple("vivo", "V2324A", 14),
            Triple("Xiaomi", "M2101K6G", 11),
            Triple("Xiaomi", "2201117TG", 12),
            Triple("Xiaomi", "23021RAA2Y", 13),
            Triple("Xiaomi", "2404APC5FG", 14),
        )
        for ((manufacturer, model, launch) in otherAndroid) android(manufacturer, model, launch)
        val iphones = listOf(
            "iPhone10,1" to 11,
            "iPhone10,3" to 11,
            "iPhone11,2" to 12,
            "iPhone11,8" to 12,
            "iPhone12,1" to 13,
            "iPhone12,3" to 13,
            "iPhone13,2" to 14,
            "iPhone13,4" to 14,
            "iPhone14,5" to 15,
            "iPhone14,2" to 15,
            "iPhone14,7" to 16,
            "iPhone15,3" to 16,
            "iPhone15,4" to 17,
            "iPhone16,1" to 17,
            "iPhone17,3" to 18,
            "iPhone17,2" to 18,
        )
        for ((model, launch) in iphones) {
            for (version in launch..18) {
                add(
                    SubscriptionDeviceProfile(
                        "ios_${model.replace(',', '_')}_$version",
                        "Apple", model, "iOS", "$version.0",
                    ),
                )
            }
        }
    }
}
