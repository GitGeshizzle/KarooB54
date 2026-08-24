# B54 BLE Protocol

How the B54 light communicates over Bluetooth Low Energy, as used by this extension.
The light exposes a serial-style pipe over the **Nordic UART Service (NUS)** and
exchanges short **ASCII** messages.

## Transport (Nordic UART Service)

| Role | UUID | Access |
|---|---|---|
| Service (NUS) | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | — |
| **RX** (host → light, commands) | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | Write (we use write-with-response) |
| **TX** (light → host, data) | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | Notify |
| CCCD (enable notifications) | `00002902-0000-1000-8000-00805f9b34fb` | Write `01 00` |

## Discovery

The advertised device name starts with `B54` (or `sn` / `dfu`). The NUS service UUID and
the name are sent in the **scan response**, so scan without a hardware service-UUID filter
and match in software; on Android 12+ declare `BLUETOOTH_SCAN` with `neverForLocation`.

## Framing

- Every message is an ASCII string: `$` + one letter.
- **Lowercase = request** (host → light), **UPPERCASE = reply** (light → host).
  Example: request `$l` → reply `$L25600`.
- After the 2-character prefix comes a fixed-length ASCII-digit payload.

## Fields used for battery status

| Purpose | Request | Reply | Payload | Decoding |
|---|---|---|---|---|
| **Battery level** | `$l` | `$L` | 5 digits | **percent = value / 256** (full = 25600 = 100 %) |
| **Runtime (active mode)** | (streamed) | `$S<m>` | 8 digits | minutes, per beam mode `<m>`; pick the one matching `$B` |
| Voltage | `$q` | `$Q` | 5 digits | millivolts |
| Current | `$p` | `$P` | sign + 4 digits | milliamps (negative = discharging) |
| Power | `$w` | `$W` | 5 digits | milliwatts |
| Temperature | `$g` | `$G` | sign + 3 digits | °C |
| Charge cycles | `$y` | `$Y` | 4 digits | count |
| Firmware | `$v` | `$V` | 5 chars | e.g. "3.9.7" |
| Active beam mode | `$b` | `$B` | 1 char | e.g. `$B2` = low-beam standard |
| Ambient light | `$a` | `$A` | 3 digits | sensor value |

Battery capacity is 54 Wh.

Per-mode runtime uses `$S<mode>`, e.g. `$S1` = LB-Eco, `$S2` = LB-Std, `$S6` = DRL/TFL,
`$SA…SC` / `$SH…SL` = MAX modes. For the "remaining light" figure, take the `$S` value
whose mode matches the current `$B`.

## Connection & keepalive (important)

1. **Auto-stream:** right after connecting and enabling notifications, the light sends a
   full status burst on its own (battery, voltage, all `$S` runtimes, `$B`, `$Q`, …).
2. **Keepalive:** the light disconnects a client that sends nothing for ~1–2 s. Writing
   `$l` once per second keeps the connection alive and yields a continuous `$L` stream.
   Send it **with response** — a busy stack can silently drop write-no-response, which
   starves the keepalive and trips the watchdog.
3. The light drops the link easily. Use **direct connect** (`autoConnect = false`, for
   aggressive connection parameters) and **reconnect actively** on every drop (re-issue the
   connect after a short backoff) rather than relying on the OS auto-reconnect that
   `autoConnect = true` provides. Send the first keepalive only after the CCCD write is
   confirmed, so it can't collide with the pending descriptor write.

## Control commands

- Set beam: `$B0` off, `$B1` LB-Eco, `$B2` LB-Std, `$B3` HB-Eco, `$B4` HB-Std, `$B5` HB-Power,
  `$B6` DRL/TFL. MAX modes: `$BA…$BC` (LB1-3), `$BH…$BL` (HB1-5).
- Set info flags: `$I<5 chars>` — `longlife`, `cominghome`, `hibernate`, `bodyguard`,
  `tunnel` (uppercase = on). The **tunnel** flag (index 4) is the light's native ambient
  **autolight**. Read the current flags with `$i` first and flip only the wanted char so the
  others are preserved.
- **Never send** these — they change device state or firmware: `$*` (reboot),
  `$=` (recalibrate), `$#XX` (firmware update), `$!` (test).

The extension is read-only by default. The only writes it ever makes are `$B` (set beam) and
`$I` (set info flags), and only to fulfil a user-enabled automation — pause dimming and
ambient autolight respectively.
