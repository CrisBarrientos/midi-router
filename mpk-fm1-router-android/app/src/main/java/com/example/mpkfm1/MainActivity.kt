package com.example.mpkfm1

import android.app.Activity
import android.content.Intent
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var mm: MidiManager
    private var ins = listOf<MidiDeviceInfo>()
    private var outs = listOf<MidiDeviceInfo>()
    private lateinit var inSp: Spinner
    private lateinit var outSp: Spinner
    private lateinit var state: TextView
    private lateinit var log: TextView
    private lateinit var trTxt: TextView
    private lateinit var go: Button
    private val logLines = ArrayDeque<String>()

    private val cb = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(d: MidiDeviceInfo) { refresh() }
        override fun onDeviceRemoved(d: MidiDeviceInfo) {
            if (Router.running) stopRouting()
            refresh()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        mm = getSystemService(MIDI_SERVICE) as MidiManager
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        fun text(t: String, size: Float = 16f) = TextView(this).apply {
            text = t; textSize = size; setPadding(0, dp(12), 0, dp(2))
            root.addView(this)
        }
        text("Enrutador MPK Mini -> FM-1", 22f)
        text("Entrada (teclado)")
        inSp = Spinner(this).also { root.addView(it) }
        text("Salida (sintetizador)")
        outSp = Spinner(this).also { root.addView(it) }

        text("Canal de salida")
        val chSp = Spinner(this).also { root.addView(it) }
        chSp.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Mantener el original") + (1..16).map { "Canal $it" }
        )
        chSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                Router.channel = pos - 1
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        text("Transposicion (semitonos)")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val minus = Button(this).apply { text = "-" }
        val plus = Button(this).apply { text = "+" }
        trTxt = TextView(this).apply { text = "0"; textSize = 20f; setPadding(dp(24), 0, dp(24), 0) }
        row.addView(minus); row.addView(trTxt); row.addView(plus)
        root.addView(row)
        minus.setOnClickListener { setTranspose(Router.transpose - 1) }
        plus.setOnClickListener { setTranspose(Router.transpose + 1) }

        root.addView(CheckBox(this).apply {
            text = "Dejar pasar el reloj MIDI"
            setOnCheckedChangeListener { _, c -> Router.passClock = c }
        })
        root.addView(CheckBox(this).apply {
            text = "Ignorar los pads (canal 10)"
            setOnCheckedChangeListener { _, c -> Router.ignorePads = c }
        })

        go = Button(this).apply { text = "Iniciar enrutado"; setOnClickListener { toggle() } }
        root.addView(go)
        root.addView(Button(this).apply {
            text = "Silenciar todo"; setOnClickListener { Router.panic() }
        })
        state = text("Detenido")
        log = text("Sin actividad.", 13f)
        setContentView(ScrollView(this).apply { addView(root) })
        mm.registerDeviceCallback(cb, Handler(Looper.getMainLooper()))
        refresh()
    }

    private fun setTranspose(v: Int) {
        Router.transpose = v.coerceIn(-24, 24)
        trTxt.text = Router.transpose.toString()
    }

    private fun refresh() {
        @Suppress("DEPRECATION")
        val all = mm.devices.toList()
        ins = all.filter { it.outputPortCount > 0 }
        outs = all.filter { it.inputPortCount > 0 }
        fun fill(sp: Spinner, l: List<MidiDeviceInfo>, rx: Regex) {
            sp.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item,
                if (l.isEmpty()) listOf("Ninguno detectado") else l.map { Router.name(it) }
            )
            val k = l.indexOfFirst { rx.containsMatchIn(Router.name(it)) }
            if (k >= 0) sp.setSelection(k)
        }
        fill(inSp, ins, Regex("mpk|akai", RegexOption.IGNORE_CASE))
        fill(outSp, outs, Regex("fm|vave", RegexOption.IGNORE_CASE))
    }

    private fun toggle() {
        if (Router.running) { stopRouting(); return }
        val si = ins.getOrNull(inSp.selectedItemPosition)
        val di = outs.getOrNull(outSp.selectedItemPosition)
        if (si == null || di == null) { state.text = "Elige una entrada y una salida"; return }
        val i = Intent(this, RouterService::class.java)
            .putExtra("src", si.id).putExtra("dst", di.id)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        go.text = "Detener enrutado"
    }

    private fun stopRouting() {
        stopService(Intent(this, RouterService::class.java))
        Router.stop()
        go.text = "Iniciar enrutado"
    }

    override fun onResume() {
        super.onResume()
        state.text = Router.lastState
        go.text = if (Router.running) "Detener enrutado" else "Iniciar enrutado"
        Router.onState = { state.text = it }
        Router.onLog = {
            logLines.addLast(it)
            while (logLines.size > 6) logLines.removeFirst()
            log.text = logLines.joinToString("\n")
        }
    }

    override fun onPause() {
        Router.onState = null
        Router.onLog = null
        super.onPause()
    }

    override fun onDestroy() {
        mm.unregisterDeviceCallback(cb)
        super.onDestroy()
    }
}
