package dev.memoh.android

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Run the shipped desktop script in Android WebView with deterministic transport peers. */
@RunWith(AndroidJUnit4::class)
class DesktopProtocolTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var view: WebView

    @SuppressLint("SetJavaScriptEnabled")
    @Before fun renderer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val html = context.assets.open("workspace/desktop.html").bufferedReader().use { it.readText() }
            .replace("import RFB from './novnc/core/rfb.js';", "const RFB=window.FixtureRFB;")
            .replace("<body>", "<body><script>$transport</script>")
        compose.runOnUiThread {
            view = WebView(compose.activity).apply { settings.javaScriptEnabled = true }
            compose.activity.setContentView(view)
            view.loadDataWithBaseURL("https://workspace.memoh.invalid/", html, "text/html", "UTF-8", null)
        }
        compose.waitUntil(8000) { js("Boolean(window.fixture?.ready)").jsonPrimitive.boolean }
    }

    @After fun close() { if (::view.isInitialized) compose.runOnUiThread { view.destroy() } }

    private fun js(expression: String): JsonElement {
        val result = arrayOfNulls<String>(1)
        val done = CountDownLatch(1)
        compose.runOnUiThread { view.evaluateJavascript("JSON.stringify(($expression))") { result[0] = it; done.countDown() } }
        assertTrue("WebView evaluation must finish", done.await(5, TimeUnit.SECONDS))
        val encoded = Json.parseToJsonElement(result[0] ?: "null")
        return if (encoded is JsonPrimitive && encoded.isString) Json.parseToJsonElement(encoded.content) else encoded
    }

    private fun connect() {
        js("(window.startDesktop(),true)")
        compose.waitUntil(8000) { js("Boolean(fixture.peer?.localDescription)").jsonPrimitive.boolean }
        js("(fixture.peer.connectionState='connected',fixture.peer.onconnectionstatechange(),true)")
        assertEquals(0, js("fixture.statuses.length").jsonPrimitive.int)
        assertFalse(js("desktopText('中文')").jsonPrimitive.boolean)
        js("(fixture.channel.readyState='open',fixture.channel.onopen(),true)")
        assertEquals("connected", js("fixture.statuses[0]").jsonPrimitive.content)
    }

    @Test fun webrtcWaitsForInputAndPreservesChineseWhileClipboardFailuresKeepTheViewerConnected() {
        connect()
        assertTrue(js("desktopText('中文🙂\\n')").jsonPrimitive.boolean)
        val sent = js("fixture.sent").jsonArray
        assertEquals(listOf(0x01004e2d, 0x01006587, 0x0101f642, 0xff0d), sent.filter { it.jsonObject["down"]?.jsonPrimitive?.boolean == true }.map { it.jsonObject["keysym"]!!.jsonPrimitive.int })
        js("(fixture.sent=[],true)")
        assertTrue(js("desktopPaste('粘贴\\nhello')").jsonPrimitive.boolean)
        assertEquals(apiBody("type" to "clipboard", "text" to "粘贴\nhello"), js("fixture.sent[0]"))
        assertEquals(1, js("fixture.sent.length").jsonPrimitive.int)
        js("(fixture.channel.onmessage({data:JSON.stringify({type:'clipboard',text:'来自桌面🙂'})}),true)")
        assertEquals("来自桌面🙂", js("fixture.clipboards[0]").jsonPrimitive.content)
        js("(fixture.channel.onmessage({data:JSON.stringify({type:'clipboard_error',code:'workspace.display_clipboard_failed'})}),true)")
        js("(desktopPaste('中'.repeat(16385)),true)")
        assertEquals(listOf("workspace.display_clipboard_failed", "workspace.display_clipboard_too_large"), js("fixture.errors").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(js("desktopText('仍可输入')").jsonPrimitive.boolean)
        assertEquals(listOf("connected"), js("fixture.statuses").jsonArray.map { it.jsonPrimitive.content })
        js("(fixture.channel.readyState='closed',true)")
        assertFalse(js("desktopText('保留草稿')").jsonPrimitive.boolean)
    }

    @Test fun cancelledPeersCannotReopenAndBlurReleasesHardwareModifiers() {
        connect()
        js("(document.getElementById('desktop').dispatchEvent(new KeyboardEvent('keydown',{key:'Control',cancelable:true})),window.dispatchEvent(new Event('blur')),true)")
        assertEquals(listOf(true, false), js("fixture.sent").jsonArray.map { it.jsonObject["down"]!!.jsonPrimitive.boolean })
        js("(fixture.oldPeer=fixture.peer,fixture.oldChannel=fixture.channel,window.stopWorkspace(),fixture.oldPeer.onconnectionstatechange(),fixture.oldChannel.onopen(),true)")
        assertEquals(listOf("connected"), js("fixture.statuses").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(js("desktopText('断开后')").jsonPrimitive.boolean)
    }

    @Test fun cloudRfbUsesItsClipboardEventAndOrderedPasteShortcutWithCommittedImeText() {
        js("(startRuntimeDisplay(),true)")
        assertFalse(js("desktopText('还没连接')").jsonPrimitive.boolean)
        js("(fixture.rfb.listeners.connect(),true)")
        assertTrue(js("desktopPaste('中文剪贴板')").jsonPrimitive.boolean)
        assertEquals("中文剪贴板", js("fixture.rfb.clipboard").jsonPrimitive.content)
        assertEquals(3, js("fixture.rfb.keys.length").jsonPrimitive.int)
        assertEquals(0xffe3, js("fixture.rfb.keys[0][0]").jsonPrimitive.int)
        assertTrue(js("fixture.rfb.keys[0][2]").jsonPrimitive.boolean)
        assertEquals(0x76, js("fixture.rfb.keys[1][0]").jsonPrimitive.int)
        assertFalse(js("fixture.rfb.keys[2][2]").jsonPrimitive.boolean)
        js("(fixture.rfb.listeners.clipboard({detail:{text:'远程复制'}}),true)")
        assertEquals("远程复制", js("fixture.clipboards[0]").jsonPrimitive.content)
        assertTrue(js("desktopText('输入法提交')").jsonPrimitive.boolean)
    }

    companion object {
        private fun apiBody(vararg values: Pair<String, String>): JsonObject = buildJsonObject { values.forEach { put(it.first, it.second) } }
        private val transport = """
            window.fixture={statuses:[],sent:[],clipboards:[],errors:[]};
            window.NativeWorkspace={ready(){fixture.ready=true;},status(value){fixture.statuses.push(value);},clipboard(value){fixture.clipboards.push(value);},clipboardError(value){fixture.errors.push(value);},offer(){},runtimeReady(){},runtimeBinary(){}};
            window.RTCPeerConnection=class{
              constructor(){fixture.peer=this;this.connectionState='new';this.iceGatheringState='complete';}
              createDataChannel(){return fixture.channel={readyState:'connecting',send(value){fixture.sent.push(JSON.parse(value));},close(){this.readyState='closed';this.onclose?.();}};}
              addTransceiver(){} async createOffer(){return{type:'offer',sdp:'fixture'};} async setLocalDescription(value){this.localDescription=value;} async setRemoteDescription(){} close(){this.connectionState='closed';}
            };
            window.FixtureRFB=class{
              constructor(){fixture.rfb=this;this.listeners={};this.keys=[];}
              addEventListener(name,fn){this.listeners[name]=fn;} disconnect(){}
              clipboardPasteFrom(text){this.clipboard=text;} sendKey(...args){this.keys.push(args);}
            };
        """.trimIndent()
    }
}
