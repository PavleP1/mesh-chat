package app.mesh

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.content.*
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.*
import java.io.*
import java.net.*
import java.util.*
import kotlin.concurrent.thread

/** Wire protocol:  M|id|sender|group|text  (one line, UTF-8). */
@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity() {
    private val uuid: UUID = UUID.fromString("a1b2c3d4-e5f6-0718-293a-4b5c6d7e8f90")
    private var me = "Droid" + (100..999).random()
    private var group = "all"
    private var theme = 0
    private val themes = arrayOf(
        intArrayOf(Color.WHITE, Color.BLACK),
        intArrayOf(0xFF101820.toInt(), 0xFFE0E0E0.toInt()),
        intArrayOf(0xFFFFF3E0.toInt(), 0xFF3E2723.toInt()))
    private val links = Collections.synchronizedList(mutableListOf<Link>())
    private val seen = Collections.synchronizedSet(LinkedHashSet<String>())
    private lateinit var root: LinearLayout
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private val bt: BluetoothAdapter? by lazy { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter }
    private var serversStarted = false

    inner class Link(private val inp: InputStream, private val out: OutputStream, private val c: Closeable) {
        fun send(s: String) { try { synchronized(out) { out.write((s + "\n").toByteArray()); out.flush() } } catch (_: Exception) {} }
        fun run() {
            try { val r = BufferedReader(InputStreamReader(inp, "UTF-8")); while (true) { onLine(this, r.readLine() ?: break) } } catch (_: Exception) {}
            links.remove(this); note("link lost"); updateStatus(); try { c.close() } catch (_: Exception) {}
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        status = TextView(this); logView = TextView(this).apply { textSize = 15f }
        scroll = ScrollView(this).apply { addView(logView) }
        input = EditText(this).apply { hint = "Message" }
        val row1 = LinearLayout(this); val row2 = LinearLayout(this)
        fun btn(t: String, r: LinearLayout, f: () -> Unit) = r.addView(Button(this).apply { text = t; setOnClickListener { f() } }, LinearLayout.LayoutParams(0, -2, 1f))
        btn("Send", row1) { send() }; btn("Nearby", row1) { nearby() }; btn("Relay/Wi-Fi", row1) { relay() }
        btn("Name/Group", row2) { nameGroup() }; btn("Theme", row2) { theme = (theme + 1) % themes.size; applyTheme() }
        root.addView(status); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(input); root.addView(row1); root.addView(row2)
        setContentView(root); applyTheme(); updateStatus()
        val perms = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) perms += listOf(android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_ADVERTISE)
        if (Build.VERSION.SDK_INT >= 33) perms += android.Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) { startServers() }

    private fun startServers() {
        if (serversStarted) return; serversStarted = true
        thread { try {
            val s = bt?.listenUsingInsecureRfcommWithServiceRecord("MeshChat", uuid) ?: return@thread
            while (true) { val c = s.accept(); addLink(c.inputStream, c.outputStream, c, "BT in") }
        } catch (e: Exception) { note("BT server: $e") } }
        thread { try {
            val s = ServerSocket(9001)
            while (true) { val c = s.accept(); addLink(c.getInputStream(), c.getOutputStream(), c, "TCP in") }
        } catch (e: Exception) { note("TCP server: $e") } }
    }

    private fun addLink(i: InputStream, o: OutputStream, c: Closeable, n: String) {
        val l = Link(i, o, c); links.add(l); note("linked: $n"); updateStatus(); thread { l.run() }
    }

    private fun send() {
        val t = input.text.toString().replace("\n", " "); if (t.isEmpty()) return
        val id = me + System.currentTimeMillis(); seen.add(id); input.setText("")
        note("me: $t"); flood("M|$id|$me|$group|$t", null)
    }
    private fun flood(line: String, except: Link?) { synchronized(links) { links.filter { it !== except }.forEach { it.send(line) } } }

    private fun onLine(from: Link, line: String) {
        val p = line.split("|", limit = 5); if (p.size < 5 || p[0] != "M" || !seen.add(p[1])) return
        if (seen.size > 300) synchronized(seen) { seen.remove(seen.first()) }
        flood(line, from)
        if (p[3] == group || p[3] == "all") { note("${p[2]}: ${p[4]}"); notify(p[2], p[4]) }
    }

    private fun notify(title: String, text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("m", "Messages", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "m") else Notification.Builder(this))
            .setSmallIcon(android.R.drawable.sym_action_chat).setContentTitle(title).setContentText(text).setContentIntent(pi).setAutoCancel(true).build()
        try { nm.notify((System.nanoTime() % 100000).toInt(), n) } catch (_: Exception) {}
    }

    private fun nearby() {
        val names = mutableListOf<String>(); val devs = mutableListOf<BluetoothDevice>()
        val ad = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        fun add(d: BluetoothDevice) { if (devs.none { it.address == d.address }) { devs.add(d); names.add((d.name ?: "?") + "  " + d.address); ad.notifyDataSetChanged() } }
        bt?.bondedDevices?.forEach { add(it) }
        val rcv = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(c: Context, i: Intent) { i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.let { add(it) } }
        }
        registerReceiver(rcv, IntentFilter(BluetoothDevice.ACTION_FOUND)); bt?.startDiscovery()
        AlertDialog.Builder(this).setTitle("Nearby devices")
            .setAdapter(ad) { _, i -> bt?.cancelDiscovery(); connectBt(devs[i]) }
            .setOnDismissListener { try { unregisterReceiver(rcv) } catch (_: Exception) {}; bt?.cancelDiscovery() }.show()
    }

    private fun connectBt(d: BluetoothDevice) = thread {
        try { val s = d.createInsecureRfcommSocketToServiceRecord(uuid); s.connect(); addLink(s.inputStream, s.outputStream, s, "BT " + d.name) }
        catch (e: Exception) { note("connect failed: $e") }
    }

    private fun relay() = prompt("host:port (relay :9000, or a phone :9001)", "192.168.1.10:9000") { hp ->
        thread { try { val (h, p) = hp.split(":"); val s = Socket(); s.connect(InetSocketAddress(h, p.toInt()), 8000)
            addLink(s.getInputStream(), s.getOutputStream(), s, "TCP $hp") } catch (e: Exception) { note("relay: $e") } }
    }

    private fun nameGroup() = prompt("name|group", "$me|$group") { t ->
        val p = t.split("|", limit = 2); me = p[0]; if (p.size > 1) group = p[1]; updateStatus() }

    private fun prompt(title: String, init: String, ok: (String) -> Unit) {
        val e = EditText(this).apply { setText(init) }
        AlertDialog.Builder(this).setTitle(title).setView(e).setPositiveButton("OK") { _, _ -> ok(e.text.toString()) }.show()
    }

    private fun note(s: String) = runOnUiThread { logView.append(s + "\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }
    private fun updateStatus() = runOnUiThread { status.text = "$me | group: $group | links: ${links.size}" }
    private fun applyTheme() { root.setBackgroundColor(themes[theme][0]); logView.setTextColor(themes[theme][1]); status.setTextColor(themes[theme][1]) }
}
