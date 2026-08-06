package io.github.gitgeshizzle.karoob54.data

import io.hammerhead.karooext.extension.DataTypeImpl

/**
 * Data-field definitions for the B54 extension.
 *
 * For plain numeric values an empty [DataTypeImpl] is enough — the values are streamed
 * from the device (see B54Light) and rendered by Karoo in the standard numeric view.
 * Custom views would only be needed for graphical="true".
 */

class BatteryDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {
    companion object { const val TYPE_ID = "battery" }
}

class RuntimeDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {
    companion object { const val TYPE_ID = "runtime" }
}

class VoltageDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {
    companion object { const val TYPE_ID = "voltage" }
}

class TemperatureDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {
    companion object { const val TYPE_ID = "temperature" }
}

class CyclesDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {
    companion object { const val TYPE_ID = "cycles" }
}
