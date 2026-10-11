package io.nekohasekai.sagernet.bg

/** Pure per-app selection policy shared by VPN and root interception. */
internal data class AppCapturePlan(val bypass: Boolean?, val packages: Set<String>) {
    companion object {
        fun build(
            ownPackage: String,
            proxyApps: Boolean,
            bypass: Boolean,
            individual: Set<String>,
            rulePackages: Set<String>,
            adblockPackages: Set<String>,
            systemWideAdblock: Boolean,
            bypassRoot: Boolean,
            installedPackages: Set<String>,
        ): AppCapturePlan {
            if (!proxyApps && !systemWideAdblock && !bypassRoot) return AppCapturePlan(null, emptySet())
            var exclude = bypass
            val selected = when {
                systemWideAdblock -> { exclude = false; installedPackages.toMutableSet() }
                proxyApps -> individual.toMutableSet().apply {
                    if (exclude) removeAll(rulePackages + adblockPackages)
                    else addAll(rulePackages + adblockPackages)
                }
                else -> { exclude = false; installedPackages.toMutableSet() }
            }
            if (exclude && bypassRoot) {
                val included = installedPackages - selected
                selected.clear()
                selected.addAll(included)
                exclude = false
            }
            selected.remove(ownPackage)
            if (!exclude) selected.add(ownPackage)
            return AppCapturePlan(exclude, selected)
        }
    }
}
