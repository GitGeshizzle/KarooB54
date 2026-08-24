package io.github.gitgeshizzle.karoob54

/**
 * B54 ASCII protocol over the Nordic UART Service (NUS). See docs/PROTOCOL.md.
 *
 * Framing: "$" + 1 letter; lowercase = request (host -> light),
 * UPPERCASE = reply (light -> host). Payloads are ASCII digits.
 *
 * WRITES: reads ($l level, $b beam, $i info) are the default. Two set commands are used, and
 * only to fulfil a user-enabled automation: $B<code> (set beam mode) and $I<flags> (set info
 * flags, e.g. the light's native autolight). Everything else stays forbidden — never $T (time),
 * $* (reboot), $= (recalibrate), $#XX (firmware), $! (test).
 */
object B54Protocol {
    // Nordic UART Service
    const val NUS_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val NUS_RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e" // write: host -> light
    const val NUS_TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e" // notify: light -> host

    /** Read request for the battery level. Doubles as the 1 s keepalive. */
    const val REQ_LEVEL = "\$l"

    /** Read request for the active beam mode (reply "$B<code>"). Read-only, safe. */
    const val REQ_BEAM = "\$b"

    /** Read request for the info flags (reply "$I<5 chars>"). Read-only, safe. */
    const val REQ_INFO = "\$i"

    /** Index of the tunnel/autolight flag within the 5-char info-flags string. */
    const val INFO_TUNNEL_INDEX = 4

    const val FULL_LEVEL = 25600 // equals 100 %

    /** Set the active beam mode (control write). See WRITES note. */
    fun setBeam(code: String): String = "\$B$code"

    /** Set the info flags (control write). Pass the full 5-char string. See WRITES note. */
    fun setInfo(flags: String): String = "\$I$flags"

    /** Return [flags] with the tunnel/autolight flag set on ('T') or off ('t'). */
    fun withAutolight(flags: String, on: Boolean): String {
        if (flags.length <= INFO_TUNNEL_INDEX) return flags
        val chars = flags.toCharArray()
        chars[INFO_TUNNEL_INDEX] = if (on) 'T' else 't'
        return String(chars)
    }

    /** Whether the tunnel/autolight flag is on in [flags]. */
    fun isAutolightOn(flags: String): Boolean =
        flags.length > INFO_TUNNEL_INDEX && flags[INFO_TUNNEL_INDEX] == 'T'

    /** A decoded reading from a reply message. */
    sealed interface Reading {
        /** Battery level in percent (0..100). */
        data class Battery(val percent: Double) : Reading
        /** Active beam-mode code (e.g. "2" = LB-Std). Internal state only. */
        data class BeamMode(val code: String) : Reading
        /** Info flags (5 chars); index 4 is the tunnel/autolight flag. Internal state only. */
        data class Info(val flags: String) : Reading
        /** Remaining runtime in minutes for a specific beam mode. */
        data class RuntimeForMode(val modeCode: String, val minutes: Int) : Reading
        data class Voltage(val volts: Double) : Reading
        data class Temperature(val celsius: Int) : Reading
        data class Cycles(val count: Int) : Reading
    }

    /**
     * Decodes a single reply message (one BLE notify payload).
     * Returns null if the message is irrelevant or cannot be parsed.
     */
    fun decode(message: String): Reading? {
        val m = message.trim()
        if (m.length < 3 || m[0] != '$') return null
        return try {
            when {
                // $L##### -> level (5 digits), percent = value / 256
                m.startsWith("\$L") && m.length >= 7 ->
                    Reading.Battery(m.substring(2, 7).toInt() / 256.0)

                // $B# -> active beam mode (1 char)
                m.startsWith("\$B") && m.length >= 3 ->
                    Reading.BeamMode(m.substring(2, 3))

                // $I##### -> info flags (5 chars)
                m.startsWith("\$I") && m.length >= 7 ->
                    Reading.Info(m.substring(2, 7))

                // $S<mode>######## -> runtime for mode (8 digits, minutes)
                m.startsWith("\$S") && m.length >= 11 ->
                    Reading.RuntimeForMode(m.substring(2, 3), m.substring(3, 11).toInt())

                // $Q##### -> voltage in mV (5 digits)
                m.startsWith("\$Q") && m.length >= 7 ->
                    Reading.Voltage(m.substring(2, 7).toInt() / 1000.0)

                // $G+### -> temperature, sign + 3 digits
                m.startsWith("\$G") && m.length >= 6 ->
                    Reading.Temperature(m.substring(2, 6).toInt())

                // $Y#### -> charge cycles (4 digits)
                m.startsWith("\$Y") && m.length >= 6 ->
                    Reading.Cycles(m.substring(2, 6).toInt())

                else -> null
            }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
