package com.example.mpkfm1

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

object Router {
    @Volatile var transpose = 0
    @Volatile var channel = -1
    @Volatile var passClock = false
    @Volatile var ignorePads = false
    @Volatile var running = false
    var lastState = "Detenido"
    var onLog: ((String) -> Unit)? = null
    var onState: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var srcDev: MidiDevice? = null
    private var dstDev: MidiDevice? = null
    private var outPort: MidiOutputPort? = null
    @Volatile private var inPort: MidiInputPort? = null

    fun name(i: MidiDeviceInfo): String =
        i.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "Dispositivo ${i.id}"

    private fun state(s: String) {
        lastState = s
        main.post { onState?.invoke(s) }
    }

    fun start(ctx: Context, srcId: Int, dstId: Int) {
        stop()
        val mm = ctx.getSystemService(Context.MIDI_SERVICE) as MidiManager
        @Suppress("DEPRECATION")
        val devs = mm.devices
        val si = devs.firstOrNull { it.id == srcId }
        val di = devs.firstOrNull { it.id == dstId }
        if (si == null || di == null || srcId == dstId) {
            state("Elige dos dispositivos distintos")
            return
        }
        state("Abriendo dispositivos...")
        mm.openDevice(si, MidiManager.OnDeviceOpenedListener { d ->
            if (d == null) { state("No se pudo abrir la entrada"); return@OnDeviceOpenedListener }
            srcDev = d
            outPort = d.openOutputPort(0)
            connect(si, di)
        }, main)
        mm.openDevice(di, MidiManager.OnDeviceOpenedListener { d ->
            if (d == null) { state("No se pudo abrir la salida"); return@OnDeviceOpenedListener }
            dstDev = d
            inPort = d.openInputPort(0)
            connect(si, di)
        }, main)
    }

    private fun connect(si: MidiDeviceInfo, di: MidiDeviceInfo) {
        val o = outPort
        if (o != null && inPort != null && !running) {
            o.connect(receiver)
            running = true
            state("Enrutando: ${name(si)} -> ${name(di)}")
        }
    }

    fun stop() {
        try { outPort?.disconnect(receiver) } catch (_: Exception) {}
        try { outPort?.close() } catch (_: Exception) {}
        try { inPort?.close() } catch (_: Exception) {}
        try { srcDev?.close() } catch (_: Exception) {}
        try { dstDev?.close() } catch (_: Exception) {}
        outPort = null; inPort = null; srcDev = null; dstDev = null
        running = false
        state("Detenido")
    }

    fun panic() {
        val p = inPort ?: return
        val b = ByteArray(96)
        var n = 0
        for (c in 0..15) {
            b[n++] = (0xB0 or c).toByte(); b[n++] = 123; b[n++] = 0
            b[n++] = (0xB0 or c).toByte(); b[n++] = 120; b[n++] = 0
        }
        try { p.send(b, 0, n) } catch (_: Exception) {}
        main.post { onLog?.invoke("Silenciar todo enviado") }
    }

    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            val out = ByteArray(count + 3)
            var n = 0
            var i = offset
            val end = offset + count
            while (i < end) {
                val s = msg[i].toInt() and 0xFF
                if (s >= 0xF8) {
                    if (s != 0xFE && passClock) out[n++] = msg[i]
                    i++; continue
                }
                if (s == 0xF0) {
                    while (i < end && (msg[i].toInt() and 0xFF) != 0xF7) i++
                    i++; continue
                }
                if (s >= 0xF0 || s < 0x80) { i++; continue }
                val type = s and 0xF0
                val len = if (type == 0xC0 || type == 0xD0) 1 else 2
                if (i + len > end - 1) break
                var ch = s and 15
                val skip = ignorePads && ch == 9
                if (channel >= 0) ch = channel
                if (!skip) {
                    var d1 = msg[i + 1].toInt() and 0x7F
                    var ok = true
                    if (type == 0x80 || type == 0x90) {
                        d1 += transpose
                        if (d1 < 0 || d1 > 127) ok = false
                    }
                    if (ok) {
                        out[n++] = (type or ch).toByte()
                        out[n++] = d1.toByte()
                        if (len == 2) out[n++] = msg[i + 2]
                    }
                }
                i += 1 + len
            }
            if (n > 0) {
                try { inPort?.send(out, 0, n) } catch (_: Exception) {}
                val t = out.take(n).joinToString(" ") { "%02X".format(it) }
                main.post { onLog?.invoke(t) }
            }
        }
    }
}
