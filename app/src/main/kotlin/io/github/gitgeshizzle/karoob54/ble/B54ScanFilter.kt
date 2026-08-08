package io.github.gitgeshizzle.karoob54.ble

/**
 * True if a BLE scan result looks like a B54 light: either the advertised name matches a
 * known prefix, or the advertisement carries the Nordic UART Service UUID.
 *
 * Kept as a pure function so the matching rule is unit-tested (B54ScanFilterTest) instead of
 * only ever being verified by scanning next to a real light.
 */
fun isB54ScanResult(name: String?, serviceUuids: List<String>, nusUuid: String): Boolean {
    val nameMatches = name != null && (name.startsWith("B54") || name.startsWith("sn"))
    return nameMatches || serviceUuids.any { it.equals(nusUuid, ignoreCase = true) }
}
