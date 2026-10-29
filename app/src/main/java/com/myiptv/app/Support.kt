package com.myiptv.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.LruCache
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors

object Util {
    /** Opens the phone's screen-mirroring (cast) screen, falling back to general settings. */
    fun openMirror(a: Activity) {
        val intents = listOf(
            Intent("android.settings.CAST_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in intents) {
            try {
                a.startActivity(i)
                Toast.makeText(
                    a,
                    "Choose your M7 to mirror. On Samsung phones you can also swipe down and tap Smart View.",
                    Toast.LENGTH_LONG
                ).show()
                return
            } catch (_: Exception) {
            }
        }
    }
}

/** Small logo loader with an in-memory cache. */
object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String, view: ImageView) {
        view.tag = url
        if (url.isEmpty() || url in failed) {
            view.setImageDrawable(null)
            return
        }
        val hit = cache.get(url)
        if (hit != null) {
            view.setImageBitmap(hit)
            return
        }
        view.setImageDrawable(null)
        pool.execute {
            val bmp: Bitmap? = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 15000
                c.inputStream.use { BitmapFactory.decodeStream(it) }?.let { scale(it) }
            } catch (_: Exception) {
                null
            }
            main.post {
                if (bmp == null) failed.add(url) else cache.put(url, bmp)
                if (view.tag == url && bmp != null) view.setImageBitmap(bmp)
            }
        }
    }

    private fun scale(b: Bitmap): Bitmap {
        val h = 96
        if (b.height <= h) return b
        val w = (b.width * h / b.height).coerceAtLeast(1)
        return Bitmap.createScaledBitmap(b, w, h, true)
    }
}

class ChannelAdapter(
    private val onClick: (Channel) -> Unit,
    private val onLongClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    val items = mutableListOf<Channel>()
    var editMode = false
    var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.num)
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val group: TextView = v.findViewById(R.id.group)
        val fav: TextView = v.findViewById(R.id.fav)
        val handle: TextView = v.findViewById(R.id.handle)
    }

    fun submit(newItems: List<Channel>, edit: Boolean) {
        items.clear()
        items.addAll(newItems)
        editMode = edit
        notifyDataSetChanged()
    }

    fun move(from: Int, to: Int) {
        if (from < 0 || to < 0 || from >= items.size || to >= items.size) return
        val c = items.removeAt(from)
        items.add(to, c)
        notifyItemMoved(from, to)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        h.num.text = (position + 1).toString()
        h.name.text = c.name
        h.group.text = if (c.hidden) "${c.group} · hidden" else c.group
        h.fav.visibility = if (c.fav) View.VISIBLE else View.GONE
        h.handle.visibility = if (editMode) View.VISIBLE else View.GONE
        h.itemView.alpha = if (c.hidden) 0.4f else 1f
        ImageLoader.load(c.logo, h.logo)
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(items[p])
        }
        h.itemView.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLongClick(items[p])
            true
        }
        h.handle.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag?.invoke(h)
            false
        }
    }
}

/** A screen on the network that can play a stream link by itself (DLNA / UPnP). */
data class Renderer(val name: String, val controlUrl: String)

object Dlna {
    private const val ST = "urn:schemas-upnp-org:service:AVTransport:1"

    /** Finds TVs/monitors on the Wi-Fi. Call off the main thread. */
    fun discover(ctx: Context, timeoutMs: Int = 3500): List<Renderer> {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("myiptv-dlna")
        lock.setReferenceCounted(false)
        lock.acquire()
        val locations = LinkedHashSet<String>()
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 500
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\nST: $ST\r\n\r\n").toByteArray()
                val addr = InetAddress.getByName("239.255.255.250")
                repeat(2) { s.send(DatagramPacket(msg, msg.size, addr, 1900)) }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val text = String(p.data, 0, p.length)
                        Regex("(?im)^location:\\s*(\\S+)").find(text)?.let { locations.add(it.groupValues[1]) }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        } finally {
            lock.release()
        }
        val out = ArrayList<Renderer>()
        for (loc in locations) {
            try {
                describe(loc)?.let { out.add(it) }
            } catch (_: Exception) {
            }
        }
        return out.distinctBy { it.controlUrl }
    }

    private fun describe(location: String): Renderer? {
        val c = URL(location).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 4000
        val xml = c.inputStream.bufferedReader().use { it.readText() }
        val dot = setOf(RegexOption.DOT_MATCHES_ALL)
        val name = Regex("<friendlyName>(.*?)</friendlyName>", dot).find(xml)?.groupValues?.get(1)?.trim() ?: "TV"
        val service = Regex("<service>(.*?)</service>", dot).findAll(xml)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains("AVTransport") } ?: return null
        val control = Regex("<controlURL>(.*?)</controlURL>", dot).find(service)?.groupValues?.get(1)?.trim()
            ?: return null
        val baseTag = Regex("<URLBase>(.*?)</URLBase>", dot).find(xml)?.groupValues?.get(1)?.trim()
        val base = if (baseTag.isNullOrEmpty()) location else baseTag
        return Renderer(unescape(name), URL(URL(base), control).toString())
    }

    /** Tells the screen to play the link itself. Call off the main thread. */
    fun play(r: Renderer, url: String, title: String) {
        try {
            soap(r, "Stop", "<InstanceID>0</InstanceID>")
        } catch (_: Exception) {
        }
        val mime = if (url.contains(".m3u8", ignoreCase = true)) "application/vnd.apple.mpegurl" else "video/mp2t"
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${esc(title)}</dc:title>" +
                "<upnp:class>object.item.videoItem</upnp:class>" +
                "<res protocolInfo=\"http-get:*:$mime:*\">${esc(url)}</res></item></DIDL-Lite>"
        soap(
            r, "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>${esc(url)}</CurrentURI>" +
                    "<CurrentURIMetaData>${esc(didl)}</CurrentURIMetaData>"
        )
        soap(r, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }

    private fun soap(r: Renderer, action: String, args: String) {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
                "<u:$action xmlns:u=\"$ST\">$args</u:$action></s:Body></s:Envelope>"
        val c = URL(r.controlUrl).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 5000
        c.readTimeout = 8000
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        c.setRequestProperty("SOAPAction", "\"$ST#$action\"")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        c.disconnect()
        if (code !in 200..299) throw Exception("$action failed, code $code")
    }

    private fun esc(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun unescape(s: String) =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
}
