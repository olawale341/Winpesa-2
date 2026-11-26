package com.winpesa.autobot

import com.winpesa.autobot.R
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var tvStatus: TextView
    private lateinit var tvAccountInfo: TextView
    private lateinit var btnAutoRun: Button
    private lateinit var btnDataMenu: Button
    private lateinit var btnStep1: Button
    private lateinit var btnStep2: Button
    private lateinit var btnStep3: Button
    private lateinit var btnStep4: Button

    private var activeMaharaUrl = "https://winpesa.ng/games/Mahara-Jackpot-9192"
    private val handler = Handler(Looper.getMainLooper())
    private val numbersList = mutableListOf<String>()
    private val patternsList = mutableListOf<String>()
    
    // Format: "phone|pattern|reason|originalAccountNum"
    private val skippedDataList = mutableListOf<String>()

    private var currentIndex = 0
    private var commonPassword = "YourDefaultPassword123"
    private var isAutoRunning = false
    private var isStepActive = false
    private var isLoggingOut = false
    private var speedMultiplier = 1.0f
    private var blockImages = false

    // Step 1: Login Retry Counter & Watchdog
    private var loginRetryCount = 0
    private var loginWatchdog: Runnable? = null

    // Step 2: Mahara Retry Counter & Watchdog
    private var maharaRetryCount = 0
    private var maharaWatchdog: Runnable? = null

    private var filePickTarget = "NUMBERS"
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleUploadedFile(it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvAccountInfo = findViewById(R.id.tvAccountInfo)
        btnAutoRun = findViewById(R.id.btnAutoRun)
        btnDataMenu = findViewById(R.id.btnDataMenu)
        btnStep1 = findViewById(R.id.btnStep1)
        btnStep2 = findViewById(R.id.btnStep2)
        btnStep3 = findViewById(R.id.btnStep3)
        btnStep4 = findViewById(R.id.btnStep4)
        webView = findViewById(R.id.webView)

        loadPersistedData()
        setupWebView()

        btnAutoRun.setOnClickListener { toggleAutoRun() }
        btnDataMenu.setOnClickListener { showDataManagementMenu() }
        btnStep1.setOnClickListener { 
            loginRetryCount = 0
            runStep1_Login() 
        }
        btnStep2.setOnClickListener { 
            maharaRetryCount = 0
            runStep2_OpenMahara() 
        }
        btnStep3.setOnClickListener { runStep3_PickAndBet() }
        btnStep4.setOnClickListener { runStep4_LogoutAndNext() }
    }

    private fun getDelay(baseMs: Long): Long {
        return (baseMs / speedMultiplier).toLong().coerceAtLeast(150L)
    }

    private fun simulateHardwareTouch(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val density = resources.displayMetrics.density
        val px = x * density
        val py = y * density
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, px, py, 0)
        val up = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, px, py, 0)
        webView.dispatchTouchEvent(down)
        webView.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.cacheMode = WebSettings.LOAD_DEFAULT

        s.blockNetworkImage = blockImages
        s.loadsImagesAutomatically = !blockImages

        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.userAgentString = s.userAgentString.replace("; wv", "")
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                tvStatus.text = "Loaded: $url"
                dismissBanners()
                if (url != null && url.contains("winpesa.ng")) {
                    detectDynamicMaharaLink()
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    safeDelay(1500L) { view?.reload() }
                }
            }
        }
        webView.loadUrl("https://winpesa.ng")
    }

    private fun safeDelay(delayMs: Long, action: () -> Unit) {
        handler.postDelayed({ action() }, delayMs)
    }

    private fun dismissBanners() {
        val js = """
            (function() {
                Array.from(document.querySelectorAll('button, div, span, a')).forEach(e => {
                    let t = (e.innerText||'').trim().toLowerCase();
                    if (t.includes('got it') || t === 'accept' || t === '×' || t === 'x') {
                        try { e.click(); } catch(err) {}
                    }
                });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun toggleAutoRun() {
        if (numbersList.isEmpty()) {
            Toast.makeText(this, "Load numbers first via ⚙️ DATA!", Toast.LENGTH_SHORT).show()
            return
        }
        if (isAutoRunning) {
            isAutoRunning = false
            isStepActive = false
            loginWatchdog?.let { handler.removeCallbacks(it) }
            maharaWatchdog?.let { handler.removeCallbacks(it) }
            handler.removeCallbacksAndMessages(null)
            btnAutoRun.text = "▶️ AUTO"
            btnAutoRun.backgroundTintList = getColorStateList(android.R.color.holo_green_dark)
            tvStatus.text = "⏸️ Auto-Run Paused."
        } else {
            isAutoRunning = true
            isStepActive = false
            btnAutoRun.text = "⏸️ PAUSE"
            btnAutoRun.backgroundTintList = getColorStateList(android.R.color.holo_red_dark)
            tvStatus.text = "▶️ Auto-Run (${speedMultiplier}x): Acc #${currentIndex + 1}..."
            loginRetryCount = 0
            runStep1_Login()
        }
    }

    private fun detectDynamicMaharaLink() {
        val js = "(function(){ let l = Array.from(document.querySelectorAll('a,div')).find(e => (e.getAttribute('href')||'').includes('Mahara-Jackpot')); return l ? (l.getAttribute('href').startsWith('http') ? l.getAttribute('href') : 'https://winpesa.ng'+l.getAttribute('href')) : null; })()"
        webView.evaluateJavascript(js) { res ->
            val clean = res?.replace("\"", "")?.trim()
            if (!clean.isNullOrEmpty() && clean != "null" && clean.contains("Mahara-Jackpot")) activeMaharaUrl = clean
        }
    }

    private fun sanitizePhone(phone: String): String {
        var p = phone.trim().replace(" ", "").replace("-", "")
        if (p.startsWith("+234")) p = p.substring(4)
        else if (p.startsWith("234") && p.length > 10) p = p.substring(3)
        else if (p.startsWith("0") && p.length == 11) p = p.substring(1)
        return p
    }

    private fun sanitizePattern(raw: String): String {
        var p = raw.replace(Regex("^[#\\d]+[.:\\-\\s)]+"), "")
        p = p.replace(Regex("[^1X2HDAMhda]"), "").uppercase()
        return p
    }

    // ========================================================
    // ⚙️ DATA MANAGEMENT MENU
    // ========================================================
    private fun showDataManagementMenu() {
        val imgLabel = if (blockImages) "🖼️ Game Images: BLOCKED (Data Saver ON)" else "🖼️ Game Images: ALLOWED (Normal)"

        val options = arrayOf(
            "📱 Numbers: Paste Text",
            "📁 Numbers: Upload .txt File",
            "🎯 Patterns: Paste Text (H-D-A)",
            "📁 Patterns: Upload .txt File",
            "🔑 Set Common Password",
            "⚡ Set Speed (${speedMultiplier}x)",
            imgLabel,
            "🔄 Re-run All Skipped Accounts (${skippedDataList.size})",
            "⚠️ View Skipped Accounts (${skippedDataList.size})",
            "🔢 Jump to Specific Account #",
            "🔄 Reset Progress to Account #1",
            "🗑️ WIPE ALL (Clear Numbers, Patterns & Reset)"
        )
        AlertDialog.Builder(this)
            .setTitle("⚙️ Data Manager")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showPasteDialog("NUMBERS")
                    1 -> { filePickTarget = "NUMBERS"; filePickerLauncher.launch("text/*") }
                    2 -> showPasteDialog("PATTERNS")
                    3 -> { filePickTarget = "PATTERNS"; filePickerLauncher.launch("text/*") }
                    4 -> showPasswordDialog()
                    5 -> showSpeedSelectionDialog()
                    6 -> toggleImageBlocking()
                    7 -> rerunSkippedAccounts()
                    8 -> showSkippedAccountsDialog()
                    9 -> showJumpToAccountDialog()
                    10 -> { currentIndex = 0; saveData(); updateUI(); Toast.makeText(this, "Reset to #1", Toast.LENGTH_SHORT).show() }
                    11 -> confirmWipeAllData()
                }
            }
            .setNegativeButton("Close", null).show()
    }

    private fun rerunSkippedAccounts() {
        if (skippedDataList.isEmpty()) {
            Toast.makeText(this, "No skipped accounts to re-run!", Toast.LENGTH_SHORT).show()
            return
        }

        val count = skippedDataList.size
        AlertDialog.Builder(this)
            .setTitle("🔄 Re-run $count Skipped Accounts?")
            .setMessage("This will load only the $count failed accounts (one by one) with their exact matching patterns. Ready to start?")
            .setPositiveButton("Yes, Start Re-run") { _, _ ->
                val newNumbers = mutableListOf<String>()
                val newPatterns = mutableListOf<String>()
                for (item in skippedDataList) {
                    val parts = item.split("|")
                    if (parts.isNotEmpty()) newNumbers.add(parts[0])
                    if (parts.size >= 2) newPatterns.add(parts[1])
                }

                numbersList.clear()
                numbersList.addAll(newNumbers)

                patternsList.clear()
                patternsList.addAll(newPatterns)

                skippedDataList.clear()
                currentIndex = 0
                isLoggingOut = false
                loginRetryCount = 0
                maharaRetryCount = 0

                saveData()
                updateUI()

                tvStatus.text = "Loaded $count skipped accounts! Starting..."
                if (!isAutoRunning) {
                    toggleAutoRun()
                } else {
                    runStep1_Login()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSkippedAccountsDialog() {
        val count = skippedDataList.size
        val lines = skippedDataList.map { item ->
            val p = item.split("|")
            val phone = p.getOrElse(0) { "" }
            val reason = p.getOrElse(2) { "Unknown" }
            val origNum = p.getOrElse(3) { "?" }
            "Acc #$origNum: $phone ($reason)"
        }

        val msg = if (count == 0) "No accounts have been skipped!" else "Total Skipped: $count\n\n" + lines.joinToString("\n")

        val builder = AlertDialog.Builder(this)
            .setTitle("⚠️ Skipped Accounts ($count)")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .setNeutralButton("Clear List") { _, _ ->
                skippedDataList.clear()
                saveData()
                updateUI()
                Toast.makeText(this, "Skipped list cleared!", Toast.LENGTH_SHORT).show()
            }

        if (count > 0) {
            builder.setNegativeButton("📋 Copy All") { _, _ ->
                val rawNumbers = skippedDataList.map { it.split("|")[0] }.joinToString("\n")
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Skipped Accounts", rawNumbers))
                Toast.makeText(this, "Copied $count phone numbers to clipboard!", Toast.LENGTH_LONG).show()
            }
        }
        builder.show()
    }

    private fun logSkippedAccount(phone: String, reason: String) {
        val pat = if (patternsList.isNotEmpty()) patternsList[currentIndex % patternsList.size] else ""
        val entry = "$phone|$pat|$reason|${currentIndex + 1}"
        skippedDataList.add(entry)
        saveData()
        updateUI()
        tvStatus.text = "⚠️ Skipped #${currentIndex + 1}: $phone ($reason)"
    }

    private fun toggleImageBlocking() {
        blockImages = !blockImages
        webView.settings.blockNetworkImage = blockImages
        webView.settings.loadsImagesAutomatically = !blockImages
        saveData()
        val status = if (blockImages) "Images BLOCKED: Ultra Fast & Low Data Usage!" else "Images ALLOWED: Normal mode"
        Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
    }

    private fun showSpeedSelectionDialog() {
        val presets = arrayOf("0.5x (Safe)", "1.0x (Standard)", "2.0x (Fast)", "5.0x (Turbo)", "Custom...")
        AlertDialog.Builder(this)
            .setTitle("⚡ Select Speed")
            .setItems(presets) { _, which ->
                when (which) {
                    0 -> setSpeed(0.5f)
                    1 -> setSpeed(1.0f)
                    2 -> setSpeed(2.0f)
                    3 -> setSpeed(5.0f)
                    4 -> {
                        val input = EditText(this)
                        input.hint = "e.g. 1.5, 3.0"
                        AlertDialog.Builder(this)
                            .setTitle("Enter Speed (0.2 - 5.0)")
                            .setView(input)
                            .setPositiveButton("Set") { _, _ ->
                                val custom = input.text.toString().trim().toFloatOrNull()
                                if (custom != null && custom in 0.2f..5.0f) setSpeed(custom)
                            }
                            .setNegativeButton("Cancel", null).show()
                    }
                }
            }
            .setNegativeButton("Back", null).show()
    }

    private fun setSpeed(newSpeed: Float) {
        speedMultiplier = newSpeed
        saveData()
        updateUI()
        Toast.makeText(this, "Speed set to ${speedMultiplier}x", Toast.LENGTH_SHORT).show()
    }

    private fun confirmWipeAllData() {
        AlertDialog.Builder(this)
            .setTitle("🗑️ Wipe All Data?")
            .setMessage("Delete all saved numbers, patterns, skipped accounts and reset progress?")
            .setPositiveButton("Yes, Wipe All") { _, _ ->
                numbersList.clear()
                patternsList.clear()
                skippedDataList.clear()
                currentIndex = 0
                isLoggingOut = false
                loginRetryCount = 0
                maharaRetryCount = 0
                val prefs = getSharedPreferences("winpesa_bot", MODE_PRIVATE)
                prefs.edit().clear().apply()
                saveData(); updateUI()
                tvStatus.text = "All data cleared! Ready for new batch."
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showJumpToAccountDialog() {
        val input = EditText(this)
        input.hint = "Account number e.g. 50"
        AlertDialog.Builder(this)
            .setTitle("Jump to Account #")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                val num = input.text.toString().trim().toIntOrNull()
                if (num != null && num in 1..numbersList.size) {
                    currentIndex = num - 1; saveData(); updateUI()
                    Toast.makeText(this, "Jumped to #$num", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showPasteDialog(type: String) {
        val input = EditText(this)
        input.setLines(8)
        input.hint = if (type == "NUMBERS") "Paste phone numbers (one per line)" else "Paste patterns (one per line)"
        AlertDialog.Builder(this)
            .setTitle(if (type == "NUMBERS") "Paste Numbers" else "Paste Patterns")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val lines = input.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                if (type == "NUMBERS") {
                    numbersList.clear()
                    numbersList.addAll(lines.map { sanitizePhone(it) })
                } else {
                    patternsList.clear()
                    patternsList.addAll(lines)
                }
                saveData(); updateUI()
                Toast.makeText(this, "Saved ${lines.size} items!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun handleUploadedFile(uri: Uri) {
        Thread {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val lines = stream.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
                    runOnUiThread {
                        if (filePickTarget == "NUMBERS") {
                            numbersList.clear()
                            numbersList.addAll(lines.map { sanitizePhone(it) })
                        } else {
                            patternsList.clear()
                            patternsList.addAll(lines)
                        }
                        saveData(); updateUI()
                        Toast.makeText(this, "Loaded ${lines.size} items safely!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun showPasswordDialog() {
        val input = EditText(this)
        input.setText(commonPassword)
        AlertDialog.Builder(this)
            .setTitle("🔑 Common Password")
            .setMessage("Set password for all accounts:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                commonPassword = input.text.toString().trim()
                saveData(); Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun updateUI() {
        val numCount = numbersList.size
        val patCount = patternsList.size
        val dataSaver = if (blockImages) "[Data Saver: ON] " else ""
        if (numCount == 0) {
            tvAccountInfo.text = "Acc: #0/0 | ${dataSaver}Speed: ${speedMultiplier}x"
        } else {
            val phone = numbersList.getOrElse(currentIndex) { "None" }
            val pattern = if (patCount > 0) patternsList[currentIndex % patCount] else "None"
            tvAccountInfo.text = "Acc #${currentIndex + 1}/$numCount: $phone\n${dataSaver}Pat: $pattern"
        }
    }

    private fun saveData() {
        val prefs = getSharedPreferences("winpesa_bot", MODE_PRIVATE)
        prefs.edit()
            .putString("numbers", numbersList.joinToString("\n"))
            .putString("patterns", patternsList.joinToString("\n"))
            .putString("skipped", skippedDataList.joinToString("\n"))
            .putString("password", commonPassword)
            .putInt("index", currentIndex)
            .putFloat("speed_multiplier", speedMultiplier)
            .putBoolean("block_images", blockImages)
            .apply()
    }

    private fun loadPersistedData() {
        val prefs = getSharedPreferences("winpesa_bot", MODE_PRIVATE)
        commonPassword = prefs.getString("password", "YourDefaultPassword123") ?: "YourDefaultPassword123"
        currentIndex = prefs.getInt("index", 0)
        speedMultiplier = prefs.getFloat("speed_multiplier", 1.0f).coerceIn(0.2f, 5.0f)
        blockImages = prefs.getBoolean("block_images", false)

        val nums = prefs.getString("numbers", "") ?: ""
        if (nums.isNotEmpty()) { numbersList.clear(); numbersList.addAll(nums.split("\n").map { sanitizePhone(it) }) }

        val pats = prefs.getString("patterns", "") ?: ""
        if (pats.isNotEmpty()) { patternsList.clear(); patternsList.addAll(pats.split("\n")) }

        val skips = prefs.getString("skipped", "") ?: ""
        if (skips.isNotEmpty()) { skippedDataList.clear(); skippedDataList.addAll(skips.split("\n").filter { it.isNotEmpty() }) }

        updateUI()
    }

    // ========================================================
    // 1. LOGIN (7s Watchdog: Re-taps 1. LOGIN, then Skips)
    // ========================================================
    private fun runStep1_Login() {
        if (numbersList.isEmpty()) {
            tvStatus.text = "Error: Load phone numbers first!"
            if (isAutoRunning) toggleAutoRun()
            return
        }

        isStepActive = true
        isLoggingOut = false
        val phone = numbersList[currentIndex]
        tvStatus.text = if (loginRetryCount == 0) "Step 1: Logging in $phone..." else "Step 1: Re-tapping 1. LOGIN ($phone)..."

        loginWatchdog?.let { handler.removeCallbacks(it) }

        // 7-Second Login Watchdog (Re-taps 1. LOGIN on first stall, then skips on second)
        loginWatchdog = Runnable {
            if (isAutoRunning) {
                if (loginRetryCount == 0) {
                    loginRetryCount = 1
                    tvStatus.text = "⚠️ Login stuck (7s). Re-tapping 1. LOGIN..."
                    runStep1_Login()
                } else {
                    loginRetryCount = 0
                    tvStatus.text = "⚠️ Login failed after retry. Skipping $phone..."
                    logSkippedAccount(phone, "Login Stuck (Retried)")
                    runStep4_LogoutAndNext()
                }
            }
        }
        handler.postDelayed(loginWatchdog!!, 7000L)

        val script = """
            (function() {
                function setReact(input, val) {
                    input.focus();
                    let old = input.value; input.value = val;
                    let trk = input._valueTracker; if (trk) trk.setValue(old);
                    input.dispatchEvent(new Event('input', { bubbles: true }));
                    input.dispatchEvent(new Event('change', { bubbles: true }));
                }

                let isLogged = Array.from(document.querySelectorAll('button, a, div')).some(e => (e.innerText||'').trim() === 'DEPOSIT');
                if (isLogged) return "ALREADY_LOGGED";

                let p = document.querySelector('input[placeholder*="Phone"], input[type="tel"], input[name*="phone"]');
                let pass = document.querySelector('input[placeholder*="Password"], input[type="password"]');

                if (p && pass) {
                    setReact(p, '$phone'); setReact(pass, '$commonPassword');
                    setTimeout(() => {
                        let btn = Array.from(document.querySelectorAll('button, div[role="button"]')).find(b => {
                            let t = (b.innerText||'').trim();
                            return t === 'LOGIN' && b.offsetWidth > 80;
                        });
                        if (btn) btn.click();
                    }, 500);
                    return "SUBMITTED";
                } else {
                    let h = Array.from(document.querySelectorAll('button, a, div')).find(e => {
                        let t = (e.innerText||'').trim();
                        return t === 'LOGIN' && e.offsetWidth < 120;
                    });
                    if (h) { h.click(); return "OPENED"; }
                    return "WAIT_MODAL";
                }
            })();
        """.trimIndent()

        val execLogin = object : Runnable {
            override fun run() {
                webView.evaluateJavascript(script) { res ->
                    if (res?.contains("ALREADY_LOGGED") == true) {
                        loginWatchdog?.let { handler.removeCallbacks(it) }
                        loginRetryCount = 0
                        tvStatus.text = "Step 1: Logged in!"
                        if (isAutoRunning) safeDelay(getDelay(800L)) { 
                            maharaRetryCount = 0
                            runStep2_OpenMahara() 
                        }
                    } else if (res?.contains("SUBMITTED") == true) {
                        val verify = object : Runnable {
                            override fun run() {
                                val checkJs = """
                                    (function() {
                                        let text = (document.body.innerText || '').toLowerCase();
                                        let logged = Array.from(document.querySelectorAll('button,a,div')).some(e => (e.innerText||'').trim() === 'DEPOSIT');
                                        if (logged) return 'LOGGED';

                                        if (text.includes('incorrect password') || text.includes('wrong password') || text.includes('invalid password')) return 'ERR_PASSWORD';
                                        if (text.includes('not register') || text.includes('user not found') || text.includes('not exist') || text.includes('invalid account')) return 'ERR_UNREGISTERED';
                                        if (text.includes('phone must be 10')) return 'REFILL_NEEDED';

                                        return 'WAIT';
                                    })()
                                """.trimIndent()

                                webView.evaluateJavascript(checkJs) { status ->
                                    if (status?.contains("LOGGED") == true) {
                                        loginWatchdog?.let { handler.removeCallbacks(it) }
                                        loginRetryCount = 0
                                        tvStatus.text = "Step 1: Logged in $phone!"
                                        if (isAutoRunning) safeDelay(getDelay(900L)) { 
                                            maharaRetryCount = 0
                                            runStep2_OpenMahara() 
                                        }
                                    } else if (status?.contains("ERR_PASSWORD") == true) {
                                        loginWatchdog?.let { handler.removeCallbacks(it) }
                                        loginRetryCount = 0
                                        logSkippedAccount(phone, "Incorrect Password")
                                        if (isAutoRunning) runStep4_LogoutAndNext()
                                    } else if (status?.contains("ERR_UNREGISTERED") == true) {
                                        loginWatchdog?.let { handler.removeCallbacks(it) }
                                        loginRetryCount = 0
                                        logSkippedAccount(phone, "Number Not Registered")
                                        if (isAutoRunning) runStep4_LogoutAndNext()
                                    } else if (status?.contains("REFILL_NEEDED") == true) {
                                        handler.postDelayed({ runStep1_Login() }, 500L)
                                    } else {
                                        handler.postDelayed(this, 500L)
                                    }
                                }
                            }
                        }
                        handler.postDelayed(verify, 700L)
                    } else {
                        handler.postDelayed(this, 400L)
                    }
                }
            }
        }
        execLogin.run()
    }

    // ========================================================
    // 2. MAHARA (6s Watchdog: Re-taps 2. MAHARA, then Skips)
    // ========================================================
    private fun runStep2_OpenMahara() {
        tvStatus.text = if (maharaRetryCount == 0) "Step 2: Entering Mahara..." else "Step 2: Re-tapping 2. MAHARA..."
        webView.loadUrl(activeMaharaUrl)

        maharaWatchdog?.let { handler.removeCallbacks(it) }

        // 6-Second Mahara Watchdog
        maharaWatchdog = Runnable {
            if (isAutoRunning) {
                if (maharaRetryCount == 0) {
                    maharaRetryCount = 1
                    tvStatus.text = "⚠️ Mahara stuck (6s). Re-tapping 2. MAHARA..."
                    runStep2_OpenMahara()
                } else {
                    maharaRetryCount = 0
                    val phone = numbersList.getOrElse(currentIndex) { "Account" }
                    tvStatus.text = "⚠️ Mahara failed after retry. Skipping $phone..."
                    logSkippedAccount(phone, "Mahara Stuck (Retried)")
                    runStep4_LogoutAndNext()
                }
            }
        }
        handler.postDelayed(maharaWatchdog!!, 6000L)

        val checkMahara = object : Runnable {
            override fun run() {
                if (!isStepActive && !isAutoRunning) return

                val js = """
                    (function() {
                        let iframes = Array.from(document.querySelectorAll('iframe'));
                        let aggr = iframes.find(f => f.src && (f.src.includes('aggrhub.com') || f.src.includes('soccer13') || f.src.includes('session=')));
                        if (aggr) return "JUMP:" + aggr.src;

                        Array.from(document.querySelectorAll('button, div, span, a')).forEach(e => {
                            let t = (e.innerText||'').trim().toLowerCase();
                            if (t.includes('got it') || t === 'accept' || t === '×' || t === 'x') {
                                try { e.click(); } catch(err) {}
                            }
                        });

                        let playBtn = Array.from(document.querySelectorAll('button, [role="button"], a, div')).find(el => {
                            let t = (el.innerText || '').trim().toUpperCase();
                            return t === 'PLAY' && el.offsetWidth > 40;
                        });

                        if (playBtn) {
                            let rect = playBtn.getBoundingClientRect();
                            try {
                                let touch = new Touch({ identifier: Date.now(), target: playBtn, clientX: rect.left+rect.width/2, clientY: rect.top+rect.height/2 });
                                playBtn.dispatchEvent(new TouchEvent('touchstart', { touches: [touch], targetTouches: [touch], changedTouches: [touch], bubbles: true }));
                                playBtn.dispatchEvent(new TouchEvent('touchend', { touches: [], targetTouches: [], changedTouches: [touch], bubbles: true }));
                            } catch(e) {}
                            try { playBtn.click(); } catch(e) {}
                            let p = playBtn.closest('button, [role="button"], a');
                            if (p) { try { p.click(); } catch(e) {} }

                            return "TOUCH:" + (rect.left + rect.width/2) + ":" + (rect.top + rect.height/2);
                        }

                        return "WAIT_MODAL";
                    })();
                """.trimIndent()

                webView.evaluateJavascript(js) { res ->
                    if (res?.contains("JUMP:") == true) {
                        maharaWatchdog?.let { handler.removeCallbacks(it) }
                        maharaRetryCount = 0

                        val gameUrl = res.replace("\"", "").substringAfter("JUMP:")
                        tvStatus.text = "Step 2: Inside match board!"
                        webView.loadUrl(gameUrl)
                        waitFor17Fixtures()
                    } else {
                        if (res?.contains("TOUCH:") == true) {
                            val clean = res.replace("\"", "").substringAfter("TOUCH:")
                            val parts = clean.split(":")
                            if (parts.size == 2) {
                                val x = parts[0].toFloatOrNull() ?: 0f
                                val y = parts[1].toFloatOrNull() ?: 0f
                                if (x > 0 && y > 0) simulateHardwareTouch(x, y)
                            }
                        }
                        handler.postDelayed(this, 500L)
                    }
                }
            }
        }
        safeDelay(600L) { checkMahara.run() }
    }

    private fun waitFor17Fixtures() {
        var drawAttempts = 0
        val checkDraws = object : Runnable {
            override fun run() {
                drawAttempts++
                val countJs = "(function(){ return Array.from(document.querySelectorAll('*')).filter(e => { let l = (e.innerText||'').trim().split('\\n').map(s=>s.trim()).filter(Boolean); return l.length === 2 && l[0] === 'DRAW' && !isNaN(parseFloat(l[1])); }).length; })()"
                webView.evaluateJavascript(countJs) { countStr ->
                    val count = countStr?.replace("\"", "")?.toIntOrNull() ?: 0
                    if (count >= 17) {
                        tvStatus.text = "Step 2: All 17 matches confirmed!"
                        if (isAutoRunning) safeDelay(getDelay(500L)) { runStep3_PickAndBet() }
                    } else if (drawAttempts >= 20) {
                        val phone = numbersList.getOrElse(currentIndex) { "Account" }
                        logSkippedAccount(phone, "Fixtures Timeout")
                        if (isAutoRunning) runStep4_LogoutAndNext()
                    } else {
                        tvStatus.text = "Loading matches ($count/17)..."
                        handler.postDelayed(this, 500L)
                    }
                }
            }
        }
        handler.postDelayed(checkDraws, 800L)
    }

    // ========================================================
    // 3. BET
    // ========================================================
    private fun runStep3_PickAndBet() {
        if (patternsList.isEmpty()) {
            tvStatus.text = "Error: Load patterns first via ⚙️ DATA"
            if (isAutoRunning) toggleAutoRun()
            return
        }

        val rawPattern = patternsList[currentIndex % patternsList.size]
        val clean = sanitizePattern(rawPattern)

        val safe13 = clean.take(13).toMutableList()
        while (safe13.size < 13) safe13.add(listOf('H', 'D', 'A').random())

        val picks13 = safe13.map { if (it == 'D' || it == 'X') 1 else if (it == 'A' || it == '2') 2 else 0 }
        val final17 = if (clean.length >= 17) {
            picks13 + clean.substring(13, 17).map { if (it == 'D' || it == 'X') 1 else if (it == 'A' || it == '2') 2 else 0 }
        } else {
            picks13 + List(4) { listOf(0, 1, 2).random() }
        }

        val jsArray = final17.joinToString(prefix = "[", postfix = "]")
        val accNum = currentIndex + 1

        val selectAllJs = """
            (function() {
                window._betPlacedLocked = false;
                let clearBtn = Array.from(document.querySelectorAll('button, div')).find(e => (e.innerText||'').trim() === 'Clear');
                if (clearBtn) clearBtn.click();

                const picks = $jsArray;
                const draws = Array.from(document.querySelectorAll('*')).filter(e => {
                    let l = (e.innerText||'').trim().split('\n').map(s => s.trim()).filter(Boolean);
                    return l.length === 2 && l[0] === 'DRAW' && !isNaN(parseFloat(l[1]));
                });

                if (draws.length < 17) return "WAIT_RETRY:" + draws.length;

                for (let i = 0; i < 17; i++) {
                    let d = draws[i], ch = picks[i];
                    let target = (ch === 0 ? d.previousElementSibling : (ch === 1 ? d : d.nextElementSibling));
                    if (target) target.click();
                }

                return "ALL_17_SELECTED";
            })();
        """.trimIndent()

        var retries = 0
        val execSelect = object : Runnable {
            override fun run() {
                webView.evaluateJavascript(selectAllJs) { res ->
                    if (res?.contains("WAIT_RETRY") == true && retries < 15) {
                        retries++
                        tvStatus.text = "Loading matches ($retries/15)..."
                        handler.postDelayed(this, 400L)
                    } else if (res?.contains("ALL_17_SELECTED") == true) {
                        tvStatus.text = "Step 3: All 17 picked! Placing bet..."
                        safeDelay(getDelay(500L)) { triggerPlaceBet(accNum) }
                    } else {
                        tvStatus.text = "Step 3: $res"
                    }
                }
            }
        }
        execSelect.run()
    }

    private fun triggerPlaceBet(accNum: Int) {
        val clickBetJs = """
            (function() {
                if (window._betPlacedLocked) return "ALREADY_TAPPED";

                let candidates = Array.from(document.querySelectorAll('button, div, [role="button"], a')).filter(el => {
                    let txt = (el.innerText || '').trim().toLowerCase();
                    return txt.startsWith('place bet') && el.offsetWidth > 40;
                });

                if (candidates.length > 0) {
                    let btn = candidates[candidates.length - 1];
                    btn.click();
                    window._betPlacedLocked = true;
                    return "BET_CLICKED";
                }
                return "SEARCHING";
            })();
        """.trimIndent()

        var attempts = 0
        val pollPlaceBet = object : Runnable {
            override fun run() {
                attempts++
                webView.evaluateJavascript(clickBetJs) { res ->
                    if (res?.contains("BET_CLICKED") == true || res?.contains("ALREADY_TAPPED") == true) {
                        tvStatus.text = "SUCCESS: Acc #$accNum placed bet!"
                        if (isAutoRunning) safeDelay(getDelay(1500L)) { runStep4_LogoutAndNext() }
                    } else if (attempts < 15) {
                        handler.postDelayed(this, 200L)
                    } else {
                        tvStatus.text = "Step 3: Bet submitted."
                        if (isAutoRunning) safeDelay(1000L) { runStep4_LogoutAndNext() }
                    }
                }
            }
        }
        pollPlaceBet.run()
    }

    // ========================================================
    // 4. NEXT
    // ========================================================
    private fun runStep4_LogoutAndNext() {
        if (isLoggingOut) return
        isLoggingOut = true

        tvStatus.text = "Step 4: Clearing session..."
        isStepActive = false
        loginWatchdog?.let { handler.removeCallbacks(it) }
        maharaWatchdog?.let { handler.removeCallbacks(it) }

        WebStorage.getInstance().deleteAllData()

        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies {
            cookieManager.flush()
            runOnUiThread {
                if (numbersList.isNotEmpty()) {
                    if (currentIndex < numbersList.size - 1) {
                        currentIndex++ // Strictly one advance
                    } else {
                        currentIndex = 0
                        saveData()
                        updateUI()
                        tvStatus.text = "ALL ACCOUNTS COMPLETED! 🎉 Ready for next week."
                        if (isAutoRunning) toggleAutoRun()
                        return@runOnUiThread
                    }
                }
                saveData()
                updateUI()
                webView.loadUrl("https://winpesa.ng")

                if (isAutoRunning) {
                    safeDelay(1600L) { 
                        loginRetryCount = 0
                        runStep1_Login() 
                    }
                }
            }
        }
    }
}