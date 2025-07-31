package com.example.aibooo

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraManager
import android.icu.util.Calendar
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.SmsManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.text.isDigitsOnly
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.aibooo.databinding.ActivityMainBinding
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.TextPart
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.view.isGone
import com.google.ai.client.generativeai.type.ImagePart
import com.prexoft.prexocore.Permission
import com.prexoft.prexocore.after
import com.prexoft.prexocore.alert
import com.prexoft.prexocore.captureScreen
import com.prexoft.prexocore.copy
import com.prexoft.prexocore.distract
import com.prexoft.prexocore.focus
import com.prexoft.prexocore.formatAsTime
import com.prexoft.prexocore.getPermission
import com.prexoft.prexocore.goTo
import com.prexoft.prexocore.havePermission
import com.prexoft.prexocore.hide
import com.prexoft.prexocore.input
import com.prexoft.prexocore.now
import com.prexoft.prexocore.observeNetworkStatus
import com.prexoft.prexocore.onClick
import com.prexoft.prexocore.onLongClick
import com.prexoft.prexocore.onSafeClick
import com.prexoft.prexocore.onScroll
import com.prexoft.prexocore.optimisedMultiPhotos
import com.prexoft.prexocore.parseMarkdown
import com.prexoft.prexocore.readInternalFile
import com.prexoft.prexocore.show
import com.prexoft.prexocore.shutdownSpeaker
import com.prexoft.prexocore.speak
import com.prexoft.prexocore.toast
import com.prexoft.prexocore.unEmojify
import com.prexoft.prexocore.vibrate
import com.prexoft.prexocore.writeInternalFile
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var speechRecognizer: SpeechRecognizer
    private var isAutoReadEnabled = true
    private var chatHistory = mutableListOf<Content>()
    private var pendingFormData: JSONObject? = null
    private var imageCapture: ImageCapture? = null
    private var photoFile: File? = null
    private var capturedImageUri: Uri? = null
    private var tweetFilled = false
    private var passwordPromptShown = false
    private var model: GenerativeModel? = null
    private lateinit var permission: Permission

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        permission = Permission(this)
        setContentView(binding.root)

        setupBackNavigation()
        setupModel()
        setupSpeechRecognizer()
        enableEdgeToEdge()

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            
            v.setPadding(0, systemBars.top, 0, if (keyboard > 0) keyboard else systemBars.bottom)
            insets
        }

        setupWebView()
        setupAutoRead()
        setupCaptureContainer()

        binding.scroller.isSmoothScrollingEnabled = true
        binding.scroller.onScroll(
            onTop = {
                binding.lottieAnimationView.show()
            },
            other = {
                binding.lottieAnimationView.hide(0)
            }
        )

        binding.inputCard.onClick { toggleTextInput(true) }
        binding.sendButton.onSafeClick(1) {
            val query = binding.queryInput.text.toString().trim()
            if (query.isNotBlank()) {
                getGeminiResponse(query)
                toggleTextInput(false)
                binding.queryInput.text.clear()
            }
        }

        binding.clear.onClick { chatHistory.clear() }
        binding.geminiResponseText.onLongClick { copy(binding.geminiResponseText.text.toString()) }

        binding.cardView.onClick {
            if (binding.textInputLayout.isVisible) {
                toggleTextInput(false)
                return@onClick
            }

            if (!binding.lottieAnimationView.isVisible) {
                binding.lottieAnimationView.show()
                binding.scroller.smoothScrollTo(-1, -1)
            }

            if (binding.lottieAnimationView.isAnimating) speechRecognizer.stopListening()
            else if (havePermission(Manifest.permission.RECORD_AUDIO)) startListening()
            else {
                permission.request(Manifest.permission.RECORD_AUDIO) { permitted ->
                    if (permitted) startListening()
                    else alert("Permission denied", "Microphone permission is required to use this feature.")
                }
            }
        }

        observeNetworkStatus(this) {
            if (!it) alert("Connection failed!", "Please check to wifi or stable internet connection to use this app")
        }
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { }
            
            override fun onBeginningOfSpeech() { }
            
            override fun onRmsChanged(rmsdB: Float) { }
            
            override fun onBufferReceived(buffer: ByteArray?) { }

            override fun onEndOfSpeech() {
                binding.lottieAnimationView.pauseAnimation()
                binding.lottieAnimationView2.pauseAnimation()
            }
            
            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                    else -> "Unknown error"
                }
                binding.lottieAnimationView.pauseAnimation()
                binding.lottieAnimationView2.pauseAnimation()
                toast(errorMessage)
            }
            
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val spokenText = matches[0]
                    getGeminiResponse(spokenText)
                }
            }
            
            override fun onPartialResults(partialResults: Bundle?) { }
            
            override fun onEvent(eventType: Int, params: Bundle?) { }
        })
    }

    private fun setupModel() {
        val key = readInternalFile("key.txt")
        val model = readInternalFile("model.txt")

        if (key.isBlank()) {
            input("Enter Gemini API key", "Get it from aistudio.google.com", "Ai...", required = true) { key->
                input("Enter Gemini model", "Leave it empty to use default", "gemini-2.5-flash-lite") {
                    alert("Credentials saved locally", "To change configuration, clear data or reinstall the app.", "Okay")
                    modelInit(
                        key.writeInternalFile(this, "key.txt"),
                        model.writeInternalFile(this, "model.txt")
                    )
                }
            }
        }
        else modelInit(key, model)
    }

    private fun modelInit(key: String, modelName: String) {
        model = GenerativeModel(
            modelName = modelName.ifBlank { "gemini-2.5-flash-lite" },
            apiKey = key,
            systemInstruction = Content(role = "system", listOf(TextPart(systemPrompt))),
            safetySettings = listOf(
                SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
                SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
                SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
                SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE),
            )
        )
    }
    
    private fun scheduleReminder(triggerTime: Long, message: String, priority: String) {
        val intent = Intent(this, ReminderReceiver::class.java).apply {
            putExtra("message", message)
            putExtra("priority", priority)
        }

        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(this, (now()/1000).toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerTime, pendingIntent), pendingIntent)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this) {
            when {
                binding.captureContainer.isVisible -> hideCaptureContainer()
                binding.webViewContainer.isVisible -> if (binding.progress.isGone) showWebView(false)
                binding.textInputLayout.isVisible -> toggleTextInput(false)
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = false

                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowContentAccess = true
                allowFileAccess = true
                domStorageEnabled = true

                setNeedInitialFocus(true)
                setSupportZoom(true)

                loadsImagesAutomatically = true
                blockNetworkImage = false
                blockNetworkLoads = false
            }
            
            addJavascriptInterface(object {
                @SuppressLint("SetTextI18s", "SetTextI18n")
                @android.webkit.JavascriptInterface
                fun onCaptainFound(
                    captainNumber: String,
                    captainName: String,
                    rating: String,
                    pickup: String,
                    drop: String,
                    captainPhotoUrl: String
                ) {
                    Handler(mainLooper).post {
                        showWebView(false)
                        Glide.with(this@MainActivity)
                            .load(captainPhotoUrl)
                            .into(binding.captainsPhoto)

                        binding.geminiResponseText.text = "Booked successfully"
                        binding.geminiResponseText6.text = "( $rating stars )"

                        binding.geminiResponseText2.text = pickup
                        binding.geminiResponseText3.text = drop
                        binding.geminiResponseText5.text = captainName

                        binding.rideLayout.show()
                        binding.lottieAnimationView.hide()
                        binding.caller.onClick { goTo(captainNumber) }
                    }
                }

                @android.webkit.JavascriptInterface
                fun updateProgress(message: String) {
                    Handler(mainLooper).post {
                        binding.progress.text = message
                        binding.progress.show()
                    }
                }

                @android.webkit.JavascriptInterface
                fun hideWebView(message: String) {
                    Handler(mainLooper).post {
                        showWebView(false)
                        binding.geminiResponseText.text = message
                    }
                }

                @android.webkit.JavascriptInterface
                fun paymentHandleOnWeb() {
                    Handler(mainLooper).post {
                        binding.progress.hide()
                        binding.webView.alpha = 1f
                        binding.anim.alpha = 0f
                    }
                }

                @android.webkit.JavascriptInterface
                fun reloadPlatform() {
                    Handler(mainLooper).post {
                        loadPlatformWebsite(pendingFormData!!)
                    }
                }

                @SuppressLint("SetTextI18s", "SetTextI18n")
                @android.webkit.JavascriptInterface
                fun handleCheckout() {
                    Handler(mainLooper).post {
                        binding.progress.text = "Processing checkout..."
                        
                        val checkListItemJs = """
                            (function checkListItem() {
                                const listItem = document.querySelector('div[role="list"]._34m_P');
                                if (listItem) {
                                    window.androidInterface.updateProgress('Connecting to restaurant...');
                                    
                                    const continueButton = document.querySelector('button.r5vwW.J3pgo._28p4o.ebTvJ[aria-label="Tap here to go to Login Page"]');
                                    if (continueButton) {
                                        console.log('Found continue button, clicking it...');
                                        window.androidInterface.updateProgress('Logging in...');
                                        continueButton.removeAttribute('disabled');
                                        continueButton.click();
                                        
                                        window.androidInterface.handlePhoneInput();
                                    } else {
                                        const paymentButton = document.querySelector('button#makePaymentButton[data-cy="cart-make-payment-btn"]');
                                        if (paymentButton) {
                                            console.log('Found payment button, clicking it...');
                                            window.androidInterface.updateProgress('Proceeding to payment...');
                                            window.androidInterface.paymentHandleOnWeb();
                                            paymentButton.removeAttribute('disabled');
                                            paymentButton.click();
                                        }
                                    }
                                }
                                else {
                                    setTimeout(checkListItem, 1000);
                                }
                            })();
                        """.trimIndent()
                        
                        binding.webView.evaluateJavascript(checkListItemJs, null)
                    }
                }

                @SuppressLint("SetTextI18n")
                @android.webkit.JavascriptInterface
                fun handleFoodOtpInput() {
                    Handler(mainLooper).post {
                        input("Enter OTP", required = true) { otp ->
                            if (otp.length == 6 && otp.all { it.isDigit() }) {
                                val fillOtpJs = """
                                        (function() {
                                            const otpInput = document.querySelector('input._22P1J[type="text"][name="otp"]');
                                            if (otpInput) {
                                                const descriptor = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
                                                const originalSetter = descriptor.set;
                                                
                                                Object.defineProperty(otpInput, 'value', {
                                                    get: function() {
                                                        return '$otp';
                                                    },
                                                    set: function(value) {
                                                        if (originalSetter) {
                                                            originalSetter.call(this, '$otp');
                                                        }
                                                    },
                                                    configurable: true
                                                });
                                                
                                                otpInput.value = '$otp';                                               
                                                const nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                                                nativeInputValueSetter.call(otpInput, '$otp');
                                                
                                                const events = ['input', 'change', 'blur'];
                                                events.forEach(eventType => {
                                                    const event = new Event(eventType, { bubbles: true });
                                                    otpInput.dispatchEvent(event);
                                                });
                                                
                                                setTimeout(() => {
                                                    const verifyButton = document.querySelector('button._1vTVI._2UPEv[data-cy="primary-button"]');
                                                    if (verifyButton) {
                                                        console.log('Found verify button, clicking it...');
                                                        window.androidInterface.updateProgress('Verifying OTP...');
                                                        verifyButton.removeAttribute('disabled');
                                                        verifyButton.click();
                                                    }
                                                }, 500);
                                                
                                                Object.defineProperty(otpInput, 'value', descriptor);
                                            }
                                        })();
                                    """.trimIndent()
                                binding.webView.evaluateJavascript(fillOtpJs, null)
                            }
                            else {
                                binding.geminiResponseText.text = "Food order cancelled."
                                showWebView(false)
                            }
                        }
                    }
                }

                @SuppressLint("SetTextI18n")
                @android.webkit.JavascriptInterface
                fun handlePhoneInput() {
                    Handler(mainLooper).post {
                        input("Enter Phone Number", required = true) { phoneNumber ->
                            if (phoneNumber.length == 10 && phoneNumber.all { it.isDigit() }) {
                                val fillPhoneNumberJs = """
                                        (function() {
                                            const phoneInput = document.querySelector('[data-testid="input-field-tel-national"]');
                                            if (phoneInput) {
                                                const descriptor = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
                                                const originalSetter = descriptor.set;
                                                
                                                Object.defineProperty(phoneInput, 'value', {
                                                    get: function() {
                                                        return '$phoneNumber';
                                                    },
                                                    set: function(value) {
                                                        if (originalSetter) {
                                                            originalSetter.call(this, '$phoneNumber');
                                                        }
                                                    },
                                                    configurable: true
                                                });
                                                
                                                phoneInput.value = '$phoneNumber';
                                                const nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                                                nativeInputValueSetter.call(phoneInput, '$phoneNumber');
                                                
                                                const events = ['input', 'change', 'blur'];
                                                events.forEach(eventType => {
                                                    const event = new Event(eventType, { bubbles: true });
                                                    phoneInput.dispatchEvent(event);
                                                });
                                                
                                                phoneInput.classList.remove('ng-pristine', 'ng-untouched', 'ng-invalid');
                                                phoneInput.classList.add('ng-dirty', 'ng-touched', 'ng-valid');
                                                
                                                const form = phoneInput.closest('form');
                                                if (form) {
                                                    form.classList.remove('ng-pristine', 'ng-invalid', 'ng-untouched');
                                                    form.classList.add('ng-dirty', 'ng-touched', 'ng-valid');
                                                    
                                                    const formControls = form.querySelectorAll('[formcontrol], [formControlName], [ngModel]');
                                                    formControls.forEach(control => {
                                                        control.classList.remove('ng-pristine', 'ng-untouched', 'ng-invalid');
                                                        control.classList.add('ng-dirty', 'ng-touched', 'ng-valid');
                                                    });
                                                }
                                                
                                                Object.defineProperty(phoneInput, 'value', descriptor);
                                                
                                                setTimeout(() => {
                                                    const continueButton = document.querySelector('button._1vTVI._2UPEv');
                                                    if (continueButton) {
                                                        console.log('Found continue button, clicking it...');
                                                        window.androidInterface.updateProgress('Sending OTP request...');
                                                        continueButton.removeAttribute('disabled');
                                                        continueButton.click();
                                                        
                                                        setTimeout(() => {
                                                            const otpInput = document.querySelector('input._22P1J[type="text"][name="otp"]');
                                                            if (otpInput) {
                                                                console.log('Found OTP input, requesting OTP...');
                                                                window.androidInterface.handleFoodOtpInput();
                                                            }
                                                        }, 1000);
                                                    }
                                                }, 500);
                                            }
                                        })();
                                    """.trimIndent()
                                binding.webView.evaluateJavascript(fillPhoneNumberJs, null)
                            }
                            else {
                                binding.geminiResponseText.text = "Food order cancelled."
                                showWebView(false)
                            }
                        }
                    }
                }

                @SuppressLint("SetTextI18n")
                @android.webkit.JavascriptInterface
                fun handleOtpInput() {
                    Handler(mainLooper).post {
                        input("Enter OTP", required = true) { otp ->
                            if (otp.length == 6 && otp.isDigitsOnly()) {
                                val fillOtpJs = """
                                        (function() {
                                            const otpInputs = document.querySelectorAll('.otp-input');
                                            const otpValue = '${otp}';
                                            if (otpInputs.length === 6) {
                                                function fillDigit(index) {
                                                    if (index >= 6) {
                                                        setTimeout(() => {
                                                            const verifyButton = document.querySelector('button.next-button');
                                                            if (verifyButton) {
                                                                otpInputs.forEach(input => {
                                                                    input.classList.remove('ng-pristine', 'ng-invalid', 'ng-untouched');
                                                                    input.classList.add('ng-valid', 'ng-touched', 'ng-dirty');
                                                                    
                                                                    const inputEvent = new InputEvent('input', {
                                                                        bubbles: true,
                                                                        cancelable: true,
                                                                        inputType: 'insertText',
                                                                        data: input.value
                                                                    });
                                                                    input.dispatchEvent(inputEvent);
                                                                });
                                                                
                                                                verifyButton.removeAttribute('disabled');
                                                                verifyButton.classList.remove('disabled-btn');
                                                                
                                                                verifyButton.focus();
                                                                
                                                                const form = document.querySelector('form.otp-page-wrapper');
                                                                if (form) {
                                                                    form.classList.remove('ng-pristine', 'ng-invalid', 'ng-untouched');
                                                                    form.classList.add('ng-valid', 'ng-touched', 'ng-dirty');
                                                                }
                                                                
                                                                const submitEvent = new Event('submit', {
                                                                    bubbles: true,
                                                                    cancelable: true
                                                                });
                                                                
                                                                setTimeout(() => {
                                                                    if (form) {
                                                                        form.dispatchEvent(submitEvent);
                                                                    }
                                                                    
                                                                    const mouseEvent = new MouseEvent('click', {
                                                                        bubbles: true,
                                                                        cancelable: true,
                                                                        view: window
                                                                    });
                                                                    verifyButton.dispatchEvent(mouseEvent);
                                                                    
                                                                    setTimeout(() => {
                                                                        const errorElement = document.querySelector('.error-message, .alert-error');
                                                                        if (errorElement && errorElement.offsetParent !== null) {
                                                                            window.androidInterface.reloadPlatform();
                                                                        } else {
                                                                            setTimeout(() => {
                                                                                window.androidInterface.reloadPlatform();
                                                                            }, 3000);
                                                                        }
                                                                    }, 2000);
                                                                }, 500);
                                                            }
                                                        }, 800);
                                                        return;
                                                    }
                                                    
                                                    otpInputs[index].focus();
                                                    setTimeout(() => {
                                                        otpInputs[index].value = otpValue.charAt(index);
                                                        const events = ['input', 'change', 'blur', 'focus', 'keyup', 'keydown'];
                                                        events.forEach(eventType => {
                                                            const event = new Event(eventType, { bubbles: true });
                                                            otpInputs[index].dispatchEvent(event);
                                                        });
                                                        
                                                        const inputEvent = new InputEvent('input', {
                                                            bubbles: true,
                                                            cancelable: true,
                                                            inputType: 'insertText',
                                                            data: otpValue.charAt(index)
                                                        });
                                                        otpInputs[index].dispatchEvent(inputEvent);
                                                        
                                                        otpInputs[index].classList.remove('ng-pristine', 'ng-invalid', 'ng-untouched');
                                                        otpInputs[index].classList.add('ng-valid', 'ng-touched', 'ng-dirty');
                                                        
                                                        setTimeout(() => fillDigit(index + 1), 200);
                                                    }, 100);
                                                }
                                                
                                                fillDigit(0);
                                            }
                                        })();
                                    """.trimIndent()
                                binding.webView.evaluateJavascript(fillOtpJs, null)
                            }
                            else {
                                binding.geminiResponseText.text = "OTP verification cancelled."
                                showWebView(false)
                            }
                        }
                    }
                }

                @SuppressLint("SetTextI18n")
                @android.webkit.JavascriptInterface
                fun promptForPassword() {
                    Handler(mainLooper).post {
                        input("Enter your password", required = true) { password ->
                            if (password.isNotEmpty()) {
                                val fillPasswordJs = """
                                        (function() {
                                            if (window.androidInterface && window.androidInterface.updateProgress) {
                                                window.androidInterface.updateProgress('Securing your account...');
                                            }
                                            var passwordInput = document.querySelector('input[name=\"password\"]');
                                            if (passwordInput) {
                                                passwordInput.focus();
                                                passwordInput.click();
                                                setTimeout(function() {
                                                    if (document.execCommand) {
                                                        passwordInput.focus();
                                                        document.execCommand('insertText', false, '${"" + password.replace("'", "\\'") }');
                                                    }
                                                    var events = ['input', 'change', 'keyup', 'keydown', 'keypress', 'focus', 'blur'];
                                                    events.forEach(function(eventType) {
                                                        var event = new Event(eventType, { bubbles: true, cancelable: true });
                                                        passwordInput.dispatchEvent(event);
                                                    });
                                                    var inputEvent = new InputEvent('input', {
                                                        bubbles: true,
                                                        cancelable: true,
                                                        inputType: 'insertText',
                                                        data: '${"" + password.replace("'", "\\'") }'
                                                    });
                                                    passwordInput.dispatchEvent(inputEvent);
                                                    
                                                    // Click the Log in button after 500ms
                                                    setTimeout(function() {
                                                        if (window.androidInterface && window.androidInterface.updateProgress) {
                                                            window.androidInterface.updateProgress('Logging you in...');
                                                        }
                                                        var loginButton = document.querySelector('button[data-testid=\"LoginForm_Login_Button\"]');
                                                        if (loginButton) {
                                                            loginButton.removeAttribute('disabled');
                                                            loginButton.removeAttribute('aria-disabled');
                                                            loginButton.click();
                                                        } else {
                                                            // Try alternative selectors if the first one doesn't work
                                                            var allButtons = document.querySelectorAll('button');
                                                            for (var i = 0; i < allButtons.length; i++) {
                                                                var button = allButtons[i];
                                                                if (button.textContent.includes('Log in') && !button.disabled) {
                                                                    button.removeAttribute('disabled');
                                                                    button.removeAttribute('aria-disabled');
                                                                    button.click();
                                                                    break;
                                                                }
                                                            }
                                                        }
                                                        window._passwordPrompted = false;
                                                        window._passwordWatcherActive = false;
                                                    }, 500);
                                                }, 500);
                                            }
                                        })();
                                    """.trimIndent()
                                binding.webView.evaluateJavascript(fillPasswordJs, null)
                                passwordPromptShown = false
                            }
                            else {
                                binding.geminiResponseText.text = "Login cancelled."
                                showWebView(false)
                            }
                        }
                    }
                }
            }, "androidInterface")
            
            webViewClient = object : WebViewClient() {
                @SuppressLint("SetTextI18s", "SetTextI18n")
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)                    
                    
                    when (pendingFormData?.getString("id")) {
                        "tweet" -> {
                            if (url!!.startsWith("https://x.com/i/flow/login")) {
                                tweetFilled = false
                                passwordPromptShown = false
                                
                                val watchPasswordJs = """
                                    (function() {
                                        if (window._passwordWatcherActive) return;
                                        window._passwordWatcherActive = true;
                                        function checkPasswordInput() {
                                            var input = document.querySelector('input[name=\"password"]');
                                            if (window.androidInterface && window.androidInterface.updateProgress) {
                                                window.androidInterface.updateProgress('Preparing secure login...');
                                            }
                                            if (input && input.offsetParent !== null) {
                                                if (window.androidInterface && !window._passwordPrompted) {
                                                    window._passwordPrompted = true;
                                                    if (window.androidInterface.updateProgress) {
                                                        window.androidInterface.updateProgress('Please enter your password to continue');
                                                    }
                                                    window.androidInterface.promptForPassword();
                                                }
                                            } else {
                                                setTimeout(checkPasswordInput, 1000);
                                            }
                                        }
                                        checkPasswordInput();
                                    })();
                                """.trimIndent()
                                view?.evaluateJavascript(watchPasswordJs, null)
                                
                                input("Enter your username, phone or email", required = true) { loginValue ->
                                    if (loginValue.isNotEmpty()) {
                                        val fillLoginJs = """
                                                (function() {
                                                    function findLoginInput() {
                                                        var selectors = [
                                                            'input[name=\"text\"]',
                                                            'input[autocomplete=\"username\"]',
                                                            'input[type=\"text\"]',
                                                            'input[class*=\"r-30o5oe\"]'
                                                        ];
                                                        for (var i = 0; i < selectors.length; i++) {
                                                            var input = document.querySelector(selectors[i]);
                                                            if (input) return input;
                                                        }
                                                        return null;
                                                    }
                                                    function fillLoginInput() {
                                                        var input = findLoginInput();
                                                        if (input) {
                                                            input.focus();
                                                            input.click();
                                                            setTimeout(function() {
                                                                if (document.execCommand) {
                                                                    input.focus();
                                                                    document.execCommand('insertText', false, '${"" + loginValue.replace("'", "\\'") }');
                                                                }
                                                                var events = ['input', 'change', 'keyup', 'keydown', 'keypress', 'focus', 'blur'];
                                                                events.forEach(function(eventType) {
                                                                    var event = new Event(eventType, { bubbles: true, cancelable: true });
                                                                    input.dispatchEvent(event);
                                                                });
                                                                var inputEvent = new InputEvent('input', {
                                                                    bubbles: true,
                                                                    cancelable: true,
                                                                    inputType: 'insertText',
                                                                    data: '${"" + loginValue.replace("'", "\\'") }'
                                                                });
                                                                input.dispatchEvent(inputEvent);
                                                                
                                                                setTimeout(function() {
                                                                    var nextButton = document.querySelector('button[role="button"]');
                                                                    if (nextButton && nextButton.textContent.includes('Next')) {
                                                                        nextButton.removeAttribute('disabled');
                                                                        nextButton.removeAttribute('aria-disabled');
                                                                        nextButton.click();
                                                                        
                                                                        function waitForPasswordInput() {
                                                                            var passwordInput = document.querySelector('input[name="password"]');
                                                                            if (window.androidInterface && window.androidInterface.updateProgress) {
                                                                                window.androidInterface.updateProgress('Preparing secure login...');
                                                                            }
                                                                            if (passwordInput && passwordInput.offsetParent !== null) {
                                                                                if (window.androidInterface && !window._passwordPrompted) {
                                                                                    window._passwordPrompted = true;
                                                                                    if (window.androidInterface.updateProgress) {
                                                                                        window.androidInterface.updateProgress('Please enter your password to continue');
                                                                                    }
                                                                                    window.androidInterface.promptForPassword();
                                                                                }
                                                                            } else {
                                                                                setTimeout(waitForPasswordInput, 1000);
                                                                            }
                                                                        }
                                                                        setTimeout(waitForPasswordInput, 2000);
                                                                    } else {
                                                                        var allButtons = document.querySelectorAll('button');
                                                                        for (var i = 0; i < allButtons.length; i++) {
                                                                            var button = allButtons[i];
                                                                            if (button.textContent.includes('Next') && !button.disabled) {
                                                                                button.click();
                                                                                break;
                                                                            }
                                                                        }
                                                                    }
                                                                }, 500);
                                                            }, 500);
                                                        }
                                                    }
                                                    setTimeout(fillLoginInput, 1000);
                                                })();
                                            """.trimIndent()
                                        view?.evaluateJavascript(fillLoginJs, null)
                                    }
                                    else {
                                        binding.geminiResponseText.text = "Login cancelled."
                                        showWebView(false)
                                    }
                                }
                            }
                            else if (url.startsWith("https://x.com/home")) loadPlatformWebsite(pendingFormData!!)
                            else {
                                if (!tweetFilled && pendingFormData?.has("content") == true) {
                                    tweetFilled = true
                                    val tweetContent = pendingFormData?.getString("content") ?: ""
                                    val fillTweetJs = """
                                        (function() {
                                            console.log('Starting tweet fill process...');
                                            
                                            function findTextarea() {
                                                var selectors = [
                                                    'textarea[data-testid="tweetTextarea_0"]',
                                                    'textarea[placeholder*="happening"]',
                                                    'textarea[aria-label*="Post text"]',
                                                    'textarea[class*="tweet"]',
                                                    'textarea'
                                                ];
                                                
                                                for (var i = 0; i < selectors.length; i++) {
                                                    var textarea = document.querySelector(selectors[i]);
                                                    if (textarea) {
                                                        console.log('Found textarea with selector:', selectors[i]);
                                                        return textarea;
                                                    }
                                                }
                                                return null;
                                            }
                                            
                                            function fillTextarea() {
                                                var textarea = findTextarea();
                                                if (textarea) {
                                                    console.log('Textarea found, attempting to fill...');
                                                    
                                                    textarea.focus();
                                                    textarea.click();
                                                    
                                                    setTimeout(function() {
                                                        console.log('Setting textarea value to:', '${tweetContent}');
                                                                                                             
                                                        if (document.execCommand) {
                                                            textarea.focus();
                                                            document.execCommand('insertText', false, '${tweetContent}');
                                                        }
                                                        
                                                        var events = ['input', 'change', 'keyup', 'keydown', 'keypress', 'focus', 'blur'];
                                                        events.forEach(function(eventType) {
                                                            var event = new Event(eventType, { bubbles: true, cancelable: true });
                                                            textarea.dispatchEvent(event);
                                                        });
                                                        
                                                        var inputEvent = new InputEvent('input', {
                                                            bubbles: true,
                                                            cancelable: true,
                                                            inputType: 'insertText',
                                                            data: '${tweetContent}'
                                                        });
                                                        textarea.dispatchEvent(inputEvent);
                                                        
                                                        console.log('Textarea fill attempt completed');
                                                        console.log('Current textarea value:', textarea.value);
                                                        
                                                        setTimeout(function() {
                                                            var tweetButton = document.querySelector('button[data-testid="tweetButton"]');
                                                            if (tweetButton) {
                                                                console.log('Found tweet button, clicking it...');
                                                                tweetButton.removeAttribute('disabled');
                                                                tweetButton.removeAttribute('aria-disabled');
                                                                tweetButton.click();
                                                                console.log('Tweet button clicked');
                                                                
                                                                function waitForTextareaRemoval() {
                                                                    var textarea = document.querySelector('textarea[data-testid="tweetTextarea_0"]');
                                                                    if (!textarea) {
                                                                        console.log('Textarea removed, hiding WebView...');
                                                                        if (window.androidInterface && window.androidInterface.hideWebView) {
                                                                            window.androidInterface.hideWebView('Your tweet is live now.');
                                                                        }                                                                     
                                                                    } else {
                                                                        setTimeout(waitForTextareaRemoval, 1000);
                                                                    }
                                                                }
                                                                setTimeout(waitForTextareaRemoval, 2000);
                                                            } else {
                                                                console.log('Tweet button not found');
                                                            }
                                                        }, 500);
                                                        
                                                    }, 500);
                                                } else {
                                                    console.log('Textarea not found');
                                                }
                                            }
                                            
                                            setTimeout(fillTextarea, 1000);
                                        })();
                                    """.trimIndent()
                                    view?.evaluateJavascript(fillTweetJs, null)
                                }
                            }
                        }
                        "food" -> {
                            val clickDishesButtonJs = """
                                (function() {
                                    let retryCount = 0;
                                    function waitForResults() {
                                        const dishResults = document.querySelectorAll('div[data-testid="search-pl-dish-first-v2-card"]');
                                        if (dishResults.length > 0) {
                                            console.log('Dish-specific results found, checking cart status...');
                                            window.androidInterface.updateProgress('Checking cart...');
                                            
                                            const checkoutButton = document.querySelector('button._190NL');
                                            if (checkoutButton) {
                                                console.log('Found existing items in cart, proceeding to clear cart...');
                                                window.androidInterface.updateProgress('Opening cart...');
                                                checkoutButton.click();
                                                
                                                setTimeout(() => {
                                                    function clearCartItems() {
                                                        const cartItems = document.querySelectorAll('div[data-cy="cart-food-item-instock"]');
                                                        
                                                        if (cartItems.length > 0) {
                                                            console.log('Found ' + cartItems.length + ' items to remove');
                                                            window.androidInterface.updateProgress('Removing item ' + cartItems.length);
                                                            
                                                            const decreaseButton = cartItems[0].querySelector('button[aria-label^="Decrease Quantity"]');
                                                            if (decreaseButton) {
                                                                decreaseButton.click();
                                                                setTimeout(clearCartItems, 1000);
                                                            }
                                                        } else {
                                                            console.log('Cart is empty, returning to search...');
                                                            window.androidInterface.updateProgress('Returning to search...');
                                                            
                                                            const searchButton = document.querySelector('button[data-testid="bottom-nav-search"]');
                                                            if (searchButton) {
                                                                searchButton.click();
                                                                
                                                                setTimeout(() => {
                                                                    handleDishResults(dishResults);
                                                                }, 1000);
                                                            }
                                                        }
                                                    }
                                                    
                                                    clearCartItems();
                                                }, 1000);
                                                
                                                return;
                                            } else {
                                                handleDishResults(dishResults);
                                            }
                                            return;
                                        }

                                        const searchResults = document.querySelector('div[data-testid="search-pl-restaurant-card"]');
                                        if (searchResults) {
                                            console.log('Restaurant results found, looking for Dishes button...');
                                            
                                            const dishesTab = document.querySelector('span[data-testid="DISH-nav-tab-pl"]');
                                            
                                            if (dishesTab) {
                                                console.log('Found Dishes tab, triggering click event...');
                                                window.androidInterface.updateProgress('Opening Dishes section...');
                                                
                                                ['mousedown', 'mouseup', 'click'].forEach(eventType => {
                                                    const clickEvent = new MouseEvent(eventType, {
                                                        view: window,
                                                        bubbles: true,
                                                        cancelable: true,
                                                        buttons: 1
                                                    });
                                                    dishesTab.dispatchEvent(clickEvent);
                                                });
                                                
                                                const innerButton = dishesTab.querySelector('span[role="button"]');
                                                if (innerButton) {
                                                    console.log('Also clicking inner button...');
                                                    ['mousedown', 'mouseup', 'click'].forEach(eventType => {
                                                        const clickEvent = new MouseEvent(eventType, {
                                                            view: window,
                                                            bubbles: true,
                                                            cancelable: true,
                                                            buttons: 1
                                                        });
                                                        innerButton.dispatchEvent(clickEvent);
                                                    });
                                                }
                                            } 
                                            else if (retryCount < 2) {
                                                retryCount++;
                                                console.log('Dishes tab not found, retry attempt: ' + retryCount);
                                                setTimeout(waitForResults, 1000);
                                            }
                                            else {
                                                console.log('Failed to find Dishes tab after 2 retries');
                                                window.androidInterface.updateProgress('Could not find Dishes section');
                                            }
                                        } 
                                        else {
                                            setTimeout(waitForResults, 1000);
                                        }
                                    }

                                    function handleDishResults(dishResults) {
                                            const preference = "${pendingFormData?.getString("preference") ?: "all"}";
                                            const priceRange = "${pendingFormData?.getString("priceRange")}";
                                            
                                            let priceLimit = 0;
                                            if (priceRange.startsWith('<')) {
                                                priceLimit = parseInt(priceRange.substring(1));
                                            } else if (priceRange.startsWith('>')) {
                                                priceLimit = parseInt(priceRange.substring(1)) * 1.5;
                                            }
                                            
                                            const filteredDishes = Array.from(dishResults).filter(dish => {
                                                const isVeg = dish.querySelector('svg[class*="fDcVYp"]') !== null;
                                                const priceText = dish.querySelector('div[class*="chixpw"]')?.textContent || '0';
                                                const price = parseInt(priceText);
                                                const ratingText = dish.querySelector('span[class*="_30uSg"]')?.textContent || '0';
                                                const rating = parseFloat(ratingText);
                                                
                                                console.log('Dish found - Price:', price, 'Rating:', rating, 'IsVeg:', isVeg);
                                                
                                                if (preference === 'veg' && !isVeg) return false;
                                                if (preference === 'non-veg' && isVeg) return false;
                                                if (priceRange.startsWith('<') && price > priceLimit) return false;
                                                if (priceRange.startsWith('>') && price < priceLimit) return false;
                                                if (rating < 3) return false;
                                                
                                                return true;
                                            }).sort((a, b) => {
                                                const ratingA = parseFloat(a.querySelector('span[class*="_30uSg"]')?.textContent || '0');
                                                const ratingB = parseFloat(b.querySelector('span[class*="_30uSg"]')?.textContent || '0');
                                                return ratingB - ratingA; // Sort by rating descending
                                            });
                                            
                                            if (filteredDishes.length > 0) {
                                                const bestMatch = filteredDishes[0];
                                                console.log('Found best matching dish, clicking add button...');
                                                
                                                const itemName = bestMatch.querySelector('div[class*="sc-aXZVg eqSzsP"]')?.textContent || '';
                                                const price = bestMatch.querySelector('div[class*="chixpw"]')?.textContent || '';
                                                const rating = bestMatch.querySelector('span[class*="_30uSg"]')?.textContent || '';
                                                const isVeg = bestMatch.querySelector('svg[class*="fDcVYp"]') !== null;
                                                
                                                const itemDetails = 'Selected: ' + itemName + ' (' + (isVeg ? 'Veg' : 'Non-veg') + ') - ₹' + price + ' - ' + rating + '★';
                                                console.log(itemDetails);
                                                window.androidInterface.updateProgress(itemDetails);
                                                
                                                setTimeout(() => {
                                                    const addButton = bestMatch.querySelector('button.add-button-center-container');
                                                    
                                                    if (addButton) {
                                                        console.log('Found Add button, clicking it...');
                                                        
                                                        addButton.removeAttribute('disabled');
                                                        addButton.classList.remove('disabled');
                                                        
                                                        ['mousedown', 'mouseup', 'click'].forEach(eventType => {
                                                            const clickEvent = new MouseEvent(eventType, {
                                                                view: window,
                                                                bubbles: true,
                                                                cancelable: true,
                                                                buttons: 1
                                                            });
                                                            addButton.dispatchEvent(clickEvent);
                                                            
                                                            const innerDiv = addButton.querySelector('div.sc-aXZVg.biMKCZ');
                                                            if (innerDiv) {
                                                                innerDiv.dispatchEvent(clickEvent);
                                                            }
                                                        });
                                                        
                                                        addButton.click();
                                                        
                                                        const checkCustomizeInterval = setInterval(() => {
                                                            const customizeButton = document.querySelector('button[data-testid="menu-customize-continue-button"], button[data-cy="customize-footer-add-button"]');
                                                            if (customizeButton) {
                                                                console.log('Found customize button, clicking it...');
                                                                window.androidInterface.updateProgress(itemDetails + ' - Customizing...');
                                                                customizeButton.click();
                                                            } else {
                                                                clearInterval(checkCustomizeInterval);
                                                                window.androidInterface.updateProgress(itemDetails + ' - Added to cart');
                                                                
                                                                setTimeout(() => {
                                                                    const checkoutButton = document.querySelector('button._190NL');
                                                                    if (checkoutButton) {
                                                                        console.log('Found checkout button, proceeding to checkout...');
                                                                        window.androidInterface.handleCheckout();
                                                                        checkoutButton.click();
                                                                    }
                                                                }, 1000);
                                                            }
                                                        }, 1000);
                                                        
                                                    } 
                                                    else {
                                                        console.log('Add button not found');
                                                    }
                                                }, 2000);
                                            } else {
                                                console.log('No dishes match the criteria');
                                                window.androidInterface.updateProgress('No matching dishes found');
                                            }
                                    }
                                    
                                    setTimeout(waitForResults, 2000);
                                })();
                            """.trimIndent()
                            
                            view?.evaluateJavascript(clickDishesButtonJs) { result ->
                                android.util.Log.d("WebView", "Dishes button click result: $result")
                            }
                        }
                        "ride" -> {
                            val checkPhoneInputJs = """
                                (function() {
                                    const phoneInput = document.querySelector('input[type="tel"].mobile-input.phone-number');
                                    return phoneInput != null;
                                })();
                            """.trimIndent()
                            
                            view?.evaluateJavascript(checkPhoneInputJs) { result ->
                                val hasPhoneInput = result.equals("true", ignoreCase = true)
                                
                                if (hasPhoneInput) {
                                    binding.progress.text = "Please enter your phone number..."
                                    
                                    runOnUiThread {
                                        input("Enter your phone number", required = true) { phoneNumber ->
                                            if (phoneNumber.length == 10 && phoneNumber.all { it.isDigit() }) {
                                                val fillPhoneNumberJs = """
                                                        (function() {
                                                            const phoneInput = document.querySelector('input[type="tel"].mobile-input.phone-number');
                                                            if (phoneInput) {
                                                                phoneInput.value = '$phoneNumber';
                                                                phoneInput.dispatchEvent(new Event('input', { bubbles: true }));
                                                                phoneInput.dispatchEvent(new Event('change', { bubbles: true }));
                                                                
                                                                const nextButton = document.querySelector('button.next-button');
                                                                if (nextButton) {
                                                                    nextButton.removeAttribute('disabled');
                                                                    nextButton.classList.remove('disabled-btn');
                                                                    nextButton.click();

                                                                    setTimeout(() => {
                                                                        const otpForm = document.querySelector('.otp-page-wrapper');
                                                                        if (otpForm) {
                                                                            window.androidInterface.handleOtpInput();
                                                                        }
                                                                    }, 1000);
                                                                }
                                                            }
                                                        })();
                                                    """.trimIndent()
                                                view.evaluateJavascript(fillPhoneNumberJs, null)
                                            }
                                            else {
                                                toast("Please enter a valid 10-digit phone number")
                                            }
                                        }
                                    }
                                }
                                else {
                                    binding.progress.text = "Connecting to Rapido's server..."
                                    Handler(mainLooper).postDelayed({
                                        val pickupLocation = pendingFormData?.getString("pickup") ?: ""
                                        val dropLocation = pendingFormData?.getString("drop") ?: ""

                                        val findPickupInputJs = """
                            (function() {
                                window.androidInterface.updateProgress('Initializing location services...');
                                const pickupInput = document.querySelector('input[placeholder="Enter pickup location here"]');
                                if (pickupInput) {
                                    console.log('Found pickup input, clicking it...');
                                    pickupInput.click();

                                    const pickupInput2 = document.querySelector('input[placeholder="Enter pickup location"]');
                                    pickupInput2.value = '$pickupLocation';
                                    pickupInput2.focus();
                                    window.androidInterface.updateProgress('Processing origin coordinates...');
                                    
                                    const events = ['input', 'keydown', 'keyup', 'change'];
                                    events.forEach(eventType => {
                                        const event = new Event(eventType, { bubbles: true });
                                        pickupInput2.dispatchEvent(event);
                                    });
                                    
                                    const inputEvent = new InputEvent('input', {
                                        bubbles: true,
                                        cancelable: true,
                                        inputType: 'text',
                                        data: '$pickupLocation'
                                    });
                                    pickupInput2.dispatchEvent(inputEvent);
                                    
                                    function checkAndClickSuggestion(retryCount = 0) {
                                        const suggestionList = document.querySelector('.suggestion-result');
                                        if (suggestionList) {
                                            console.log('Found suggestion list');
                                            const firstItem = suggestionList.querySelector('.location-item');
                                            if (firstItem) {
                                                console.log('Clicking first suggestion');
                                                firstItem.click();
                                                
                                                setTimeout(() => {
                                                    const dropInput = document.querySelector('input[placeholder="Enter drop location here"]');
                                                    if (dropInput) {
                                                        console.log('Found drop input, clicking it...');
                                                        dropInput.click();

                                                        const dropInput2 = document.querySelector('input[placeholder="Enter drop location"]');
                                                        dropInput2.focus();
                                                        
                                                        dropInput2.value = '$dropLocation';
                                                        const events = ['input', 'keydown', 'keyup', 'change'];
                                                        events.forEach(eventType => {
                                                            const event = new Event(eventType, { bubbles: true });
                                                            dropInput2.dispatchEvent(event);
                                                        });
                                                        
                                                        const inputEvent = new InputEvent('input', {
                                                            bubbles: true,
                                                            cancelable: true,
                                                            inputType: 'text',
                                                            data: '$dropLocation'
                                                        });
                                                        dropInput2.dispatchEvent(inputEvent);

                                                        function checkAndClickDropSuggestion(retryCount = 0) {
                                                            const suggestionList = document.querySelector('.suggestion-result');
                                                            if (suggestionList) {
                                                                window.androidInterface.updateProgress('Validating destination parameters...');
                                                                console.log('Found drop suggestion list');
                                                                const firstItem = suggestionList.querySelector('.location-item');
                                                                if (firstItem) {
                                                                    console.log('Clicking first drop suggestion');
                                                                    firstItem.click();
                                                                    window.androidInterface.updateProgress('Analyzing route optimization...');
                                                                    
                                                                    setTimeout(() => {
                                                                        const desiredCarType = '${if (pendingFormData?.has("vehicle") == true) pendingFormData?.getString("vehicle") else "Car"}';
                                                                        window.androidInterface.updateProgress('Calculating available fleet options...');
                                                                        
                                                                        function attemptCardSelection(retryCount = 0) {
                                                                            console.log('Attempting card selection, attempt:', retryCount + 1);
                                                                            const cards = document.querySelectorAll('.card-wrap');
                                                                            
                                                                            if (cards.length === 0) {
                                                                                if (retryCount < 2) {
                                                                                    console.log('No cards found, retrying in 3 seconds...');
                                                                                    window.androidInterface.updateProgress('Synchronizing service catalog...');
                                                                                    setTimeout(() => attemptCardSelection(retryCount + 1), 3000);
                                                                                    return;
                                                                                } else {
                                                                                    console.log('No cards found after maximum retries');
                                                                                    window.androidInterface.updateProgress('Service synchronization incomplete...');
                                                                                    return;
                                                                                }
                                                                            }
                                                                            
                                                                            let foundRequestedService = false;
                                                                            
                                                                            cards.forEach(card => {
                                                                                const serviceNameSpan = card.querySelector('span.selected-service-name, span:not([class])');
                                                                                if (serviceNameSpan) {
                                                                                    const serviceName = serviceNameSpan.textContent.trim();
                                                                                    console.log('Found service:', serviceName);
                                                                                    
                                                                                    if (serviceName.toLowerCase() === desiredCarType.toLowerCase()) {
                                                                                        foundRequestedService = true;
                                                                                        console.log('Clicking service card:', serviceName);
                                                                                        window.androidInterface.updateProgress('Optimizing service parameters...');
                                                                                        card.click();
                                                                                        
                                                                                        proceedWithBooking();
                                                                                    }
                                                                                }
                                                                            });
                                                                            
                                                                            if (!foundRequestedService) {
                                                                                if (retryCount < 2) {
                                                                                    console.log('Requested service not found, retrying in 3 seconds...');
                                                                                    window.androidInterface.updateProgress('Recalibrating service options...');
                                                                                    setTimeout(() => attemptCardSelection(retryCount + 1), 3000);
                                                                                } else {
                                                                                    console.log('Requested service not found after retries, selecting first available');
                                                                                    const firstCard = cards[0];
                                                                                    const serviceNameSpan = firstCard.querySelector('span.selected-service-name, span:not([class])');
                                                                                    const serviceName = serviceNameSpan ? serviceNameSpan.textContent.trim() : 'Default';
                                                                                    console.log('Selecting first available:', serviceName);
                                                                                    window.androidInterface.updateProgress('Optimizing alternative service parameters...');
                                                                                    firstCard.click();
                                                                                    
                                                                                    proceedWithBooking();
                                                                                }
                                                                            }
                                                                        }
                                                                        
                                                                        function proceedWithBooking() {
                                                                            setTimeout(() => {
                                                                                const bookButton = document.querySelector('button.next-button');
                                                                                if (bookButton) {
                                                                                    console.log('Found Book button, clicking it...');
                                                                                    window.androidInterface.updateProgress('Initiating secure booking protocol...');
                                                                                     bookButton.click();
                                                                                    
                                                                                    function checkForCallCaptain(retryCount = 0) {
                                                                                        console.log('Checking for call captain element, attempt:', retryCount + 1);
                                                                                        const callCaptainLink = document.querySelector('a[href^="tel:"]');
                                                                                        const captainMetadata = document.querySelector('.captain-metadata');
                                                                                        
                                                                                        if (callCaptainLink && captainMetadata) {
                                                                                            console.log('Found captain details!');
                                                                                            
                                                                                            const phoneNumber = callCaptainLink.getAttribute('href').replace('tel:', '');
                                                                                            
                                                                                            const vehicleNumber = captainMetadata.querySelector('.vehicle-number')?.textContent || '';
                                                                                            
                                                                                            const captainName = captainMetadata.querySelector('.sub-info:last-child')?.textContent || '';
                                                                                            
                                                                            const rating = captainMetadata.querySelector('.rating-text')?.textContent || '';
                                                                            
                                                                            const pinBoxes = document.querySelectorAll('.otp-section .box');
                                                                            const pin = Array.from(pinBoxes)
                                                                                .map(box => box.textContent || '')
                                                                                .join('');
                                                                                
                                                                            const locationInfo = document.querySelector('.location-info-wrap');
                                                                            const pickup = locationInfo?.querySelector('.border-loc-bottom .location')?.textContent || '';
                                                                            const drop = locationInfo?.querySelector('.location-wrap:not(.border-loc-bottom) .location')?.textContent || '';
                                                                            
                                                                            const captainPhotoUrl = captainMetadata.querySelector('.captain-profile img.driver-photo')?.getAttribute('src') || '';
                                                                            
                                                                            window.androidInterface.onCaptainFound(
                                                                                phoneNumber,
                                                                                captainName,
                                                                                rating,
                                                                                pickup,
                                                                                drop,
                                                                                captainPhotoUrl
                                                                            );
                                                                                        } else {
                                                                                            console.log('Captain details not found, retrying in 3 seconds...');
                                                                                            window.androidInterface.updateProgress('Establishing secure connection with nearest captain...');
                                                                                            setTimeout(() => checkForCallCaptain(retryCount + 1), 3000);
                                                                                        }
                                                                                    }
                                                                                    
                                                                                    setTimeout(() => checkForCallCaptain(), 3000);
                                                                                } else {
                                                                                    console.log('Book button not found');
                                                                                    window.androidInterface.updateProgress('Finalizing booking parameters...');
                                                                                }
                                                                            }, 1000);
                                                                        }
                                                                        
                                                                        attemptCardSelection();
                                                                    }, 2000);
                                                                }
                                                            } else if (retryCount < 2) {
                                                                console.log('Drop suggestion list not found, retrying in 3 seconds...');
                                                                setTimeout(() => checkAndClickDropSuggestion(retryCount + 1), 3000);
                                                            } else {
                                                                console.log('Drop suggestion list not found after retries');
                                                            }
                                                        }
                                                        
                                                        setTimeout(() => checkAndClickDropSuggestion(), 2000);
                                                    } else {
                                                        console.log('Drop input not found');
                                                    }
                                                }, 500);
                                            }
                                        } else if (retryCount < 2) { 
                                            console.log('Suggestion list not found, retrying in 3 seconds...');
                                            setTimeout(() => checkAndClickSuggestion(retryCount + 1), 3000);
                                        } else {
                                            console.log('Suggestion list not found after retries');
                                        }
                                    }
                                    
                                    setTimeout(() => checkAndClickSuggestion(), 2000);
                                } else {
                                    console.log('Pickup input not found');
                                }
                            })();
                        """.trimIndent()

                                        view.evaluateJavascript(findPickupInputJs) { result ->
                                            android.util.Log.d("WebView", "Input click result: $result")
                                        }
                                    }, 2000) }
                            }
                        }
                    }
                }
            }
        }
    }

    private val systemPrompt = """
        You are Aiboo, an AI agent.

        AGENTIC QUERIES ---
        You have to extract some details based on user (he) queries.

        ...
        When he wants to order food [id = 'food']:
           - item (food name)
           - priceRange (valid formats: 100-200, >200, <300)
           - preference (veg, non-veg)
        
        When he wants to book a ride [id = 'ride']:
           - pickup
           - drop
           - vehicle (Bike, Auto, Car)
           
        When he wants to post something on twitter/X [id = 'tweet']:
           - content
                    
        When he wants to call or text [id = 'call' or 'text']:
           - contact (name, phone number, email (only for text))
           - text (optional for call, required for text)
           - platform (e.g. Whatsapp, Telegram, Email etc)
   
        When he wants to open an app or website [id = 'app' or 'web']:
           - platform (app name, website name/domain)
        
        When he wants to capture picture/screenshot [id = 'capture']:
           - mode (front, back, screen)
                      
        When he wants to perform a quick action [id = 'action']:
           - name (valid names - flashlight, vibrate, playRingtone, silent, brightness, volume)
           - todo (optional for vibrate, playRingtone; valid todos - on, off (for flashlight, silent): increase, decrease, auto, [percent] (for brightness, volume))
           
        When he wants to set an alarm or reminder [id = 'reminder'], current time is ${System.currentTimeMillis().formatAsTime("hh:mm a dd-MM-yyyy")}:
           - time (format (12hr): HH:MM AM/PM DD-MM-YYYY)
           - priority (auto detect, valid values: low, high)
           - note (auto generate based on query, also include short suggestion)
           
        When he asks to delete, rename, copy or move files/folders (fetch the files using 'data' method explained below if user has not provided files yet) [id = 'delete', 'rename', 'copy' or 'move']:
           - list (requested files/folders full path separated by '#', e.g. of delete - 'Downloads/Aiboo/file1#Documents/file2#file3', e.g. of rename - 'Downloads/Aiboo/file1..newname#Documents/file2..newname', e.g. of copy and move - 'Downloads/Aiboo/file1..Downloads/OtherFolder/file1#Documents/file2..AnotherFolder/file2')
           
        How / When to fetch data from device - When he asks something which requires data from device to do/answer (photos (images/gallery), calender, contacts, sms, files, callHistory) [id = 'data']:
           - type (valid values: photos, calender, contacts, sms, files, callHistory)
        ...
        
        If any required detail is missing:
           - Ask the user specifically for the missing information
          
        If all required details are present:
           - Format your response in valid json with extracted details, e.g. : {"id": "...", "platform": "...", "message": "[Information to user that we're processing the request]"} ('id' and 'message' must be present in all json responses)
           
        Correct agent response: 
           - valid json or json array format (to handle multiple commands at once, e.g. if he asked to call and text in same command then use json array to format each command)
           - any of these commands must never be processed together in single query - 'ride', 'food', 'tweet' (e.g. if user asked to book ride and order food at once, avoid one of them)
           - use message key of json object to inform or explain about ongoing process
           - all fields are must until it has mentioned 'optional'
        Incorrect agent response: 
           - invalid or not json format
           - missing 'id', 'message' fields in any response
           - adding any additional text or explanation outside json object ('hey i will do it { "name": "...." }' - this is invalid format)
        
        --- AGENETIC QUERIES

        RESPONSE GUIDELINES
        - Use SMS for financial or any other data, unless he specified other source
        - Don't ask permission for anything, just do it
        - Use emojis to feel futuristic, always be concise
    """.trimIndent()

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...")
        }
        binding.lottieAnimationView.playAnimation()
        binding.lottieAnimationView2.playAnimation()
        speechRecognizer.startListening(intent)
    }

    @SuppressLint("SetTextI18n")
    private fun loadPlatformWebsite(jsonResponse: JSONObject) {
        val identifier = jsonResponse.getString("id")?:""
        when (identifier) {
            "ride", "tweet", "food", "bid" -> {
                pendingFormData = jsonResponse
                binding.webView.loadUrl(
                    when (identifier) {
                        "ride" -> "https://m.rapido.bike"
                        "tweet" -> "https://x.com/i/flow/login"
                        "food" -> "https://www.swiggy.com/search?query=${jsonResponse.getString("item")}"
                        else -> ""
                    }
                )
                showWebView(true)
            }
            "call" -> {
                permission.request(Manifest.permission.CALL_PHONE) {
                    if (it) {
                        permission.request(Manifest.permission.READ_CONTACTS) { granted->
                            if (granted) {
                                val phoneNumber = fetchContact(jsonResponse.getString("contact"))
                                if (phoneNumber.isNotEmpty()) {
                                    val encodedHash = Uri.encode("#")
                                    goTo(Intent(Intent.ACTION_CALL, "tel:${phoneNumber.replace("#", encodedHash)}".toUri()))
                                }
                            }
                        }
                    }
                }
            }
            "text" -> {
                permission.request(Manifest.permission.READ_CONTACTS) {
                    if (it) {
                        sendMessage(fetchContact(jsonResponse.getString("contact")), jsonResponse)
                    }
                }
            }
            "app" -> {
                val platform = fetchApp(jsonResponse.getString("platform"))
                if (platform.isEmpty()) return

                val intent = packageManager.getLaunchIntentForPackage(platform)?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                if (intent != null) goTo(intent)
            }
            "web" -> goTo(jsonResponse.getString("platform"))
            "capture" -> capture(jsonResponse.getString("mode"))
            "action" -> performAction(jsonResponse.getString("name"), if (jsonResponse.has("todo")) jsonResponse.getString("todo") else "")
            "reminder" -> {
                if (!Settings.canDrawOverlays(this)) {
                    goTo(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
                    binding.geminiResponseText.text = "Grant overlay permission and try again."
                    return
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permission.request(Manifest.permission.POST_NOTIFICATIONS) {
                        if (it) {
                            setupReminder(jsonResponse)
                        }
                    }
                }
                else setupReminder(jsonResponse)
                requestIgnoreBatteryOptimization()
            }
            "data" -> if (jsonResponse.has("type")) dataProcess(jsonResponse.getString("type"))
            "delete", "rename", "move", "copy" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!Environment.isExternalStorageManager()) {
                        goTo(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION))
                        binding.geminiResponseText.text = "Grant storage permission and try again."
                        return
                    }
                }
                else {
                    val permissions = listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    if (havePermission(permissions)) {
                        getPermission(permissions)
                        binding.geminiResponseText.text = "Grant storage permission and try again."
                        return
                    }
                }
                processFiles(jsonResponse.getString("id"), jsonResponse.getString("list").split("#"))
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupReminder(jsonResponse: JSONObject) {
        val time = jsonResponse.getString("time")
        val message = jsonResponse.getString("note")
        val priority = jsonResponse.getString("priority") ?: "low"
        val dateFormat = SimpleDateFormat("hh:mm a dd-MM-yyyy", Locale.getDefault())
        val reminderTime = dateFormat.parse(time)

        if (reminderTime != null) {
            scheduleReminder(reminderTime.time, message ?: "Reminder from Aiboo", priority)
            binding.geminiResponseText.append("\nReminder set for $time")

            if (readInternalFile("reminders.txt").isNotEmpty()) {
                val reminders = File(filesDir, "reminders.txt").readText()
                val reminderList = reminders.split("\n").toMutableList()

                reminderList.add("$time: ${message.replace("\n", " ")}")
                writeInternalFile("reminders.txt", reminderList.joinToString("\n"))
            }
            else writeInternalFile("reminders.txt", "$time: ${message.replace("\n", " ")}")
        }
        else binding.geminiResponseText.text = "Invalid time format. Please use HH:MM DD-MM-YYYY"
    }

    private fun processFiles(type: String, files: List<String>) {
        var processed = 0
        for (file in files) {
            if (file.trim().isEmpty()) continue
            try {
                val filePath = File(Environment.getExternalStorageDirectory().absolutePath + "/" + file.split("..")[0].trim())

                when (type) {
                    "delete" -> if (filePath.delete()) processed++
                    "copy" -> {
                        val newPath = File(Environment.getExternalStorageDirectory().absolutePath + "/" + file.split("..")[1].trim())
                        filePath.copyTo(newPath)
                        processed++
                    }
                    "move" -> {
                        val newPath = File(Environment.getExternalStorageDirectory().absolutePath + "/" + file.split("..")[1].trim())
                        filePath.renameTo(newPath)
                        processed++
                    }
                    "rename" -> {
                        val newPath = Environment.getExternalStorageDirectory().absolutePath + "/" + file.split("..")[0].trim().removeSuffix(filePath.name).removeSuffix("/") + "/" + file.split("..")[1].trim()
                        filePath.renameTo(File(newPath))
                        processed++
                    }
                }
            }
            catch (e: Exception) { e.printStackTrace() }
        }
        toast(
            when (type) {
                "delete" -> "Deleted"
                "copy" -> "Copied"
                "move" -> "Moved"
                "rename" -> "Renamed"
                else -> "Processed"
            }
        )
    }

    private var lastProcess = ""
    @SuppressLint("SetTextI18n", "Recycle", "SimpleDateFormat")
    private fun dataProcess(string: String) {
        if (lastProcess == string) return
        binding.geminiResponseText.append("\nFetching $string...")
        lastProcess = string

        when (string) {
            "photos" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permission.request(Manifest.permission.READ_MEDIA_IMAGES) {
                        if (it) {
                            getPhotos()
                        }
                    }
                }
                else {
                    permission.request(Manifest.permission.READ_EXTERNAL_STORAGE) {
                        if (it) {
                            getPhotos()
                        }
                    }
                }
            }
            "calender" -> {
                permission.request(Manifest.permission.READ_CALENDAR) { granted ->
                    if (granted) {
                        val contentResolver: ContentResolver = applicationContext.contentResolver
                        val uri = CalendarContract.Events.CONTENT_URI
                        val projection = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DESCRIPTION, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.EVENT_LOCATION)

                        val calendar = Calendar.getInstance()
                        val startTimeMillis = calendar.timeInMillis

                        calendar.add(Calendar.DAY_OF_YEAR, 31)

                        val endTimeMillis = calendar.timeInMillis
                        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTEND} <= ?"
                        val selectionArgs = arrayOf(startTimeMillis.toString(), endTimeMillis.toString())
                        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
                        val events = mutableListOf<String>()

                        cursor?.use {
                            while (it.moveToNext()) {
                                val title = it.getString(it.getColumnIndexOrThrow(CalendarContract.Events.TITLE))
                                val description = it.getString(it.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION))
                                val startDate = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events.DTSTART))
                                val endDate = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events.DTEND))
                                val location = it.getString(it.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION))

                                val eventDetails = "Event: $title\nDescription: $description\nStart: $startDate\nEnd: $endDate\nLocation: $location"
                                events.add(eventDetails)
                            }
                        }

                        if (events.isNotEmpty()) getGeminiResponse(events.joinToString("\n"), false)
                        else binding.geminiResponseText.text = "No events found."
                    }
                }
            }
            "contacts" -> {
                permission.request(Manifest.permission.READ_CONTACTS) {
                    if (it) {
                        val contacts = getContacts()
                        if (contacts.isNotEmpty()) getGeminiResponse(contacts.toString(), false)
                        else binding.geminiResponseText.text = "No contacts found."
                    }
                }
            }
            "callHistory" -> {
                permission.request(Manifest.permission.READ_CALL_LOG) { granted ->
                    if (granted) {
                        val uri = CallLog.Calls.CONTENT_URI
                        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE)
                        val cursor = contentResolver.query(uri, projection, null, null, null)
                        val calls = mutableListOf<String>()

                        cursor?.use {
                            var count = 0
                            while (it.moveToNext() && count < 100) {
                                val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                                val type = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                                val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                                val dateFormatted = SimpleDateFormat("HH:mm dd-MM-yyyy").format(Date(date))

                                val callDetails = "From: $number\nDate: $dateFormatted\nType: $type"
                                calls.add(callDetails)
                                count++
                            }
                        }

                        if (calls.isNotEmpty()) getGeminiResponse(calls.joinToString("\n"), false)
                        else binding.geminiResponseText.text = "No call history found."
                    }
                }
            }
            "sms" -> {
                permission.request(Manifest.permission.READ_SMS) { granted ->
                    if (granted) {
                        val uri = Telephony.Sms.Inbox.CONTENT_URI
                        val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
                        val sortOrder = "${Telephony.Sms.DATE} DESC"
                        val cursor = contentResolver.query(uri, projection, null, null, sortOrder)
                        val messages = mutableListOf<String>()

                        cursor?.use {
                            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            var count = 0
                            while (it.moveToNext() && count < 100) {
                                val address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS))
                                val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY))
                                val dateMillis = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE))
                                val dateFormatted = dateFormat.format(Date(dateMillis))

                                val smsDetails = "From: $address\nDate: $dateFormatted\nMessage: $body"
                                messages.add(smsDetails)
                                count++
                            }
                        }

                        if (messages.isNotEmpty()) getGeminiResponse(messages.joinToString("\n"), false)
                        else binding.geminiResponseText.text = "No SMS messages found."
                    }
                }
            }
            "files" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!Environment.isExternalStorageManager()) {
                        goTo(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        binding.geminiResponseText.text = "Grant all files access permission and try again."
                    }
                    else {
                        fileStructure = ""
                        listFileStructure(Environment.getExternalStorageDirectory())
                        after(1.5) { getGeminiResponse(fileStructure, false) }
                    }
                }
                else {
                    permission.request(Manifest.permission.READ_EXTERNAL_STORAGE) {
                        if (it) {
                            fileStructure = ""
                            listFileStructure(Environment.getExternalStorageDirectory())
                            after(1.5) { getGeminiResponse(fileStructure, false) }
                        }
                    }
                }
            }
        }
    }

    private lateinit var fileStructure: String
    private fun listFileStructure(directory: File, indent: String = "") {
        val files = directory.listFiles()
        files?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))?.forEach { file ->
            if (!file.name.endsWith(".crypt14") && !file.name.contains("#") && !file.name.contains("..")) {
                fileStructure += "$indent${file.name}\n"
                if (file.isDirectory) listFileStructure(file, "$indent    ")
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun performAction(name: String, todo: String) {
        when (name) {
            "vibrate" -> vibrate(legacyFallback = true)
            "playRingtone" -> {
                val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val ringtone = RingtoneManager.getRingtone(applicationContext, ringtoneUri)
                ringtone.play()
            }
            "flashlight", "silent" -> {
                when (name) {
                    "flashlight" -> {
                        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
                        val cameraId = cameraManager.cameraIdList[0]
                        cameraManager.setTorchMode(cameraId, todo == "on")
                    }
                    "silent" -> {
                        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

                        if (notificationManager.isNotificationPolicyAccessGranted) {
                            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                            audioManager.ringerMode = if (todo == "on") AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
                        }
                        else {
                            goTo(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                            binding.geminiResponseText.text = "Grant Do Not Disturb access and try again."
                        }
                    }
                }
            }
            "brightness", "volume" -> {
                when (name) {
                    "brightness" -> {
                        if (!Settings.System.canWrite(this)) {
                            goTo(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply { data = "package:$packageName".toUri() })
                            binding.geminiResponseText.text = "Grant write settings permission and try again."
                            return
                        }

                        if (todo.isDigitsOnly() && todo.toInt() in 0..100) Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, todo.toInt() * 255/100)
                        else if (todo == "auto") Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                        else Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, if (todo == "increase") 255 else 0)
                    }
                    "volume" -> {
                        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

                        if (todo.isDigitsOnly()) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, todo.toInt()*maxVolume/100, 0)
                        else if (todo == "increase") audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                        else if (todo == "decrease") audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                        else if (todo == "auto") audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
                    }
                }
            }
        }
    }

    private fun sendMessage(phoneNumber: String, pendingFormData: JSONObject) {
        val text = pendingFormData.getString("text")
        val platform = if (pendingFormData.has("platform")) pendingFormData.getString("platform") else "default"

        if (phoneNumber.isEmpty()) return
        var intent: Intent? = null
        when (platform.lowercase()) {
            "whatsapp" -> intent = Intent(Intent.ACTION_VIEW, "https://wa.me/$phoneNumber?text=$text".toUri())
            "messenger" -> intent = Intent(Intent.ACTION_VIEW, "https://m.me/$phoneNumber?text=$text".toUri())
            "gmail", "mail", "email" -> intent = Intent(Intent.ACTION_SENDTO, "mailto:$phoneNumber?subject=Message from Aiboo&body=$text".toUri())
            else -> {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), 13)
                    return
                }

                val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) applicationContext.getSystemService(SmsManager::class.java)
                else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                smsManager.sendTextMessage(phoneNumber, null, text, null, null)
            }
        }
        if (intent != null) goTo(intent.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
    }

    private fun capture(mode: String) {
        when (mode.lowercase()) {
            "screen" -> {
                captureScreen { bitmap ->
                    if (bitmap == null) toast("Failed to capture screen.")
                    val fileName = "Aiboo_Screenshot_${System.currentTimeMillis().formatAsTime("yyyyMMDDmmss")}.jpg"
                    val imagesDir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Aiboo")

                    if (!imagesDir.exists()) {
                        imagesDir.mkdirs()
                    }

                    val imageFile = File(imagesDir, fileName)
                    val outputStream = FileOutputStream(imageFile)

                    bitmap!!.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                    outputStream.close()

                    capturedImageUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", imageFile)
                    binding.capturedImageView.setImageBitmap(bitmap)
                    binding.captureContainer.show()
                }
            }
            "front", "back" -> {
                permission.request(Manifest.permission.CAMERA) {
                    if (it) {
                        capturePhoto(mode)
                    }
                }
            }
        }
    }

    private fun capturePhoto(mode: String) {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                
                val cameraSelector = when (mode.lowercase()) {
                    "front" -> CameraSelector.DEFAULT_FRONT_CAMERA
                    "back" -> CameraSelector.DEFAULT_BACK_CAMERA
                    else -> CameraSelector.DEFAULT_BACK_CAMERA
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        imageCapture!!
                    )
                    takePhoto()
                }
                catch (e: Exception) {
                    toast("Failed to bind camera: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        }
        catch (e: Exception) {
            toast("Failed to initialize camera: ${e.message}")
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val fileName = "Aiboo_Photo_${System.currentTimeMillis().formatAsTime("yyyyMMDDmmss")}.jpg"
        val imagesDir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Aiboo")

        if (!imagesDir.exists()) {
            imagesDir.mkdirs()
        }
        
        photoFile = File(imagesDir, fileName)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile!!).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    try {
                        photoFile?.let { file ->
                            capturedImageUri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", file)
                            binding.capturedImageView.setImageURI(capturedImageUri)
                            binding.captureContainer.show()
                        }
                    }
                    catch (e: Exception) {
                        toast("Failed to display image: ${e.message}")
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    toast("Failed to capture photo: ${exception.message}")
                }
            }
        )
    }

    private fun fetchApp(platform: String): String {
        val apps = getApps()
        for (a in apps) {
            if (a.value.lowercase() == platform.lowercase()) return a.key
        }

        for (a in apps) {
            if (a.value.lowercase().contains(platform.lowercase())) return a.key
        }

        toast("No app found with name: $platform")
        return ""
    }

    private fun fetchContact(contact: String): String {
        if (contact.removePrefix("+").removeSuffix("#").replace("-", "").replace(" ", "").isDigitsOnly()) return contact
        else {
            val contactList = getContacts()
            for (a in contactList) { if (a.value.lowercase() == contact.lowercase()) return a.key }
            for (a in contactList) { if (a.value.lowercase().startsWith( contact.lowercase())) return a.key }
            for (a in contactList) { if (a.value.lowercase().contains( contact.lowercase())) return a.key }

            toast("No contact found with name: $contact")
            return ""
        }
    }

    private fun getApps(): HashMap<String, String> {
        val appsMap = HashMap<String, String>()
        val applications = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

        for (appInfo in applications) {
            val packageName = appInfo.packageName
            val appName = packageManager.getApplicationLabel(appInfo).toString()
            appsMap[packageName] = appName
        }
        return appsMap
    }

    private fun getContacts(): HashMap<String, String> {
        val contactsMap = HashMap<String, String>()
        val contactCursor: Cursor? = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null, null, null
        )

        contactCursor?.use {
            val idCol = it.getColumnIndex(ContactsContract.Contacts._ID)
            val nameCol = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)

            while (it.moveToNext()) {
                val contactId = it.getString(idCol)
                val contactName = it.getString(nameCol)

                val phoneCursor: Cursor? = contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                    arrayOf(contactId),
                    null
                )

                phoneCursor?.use { pCursor ->
                    val numberCol = pCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (pCursor.moveToNext()) {
                        pCursor.getString(numberCol)?.let { phoneNumber ->
                            contactsMap[phoneNumber] = contactName ?: "Unknown Name"
                        }
                    }
                }
            }
        }
        return contactsMap
    }

    private fun showWebView(show: Boolean) {
        if (show) {
            binding.progress.show()
            binding.anim.alpha = 1f
            binding.webView.alpha = 0f
            binding.webViewContainer.show()
        }
        else binding.webViewContainer.hide()
    }

    @SuppressLint("SetTextI18s", "SetTextI18n")
    private fun getGeminiResponse(query: String = "", updateUI: Boolean = true, image: Bitmap? = null) {
        if (model == null) return
        if (updateUI) {
            binding.geminiResponseText.text = "Processing..."
            binding.loading.show()
            binding.textView5.text = query
        }
        else binding.geminiResponseText.append("\nProcessing...")

        val content = Content("user", if (image != null) listOf(ImagePart(image)) else listOf(TextPart(query)))
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val response = model!!.startChat(chatHistory).sendMessage(content)
                val responseText = response.text?.removePrefix("```json")?.removeSuffix("```")?.trim() ?: ""

                binding.loading.hide(0)
                pendingFormData = null
                if (binding.rideLayout.isVisible) binding.rideLayout.hide()
                try {
                    if (responseText.startsWith("{") && responseText.endsWith("}")) {
                        val jsonResponse = JSONObject(responseText)
                        val message = if (jsonResponse.has("message")) jsonResponse.getString("message") else "Okay."

                        after(1) {
                            pendingFormData = jsonResponse
                            loadPlatformWebsite(jsonResponse)
                        }

                        binding.geminiResponseText.text = message.parseMarkdown()
                        if (isAutoReadEnabled) speakResponse()
                    }
                    else if (responseText.startsWith("[") && responseText.endsWith("]")) {
                        val jsonArray = JSONArray(responseText)
                        for (i in 0 until jsonArray.length()) {
                            val jsonObject = jsonArray.getJSONObject(i)
                            val message = if (jsonObject.has("message")) jsonObject.getString("message") else "Command $i processed..."

                            after(1) { loadPlatformWebsite(jsonObject) }
                            if (i == 0) binding.geminiResponseText.text = message
                            else binding.geminiResponseText.append("\n$message")
                        }
                        if (isAutoReadEnabled) speakResponse()
                    }
                    else {
                        binding.geminiResponseText.text = responseText.parseMarkdown()
                        if (isAutoReadEnabled) speakResponse()
                    }
                }
                catch (e: Exception) {
                    toast(e.message)
                    binding.geminiResponseText.text = responseText.parseMarkdown()

                    if (binding.rideLayout.isVisible) binding.rideLayout.hide()
                    if (isAutoReadEnabled) speakResponse()
                }
                
                binding.geminiResponseText.show()
                chatHistory.add(response.candidates.firstOrNull()?.content?: Content(role = "model", listOf(TextPart(response.text.toString()))))
                chatHistory.add(content)
            }
            catch (e: Exception) {
                val errorMessage = e.message

                binding.geminiResponseText.text = errorMessage
                binding.geminiResponseText.show()
                binding.loading.hide(0)
            }
        }
    }

    private fun speakResponse() {
        speak(binding.geminiResponseText.text.toString().unEmojify())
    }

    private fun toggleTextInput(show: Boolean) {
        if (show) {
            binding.queryInput.focus()
            binding.textInputLayout.show()
            binding.cardView.hide()
            binding.inputCard.hide()
            binding.lottieAnimationView.hide()
            binding.lottieAnimationView2.hide()
        }
        else {
            binding.queryInput.distract()
            binding.textInputLayout.hide()
            binding.cardView.show()
            binding.inputCard.show()
            binding.lottieAnimationView2.show()
            binding.lottieAnimationView.show()
        }
    }

    private fun setupAutoRead() {
        updateAutoReadUI()
        binding.autoReadCard.onClick {
            isAutoReadEnabled = !isAutoReadEnabled
            updateAutoReadUI()
        }
    }

    private fun updateAutoReadUI() {
        binding.autoReadCard.alpha = if (isAutoReadEnabled) 1f else 0.3f
    }

    private fun setupCaptureContainer() {
        binding.saveButton.onClick { saveImage() }
        binding.shareButton.onClick { shareImage() }
        binding.closeCaptureButton.onClick { hideCaptureContainer() }
    }

    private fun hideCaptureContainer() {
        binding.captureContainer.hide()
        binding.capturedImageView.setImageDrawable(null)
        capturedImageUri = null
    }

    private fun saveBitmapToPictures(bitmap: Bitmap, fileName: String): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        imageUri?.let { uri ->
            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    val success = bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                    android.util.Log.d("MainActivity", "Bitmap compression result: $success")
                }

                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            catch (_: Exception) {
                return null
            }
        }
        return imageUri
    }

    private fun saveImage() {
        capturedImageUri?.let { uri ->
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                
                if (bitmap != null) {
                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val fileName = "Aiboo_Capture_$timeStamp"
                    val savedUri = saveBitmapToPictures(bitmap, fileName)

                    if (savedUri != null) toast("Image saved to Pictures folder")
                    else toast("Failed to save image")
                }
                else toast("Failed to decode image")
            }
            catch (e: Exception) {
                toast("Failed to save image: ${e.message}")
            }
        } ?: run {
            toast("No image to save")
        }
    }

    private fun shareImage() {
        capturedImageUri?.let { uri ->
            try {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                goTo(Intent.createChooser(shareIntent, "Share Image"))
            }
            catch (e: Exception) {
                toast("Failed to share image: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        shutdownSpeaker()
        super.onDestroy()
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val packageName = packageName
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            }
            try {
                goTo(intent)
            }
            catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun getPhotos() {
        lifecycleScope.launch {
            try {
                val images = mutableListOf<Pair<Bitmap, String>>()
                val contentResolver = applicationContext.contentResolver
                val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DISPLAY_NAME
                )
                val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
                val cursor = contentResolver.query(uri, projection, null, null, sortOrder)

                cursor?.use {
                    var count = 0
                    while (it.moveToNext() && count < 15) {
                        val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                        val dateTaken = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))
                        val imageUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                        
                        try {
                            val inputStream = contentResolver.openInputStream(imageUri)
                            val bitmap = BitmapFactory.decodeStream(inputStream)
                            inputStream?.close()
                            
                            if (bitmap != null) {
                                val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                                val timestamp = dateFormat.format(Date(dateTaken))
                                images.add(Pair(bitmap, timestamp))
                                count++
                            }
                        }
                        catch (e: Exception) {
                            toast(e.message)
                        }
                    }
                }

                if (images.isEmpty()) {
                    binding.geminiResponseText.text = "No photos found."
                    return@launch
                }

                getGeminiResponse(updateUI = false, image = images.optimisedMultiPhotos())
            }
            catch (e: Exception) {
                binding.geminiResponseText.text = "Error getting photos: ${e.message}"
            }
        }
    }
}