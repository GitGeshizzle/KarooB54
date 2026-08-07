package io.github.gitgeshizzle.karoob54.data

import android.content.Context
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/**
 * Data-field definitions for the B54 extension.
 *
 * For plain numeric values an empty [DataTypeImpl] is enough — the values are streamed
 * from the device (see B54Light) and rendered by Karoo in the standard numeric view.
 */

class BatteryDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {
    companion object { const val TYPE_ID = "battery" }
}

/**
 * Remaining runtime, shown as H:MM.
 *
 * Karoo has no generic "duration without seconds" formatter, so we borrow the formatter of
 * a context-neutral H:MM time type — [DataType.Type.TIME_TO_SUNSET] — via
 * [UpdateGraphicConfig.formatDataTypeId]. It formats OUR own streamed value (in ms; see
 * B54Light), not the actual sunset time; only the number format is borrowed, while the
 * field keeps its own name and icon.
 *
 * Alternatives don't fit: ELAPSED_TIME adds seconds; workout and navigation duration types
 * render 0 outside their context.
 */
class RuntimeDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(
                UpdateGraphicConfig(
                    formatDataTypeId = DataType.Type.TIME_TO_SUNSET,
                    showHeader = true,
                ),
            )
            awaitCancellation()
        }
        emitter.setCancellable { job.cancel() }
    }

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
