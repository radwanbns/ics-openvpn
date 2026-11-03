package com.xrayovpn

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ProfileManager
import java.io.StringReader

class XrayActivity : Activity() {
    private lateinit var ovpn: EditText
    private lateinit var status: TextView

    companion object {
        const val LOCAL_PORT = 11940
        const val PROFILE_NAME = "XrayOvpn"
        const val PICK = 42
        const val DEFAULT_PAYLOAD =
            "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade[crlf]" +
            "User-Agent: [ua][crlf]Upgrade: websocket[crlf][crlf]"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun field(h: String, t: String = "", lines: Int = 1): EditText {
            val e = EditText(this)
            e.hint = h
            e.setText(t)
            if (lines > 1) { e.minLines = lines; e.gravity = Gravity.TOP }
            root.addView(e)
            return e
        }

        val title = TextView(this)
        title.text = "Xray OVPN"
        title.textSize = 24f
        root.addView(title)

        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Mode.values().map { it.label }
        )
        root.addView(spinner)

        val open = Button(this)
        open.text = "Open .ovpn file"
        root.addView(open)
        ovpn = field("Paste .ovpn here (certificates inline)", "", 6)
        val sni = field("SNI (SSL modes)")
        val host = field("Host header")
        val ua = field("User-Agent", "Mozilla/5.0")
        val payload = field("Payload", DEFAULT_PAYLOAD, 3)

        val connect = Button(this)
        connect.text = "Connect"
        root.addView(connect)
        val stop = Button(this)
        stop.text = "Stop"
        root.addView(stop)
        status = TextView(this)
        root.addView(status)

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val m = Mode.values()[pos]
                sni.visibility = if (m == Mode.SSL || m == Mode.SSL_PAYLOAD) View.VISIBLE else View.GONE
                val pay = m == Mode.WS_PAYLOAD || m == Mode.SSL_PAYLOAD
                host.visibility = if (pay) View.VISIBLE else View.GONE
                ua.visibility = if (pay) View.VISIBLE else View.GONE
                payload.visibility = if (pay) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        open.setOnClickListener {
            val i = Intent(Intent.ACTION_GET_CONTENT)
            i.type = "*/*"
            i.addCategory(Intent.CATEGORY_OPENABLE)
            startActivityForResult(i, PICK)
        }

        connect.setOnClickListener {
            try {
                val mode = Mode.values()[spinner.selectedItemPosition]
                val config = ovpn.text.toString().trim()
                if (config.isEmpty()) { status.text = "Please import an .ovpn configuration"; return@setOnClickListener }
                val remote = OvpnPatcher.parseRemote(config)
                if (remote == null) { status.text = "No 'remote' line in the .ovpn"; return@setOnClickListener }
                val si = Intent(this, XrayService::class.java)
                if (mode.needsRelay) {
                    si.putExtra("mode", mode.name)
                    si.putExtra("localPort", LOCAL_PORT)
                    si.putExtra("server", remote.first)
                    si.putExtra("port", remote.second)
                    si.putExtra("sni", sni.text.toString().trim())
                    si.putExtra("host", host.text.toString().trim())
                    si.putExtra("ua", ua.text.toString().trim())
                    si.putExtra("payload", payload.text.toString())
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(si) else startService(si)
                } else {
                    stopService(si)
                }
                status.text = "Starting ${mode.label}..."
                startProfile(OvpnPatcher.patch(config, mode, LOCAL_PORT))
            } catch (e: Exception) {
                status.text = "Error: ${e.message}"
            }
        }

        stop.setOnClickListener {
            stopService(Intent(this, XrayService::class.java))
            try {
                startActivity(Intent().setClassName(this, "de.blinkt.openvpn.activities.DisconnectVPN"))
            } catch (e: Exception) {
                status.text = "Disconnect from the VPN notification"
            }
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun startProfile(text: String) {
        val cp = ConfigParser()
        cp.parseConfig(StringReader(text))
        val p = cp.convertProfile()
        p.mName = PROFILE_NAME
        // Our own app (the relay) must stay outside the tunnel.
        p.mAllowedAppsVpn.add(packageName)
        p.mAllowedAppsVpnAreDisallowed = true

        val pm = ProfileManager.getInstance(this)
        pm.getProfileByName(PROFILE_NAME)?.let { pm.removeProfile(this, it) }
        pm.addProfile(p)
        ProfileManager.saveProfile(this, p)
        pm.saveProfileList(this)

        val i = Intent(Intent.ACTION_MAIN)
        i.setClassName(this, "de.blinkt.openvpn.LaunchVPN")
        i.putExtra("de.blinkt.openvpn.shortcutProfileName", PROFILE_NAME)
        startActivity(i)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == PICK && resultCode == RESULT_OK && uri != null) {
            contentResolver.openInputStream(uri)?.use { ovpn.setText(it.readBytes().toString(Charsets.UTF_8)) }
        }
    }
}
