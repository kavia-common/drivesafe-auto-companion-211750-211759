package org.example.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.PhoneNumberUtils
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var micButton: Button
    private lateinit var tileMusic: Button
    private lateinit var tileNavigation: Button
    private lateinit var tileCalls: Button
    private lateinit var tileMessages: Button

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var isListening: Boolean = false

    private val permissionRequestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        micButton = findViewById(R.id.micButton)
        tileMusic = findViewById(R.id.tileMusic)
        tileNavigation = findViewById(R.id.tileNavigation)
        tileCalls = findViewById(R.id.tileCalls)
        tileMessages = findViewById(R.id.tileMessages)

        wireTileHandlers()
        setupVoice()

        requestNeededPermissions()

        micButton.setOnTouchListener { _: View, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startListening()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopListening()
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun wireTileHandlers() {
        tileMusic.setOnClickListener { openSpotifyOrShowMessage() }
        tileNavigation.setOnClickListener { launchMapsWithDestination("Home") }
        tileCalls.setOnClickListener { openDialer(null) }
        tileMessages.setOnClickListener { openDefaultSmsComposer(null, null) }
    }

    private fun setupVoice() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast(getString(R.string.toast_no_speech_recognition))
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                setStatus(getString(R.string.status_listening))
            }

            override fun onBeginningOfSpeech() {
                setStatus(getString(R.string.status_listening))
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Could animate UI here; keeping minimal.
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                setStatus(getString(R.string.status_processing))
            }

            override fun onError(error: Int) {
                isListening = false
                setStatus(getString(R.string.status_ready))
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                setStatus(getString(R.string.status_ready))

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    handleRecognizedText(text)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim()
                if (!text.isNullOrEmpty()) {
                    setStatus("… $text")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS
        )

        if (Build.VERSION.SDK_INT >= 33) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val toRequest = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (toRequest.isNotEmpty()) {
            requestPermissions(toRequest.toTypedArray(), permissionRequestCode)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != permissionRequestCode) return

        // Minimal UX: show a toast for denied permissions.
        for (i in permissions.indices) {
            if (grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED) {
                toast(getString(R.string.toast_permission_required, permissions[i]))
            }
        }
    }

    private fun startListening() {
        if (speechRecognizer == null || recognizerIntent == null) return

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast(getString(R.string.toast_permission_required, Manifest.permission.RECORD_AUDIO))
            requestNeededPermissions()
            return
        }

        if (isListening) return
        isListening = true
        setStatus(getString(R.string.status_listening))
        speechRecognizer?.startListening(recognizerIntent)
    }

    private fun stopListening() {
        if (!isListening) return
        isListening = false
        speechRecognizer?.stopListening()
        setStatus(getString(R.string.status_processing))
    }

    private fun handleRecognizedText(text: String) {
        // Always forward to backend as best-effort (non-blocking).
        thread {
            BackendClient.sendRecognizedCommand(AppConfig.backendBaseUrl, text)
        }

        // Local routing (minimal rules)
        val lower = text.lowercase(Locale.getDefault())

        when {
            lower.startsWith("navigate") || lower.startsWith("directions") || lower.startsWith("go to") -> {
                val destination = lower
                    .replace("navigate to", "")
                    .replace("navigate", "")
                    .replace("directions to", "")
                    .replace("directions", "")
                    .replace("go to", "")
                    .trim()
                    .ifEmpty { "Gas station" }
                launchMapsWithDestination(destination)
            }

            lower.startsWith("call") -> {
                val target = lower.removePrefix("call").trim()
                if (target.isEmpty()) {
                    openDialer(null)
                } else if (PhoneNumberUtils.isGlobalPhoneNumber(target.filter { it.isDigit() || it == '+' })) {
                    placeCall(target)
                } else {
                    // For contact names we'd need Contacts permission + lookup; keep stub by opening dialer.
                    openDialer(null)
                }
            }

            lower.startsWith("message") || lower.startsWith("text") || lower.startsWith("sms") -> {
                val cleaned = lower
                    .replaceFirst("message", "")
                    .replaceFirst("text", "")
                    .replaceFirst("sms", "")
                    .trim()

                // Very simple parse: "message 1234567890 hello there"
                val parts = cleaned.split(" ").filter { it.isNotBlank() }
                val maybeNumber = parts.firstOrNull()
                val maybeBody = if (parts.size > 1) parts.drop(1).joinToString(" ") else null

                if (maybeNumber != null && PhoneNumberUtils.isGlobalPhoneNumber(maybeNumber.filter { it.isDigit() || it == '+' })) {
                    openDefaultSmsComposer(maybeNumber, maybeBody)
                } else {
                    openDefaultSmsComposer(null, cleaned.ifEmpty { null })
                }
            }

            lower.contains("spotify") || lower.startsWith("music") || lower.contains("play") -> {
                openSpotifyOrShowMessage()
            }

            lower.contains("whatsapp") -> {
                openWhatsAppOrShowMessage()
            }

            else -> {
                // Unknown command: keep status as recognized phrase.
                setStatus(text)
            }
        }
    }

    private fun openSpotifyOrShowMessage() {
        val spotifyPackage = "com.spotify.music"
        val pm = packageManager
        val intent = pm.getLaunchIntentForPackage(spotifyPackage)
        if (intent != null) {
            startActivity(intent)
        } else {
            toast(getString(R.string.toast_spotify_not_installed))
        }
    }

    private fun launchMapsWithDestination(destination: String) {
        val gmmIntentUri = Uri.parse("geo:0,0?q=" + Uri.encode(destination))
        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
            setPackage("com.google.android.apps.maps")
        }

        try {
            startActivity(mapIntent)
        } catch (e: ActivityNotFoundException) {
            // Fallback to any maps-capable app
            val fallback = Intent(Intent.ACTION_VIEW, gmmIntentUri)
            if (fallback.resolveActivity(packageManager) != null) {
                startActivity(fallback)
            } else {
                toast(getString(R.string.toast_maps_not_available))
            }
        }
    }

    private fun placeCall(numberRaw: String) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            toast(getString(R.string.toast_permission_required, Manifest.permission.CALL_PHONE))
            requestNeededPermissions()
            return
        }

        val number = numberRaw.filter { it.isDigit() || it == '+' }
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$number")
        }
        startActivity(intent)
    }

    private fun openDialer(numberRaw: String?) {
        val number = numberRaw?.filter { it.isDigit() || it == '+' }
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = if (number.isNullOrEmpty()) Uri.parse("tel:") else Uri.parse("tel:$number")
        }
        startActivity(intent)
    }

    private fun openDefaultSmsComposer(numberRaw: String?, body: String?) {
        val number = numberRaw?.filter { it.isDigit() || it == '+' }
        val uri = if (number.isNullOrEmpty()) Uri.parse("smsto:") else Uri.parse("smsto:$number")

        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            if (!body.isNullOrEmpty()) {
                putExtra("sms_body", body)
            }
        }
        startActivity(intent)
    }

    private fun openWhatsAppOrShowMessage() {
        val whatsappPackage = "com.whatsapp"
        val pm = packageManager
        val intent = pm.getLaunchIntentForPackage(whatsappPackage)
        if (intent != null) {
            startActivity(intent)
        } else {
            toast(getString(R.string.toast_whatsapp_not_installed))
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }

    private fun toast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }
}
