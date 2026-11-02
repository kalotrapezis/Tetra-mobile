package com.kalotrapezis.drive

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream

// Browser (the user's sketch, 2 Nov): the page fills the screen; under a line, the address bar, a round button and three
// small stacked ones, and under them three equal buttons. More slides that row into a strip with ‹ (Office's way).

internal val BROWSER_COLOR = Color(0xFF1A87FD) // the blue of the user's icon (Assets/BrowserApp.png)
private val PRIVATE_COLOR = Color(0xFF2B2140)
private const val PRIVATE_PROFILE = "tetra_private"

private enum class BrowserPanel { Bookmarks, History, Saved, Import }

/**
 * The Browser in a window of its own (the user, 2 Nov: "I can't use the maps and the browser together, to play music in the
 * back and drive with maps in the front"): its own task, as the Phone's and Data's, so Tetra's Maps stays open beside it.
 * AI Assist's "browser-play:{site, q}" comes as [PLAY].
 */
class BrowserActivity : androidx.activity.ComponentActivity() {
    private val play = mutableStateOf<String?>(null)
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        TetraLook.load(this)
        // dark window in Tetra's dark look: WebView darkens pages only under a dark theme, and loads on dark, not white
        if (!TetraLook.light) setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(savedInstanceState)
        WriterLog.init(this)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = TetraLook.light
        play.value = intent.getStringExtra(PLAY)
        setContent { TetraTheme { BrowserScreen(back = { finish() }, play = play.value, hide = { moveTaskToBack(true) }) } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); intent.getStringExtra(PLAY)?.let { play.value = it } }
    companion object { const val PLAY = "com.kalotrapezis.drive.browser.PLAY" }
}

internal fun openBrowser(c: Context, play: String? = null) = c.startActivity(Intent(c, BrowserActivity::class.java).putExtra(BrowserActivity.PLAY, play)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))

/** [play]: AI Assist's "browser-play:{site, q}": that site's search, its first result played, in Player mode. */
@Composable
internal fun BrowserScreen(back: () -> Unit, play: String? = null, hide: () -> Unit = back) {
    val context = LocalContext.current
    var private by remember { mutableStateOf(false) }
    var normalUrl by remember { mutableStateOf("") } // the page Private left, opened again on its way out
    var said by remember { mutableStateOf<String?>(null) }
    key(private) {
        // leaving: behind the other apps with every tab as it is (the user, 2 Nov: "keep sites open on the other tabs"); Private closes, so it keeps nothing
        BrowserPage(private, if (private) "" else normalUrl, said, if (private) null else play, back = if (private) back else hide, setPrivate = { on, url ->
            when {
                on && !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) -> said = context.getString(R.string.browser_private_unsupported)
                on -> { normalUrl = url; said = null; private = true }
                else -> { said = context.getString(R.string.browser_private_closed); private = false }
            }
        })
    }
}

/** The JavaScript put in every page in the normal mode: tells which fields are there, sends a form's values on its way, fills on the user's tap. */
private const val FORMS_JS = """(function(){
if(window.__tetraFill)return;
var ADDR=['name','given-name','additional-name','family-name','email','tel','organization','street-address','address-line1','address-line2','address-level1','address-level2','postal-code','country','country-name'];
function tok(e){var a=(e.getAttribute('autocomplete')||'').toLowerCase().split(/\s+/).filter(function(w){return w&&!/^(section-|shipping$|billing$|home$|work$|mobile$|on$|off$)/.test(w);})[0]||'';
var t=(e.type||'').toLowerCase(),n=((e.name||'')+' '+(e.id||'')).toLowerCase();
if(t=='password')return 'password';
if(a=='cc-csc'||/cvc|cvv|csc|security.?code/.test(n))return '';
if(a.indexOf('cc-')==0)return a;
if(/card.?num|cc.?num/.test(n))return 'cc-number';
if(a=='username')return 'username';
if(ADDR.indexOf(a)>=0)return a;
if(t=='email')return 'email';
if(t=='tel')return 'tel';
return '';}
function inputs(r){return Array.prototype.filter.call((r||document).querySelectorAll('input,select'),function(e){return (e.offsetWidth>0||e.offsetHeight>0)&&!e.disabled&&e.type!='hidden';});}
function user(list,p){for(var k=list.indexOf(p)-1;k>=0;k--){var t=(list[k].type||'text').toLowerCase();if(t=='text'||t=='email'||t=='tel')return list[k];}return null;}
var last=null;function report(){var s={};inputs().forEach(function(e){var k=tok(e);if(k)s[k]=1;});var k=Object.keys(s).join(',');if(k!==last){last=k;TetraForms.kinds(k);}}
function send(root){var list=inputs(root),o={fields:{}};list.forEach(function(e){var k=tok(e),v=e.value;if(!k||!v)return;
if(k=='password'){if(!o.pass){o.pass=v;var u=user(list,e);if(u)o.user=u.value;}}else if(k=='username')o.user=v;else o.fields[k]=v;});
if(o.pass||Object.keys(o.fields).length)TetraForms.submitted(JSON.stringify(o));}
document.addEventListener('submit',function(e){send(e.target);},true);
document.addEventListener('click',function(e){var b=e.target.closest&&e.target.closest('button,input[type=submit]');if(!b)return;var f=b.form||document;if(f.querySelector('input[type=password]'))send(b.form);},true);
document.addEventListener('keydown',function(e){if(e.key=='Enter'&&e.target.type=='password')send(e.target.form);},true);
document.addEventListener('focusin',report,true);
function set(e,v){if(v==null||v==='')return;var p=Object.getOwnPropertyDescriptor(e.tagName=='SELECT'?HTMLSelectElement.prototype:HTMLInputElement.prototype,'value');p.set.call(e,v);
e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}
window.__tetraFill=function(d){var list=inputs();list.forEach(function(e){var k=tok(e);
if(k=='password'){if(d.password!=null){set(e,d.password);var u=user(list,e);if(u)set(u,d.username);}}
else if(k=='username')set(e,d.username);else if(k&&(k in d))set(e,d[k]);});};
report();})();"""

/**
 * Put in every page before its own scripts (and its frames): while background play is on the page always believes it is
 * shown (YouTube pauses on visibilitychange); the page's own media keys (navigator.mediaSession) are kept for ‹ and ›;
 * the media's state is told to Tetra; `__tetraFirst` plays a search's first result (AI Assist).
 */
private const val MEDIA_JS = """(function(){
function bg(){try{return TetraMedia.bg();}catch(e){return false;}}
try{[['visibilityState','visible'],['webkitVisibilityState','visible'],['hidden',false],['webkitHidden',false]].forEach(function(p){
var d=Object.getOwnPropertyDescriptor(Document.prototype,p[0]);if(d&&d.get)Object.defineProperty(document,p[0],{configurable:true,get:function(){return bg()?p[1]:d.get.call(document);}});});}catch(e){}
['visibilitychange','webkitvisibilitychange'].forEach(function(t){window.addEventListener(t,function(e){if(bg())e.stopImmediatePropagation();},true);});
if(window.top!==window||window.__tetraMediaDo)return;
var H={},ms=navigator.mediaSession;
if(ms&&ms.setActionHandler){var o=ms.setActionHandler.bind(ms);ms.setActionHandler=function(a,h){H[a]=h;try{o(a,h);}catch(e){}report();};}
function els(){return Array.prototype.slice.call(document.querySelectorAll('video,audio'));}
function cur(){var l=els();return l.filter(function(m){return !m.paused;})[0]||l.filter(function(m){return m.currentSrc&&m.duration>0;})[0]||l[0];}
var last='';function report(){var m=cur();var s=JSON.stringify({has:!!(m&&m.currentSrc),playing:!!(m&&!m.paused&&!m.ended),title:(ms&&ms.metadata&&ms.metadata.title)||document.title||'',queue:!!(H.nexttrack&&H.previoustrack)});
if(s!==last){last=s;try{TetraMedia.state(s);}catch(e){}}}
['play','playing','pause','ended','loadedmetadata','emptied'].forEach(function(t){document.addEventListener(t,report,true);});
setInterval(report,2000);
function click(sel){var e=sel&&document.querySelector(sel);if(!e)return false;e.click();return true;}
window.__tetraMediaDo=function(a,b){var m=cur();b=b||{};
if(a=='toggle')a=(m&&!m.paused)?'pause':'play';
if(a=='play'){if(m)m.play();else if(H.play)H.play({action:'play'});}
else if(a=='pause'){if(m)m.pause();else if(H.pause)H.pause({action:'pause'});}
else if(a=='next'){if(H.nexttrack)H.nexttrack({action:'nexttrack'});else click(b.next);}
else if(a=='prev'){var now=Date.now(),again=now-(window.__tetraPrevAt||0)<2000;window.__tetraPrevAt=now;
if(m&&m.currentTime>5&&!again)m.currentTime=0;else if(H.previoustrack)H.previoustrack({action:'previoustrack'});else if(!click(b.prev)){if(again&&history.length>1)history.back();else if(m)m.currentTime=0;}}
setTimeout(report,300);};
window.__tetraAutoplay=function(){var n=0,t=setInterval(function(){var m=cur();
if(m&&!m.paused){clearInterval(t);return;}
if(m&&(m.currentSrc||m.src))m.play().catch(function(){});else if(n==4)click('.jp-play,[class*=play-button],button[aria-label*=Play i],button[title*=Play i]');
if(++n>30)clearInterval(t);},500);};
window.__tetraFirst=function(sel){var n=0,t=setInterval(function(){var l=document.querySelectorAll(sel);
for(var i=0;i<l.length;i++){var a=l[i];if(!a.href||a.href.indexOf('/shorts/')>=0||a.closest('[class*=promoted],[class*=ad-slot],ytm-reel-shelf-renderer'))continue;clearInterval(t);location.href=a.href;return;}
if(++n>40)clearInterval(t);},250);};
})();"""

private data class PageMedia(val has: Boolean = false, val playing: Boolean = false, val title: String = "", val queue: Boolean = false)

private class MediaBridge(private val view: WebView, private val background: () -> Boolean, private val state: (PageMedia) -> Unit) {
    @JavascriptInterface fun bg(): Boolean = background()
    @JavascriptInterface fun state(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        view.post { state(PageMedia(o.optBoolean("has"), o.optBoolean("playing"), o.optString("title").take(200), o.optBoolean("queue"))) }
    }
}

/** Called by the page (so: any page): it only tells and proposes; nothing is saved or filled without the user. */
private class FormBridge(private val view: WebView, private val kinds: (Set<String>) -> Unit, private val sent: (String) -> Unit) {
    @JavascriptInterface fun kinds(s: String) { view.post { kinds(s.split(',').filter { it.isNotBlank() }.toSet()) } }
    @JavascriptInterface fun submitted(json: String) { if (json.length < 20_000) view.post { sent(json) } }
}

/** One tab (the user, 2 Nov: "the second row are the tabs"): a WebView of its own and what it shows; kept for the session only. */
private class BrowserTab(val id: Int) {
    lateinit var view: WebView
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var blocked by mutableIntStateOf(0)
    @Volatile var host = "" // read on the network thread, for the ad blocker
    var kinds by mutableStateOf(emptySet<String>())
    var media by mutableStateOf(PageMedia())
    var first by mutableStateOf<MediaSite?>(null) // AI Assist's search: its first result still to play
    var autoplay = false // reached by Player mode's ‹ ›: its media started when it has loaded
    var pull by mutableFloatStateOf(0f) // pull to reload: how far, in dp
    var desktop by mutableStateOf(false) // the desktop site: a desktop agent, kept per tab
    var html by mutableStateOf(false) // HTML only: no JavaScript, no autoplay, no web fonts
    var pinned by mutableStateOf(false) // a circle at the row's start, its icon only
    var group by mutableStateOf<Int?>(null) // its tab group, if any
    var pending: String? = null // a kept tab's address, loaded when it is first opened
}

private const val PULL_DP = 96f

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BrowserPage(private: Boolean, start: String, said: String?, play: String?, back: () -> Unit, setPrivate: (Boolean, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("browser", Context.MODE_PRIVATE) }
    var adblock by remember { mutableStateOf(AdBlock.on(context)) }
    // Private keeps nothing: no vault, no history; bookmarks are read, never added
    var vault by remember { mutableStateOf(if (private) BrowserVault() else BrowserSecrets.vault(context)) }
    // Settings › Browser: forgotten on leaving, after 24 hours, or never; "on leaving" also forgets what a crash left
    val keepHistory = remember { prefs.getString("keep_history", BrowserRules.DAY) ?: BrowserRules.DAY }
    var visits by remember { mutableStateOf(if (private || keepHistory == BrowserRules.ON_EXIT) emptyList() else BrowserRules.fresh(BrowserRules.visits(prefs.getString("history", null)), System.currentTimeMillis(), keepHistory)) }
    // the bar at the top, as on a computer (the user, 2 Nov: "like chrome on PC"): ☰ at its end instead of the handle's sheet
    val top = remember { prefs.getBoolean("top_bar", false) }
    var bookmarks by remember { mutableStateOf(BrowserRules.bookmarks(prefs.getString("bookmarks", null))) }
    var editing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf(TextFieldValue("")) }
    var menu by remember { mutableStateOf(false) } // the handle's sheet: the tools not on the bar
    var editTools by remember { mutableStateOf(false) } // a long press: tap tools to move them between the bar and the sheet
    var panel by remember { mutableStateOf<BrowserPanel?>(null) }
    var offer by remember { mutableStateOf<BrowserVault?>(null) } // what a sent form could keep
    var lastForm by remember { mutableStateOf("") }
    var filling by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf<Pair<String, Uri?>?>(null) } // the result, and the file to delete when it held passwords
    var chooser by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var player by remember { mutableStateOf(false) } // Player mode: the page above, ‹ play › below
    var dismissed by remember { mutableStateOf(setOf<String>()) } // playlists whose Player strip was closed
    var background by remember { mutableStateOf(BrowserMedia.background(context)) }
    var message by remember { mutableStateOf(said) }
    LaunchedEffect(message) { if (message != null) { delay(3000); message = null } }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { AdBlock.load(context) } }
    OpenTiming("Browser", true)

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        chooser?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)); chooser = null
    }
    fun cookies(): CookieManager = if (private) ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE).cookieManager else CookieManager.getInstance()
    val early = remember { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }
    if (private) remember { runCatching { ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE) } } // a crash's leftovers: Private always starts empty
    val density = context.resources.displayMetrics.density
    val pageColor = MaterialTheme.colorScheme.background.toArgb()
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600 // a tablet: more on the toolbar
    var currentId by remember { mutableIntStateOf(0) }
    val barKey = if (wide) "bar_tablet" else "bar_phone"
    var onBarTools by remember { mutableStateOf(BrowserTools.parse(prefs.getString(barKey, null), wide)) }
    // split screen (the user, 2 Nov): a tablet's two tabs side by side, or one above the other when upright; a tap chooses which the bar drives
    var splitPair by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // tab groups (the user, 2 Nov): a tab dragged onto another makes one, named; one chip that unfolds.
    // Pinned and grouped tabs are kept from one time to the next ("keep them"); Private keeps nothing.
    val kept = remember { if (private) emptyList<SavedTab>() to emptyMap() else SavedTabs.read(prefs.getString("kept_tabs", null), prefs.getString("keep_history", BrowserRules.DAY) ?: BrowserRules.DAY) }
    val groups = remember { mutableStateMapOf<Int, String>().apply { putAll(kept.second) } }
    var openGroups by remember { mutableStateOf(setOf<Int>()) }
    var nextGroup by remember { mutableIntStateOf((kept.second.keys.maxOrNull() ?: 0) + 1) }
    var naming by remember { mutableStateOf<Int?>(null) } // the group whose name is being written
    var tabOptions by remember { mutableStateOf<Int?>(null) } // a long press on a tab
    var groupOptions by remember { mutableStateOf<Int?>(null) } // a long press on a group
    // auto-hide (the user, 2 Nov: "to get full screen, show it on scroll up"): off unless switched on, kept
    var autohide by remember { mutableStateOf(prefs.getBoolean("autohide", false)) }
    var tabsRun by remember { mutableStateOf(prefs.getBoolean("tabs_run", true)) } // tabs not shown go on, sound and all (☰'s switch)
    // a video's full screen (YouTube's ⛶): its own view over everything, turned to landscape, until its ⛶ or ‹
    var fullView by remember { mutableStateOf<android.view.View?>(null) }
    var fullDone by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    fun hostActivity() = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()
    var barShown by remember { mutableStateOf(true) }
    var lowered by remember { mutableStateOf(false) } // the handle pulled down: the page full screen over a thin black strip (the user, 2 Nov)
    val drift = remember { IntArray(1) } // the scroll since its last turn, in px: a small threshold, so the bar does not flicker
    // HTML only, per site (Private: for this time only, kept nowhere)
    var htmlSites by remember { mutableStateOf(if (private) emptySet() else prefs.getStringSet("html_sites", null).orEmpty().toSet()) }
    /** The tab set as its next page's site wants; true when that changed. */
    fun applyHtml(t: BrowserTab, address: String?): Boolean {
        val on = BrowserRules.htmlOnly(htmlSites, address)
        if (on == t.html) return false
        t.html = on
        t.view.settings.javaScriptEnabled = !on // our own scripts (fill, player, media keys) cannot run either: they are JavaScript
        t.view.settings.mediaPlaybackRequiresUserGesture = on
        return true
    }
    /**
     * Auto-hide, by the finger (the user, 2 Nov: "it does not hide the bar"): [d] pixels the page was pushed up. Pages that
     * scroll inside themselves (most, now that 100vh is the screen) never move the WebView, so its own scroll cannot tell.
     */
    fun dragged(t: BrowserTab, d: Int) {
        if (t.id != currentId || !autohide) return
        drift[0] = if ((d > 0) == (drift[0] > 0)) drift[0] + d else d
        if (drift[0] < -12 * density) barShown = true else if (drift[0] > 48 * density) barShown = false
    }

    var soundId by remember { mutableStateOf<Int?>(null) } // the tab last heard: the notification's and Maps' keys go to it, whichever is shown
    fun newTab(id: Int, address: String, lazy: Boolean = false) = BrowserTab(id).also { tab -> tab.view = object : WebView(context) {
        // Tetra behind another app: Chromium pauses a hidden WebView's video within seconds (the user, 2 Nov: "it stops as soon
        // as at the island"), whatever the page is told; while background play is on and this tab plays, it is never hidden
        override fun onWindowVisibilityChanged(visibility: Int) =
            super.onWindowVisibilityChanged(if (background && tab.media.playing) android.view.View.VISIBLE else visibility)
    }.apply {
        if (private) { ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE); WebViewCompat.setProfile(this, PRIVATE_PROFILE) }
        // Tetra's own ground, not WebView's white of a light phone: that white showed through around the bar (2 Nov, emulator)
        setBackgroundColor(pageColor)
        // not the WRAP_CONTENT AndroidView gives a view without any: WebView then counts 100vh as 0, and pages sized in vh
        // fold away (YouTube's consent sheet stuck at a strip, the user 2 Nov)
        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.mediaPlaybackRequiresUserGesture = false // Player mode's and the notification's Play come from outside the page
        settings.userAgentString = BrowserRules.mobileAgent(settings.userAgentString)
        // pages follow Tetra's look (the user, 2 Nov): in the dark one, sites' own dark look, else darkened (BrowserActivity's dark theme)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
        // as Chrome: Google's consent sheet on YouTube and its sign-in live in frames of their own (the user, 2 Nov: stuck half open)
        cookies().setAcceptThirdPartyCookies(this, true)
        // nor its client hints (Sec-CH-UA, navigator.userAgentData): "Android WebView" there is Google's surest sign
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) runCatching {
            val m = androidx.webkit.WebSettingsCompat.getUserAgentMetadata(settings)
            androidx.webkit.WebSettingsCompat.setUserAgentMetadata(settings, androidx.webkit.UserAgentMetadata.Builder(m).setBrandVersionList(m.brandVersionList.map { b ->
                if (b.brand == "Android WebView") androidx.webkit.UserAgentMetadata.BrandVersion.Builder(b).setBrand("Google Chrome").build() else b
            }).build())
        }.onFailure { WriterLog.w("Browser: client hints unchanged (${it.message})") }
        // nor the X-Requested-With header naming Tetra, Google's other way of telling a WebView
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        if (wide && prefs.getBoolean("desktop", false)) { tab.desktop = true; settings.userAgentString = BrowserRules.desktopAgent(settings.userAgentString) } // a tablet's default
        setOnScrollChangeListener { _, _, y, _, _ -> if (y <= 0 && tab.id == currentId) barShown = true } // back at the top: the bar
        addJavascriptInterface(MediaBridge(this, { background }) { tab.media = it; if (it.playing) soundId = tab.id }, "TetraMedia")
        if (early) WebViewCompat.addDocumentStartJavaScript(this, MEDIA_JS, setOf("*"))
        if (!private) addJavascriptInterface(FormBridge(this, { tab.kinds = it }) { json ->
            if (json == lastForm) return@FormBridge // a click and a submit send the same form twice
            lastForm = json
            runCatching { vault.news(BrowserVault.fromForm(BrowserRules.host(this.url), JSONObject(json))) }.getOrNull()?.takeIf { !it.empty }?.let { offer = it }
        }, "TetraForms")
        // pull to reload (the user, 2 Nov): a pull down while the page is at its top; the page gets the touches too
        var from = -1f
        var lastY = 0f
        setOnTouchListener { v, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) lastY = e.y
            if (e.actionMasked == android.view.MotionEvent.ACTION_MOVE && e.pointerCount == 1) { dragged(tab, (lastY - e.y).toInt()); lastY = e.y }
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> from = if (v.canScrollVertically(-1)) -1f else e.y
                android.view.MotionEvent.ACTION_MOVE -> if (from >= 0 && e.pointerCount == 1 && !v.canScrollVertically(-1)) tab.pull = ((e.y - from) / density).coerceAtLeast(0f) else { from = -1f; tab.pull = 0f }
                android.view.MotionEvent.ACTION_UP -> { if (tab.pull >= PULL_DP && tab.url.isNotEmpty()) { WriterLog.i("Browser: pulled to reload"); (v as WebView).reload() }; from = -1f; tab.pull = 0f }
                android.view.MotionEvent.ACTION_CANCEL, android.view.MotionEvent.ACTION_POINTER_DOWN -> { from = -1f; tab.pull = 0f }
            }
            false
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (tab.html && !request.isForMainFrame && BrowserRules.webFont(request.url.toString())) return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                if (!adblock || request.isForMainFrame) return null // what the user opened is never blocked
                if (AdBlock.filter?.blocks(request.url.toString(), tab.host) != true) return null
                view.post { tab.blocked++ }
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme in setOf("http", "https", "about", "data", "blob")) return false
                // mailto:, tel:, intent:, market: — the app that opens it; a page may start only what a browser may (BROWSABLE)
                val intent = runCatching {
                    if (u.scheme == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME).apply { component = null; selector = null } else Intent(Intent.ACTION_VIEW, u)
                }.getOrNull() ?: return true
                runCatching { context.startActivity(intent.addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
                    intent.getStringExtra("browser_fallback_url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(view::loadUrl)
                        ?: run { message = context.getString(R.string.browser_no_app) }
                }
                return true
            }
            override fun onPageStarted(view: WebView, address: String?, favicon: android.graphics.Bitmap?) {
                tab.url = address.orEmpty(); tab.host = BrowserRules.host(address); tab.blocked = 0; tab.kinds = emptySet(); tab.media = PageMedia()
                // a link or Back into (or out of) an HTML-only site: the setting counts from the next load, so load it again once
                if (applyHtml(tab, address)) view.post { view.reload() }
            }
            override fun onPageFinished(view: WebView, address: String?) {
                tab.title = view.title.orEmpty()
                if (!early) view.evaluateJavascript(MEDIA_JS, null) // an old WebView: after the page's scripts, so some media keys are missed
                if (tab.autoplay) { tab.autoplay = false; view.evaluateJavascript("window.__tetraAutoplay&&window.__tetraAutoplay()", null) }
                tab.first?.let { site -> if (address.orEmpty().startsWith(site.search)) view.evaluateJavascript("window.__tetraFirst&&window.__tetraFirst(${JSONObject.quote(site.first)})", null) }
                if (private || address.isNullOrEmpty() || !address.startsWith("http")) return
                view.evaluateJavascript(FORMS_JS, null)
                visits = BrowserRules.visit(visits, Visit(address, view.title.orEmpty(), System.currentTimeMillis()), keepHistory)
                prefs.edit().putString("history", BrowserRules.visitsJson(visits)).apply()
            }
            override fun doUpdateVisitedHistory(view: WebView, address: String?, reload: Boolean) {
                tab.url = address.orEmpty(); tab.canBack = view.canGoBack(); tab.canForward = view.canGoForward()
                if (tab.first != null && address.orEmpty().contains("watch")) tab.first = null
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) { tab.progress = p }
            override fun onReceivedTitle(view: WebView, t: String?) { tab.title = t.orEmpty() }
            override fun onShowCustomView(v: android.view.View, done: CustomViewCallback) {
                fullView = v; fullDone = done
                hostActivity()?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            override fun onHideCustomView() {
                fullView = null; fullDone = null
                hostActivity()?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                chooser?.onReceiveValue(null); chooser = callback
                return runCatching { pick.launch(params.createIntent()); true }.getOrElse { chooser = null; false }
            }
        }
        setDownloadListener { address, agent, disposition, mime, _ ->
            runCatching {
                val name = URLUtil.guessFileName(address, disposition, mime)
                val r = DownloadManager.Request(Uri.parse(address)).setMimeType(mime).addRequestHeader("User-Agent", agent)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                cookies().getCookie(address)?.let { r.addRequestHeader("Cookie", it) } // a file behind a login
                context.getSystemService(DownloadManager::class.java).enqueue(r)
                message = context.getString(R.string.browser_downloading, name)
            }.onFailure { WriterLog.w("Browser: a download", it) }
        }
        if (address.isNotEmpty() && lazy) { tab.url = address; tab.pending = address } // a kept tab: its name now, its page when opened
        else if (address.isNotEmpty()) { applyHtml(tab, address); loadUrl(address) }
    } }

    var nextId by remember { mutableIntStateOf(kept.first.size + 1) }
    val tabs = remember {
        val back = kept.first.mapIndexed { i, k -> newTab(i + 1, k.url, lazy = true).apply { title = k.title; pinned = k.pinned; group = k.group } }
        mutableStateListOf(*(back + newTab(0, start)).toTypedArray())
    }
    /** A kept tab opened for the first time: its page now. */
    fun wake(t: BrowserTab) { t.pending?.let { u -> t.pending = null; applyHtml(t, u); t.view.loadUrl(u) } }
    fun saveTabs() {
        if (!private) prefs.edit().putString("kept_tabs", SavedTabs.json(tabs.map { SavedTab(it.pending ?: it.url, it.title, it.pinned, it.group) }, groups)).apply()
    }
    val tab = tabs.firstOrNull { it.id == currentId } ?: tabs.first()
    val webView = tab.view
    val media = tab.media
    fun switch(to: BrowserTab) {
        if (to.id == currentId) return
        val pair = splitPair
        if (pair != null && (to.id == pair.first || to.id == pair.second)) { currentId = to.id; editing = false; wake(to); return } // the other pane: only the focus moves
        if (pair != null) splitPair = if (pair.first == currentId) to.id to pair.second else pair.first to to.id // into the chosen pane
        // the tab left goes on as it was, its sound too (the user, 2 Nov: "no, tab switch stops the other page"), unless ☰ says otherwise
        if (!tabsRun) tabs.firstOrNull { it.id == currentId }?.view?.let { old -> old.evaluateJavascript("window.__tetraMediaDo&&window.__tetraMediaDo('pause')", null); old.onPause() }
        to.view.onResume(); wake(to); currentId = to.id; editing = false; player = false; barShown = true
    }
    fun open(address: String = ""): BrowserTab = newTab(nextId++, address).also { tabs += it; switch(it) }
    fun close(t: BrowserTab) {
        if (splitPair?.let { t.id == it.first || t.id == it.second } == true) splitPair = null
        if (tabs.size == 1) open() // never none: a fresh empty tab
        if (t.id == currentId) switch(tabs.getOrNull(tabs.indexOf(t) + 1) ?: tabs[tabs.indexOf(t) - 1])
        tabs.remove(t); t.view.stopLoading(); t.view.destroy()
    }

    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            val now = tabs.firstOrNull { it.id == currentId }
            // playing in the background: that tab is not paused (the notification keeps Tetra running)
            if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) saveTabs() // Tetra may be closed from behind: keep the pinned and grouped tabs now
            if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) tabs.forEach { if (!(background && it.media.playing)) it.view.onPause() }
            else if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) now?.view?.onResume()
        }
        lifecycle.lifecycle.addObserver(o)
        onDispose {
            lifecycle.lifecycle.removeObserver(o)
            BrowserMedia.control = null; BrowserMedia.stop(context)
            saveTabs()
            if (keepHistory == BrowserRules.ON_EXIT) prefs.edit().remove("history").apply()
            chooser?.onReceiveValue(null)
            tabs.forEach { it.view.stopLoading() }
            if (private) runCatching { // everything Private had: cookies, storage, cache, then the profile itself
                tabs.firstOrNull()?.view?.clearCache(true)
                ProfileStore.getInstance().getProfile(PRIVATE_PROFILE)?.let { it.cookieManager.removeAllCookies(null); it.webStorage.deleteAllData() }
            }.onFailure { WriterLog.e("Browser: Private not wiped", it) } else CookieManager.getInstance().flush()
            tabs.forEach { it.view.destroy() }
            if (private) android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE) }.onFailure { WriterLog.w("Browser: Private's profile kept until next time", it) }
            }
        }
    }

    fun mediaKey(a: String, t: BrowserTab = tab) {
        val view = t.view
        // a page with no ‹ › of its own (a radio station's): the site's other favourites, playing as they open
        if ((a == "next" || a == "prev") && MediaSites.of(BrowserRules.host(view.url)) == null && !t.media.queue)
            BrowserRules.hop(bookmarks, view.url.orEmpty(), a == "next")?.let { t.autoplay = true; view.loadUrl(it); return }
        view.evaluateJavascript("window.__tetraMediaDo&&window.__tetraMediaDo('$a',${MediaSites.buttons(MediaSites.of(BrowserRules.host(view.url)))})", null)
    }
    // the notification's keys: the tab heard last, else the one shown
    val sound = tabs.firstOrNull { it.id == soundId && it.media.has } ?: tab
    SideEffect { BrowserMedia.control = { a -> mediaKey(a, sound) } }
    LaunchedEffect(sound.media, background) { if (background && sound.media.has) BrowserMedia.show(context, sound.media.title, sound.media.playing) else BrowserMedia.stop(context) }
    LaunchedEffect(play) {
        val o = play?.let { runCatching { JSONObject(it.removePrefix("browser-play:")) }.getOrNull() } ?: return@LaunchedEffect
        // Maps' ♪: its station played, then this window goes behind Maps again once the sound has started (or after 8 s)
        o.optString("url").takeIf { it.isNotEmpty() }?.let { address ->
            val t = tabs.firstOrNull { it.url == address } ?: if (tab.url.isEmpty()) tab else open()
            if (t.id != currentId) switch(t)
            if (t.url == address) t.view.evaluateJavascript("window.__tetraAutoplay&&window.__tetraAutoplay()", null) else { t.autoplay = true; t.view.loadUrl(address) }
            if (o.optBoolean("behind")) {
                kotlinx.coroutines.withTimeoutOrNull(8000) { while (!t.media.playing) delay(250) }
                (context as? android.app.Activity)?.moveTaskToBack(true)
            }
            return@LaunchedEffect
        }
        val site = MediaSites.byId(o.optString("site"))
        val t = if (tab.url.isEmpty()) tab else open() // in a tab of its own, unless this one is empty
        t.first = site; player = true; menu = false; panel = null
        t.view.loadUrl(MediaSites.searchUrl(site, o.optString("q")))
    }

    fun go(input: String) {
        editing = false; panel = null; menu = false
        val target = BrowserRules.target(input)
        applyHtml(tab, target)
        webView.loadUrl(target)
    }
    fun setDesktop(t: BrowserTab, on: Boolean) {
        t.desktop = on
        t.view.settings.userAgentString = if (on) BrowserRules.desktopAgent(android.webkit.WebSettings.getDefaultUserAgent(context))
            else BrowserRules.mobileAgent(android.webkit.WebSettings.getDefaultUserAgent(context))
        if (wide) prefs.edit().putBoolean("desktop", on).apply() // a tablet: new tabs open the same way
        message = context.getString(if (on) R.string.browser_desktop else R.string.browser_mobile)
        if (t.url.isNotEmpty()) t.view.reload()
    }
    fun toggleHtml(t: BrowserTab) {
        val site = BrowserRules.site(BrowserRules.host(t.url)).takeIf { t.url.isNotEmpty() } ?: return
        htmlSites = if (t.html) htmlSites - site else htmlSites + site
        if (!private) prefs.edit().putStringSet("html_sites", htmlSites).apply()
        applyHtml(t, t.url)
        message = if (t.html) context.getString(R.string.browser_html_on, site) else context.getString(R.string.browser_html_off)
        t.view.reload()
    }
    /** A tab dropped on another (a new group, to be named) or on a group (into it). */
    fun dropOn(t: BrowserTab, target: String) {
        t.pinned = false
        val g = if (target.startsWith("g")) target.drop(1).toInt() else {
            val u = tabs.firstOrNull { "t${it.id}" == target }?.takeIf { !it.pinned && it.id != t.id } ?: return
            u.group ?: nextGroup++.also { g -> groups[g] = context.getString(R.string.browser_group_default, g); u.group = g; naming = g }
        }
        t.group = g; openGroups = openGroups + g
    }
    fun toggleSplit() {
        if (splitPair != null) { splitPair = null; return }
        val other = tabs.firstOrNull { it.id != currentId } ?: newTab(nextId++, "").also { tabs += it }
        other.view.onResume(); wake(other)
        splitPair = currentId to other.id
    }
    fun keep(v: BrowserVault) { vault = v; runCatching { BrowserSecrets.save(context, v) }.onFailure { WriterLog.e("Browser: the vault not saved", it) } }
    fun keepBookmarks(b: List<Bookmark>) { bookmarks = b; prefs.edit().putString("bookmarks", BrowserRules.bookmarksJson(b)).apply() }
    var filing by remember { mutableStateOf<Bookmark?>(null) } // a favourite being put in a folder
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { s -> FirefoxImport.read(s.readBytes().decodeToString()) } }.getOrNull() }
            if (r == null) { imported = context.getString(R.string.browser_import_unknown) to null; return@launch }
            keep(vault.with(BrowserVault(logins = r.logins)))
            keepBookmarks((bookmarks + r.bookmarks).distinctBy { it.url })
            WriterLog.i("Browser: imported ${r.logins.size} logins, ${r.bookmarks.size} bookmarks")
            imported = context.getString(R.string.browser_imported, r.logins.size, r.bookmarks.size) to uri.takeIf { r.logins.isNotEmpty() }
        }
    }

    BackHandler { when { fullView != null -> fullDone?.onCustomViewHidden(); player -> player = false; editing -> editing = false; panel != null -> panel = null; menu -> menu = false; webView.canGoBack() -> webView.goBack(); else -> back() } }

    val url = tab.url
    val host = BrowserRules.host(url)
    val fitting = remember(tab.kinds, vault, host) {
        Triple(if ("password" in tab.kinds) vault.logins.filter { BrowserRules.sameHost(it.host, host) } else emptyList(),
            if (tab.kinds.any { it in BrowserVault.ADDRESS }) vault.addresses else emptyList(),
            if (tab.kinds.any { it.startsWith("cc-") }) vault.cards else emptyList())
    }
    val canFill = fitting.first.isNotEmpty() || fitting.second.isNotEmpty() || fitting.third.isNotEmpty()
    // ♥ (the user, 2 Nov: "a heart for favorites"): the start page's tiles; saved in Private too — a favourite is no history
    val marked = url.isNotEmpty() && bookmarks.any { it.url == url }
    fun heart() {
        keepBookmarks(if (marked) bookmarks.filter { it.url != url } else bookmarks + Bookmark(url, webView.title.orEmpty().ifEmpty { host }))
        message = context.getString(if (marked) R.string.browser_unbookmarked else R.string.browser_bookmarked)
    }
    val imeOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    val showBar = (!lowered || editing || imeOpen || menu || panel != null) && (!autohide || barShown || editing || imeOpen || menu || panel != null || url.isEmpty())
    // full screen while the bar is away: the status bar goes too (a swipe brings it for a moment)
    val activity = remember { generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull() }
    LaunchedEffect(showBar, player) {
        val w = activity?.window ?: return@LaunchedEffect
        androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (showBar || player) show(androidx.core.view.WindowInsetsCompat.Type.statusBars()) else hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }
    }
    DisposableEffect(Unit) { onDispose { activity?.window?.let { w -> androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).show(androidx.core.view.WindowInsetsCompat.Type.statusBars()) } } }
    // every tool once: the bar shows those chosen, the handle's sheet the rest (BrowserTools)
    @Composable fun toolOf(id: String): BrowserTool? = when (id) {
        "fill" -> BrowserTool(R.drawable.ic_key, stringResource(R.string.browser_fill), canFill, !private) { if (canFill) filling = true else message = context.getString(R.string.browser_nothing_to_fill) }
        "ads" -> BrowserTool(R.drawable.ic_shield, stringResource(if (adblock) R.string.browser_adblock_on else R.string.browser_adblock_off), adblock, badge = if (adblock && tab.blocked > 0) "${tab.blocked}" else null) {
            adblock = !adblock; AdBlock.set(context, adblock)
            message = context.getString(if (adblock) R.string.browser_adblock_on else R.string.browser_adblock_off)
            if (url.isNotEmpty()) webView.reload()
        }
        "private" -> BrowserTool(R.drawable.ic_visibility_off, stringResource(R.string.browser_private), private) { setPrivate(!private, url) }
        "desktop" -> BrowserTool(R.drawable.ic_computer, stringResource(R.string.browser_desktop), tab.desktop) { setDesktop(tab, !tab.desktop) }
        "html" -> BrowserTool(R.drawable.ic_code, stringResource(R.string.browser_html), tab.html, url.isNotEmpty()) { toggleHtml(tab) }
        "bookmarks" -> BrowserTool(R.drawable.ic_bookmark, stringResource(R.string.browser_bookmarks)) { panel = BrowserPanel.Bookmarks }
        "history" -> if (private) null else BrowserTool(R.drawable.ic_history, stringResource(R.string.browser_history)) { panel = BrowserPanel.History }
        "saved" -> if (private) null else BrowserTool(R.drawable.ic_lock, stringResource(R.string.browser_saved)) { panel = BrowserPanel.Saved }
        "copy" -> BrowserTool(R.drawable.ic_copy, stringResource(R.string.browser_copy_url), enabled = url.isNotEmpty()) {
            context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText(host, url))
            message = context.getString(R.string.browser_copied)
        }
        "share" -> BrowserTool(R.drawable.ic_share, stringResource(R.string.browser_share), enabled = url.isNotEmpty()) {
            runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                .putExtra(Intent.EXTRA_SUBJECT, tab.title), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        "split" -> if (!wide) null else BrowserTool(R.drawable.ic_view_column, stringResource(R.string.browser_split), splitPair != null) { toggleSplit() }
        "forward" -> if (wide) null else BrowserTool(R.drawable.ic_chevron_right, stringResource(R.string.browser_forward), enabled = tab.canForward) { webView.goForward() }
        "player" -> BrowserTool(R.drawable.ic_play, stringResource(R.string.browser_player), enabled = media.has) { player = true }
        "import" -> if (private) null else BrowserTool(R.drawable.ic_file_download, stringResource(R.string.browser_import)) { imported = null; panel = BrowserPanel.Import }
        "tabs_run" -> BrowserTool(R.drawable.ic_tab, stringResource(R.string.browser_tabs_run), tabsRun) {
            tabsRun = !tabsRun; prefs.edit().putBoolean("tabs_run", tabsRun).apply()
            message = context.getString(if (tabsRun) R.string.browser_tabs_run_on else R.string.browser_tabs_run_off)
        }
        "autohide" -> BrowserTool(R.drawable.ic_fullscreen, stringResource(R.string.browser_autohide), autohide) { autohide = !autohide; barShown = true; prefs.edit().putBoolean("autohide", autohide).apply() }
        "background" -> BrowserTool(R.drawable.ic_headphones, stringResource(R.string.browser_background), background) {
            background = !background; BrowserMedia.setBackground(context, background)
            message = context.getString(if (background) R.string.browser_background_on else R.string.browser_background_off)
        }
        "close" -> BrowserTool(R.drawable.ic_close, stringResource(R.string.common_close)) { back() }
        else -> null
    }
    val editBar = { editTools = true; menu = true; editing = false }
    val bar = if (private) PRIVATE_COLOR else MaterialTheme.colorScheme.surface
    val onBar = if (private) Color.White else MaterialTheme.colorScheme.onSurface

    // ☰ at the bar's end when it is at the top: the tools not on the bar, as a computer's browser menu
    @Composable fun topMenu() = Box {
        IconButton(onClick = { menu = true; editing = false }, Modifier.size(44.dp)) { Icon(painterResource(R.drawable.ic_menu), stringResource(R.string.browser_menu)) }
        DropdownMenu(expanded = menu && !editTools, onDismissRequest = { menu = false }) {
            (BrowserTools.ALL - onBarTools.toSet()).forEach { id ->
                val t = toolOf(id) ?: return@forEach
                DropdownMenuItem(text = { Text(t.label) }, onClick = { menu = false; t.act() }, enabled = t.enabled,
                    leadingIcon = { Icon(painterResource(t.icon), null, tint = if (t.on) BROWSER_COLOR else LocalContentColor.current) })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) }, onClick = { editTools = true }, leadingIcon = { Icon(painterResource(R.drawable.ic_edit), null) })
        }
    }
    /** The bar, with its line to the page: under the page, or over it when it is at the top. */
    @Composable fun ColumnScope.barArea() {
        @Composable fun line() = if (tab.progress < 100 && url.isNotEmpty()) LinearProgressIndicator(progress = { tab.progress / 100f }, Modifier.fillMaxWidth().height(2.dp), color = BROWSER_COLOR)
            else if (showBar) HorizontalDivider(color = onBar.copy(alpha = 0.2f)) else Unit
        if (!top) line()
        // lowered: only a black strip with the handle; a tap or a drag up brings the bar back
        if (lowered && !showBar && !top) {
            var up by remember { mutableFloatStateOf(0f) }
            Box(Modifier.fillMaxWidth().height(22.dp).background(Color.Black).clickable(onClickLabel = stringResource(R.string.browser_menu)) { lowered = false }
                .pointerInput(Unit) { detectVerticalDragGestures(onDragStart = { up = 0f }, onVerticalDrag = { _, d -> up += d }, onDragEnd = { if (up < -20) lowered = false }) },
                contentAlignment = Alignment.Center) { Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.35f))) }
        }
        androidx.compose.animation.AnimatedVisibility(showBar, enter = androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.shrinkVertically()) {
        CompositionLocalProvider(LocalContentColor provides onBar) {
            val shown = onBarTools.mapNotNull { id -> toolOf(id) }
            @Composable fun tool(t: BrowserTool, size: Modifier) = BrowserSmall(t.icon, t.label, t.on, t.enabled, t.badge, size, long = editBar, click = t.act)
            Column {
            // the handle, as Tetra's others (the user, 2 Nov): a tap or a drag up opens the sheet of the other tools
            var drag by remember { mutableFloatStateOf(0f) }
            if (!top) Box(Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.browser_menu)) { menu = true; editing = false }.pointerInput(Unit) {
                detectVerticalDragGestures(onDragStart = { drag = 0f }, onVerticalDrag = { _, d -> drag += d }, onDragEnd = { if (drag < -30) { menu = true; editing = false } else if (drag > 30) { lowered = true; editing = false } })
            }.heightIn(min = 24.dp), contentAlignment = Alignment.Center) { // a finger's worth of height, though the pill is thin
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(onBar.copy(alpha = 0.4f)))
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 10.dp, end = 10.dp, bottom = 8.dp, top = if (top) 8.dp else 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    @Composable fun addressRow() {
                    // row 1: Back (a tablet: and Forward), the address, and a tablet's tools in one framed line with ☰ at its end (the user's sketch, 2 Nov)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (wide) {
                            BrowserRound(R.drawable.ic_chevron_left, stringResource(R.string.common_back), tab.canBack, 56) { webView.goBack() }
                            BrowserRound(R.drawable.ic_chevron_right, stringResource(R.string.browser_forward), tab.canForward, 44) { webView.goForward() }
                        }
                        Surface(color = onBar.copy(alpha = 0.08f), shape = CircleShape, modifier = Modifier.weight(if (wide) 0.8f else 1f).height(48.dp)) {
                            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (editing) {
                                    val focus = remember { FocusRequester() }
                                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                                    Box(Modifier.weight(1f)) {
                                        if (typed.text.isEmpty()) Text(stringResource(R.string.browser_address), color = onBar.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        BasicTextField(typed, { typed = it }, singleLine = true, textStyle = TextStyle(color = onBar, fontSize = 16.sp), cursorBrush = SolidColor(onBar),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                                            keyboardActions = KeyboardActions(onGo = { if (typed.text.isNotBlank()) go(typed.text) }), modifier = Modifier.fillMaxWidth().focusRequester(focus))
                                    }
                                } else Row(Modifier.weight(1f).fillMaxHeight().clickable { typed = TextFieldValue(url, TextRange(0, url.length)); editing = true; panel = null; menu = false },
                                    verticalAlignment = Alignment.CenterVertically) {
                                    if (url.startsWith("https://")) Icon(painterResource(R.drawable.ic_lock), null, Modifier.size(16.dp).padding(end = 4.dp))
                                    // HTML only on: said on the bar, so a page that looks broken makes sense
                                    if (tab.html) Icon(painterResource(R.drawable.ic_code), stringResource(R.string.browser_html), Modifier.padding(end = 6.dp).size(18.dp), tint = BROWSER_COLOR)
                                    Text(host.removePrefix("www.").ifEmpty { stringResource(R.string.browser_address) }, Modifier.weight(1f), color = if (host.isEmpty()) onBar.copy(alpha = 0.6f) else onBar,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    // ♥ at the end of the address, as Chrome's star, so the row is no wider
                                    if (url.isNotEmpty()) IconButton(onClick = ::heart, Modifier.size(36.dp)) { BrowserHeart(marked) }
                                }
                            }
                        }
                        if (wide) Surface(shape = MaterialTheme.shapes.large, color = Color.Transparent, border = androidx.compose.foundation.BorderStroke(1.dp, onBar.copy(alpha = 0.25f)),
                            modifier = Modifier.weight(1f).height(56.dp)) {
                            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                    shown.forEach { tool(it, Modifier.size(width = 48.dp, height = 44.dp)) }
                                }
                                if (top) topMenu()
                            }
                        }
                        if (!wide) Surface(onClick = { webView.goBack() }, enabled = tab.canBack, shape = CircleShape, color = if (tab.canBack) BROWSER_COLOR else onBar.copy(alpha = 0.08f),
                            contentColor = if (tab.canBack) Color.White else onBar.copy(alpha = 0.35f), modifier = Modifier.size(48.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.common_back)) }
                        }
                        if (top && !wide) topMenu()
                    }
                    }
                    @Composable fun tabsRow() {
                    // row 2: the tabs (☰'s rest is a sheet from the bottom, as in Tetra's other apps)
                    BrowserTabs(tabs, groups, openGroups, currentId, wide, switch = ::switch, close = ::close, add = { open(); editing = true; typed = TextFieldValue("") },
                        fold = { g -> openGroups = if (g in openGroups) openGroups - g else openGroups + g }, options = { tabOptions = it.id }, groupOptions = { groupOptions = it }, dropOn = ::dropOn)
                    }
                    if (top) { tabsRow(); addressRow() } else { addressRow(); tabsRow() }
                }
                // a phone: two columns of three small ones (the user's choice, by a long press)
                if (!wide) shown.chunked(3).forEach { column ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { column.forEach { tool(it, Modifier.size(width = 48.dp, height = 32.dp)) } }
                }
            }
            }
        } }
        if (top) line()
    }

    Column(Modifier.fillMaxSize().background(bar).statusBarsPadding().navigationBarsPadding().imePadding()) {
        if (top && !player) barArea()
        Box(Modifier.weight(if (player) 0.6f else 1f).fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            val panes = splitPair?.takeIf { wide && !player }?.let { p -> listOfNotNull(tabs.firstOrNull { it.id == p.first }, tabs.firstOrNull { it.id == p.second }) }?.takeIf { it.size == 2 }
            @Composable fun pane(t: BrowserTab, m: Modifier) = Box(m.then(if (panes != null && t.id == currentId) Modifier.border(2.dp, BROWSER_COLOR) else Modifier)
                .pointerInput(t.id, panes != null) { if (panes != null) awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); if (t.id != currentId) currentId = t.id } }) {
                key(t.id) { AndroidView({ (t.view.parent as? android.view.ViewGroup)?.removeView(t.view); t.view }, Modifier.fillMaxSize()) }
                if (t.url.isEmpty()) BrowserStart(private, BrowserRules.front(bookmarks), file = { filing = it }) { u -> currentId = t.id; editing = false; val target = BrowserRules.target(u); applyHtml(t, target); t.view.loadUrl(target) }
                if (t.pull > 8f) Surface(shape = CircleShape, color = if (t.pull >= PULL_DP) BROWSER_COLOR else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (t.pull >= PULL_DP) Color.White else MaterialTheme.colorScheme.onSurface, shadowElevation = 4.dp,
                    modifier = Modifier.align(Alignment.TopCenter).offset(y = (t.pull.coerceAtMost(PULL_DP) * 0.6f).dp).size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.browser_reload), Modifier.size(22.dp).graphicsLayer { rotationZ = t.pull * 3 }) }
                }
            }
            val upright = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenHeightDp > it.screenWidthDp }
            when {
                panes == null -> pane(tab, Modifier.fillMaxSize())
                upright -> Column(Modifier.fillMaxSize()) { panes.forEach { pane(it, Modifier.weight(1f).fillMaxWidth()) } }
                else -> Row(Modifier.fillMaxSize()) { panes.forEach { pane(it, Modifier.weight(1f).fillMaxHeight()) } }
            }
            if (editing) {
                val q = typed.text.trim()
                val found = remember(q, visits, bookmarks) {
                    (bookmarks.map { it.url to it.title } + visits.map { it.url to it.title }).distinctBy { it.first }
                        .filter { q.isEmpty() || it.first.contains(q, true) || it.second.contains(q, true) }.take(8)
                }
                if (found.isNotEmpty()) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column { found.forEach { (u, t) -> BrowserRow(t, u, click = { go(u) }) } }
                }
            }
            // a playlist or a queue: Player mode offered in a strip from the bottom, until closed
            val list = MediaSites.listId(url) ?: host.takeIf { media.queue }
            androidx.compose.animation.AnimatedVisibility(!player && panel == null && !editing && list != null && list !in dismissed, Modifier.align(Alignment.BottomCenter),
                enter = androidx.compose.animation.slideInVertically { it }, exit = androidx.compose.animation.slideOutVertically { it }) {
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                    Row(Modifier.padding(start = 18.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.browser_playlist_found), Modifier.weight(1f))
                        IconButton(onClick = { player = true }) { Icon(painterResource(R.drawable.ic_play), stringResource(R.string.browser_player)) }
                        IconButton(onClick = { list?.let { dismissed = dismissed + it } }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_close)) }
                    }
                }
            }
            message?.let {
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape, modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp, start = 16.dp, end = 16.dp)) {
                    Text(it, Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                }
            }
        }
        if (player) { HorizontalDivider(color = onBar.copy(alpha = 0.2f)); CompositionLocalProvider(LocalContentColor provides onBar) {
            BrowserPlayer(Modifier.weight(0.4f), media, key = { mediaKey(it) }, marked = marked, heart = ::heart, forMaps = BrowserMedia.station(context)?.first == url,
                setForMaps = { BrowserMedia.setStation(context, url, webView.title.orEmpty().ifEmpty { host }); message = context.getString(R.string.browser_for_maps_set) },
                exit = { player = false }) }
        } else if (!top) barArea()
    }

    offer?.let { o ->
        AlertDialog(onDismissRequest = { offer = null }, title = { Text(stringResource(R.string.browser_save_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                o.logins.forEach { Text(stringResource(R.string.browser_save_login, it.host, it.user.ifEmpty { "—" })) }
                o.addresses.forEach { Text(stringResource(R.string.browser_save_address, addressLabel(it))) }
                o.cards.forEach { Text(stringResource(R.string.browser_save_card, it.last4)) }
                Text(stringResource(R.string.browser_saved_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } },
            confirmButton = { TextButton(onClick = { keep(vault.with(o)); offer = null }) { Text(stringResource(R.string.common_save)) } },
            dismissButton = { TextButton(onClick = { offer = null }) { Text(stringResource(R.string.browser_not_now)) } })
    }
    // the handle's sheet: the tools not on the bar (the user, 2 Nov: "like the rest of the Tetra apps a modal menu from the bottom");
    // a long press, there or on the bar: every tool, a tap moves it between the bar and the sheet
    // open in full and scrolling: every tool is in sight, the last row too
    if (menu && (!top || editTools)) ModalBottomSheet(onDismissRequest = { menu = false; editTools = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        var full by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            if (editTools) Row(Modifier.padding(start = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (full) R.string.browser_bar_full else R.string.browser_edit_tools), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = if (full) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { editTools = false }) { Text(stringResource(R.string.common_done)) }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                (if (editTools) BrowserTools.ALL else BrowserTools.ALL - onBarTools.toSet()).forEach { id ->
                    val t = toolOf(id) ?: return@forEach
                    BrowserTile(t.icon, t.label, t.enabled || editTools, on = !editTools && t.on, pinned = editTools && id in onBarTools, long = { editTools = true }) {
                        if (!editTools) { menu = false; t.act(); return@BrowserTile }
                        val next = BrowserTools.toggle(onBarTools, id, wide)
                        full = next == null
                        if (next != null) { onBarTools = next; prefs.edit().putString(barKey, next.joinToString(",")).apply() }
                    }
                }
            }
        }
    }
    // Bookmarks, History, Passwords and Import: modal sheets too
    panel?.let { p -> ModalBottomSheet(onDismissRequest = { panel = null }) {
        BrowserPanelView(p, close = { panel = null }, bookmarks = bookmarks, visits = visits, vault = vault, imported = imported,
                    open = ::go, dropBookmark = { b -> keepBookmarks(bookmarks - b) }, pinBookmark = { b -> keepBookmarks(bookmarks.map { if (it == b) it.copy(pinned = !it.pinned) else it }) }, fileBookmark = { filing = it },
                    keepHistory = keepHistory,
                    clearHistory = { visits = emptyList(); prefs.edit().remove("history").apply() },
                    keep = ::keep, choose = { importer.launch(arrayOf("*/*")) },
                    deleteFile = { u ->
                        val gone = runCatching { DocumentsContract.deleteDocument(context.contentResolver, u) }.getOrDefault(false)
                        message = context.getString(if (gone) R.string.browser_file_deleted else R.string.browser_file_not_deleted)
                        if (gone) imported = imported?.let { it.first to null }
                    })
    } }
    // a long press on a tab: pin, out of its group, close
    tabOptions?.let { id -> tabs.firstOrNull { it.id == id } }?.let { t ->
        BrowserOptions(t.title.ifBlank { BrowserRules.host(t.url) }.ifBlank { stringResource(R.string.browser_tab_new) }, dismiss = { tabOptions = null }, listOfNotNull(
            stringResource(if (t.pinned) R.string.browser_unpin else R.string.browser_pin) to { t.pinned = !t.pinned; t.group = null },
            t.group?.let { stringResource(R.string.browser_ungroup) to { t.group = null } },
            stringResource(R.string.browser_tab_close) to { close(t) }))
    }
    // a long press on a group: rename, keep its pages as bookmarks, close it all
    groupOptions?.let { g ->
        val members = tabs.filter { it.group == g }
        BrowserOptions(groups[g].orEmpty(), dismiss = { groupOptions = null }, listOf(
            stringResource(R.string.common_rename) to { naming = g },
            stringResource(R.string.browser_group_bookmark) to {
                // shortcut: a group is kept as its pages' bookmarks, not as one item that opens them all; add a bookmark kind if that is wanted
                keepBookmarks((bookmarks + members.filter { it.url.isNotEmpty() }.map { Bookmark(it.url, it.title.ifBlank { BrowserRules.host(it.url) }) }).distinctBy { it.url })
                message = context.getString(R.string.browser_group_bookmarked)
            },
            stringResource(R.string.browser_group_close) to { members.forEach(::close); groups.remove(g) }))
    }
    naming?.let { g ->
        var name by remember(g) { mutableStateOf(groups[g].orEmpty()) }
        AlertDialog(onDismissRequest = { naming = null }, title = { Text(stringResource(R.string.browser_group_name)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) groups[g] = name.trim(); naming = null }) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { TextButton(onClick = { naming = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
    if (filling) BrowserFillStrip(fitting.first, fitting.second, fitting.third, dismiss = { filling = false }) { data, forHost ->
        filling = false
        // the page may have moved on while the strip was open: a password goes only to its own host
        if (forHost == null || BrowserRules.sameHost(BrowserRules.host(webView.url), forHost)) webView.evaluateJavascript("window.__tetraFill&&window.__tetraFill($data)", null)
    }
    fullView?.let { v ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { fullDone?.onCustomViewHidden() },
            androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            val window = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            LaunchedEffect(window) { window?.let { w -> androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE } } }
            AndroidView({ (v.parent as? android.view.ViewGroup)?.removeView(v); v }, Modifier.fillMaxSize().background(Color.Black))
        }
    }
    filing?.let { b -> FolderDialog(b, BrowserRules.folders(bookmarks), close = { filing = null }) { f ->
        keepBookmarks(bookmarks.map { if (it.url == b.url) it.copy(folder = f) else it }); filing = null
    } }
}

@Composable private fun BrowserHeart(on: Boolean) =
    Icon(painterResource(if (on) R.drawable.ic_favorite else R.drawable.ic_favorite_border), stringResource(R.string.browser_favorite), Modifier.size(22.dp),
        tint = if (on) Color(0xFFE53950) else LocalContentColor.current)

/** A tablet's Back and Forward: round, as the sketch, dimmed when there is nowhere to go. */
@Composable private fun BrowserRound(icon: Int, label: String, enabled: Boolean, size: Int, click: () -> Unit) =
    Surface(onClick = click, enabled = enabled, shape = CircleShape, color = if (enabled) BROWSER_COLOR else LocalContentColor.current.copy(alpha = 0.08f),
        contentColor = if (enabled) Color.White else LocalContentColor.current.copy(alpha = 0.35f), modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), label, Modifier.size((size * 0.5f).dp)) }
    }

/** A tool, wherever it stands: on the bar or in the handle's sheet. */
private class BrowserTool(val icon: Int, val label: String, val on: Boolean = false, val enabled: Boolean = true, val badge: String? = null, val act: () -> Unit)

/** One of the handle's sheet: its icon, its name small under it, lit when on; in edit mode, framed when it is on the bar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun BrowserTile(icon: Int, label: String, enabled: Boolean, on: Boolean, pinned: Boolean, long: () -> Unit, click: () -> Unit) {
    val fg = if (on) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f)
    Box(Modifier.size(width = 96.dp, height = 84.dp).clip(MaterialTheme.shapes.large).background(if (on) BROWSER_COLOR else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
        .then(if (pinned) Modifier.border(2.dp, BROWSER_COLOR, MaterialTheme.shapes.large) else Modifier)
        .combinedClickable(onClick = { if (enabled) click() }, onLongClick = long, onLongClickLabel = stringResource(R.string.browser_edit_tools))) {
        Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(painterResource(icon), null, Modifier.size(26.dp), tint = fg)
            Text(label, Modifier.padding(top = 6.dp), color = fg, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        if (pinned) Icon(painterResource(R.drawable.ic_push_pin), null, Modifier.align(Alignment.TopEnd).padding(6.dp).size(14.dp), tint = BROWSER_COLOR)
    }
}

/** A choice of actions from the bottom, as Tetra's others (no popup lists on a touch screen). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun BrowserOptions(title: String, dismiss: () -> Unit, options: List<Pair<String, () -> Unit>>) =
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp))
            options.forEach { (label, act) -> Text(label, Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { dismiss(); act() }.padding(horizontal = 8.dp, vertical = 14.dp)) }
        }
    }

/**
 * The open tabs: pinned ones as circles, then each with its initial and a short title, a group as one chip that unfolds;
 * × closes the chosen tab, + opens one. A long press gives a tab's choices; held and dragged onto another tab or a group, it joins it.
 */
@Composable private fun BrowserTabs(tabs: List<BrowserTab>, groups: Map<Int, String>, open: Set<Int>, current: Int, wide: Boolean, switch: (BrowserTab) -> Unit, close: (BrowserTab) -> Unit,
                                    add: () -> Unit, fold: (Int) -> Unit, options: (BrowserTab) -> Unit, groupOptions: (Int) -> Unit, dropOn: (BrowserTab, String) -> Unit) =
    BoxWithConstraints(Modifier.fillMaxWidth().height(40.dp)) { // a fixed height: the bar measures its rows by their intrinsic height
    // a tablet: equal tabs, four across (more scroll); a phone: each as long as its name, up to 150 dp
    val width = if (wide) (maxWidth - 52.dp) / 4 - 6.dp else 0.dp
    val items = BrowserTabRow.items(tabs.map { TabInfo(it.id, it.pinned, it.group) }, open)
    val bounds = remember { mutableMapOf<String, androidx.compose.ui.geometry.Rect>() } // each chip on the screen, for a drop
    var dragging by remember { mutableStateOf<Int?>(null) }
    var moved by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(current, items.size) { items.indexOfFirst { it is TabRowItem.Tab && it.id == current }.takeIf { it >= 0 }?.let { scroll.animateScrollToItem(it) } } // a new tab in sight
        LazyRow(Modifier.weight(1f), state = scroll, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items, key = { if (it is TabRowItem.Group) "g${it.id}" else "t${(it as TabRowItem.Tab).id}" }) { item ->
                when (item) {
                    is TabRowItem.Group -> Surface(shape = CircleShape, color = Color.Transparent, border = androidx.compose.foundation.BorderStroke(1.5.dp, BROWSER_COLOR),
                        modifier = Modifier.height(40.dp).onGloballyPositioned { bounds["g${item.id}"] = it.boundsInRoot() }
                            .pointerInput(item.id) { detectTapGestures(onTap = { fold(item.id) }, onLongPress = { groupOptions(item.id) }) }) {
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(18.dp), tint = BROWSER_COLOR)
                            Text(groups[item.id].orEmpty(), Modifier.padding(start = 6.dp).widthIn(max = 120.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${item.count}", Modifier.padding(start = 6.dp), color = BROWSER_COLOR, style = MaterialTheme.typography.labelLarge)
                            Icon(painterResource(if (item.open) R.drawable.ic_chevron_left else R.drawable.ic_chevron_right), null, Modifier.size(16.dp))
                        }
                    }
                    is TabRowItem.Tab -> {
                        val t = tabs.first { it.id == item.id }
                        val on = t.id == current
                        val key = "t${t.id}"
                        val initial = BrowserRules.site(BrowserRules.host(t.url)).take(1).uppercase().ifEmpty { "+" }
                        val gestures = Modifier.onGloballyPositioned { bounds[key] = it.boundsInRoot() }
                            .graphicsLayer { if (dragging == t.id) { translationX = moved.x; translationY = moved.y; alpha = 0.85f } }
                            .pointerInput(t.id) { detectTapGestures(onTap = { switch(t) }, onLongPress = {}) } // a long press is the drag's, below
                            .pointerInput(t.id) {
                                detectDragGesturesAfterLongPress(onDragStart = { dragging = t.id; moved = androidx.compose.ui.geometry.Offset.Zero },
                                    onDrag = { change, d -> change.consume(); moved += d },
                                    onDragEnd = {
                                        val at = bounds[key]?.center?.plus(moved)
                                        val target = at?.let { p -> bounds.entries.firstOrNull { it.key != key && it.value.contains(p) }?.key }
                                        if (moved.getDistance() < 24 * density) options(t) else if (target != null && !t.pinned) dropOn(t, target)
                                        dragging = null; moved = androidx.compose.ui.geometry.Offset.Zero
                                    },
                                    onDragCancel = { dragging = null; moved = androidx.compose.ui.geometry.Offset.Zero })
                            }
                        if (t.pinned) Box(gestures.size(40.dp).clip(CircleShape).background(if (on) BROWSER_COLOR else LocalContentColor.current.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                            Text(initial, color = if (on) Color.White else LocalContentColor.current, style = MaterialTheme.typography.labelLarge)
                        } else Surface(shape = CircleShape, color = if (on) BROWSER_COLOR else if (item.grouped) BROWSER_COLOR.copy(alpha = 0.18f) else LocalContentColor.current.copy(alpha = 0.08f),
                            contentColor = if (on) Color.White else LocalContentColor.current,
                            modifier = gestures.height(40.dp).then(if (wide) Modifier.width(width) else Modifier.widthIn(max = 150.dp))) {
                            Row(Modifier.padding(start = 6.dp, end = if (on) 0.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(26.dp).clip(CircleShape).background(LocalContentColor.current.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                                    Text(initial, fontSize = 12.sp, maxLines = 1)
                                }
                                val name = t.title.ifBlank { BrowserRules.host(t.url).removePrefix("www.") }.ifBlank { stringResource(R.string.browser_tab_new) }
                                Text(name, Modifier.padding(start = 6.dp).weight(1f, fill = wide), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (on) IconButton(onClick = { close(t) }, Modifier.size(36.dp)) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.browser_tab_close), Modifier.size(16.dp)) }
                            }
                        }
                    }
                }
            }
        }
        IconButton(onClick = add, Modifier.size(40.dp)) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.browser_tab_new)) }
    }
}

private fun addressLabel(a: Map<String, String>) = a["name"] ?: listOfNotNull(a["given-name"], a["family-name"]).joinToString(" ").ifEmpty { a["email"] ?: a["street-address"] ?: a.values.first() }

/**
 * The first page: the favourites as tiles (the user, 2 Nov: "or nothing"), then their folders ("so we can have Radios", 2 Nov),
 * a tap opening one; a tile held is put in a folder. Private says what it keeps.
 */
@Composable private fun BrowserStart(private: Boolean, bookmarks: List<Bookmark>, file: (Bookmark) -> Unit, open: (String) -> Unit) =
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        var folder by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
        BackHandler(folder.isNotEmpty()) { folder = "" }
        if (private) Text(stringResource(R.string.browser_private_start), Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (folder.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { folder = "" }) { Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.common_back)) }
            Text(folder, style = MaterialTheme.typography.titleMedium)
        }
        @Composable fun Tile(label: String, letter: String, color: Color, icon: Int?, click: () -> Unit, hold: (() -> Unit)?) =
            Column(Modifier.clip(MaterialTheme.shapes.medium).combinedClickable(onClick = click, onLongClick = hold).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                    if (icon != null) Icon(painterResource(icon), null, tint = Color.White) else Text(letter, color = Color.White, style = MaterialTheme.typography.titleLarge)
                }
                Text(label, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        val shown = bookmarks.filter { it.folder == folder } // already in the start page's order (BrowserRules.front)
        LazyVerticalGrid(GridCells.Adaptive(88.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            gridItems(shown, key = { it.url }) { b ->
                val host = BrowserRules.site(BrowserRules.host(b.url))
                Tile(b.title.ifBlank { host }, host.take(1).uppercase(), TILE_COLORS[(host.hashCode() and 0x7fffffff) % TILE_COLORS.size], null, { open(b.url) }) { file(b) }
            }
            if (folder.isEmpty()) gridItems(BrowserRules.folders(bookmarks), key = { "folder:$it" }) { f -> Tile(f, "", BROWSER_COLOR, R.drawable.ic_folder, { folder = f }, null) }
        }
    }

/** A favourite into a folder: one of those there are, a new one, or none. */
@Composable private fun FolderDialog(b: Bookmark, folders: List<String>, close: () -> Unit, put: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text(b.title.ifBlank { BrowserRules.host(b.url) }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                (listOf("") + folders).forEach { f ->
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { put(f) }.padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = b.folder == f, onClick = { put(f) })
                        Text(f.ifEmpty { stringResource(R.string.browser_no_folder) })
                    }
                }
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.browser_new_folder)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { put(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.common_add)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.common_cancel)) } })
}

private val TILE_COLORS = listOf(Color(0xFF1A87FD), Color(0xFF2E7D32), Color(0xFFC62828), Color(0xFF6A1B9A), Color(0xFFEF6C00), Color(0xFF00838F), Color(0xFF4E342E), Color(0xFFAD1457))

@Composable private fun BrowserRow(title: String, url: String, click: () -> Unit, remove: (() -> Unit)? = null, pinned: Boolean = false, pin: (() -> Unit)? = null, file: (() -> Unit)? = null) =
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(title.ifEmpty { BrowserRules.host(url) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // a favourite's pin (the user, 2 Nov): at the start page's front
        if (pin != null) IconButton(onClick = pin) { Icon(painterResource(R.drawable.ic_push_pin), stringResource(if (pinned) R.string.browser_unpin_favorite else R.string.browser_pin_favorite),
            tint = if (pinned) BROWSER_COLOR else LocalContentColor.current.copy(alpha = 0.5f)) }
        if (file != null) IconButton(onClick = file) { Icon(painterResource(R.drawable.ic_folder), stringResource(R.string.browser_to_folder), tint = LocalContentColor.current.copy(alpha = 0.6f)) }
        if (remove != null) IconButton(onClick = remove) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_delete)) }
    }

@Composable
private fun BrowserPanelView(p: BrowserPanel, close: () -> Unit, bookmarks: List<Bookmark>, visits: List<Visit>, vault: BrowserVault, imported: Pair<String, Uri?>?,
                             open: (String) -> Unit, dropBookmark: (Bookmark) -> Unit, pinBookmark: (Bookmark) -> Unit, fileBookmark: (Bookmark) -> Unit, keepHistory: String, clearHistory: () -> Unit, keep: (BrowserVault) -> Unit, choose: () -> Unit, deleteFile: (Uri) -> Unit) =
    Box(Modifier.navigationBarsPadding()) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(when (p) { BrowserPanel.Bookmarks -> R.string.browser_bookmarks; BrowserPanel.History -> R.string.browser_history
                        BrowserPanel.Saved -> R.string.browser_saved; BrowserPanel.Import -> R.string.browser_import }), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (p == BrowserPanel.History && visits.isNotEmpty()) TextButton(onClick = clearHistory) { Text(stringResource(R.string.common_clear)) }
                    IconButton(onClick = close) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_close)) }
                }
            }
            @Composable fun note(text: String) = Text(text, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            @Composable fun head(text: String) = Text(text, Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = BROWSER_COLOR)
            when (p) {
                BrowserPanel.Bookmarks -> {
                    if (bookmarks.isEmpty()) item { note(stringResource(R.string.browser_no_bookmarks)) }
                    items(BrowserRules.front(bookmarks), key = { it.url }) { b -> BrowserRow(b.title.let { t -> if (b.folder.isEmpty()) t else "${b.folder} · $t" }, b.url, click = { open(b.url) }, remove = { dropBookmark(b) }, pinned = b.pinned, pin = { pinBookmark(b) }, file = { fileBookmark(b) }) }
                }
                BrowserPanel.History -> {
                    item { note(stringResource(when { visits.isEmpty() -> R.string.browser_no_history; keepHistory == BrowserRules.ON_EXIT -> R.string.browser_history_exit_note
                        keepHistory == BrowserRules.NEVER -> R.string.browser_history_never_note; else -> R.string.browser_history_note })) }
                    items(visits, key = { it.url }) { v -> BrowserRow(v.title, v.url, click = { open(v.url) }) }
                }
                BrowserPanel.Saved -> {
                    item { note(stringResource(if (vault.empty) R.string.browser_none_saved else R.string.browser_saved_note)) }
                    if (vault.logins.isNotEmpty()) item { head(stringResource(R.string.browser_passwords)) }
                    items(vault.logins, key = { "l" + it.host + "\u0000" + it.user }) { l -> BrowserRow(l.user.ifEmpty { "—" }, l.host, click = {}, remove = { keep(vault.copy(logins = vault.logins - l)) }) }
                    if (vault.addresses.isNotEmpty()) item { head(stringResource(R.string.browser_addresses)) }
                    items(vault.addresses) { a -> BrowserRow(addressLabel(a), a.values.joinToString(", "), click = {}, remove = { keep(vault.copy(addresses = vault.addresses - a)) }) }
                    if (vault.cards.isNotEmpty()) item { head(stringResource(R.string.browser_cards)) }
                    items(vault.cards, key = { "c" + it.number }) { c -> BrowserRow("•••• ${c.last4}", listOf(c.name, "${c.month}/${c.year}").filter { it.isNotBlank() && it != "/" }.joinToString(" · "), click = {}, remove = { keep(vault.copy(cards = vault.cards - c)) }) }
                }
                BrowserPanel.Import -> item {
                    note(stringResource(R.string.browser_import_note))
                    Button(onClick = choose, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = BROWSER_COLOR, contentColor = Color.White)) {
                        Text(stringResource(R.string.browser_choose_file))
                    }
                    imported?.let { (said, file) ->
                        Text(said, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge)
                        if (file != null) {
                            note(stringResource(R.string.browser_import_delete))
                            OutlinedButton(onClick = { deleteFile(file) }, Modifier.padding(horizontal = 20.dp)) { Text(stringResource(R.string.browser_delete_file)) }
                        }
                    }
                }
            }
        }
    }

/** Fill: what fits this page, a strip from the bottom (no popup lists on a touch screen); `fill` gets the values and, for a password, its host. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserFillStrip(logins: List<SavedLogin>, addresses: List<Map<String, String>>, cards: List<SavedCard>, dismiss: () -> Unit, fill: (JSONObject, String?) -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            Text(stringResource(R.string.browser_fill_title), Modifier.padding(horizontal = 8.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            logins.forEach { l -> BrowserRow(l.user.ifEmpty { "—" }, l.host, click = { fill(JSONObject().put("username", l.user).put("password", l.password), l.host) }) }
            addresses.forEach { a -> BrowserRow(addressLabel(a), a.values.joinToString(", "), click = { fill(JSONObject(a), null) }) }
            cards.forEach { c -> BrowserRow("•••• ${c.last4}", c.name, click = {
                fill(JSONObject().put("cc-number", c.number).put("cc-name", c.name).put("cc-exp-month", c.month).put("cc-exp-year", c.year).put("cc-exp", "${c.month}/${c.year.takeLast(2)}"), null)
            }) }
        }
    }
}

/** A button of ☰'s strip: its icon only (the user, 2 Nov: "SVGs for the buttons not text"); the name is for TalkBack. */
/** A tool on the bar: its icon, lit when on (Fill: when something fits), a number beside it for the ads blocked; a long press edits the bar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun BrowserSmall(icon: Int, label: String, on: Boolean, enabled: Boolean, badge: String?, size: Modifier, long: () -> Unit, click: () -> Unit) {
    val fg = if (on) Color.White else LocalContentColor.current.copy(alpha = if (enabled) 1f else 0.35f)
    Row(size.clip(CircleShape).background(if (on) BROWSER_COLOR else LocalContentColor.current.copy(alpha = 0.08f))
        .combinedClickable(onClick = { if (enabled) click() }, onLongClick = long, onClickLabel = label, onLongClickLabel = stringResource(R.string.browser_edit_tools)),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), label, Modifier.size(18.dp), tint = fg)
        if (badge != null) Text(badge, Modifier.padding(start = 3.dp), color = fg, fontSize = 11.sp, maxLines = 1)
    }
}

/** Player mode (the user's sketch, 2 Nov): under the page, ‹ a larger play › in a row, and a small exit above ›. */
@Composable private fun BrowserPlayer(modifier: Modifier, media: PageMedia, key: (String) -> Unit, marked: Boolean, heart: () -> Unit, forMaps: Boolean,
    setForMaps: () -> Unit, exit: () -> Unit) =
    Column(modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
        // ♥ here too: a station kept is one ‹ › go through (BrowserRules.hop)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(media.title, Modifier.weight(1f, fill = false), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = heart) {
                Icon(painterResource(if (marked) R.drawable.ic_favorite else R.drawable.ic_favorite_border), stringResource(R.string.browser_favorite), tint = if (marked) BROWSER_COLOR else LocalContentColor.current)
            }
            // 🚗: this page is what Maps' ♪ plays
            IconButton(onClick = setForMaps) {
                Icon(painterResource(R.drawable.ic_directions_car), stringResource(R.string.browser_for_maps), tint = if (forMaps) BROWSER_COLOR else LocalContentColor.current)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayerRound(R.drawable.ic_skip_previous, stringResource(R.string.browser_previous), 72) { key("prev") }
            PlayerRound(if (media.playing) R.drawable.ic_pause else R.drawable.ic_play, stringResource(if (media.playing) R.string.common_pause else R.string.browser_play), 96, lit = true) { key("toggle") }
            Box(contentAlignment = Alignment.Center) {
                PlayerRound(R.drawable.ic_skip_next, stringResource(R.string.browser_next), 72) { key("next") }
                Surface(onClick = exit, shape = CircleShape, color = LocalContentColor.current.copy(alpha = 0.1f), modifier = Modifier.align(Alignment.TopCenter).offset(y = (-48).dp).size(36.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.browser_player_exit), Modifier.size(18.dp)) }
                }
            }
        }
    }

@Composable private fun PlayerRound(icon: Int, label: String, size: Int, lit: Boolean = false, click: () -> Unit) =
    Surface(onClick = click, shape = CircleShape, color = if (lit) BROWSER_COLOR else LocalContentColor.current.copy(alpha = 0.1f),
        contentColor = if (lit) Color.White else LocalContentColor.current, modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), label, Modifier.size((size * 0.45f).dp)) }
    }
