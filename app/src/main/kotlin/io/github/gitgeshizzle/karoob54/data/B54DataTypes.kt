package io.github.gitgeshizzle.karoob54.data

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.widget.RemoteViews
import io.github.gitgeshizzle.karoob54.R
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
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

/**
 * Active light mode, shown as a compact text label (e.g. "HI·PWR") via a custom RemoteViews
 * field — Karoo's numeric view can't render text.
 *
 * Both the stream and the view read [LightModeState], an app-wide flow the BLE layer updates
 * from the light's "$B" replies (not per-device DataPoint routing — the view config has no
 * source). Text colour follows the Karoo day/night theme so it stays legible on either.
 */
class LightModeDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {
    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            LightModeState.code.collect { code ->
                emitter.onNext(
                    if (code == null) {
                        StreamState.Searching
                    } else {
                        StreamState.Streaming(
                            DataPoint(
                                dataTypeId = dataTypeId,
                                values = mapOf(DataType.Field.SINGLE to LightMode.index(code)),
                            ),
                        )
                    },
                )
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = true))
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val textColor = if (night) Color.WHITE else Color.BLACK
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch {
            LightModeState.code.collect { code ->
                val views = RemoteViews(context.packageName, R.layout.light_mode_view)
                views.setTextViewText(R.id.light_mode_text, LightMode.label(code))
                views.setTextColor(R.id.light_mode_text, textColor)
                emitter.updateView(views)
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    companion object { const val TYPE_ID = "lightmode" }
}
