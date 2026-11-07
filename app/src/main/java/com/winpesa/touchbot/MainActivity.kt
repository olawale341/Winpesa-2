package com.winpesa.touchbot

import com.winpesa.touchbot.R
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var currentIndex = 0
    private var commonPassword = "YourDefaultPassword123"
    private var isAutoRunning = false
    private var isStepActive = false

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

        setupWebView()
        loadPersistedData()

        btnAutoRun.setOnClickListener { toggleAutoRun() }
        btnDataMenu.setOnClickListener { showDataManagementMenu() }
        btnStep1.setOnClickListener { runStep1_Login() }
        btnStep2.setOnClickListener { runStep2_OpenMahara() }
        btnStep3.setOnClickListener { runStep3_PickAndBet() }
        btnStep4.setOnClickListener { runStep4_LogoutAndNext() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.cacheMode = WebSettings.LOAD_DEFAULT // Retains web assets for 5x speed
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
                    if (isAutoRunning && !isStepActive && url.trimEnd('/') == "https://winpesa.ng") {
                        tvStatus.text = "Account #${currentIndex + 1}..."
                        safeDelay(800) { runStep1_Login() }
                    }
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    safeDelay(1500) { view?.reload() }
                }
            }
        }
        webView.loadUrl("https://winpesa.ng")
    }

    private fun safeDelay(delayMs: Long, action: () -> Unit) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ action() }, delayMs)
    }

    private fun dismissBanners() {
        val js = "(function(){ Array.from(document.querySelectorAll('button,div,span,a')).forEach(e=>{ let t=(e.innerText||'').trim().toLowerCase(); if(t==='got it'||t==='accept'||t==='×'||t==='x') e.click(); }); })()"
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
            handler.removeCallbacksAndMessages(null)
            btnAutoRun.text = "▶️ AUTO"
            btnAutoRun.backgroundTintList = getColorStateList(android.R.color.holo_green_dark)
            tvStatus.text = "⏸️ Auto-Run Paused."
        } else {
            isAutoRunning = true
            isStepActive = false
            btnAutoRun.text = "⏸️ PAUSE"
            btnAutoRun.backgroundTintList = getColorStateList(android.R.color.holo_red_dark)
            tvStatus.text = "▶️ Auto-Run (5x): Starting Acc #${currentIndex + 1}..."
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

    private fun showDataManagementMenu() {
        val options = arrayOf(
            "📱 Numbers: Paste Text",
            "📁 Numbers: Upload .txt File",
            "🎯 Patterns: Paste Text (H-D-A)",
            "📁 Patterns: Upload .txt File",
            "🔑 Set Common Password",
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
                    5 -> showJumpToAccountDialog()
                    6 -> { currentIndex = 0; saveData(); updateUI(); Toast.makeText(this, "Reset to #1", Toast.LENGTH_SHORT).show() }
                    7 -> confirmWipeAllData()
                }
            }
            .setNegativeButton("Close", null).show()
    }

    private fun confirmWipeAllData() {
        AlertDialog.Builder(this)
            .setTitle("🗑️ Wipe All Data?")
            .setMessage("Delete all saved numbers, patterns and reset progress to #0?")
            .setPositiveButton("Yes, Wipe All") { _, _ ->
                numbersList.clear()
                patternsList.clear()
                currentIndex = 0
                getSharedPreferences("winpesa_touchbot", MODE_PRIVATE).edit().clear().apply()
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
        if (numCount == 0) {
            tvAccountInfo.text = "Acc: #0/0 | Tap DATA"
        } else {
            val phone = numbersList.getOrElse(currentIndex) { "None" }
            val pattern = if (patCount > 0) patternsList[currentIndex % patCount] else "None"
            tvAccountInfo.text = "Acc #${currentIndex + 1}/$numCount: $phone\nPat: $pattern"
        }
    }

    private fun saveData() {
        val prefs = getSharedPreferences("winpesa_touchbot", MODE_PRIVATE)
        prefs.edit()
            .putString("numbers", numbersList.joinToString("\n"))
            .putString("patterns", patternsList.joinToString("\n"))
            .putString("password", commonPassword)
            .putInt("index", currentIndex)
            .apply()
    }

    private fun loadPersistedData() {
        val prefs = getSharedPreferences("winpesa_touchbot", MODE_PRIVATE)
        commonPassword = prefs.getString("password", "YourDefaultPassword123") ?: "YourDefaultPassword123"
        currentIndex = prefs.getInt("index", 0)
        val nums = prefs.getString("numbers", "") ?: ""
        if (nums.isNotEmpty()) { numbersList.clear(); numbersList.addAll(nums.split("\n").map { sanitizePhone(it) }) }
        val pats = prefs.getString("patterns", "") ?: ""
        if (pats.isNotEmpty()) { patternsList.clear(); patternsList.addAll(pats.split("\n")) }
        updateUI()
    }

    // ========================================================
    // 1. LOGIN (Fast 400ms Submission)
    // ========================================================
    private fun runStep1_Login() {
        if (numbersList.isEmpty()) {
            tvStatus.text = "Error: Load phone numbers first!"
            if (isAutoRunning) toggleAutoRun()
            return
        }

        isStepActive = true
        val phone = numbersList[currentIndex]
        tvStatus.text = "Step 1: Logging in $phone..."

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
                        let btn = Array.from(document.querySelectorAll('button, div[role="button"]')).find(b => (b.innerText||'').trim() === 'LOGIN' && b.offsetWidth > 80);
                        if (btn && p.value.length >= 10) btn.click();
                    }, 400);
                    return "SUBMITTED";
                } else {
                    let h = Array.from(document.querySelectorAll('button, a, div')).find(e => (e.innerText||'').trim() === 'LOGIN');
                    if (h) { h.click(); return "OPENED"; }
                    return "WAIT_MODAL";
                }
            })();
        """.trimIndent()

        var modalAttempts = 0
        val execLogin = object : Runnable {
            override fun run() {
                modalAttempts++
                webView.evaluateJavascript(script) { res ->
                    if (res?.contains("ALREADY_LOGGED") == true) {
                        tvStatus.text = "Step 1: Logged in!"
                        if (isAutoRunning) safeDelay(800) { runStep2_OpenMahara() }
                    } else if (res?.contains("SUBMITTED") == true) {
                        var verifyChecks = 0
                        val verify = object : Runnable {
                            override fun run() {
                                verifyChecks++
                                val checkJs = "(function(){ return Array.from(document.querySelectorAll('button,a,div')).some(e => (e.innerText||'').trim() === 'DEPOSIT') ? 'LOGGED' : 'WAIT'; })()"
                                webView.evaluateJavascript(checkJs) { status ->
                                    if (status?.contains("LOGGED") == true || verifyChecks >= 10) {
                                        tvStatus.text = "Step 1: Logged in $phone!"
                                        if (isAutoRunning) safeDelay(900) { runStep2_OpenMahara() }
                                    } else {
                                        handler.postDelayed(this, 400)
                                    }
                                }
                            }
                        }
                        handler.postDelayed(verify, 700)
                    } else if (modalAttempts < 15) {
                        handler.postDelayed(this, 400)
                    } else {
                        safeDelay(600) { runStep1_Login() }
                    }
                }
            }
        }
        execLogin.run()
    }

    // ========================================================
    // 2. MAHARA (Direct Link & Fast Iframe Jump)
    // ========================================================
    private fun runStep2_OpenMahara() {
        tvStatus.text = "Step 2: Loading Mahara..."
        webView.loadUrl(activeMaharaUrl)

        var attempts = 0
        val checkMahara = object : Runnable {
            override fun run() {
                attempts++
                val js = """
                    (function() {
                        let f = document.querySelector('iframe');
                        if (f && f.src && f.src.includes('aggrhub.com')) { window.location.href = f.src; return "JUMP"; }
                        let p = Array.from(document.querySelectorAll('button, div, a')).find(e => (e.innerText||'').trim() === 'PLAY');
                        if (p) { p.click(); return "PLAY"; }
                        return "WAIT";
                    })();
                """.trimIndent()

                webView.evaluateJavascript(js) { res ->
                    if (res?.contains("JUMP") == true) {
                        tvStatus.text = "Step 2: Entering board..."
                        var drawAttempts = 0
                        val checkDraws = object : Runnable {
                            override fun run() {
                                drawAttempts++
                                val countJs = "(function(){ return Array.from(document.querySelectorAll('*')).filter(e => { let l = (e.innerText||'').trim().split('\\n').map(s=>s.trim()).filter(Boolean); return l.length === 2 && l[0] === 'DRAW' && !isNaN(parseFloat(l[1])); }).length; })()"
                                webView.evaluateJavascript(countJs) { countStr ->
                                    val count = countStr?.replace("\"", "")?.toIntOrNull() ?: 0
                                    if (count >= 17) {
                                        tvStatus.text = "Step 2: Matches ready!"
                                        if (isAutoRunning) safeDelay(700) { runStep3_PickAndBet() }
                                    } else if (drawAttempts < 25) {
                                        handler.postDelayed(this, 400)
                                    }
                                }
                            }
                        }
                        handler.postDelayed(checkDraws, 600)
                    } else if (attempts < 20) {
                        handler.postDelayed(this, 400)
                    }
                }
            }
        }
        safeDelay(600) { checkMahara.run() }
    }

    // ========================================================
    // 3. BET (Instant 17 Picks + Rapid Place Bet Tap)
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
                        handler.postDelayed(this, 400)
                    } else if (res?.contains("ALL_17_SELECTED") == true) {
                        tvStatus.text = "Step 3: All 17 picked! Placing bet..."
                        safeDelay(500) { triggerPlaceBet(accNum) }
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
                        // 1.5s server buffer gives full time to submit without waiting 5 seconds
                        if (isAutoRunning) safeDelay(1500) { runStep4_LogoutAndNext() }
                    } else if (attempts < 15) {
                        handler.postDelayed(this, 200)
                    } else {
                        tvStatus.text = "Step 3: Bet submitted."
                        if (isAutoRunning) safeDelay(1000) { runStep4_LogoutAndNext() }
                    }
                }
            }
        }
        pollPlaceBet.run()
    }

    // ========================================================
    // 4. NEXT (Fast Wipe & Immediate Reload)
    // ========================================================
    private fun runStep4_LogoutAndNext() {
        tvStatus.text = "Step 4: Clearing session..."
        isStepActive = false
        handler.removeCallbacksAndMessages(null)

        WebStorage.getInstance().deleteAllData()

        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies {
            cookieManager.flush()
            runOnUiThread {
                if (numbersList.isNotEmpty()) {
                    if (currentIndex < numbersList.size - 1) {
                        currentIndex++
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
            }
        }
    }
}