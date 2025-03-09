/*
 * Copyright (c) 2022 WallPanel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.wallpanel.app.ui.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import dagger.android.support.DaggerAppCompatActivity
import timber.log.Timber
import xyz.wallpanel.app.AppExceptionHandler
import xyz.wallpanel.app.network.MQTTOptions
import xyz.wallpanel.app.network.WallPanelService
import xyz.wallpanel.app.network.WallPanelService.Companion.BROADCAST_ALERT_MESSAGE
import xyz.wallpanel.app.network.WallPanelService.Companion.BROADCAST_CLEAR_ALERT_MESSAGE
import xyz.wallpanel.app.network.WallPanelService.Companion.BROADCAST_EVENT_SCREENSAVER_CHANGE
import xyz.wallpanel.app.network.WallPanelService.Companion.BROADCAST_SCREEN_BRIGHTNESS_CHANGE
import xyz.wallpanel.app.network.WallPanelService.Companion.BROADCAST_SCREEN_WAKE
import xyz.wallpanel.app.network.WallPanelService.Companion.BROADCAST_SCREEN_WAKE_OFF
import xyz.wallpanel.app.network.WallPanelService.Companion.BROADCAST_SCREEN_WAKE_ON
import xyz.wallpanel.app.network.WallPanelService.Companion.BROADCAST_SERVICE_STARTED
import xyz.wallpanel.app.network.WallPanelService.Companion.BROADCAST_SYSTEM_SHUTDOWN
import xyz.wallpanel.app.network.WallPanelService.Companion.BROADCAST_TOAST_MESSAGE
import xyz.wallpanel.app.persistence.Configuration
import xyz.wallpanel.app.utils.DialogUtils
import xyz.wallpanel.app.utils.ScreenUtils
import java.security.AccessController.getContext
import javax.inject.Inject


abstract class BaseBrowserActivity : DaggerAppCompatActivity() {

    @Inject
    lateinit var dialogUtils: DialogUtils

    @Inject
    lateinit var configuration: Configuration

    @Inject
    lateinit var mqttOptions: MQTTOptions

    @Inject
    lateinit var screenUtils: ScreenUtils

    var mOnScrollChangedListener: ViewTreeObserver.OnScrollChangedListener? = null
    var wallPanelService: Intent? = null
    private var decorView: View? = null
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var userPresent: Boolean = false
    private var keepScreenOn = false
    var displayProgress = true
    var zoomLevel = 1.0f
    var screenSaverActive = false

    // handler for received data from service
    private val mBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (BROADCAST_ACTION_LOAD_URL == intent.action) {
                val url = intent.getStringExtra(BROADCAST_ACTION_LOAD_URL)
                resetInactivityTimer()
                url?.let {
                    loadWebViewUrl(url)
                }
            } else if (BROADCAST_ACTION_JS_EXEC == intent.action) {
                resetInactivityTimer()
                val js = intent.getStringExtra(BROADCAST_ACTION_JS_EXEC)
                js?.let {
                    evaluateJavascript(js)
                }
            } else if (BROADCAST_ACTION_CLEAR_BROWSER_CACHE == intent.action) {
                Timber.d("Clearing browser cache")
                clearCache()
            } else if (BROADCAST_ACTION_RELOAD_PAGE == intent.action) {
                Timber.d("Browser page reloading.")
                resetInactivityTimer()
                reload()
            } else if (BROADCAST_ACTION_FORCE_WEBVIEW_CRASH == intent.action) {
                Timber.d("Crashing WebView.")
                resetInactivityTimer()
                forceWebViewCrash()
            } else if (BROADCAST_ACTION_OPEN_SETTINGS == intent.action) {
                Timber.d("Browser open settings.")
                openSettings()
            } else if (BROADCAST_TOAST_MESSAGE == intent.action && !isFinishing) {
                val message = intent.getStringExtra(BROADCAST_TOAST_MESSAGE)
                resetInactivityTimer()
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            } else if (BROADCAST_ALERT_MESSAGE == intent.action && !isFinishing) {
                val message = intent.getStringExtra(BROADCAST_ALERT_MESSAGE)
                resetInactivityTimer()
                message?.let {
                    dialogUtils.showAlertDialog(this@BaseBrowserActivity, message)
                }
            } else if (BROADCAST_CLEAR_ALERT_MESSAGE == intent.action && !isFinishing) {
                dialogUtils.clearDialogs()
            } else if (BROADCAST_SCREEN_WAKE == intent.action && !isFinishing) {
                resetInactivityTimer()
            } else if (BROADCAST_SCREEN_WAKE_ON == intent.action && !isFinishing) {
                keepScreenOn = true
                resetInactivityTimer()
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else if (BROADCAST_SCREEN_WAKE_OFF == intent.action && !isFinishing) {
                keepScreenOn = false
                resetInactivityTimer()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else if (BROADCAST_SERVICE_STARTED == intent.action && !isFinishing) {
                //firstLoadUrl() // load the url after service started
            } else if (BROADCAST_SYSTEM_SHUTDOWN == intent.action) {
                val metrics = DisplayMetrics()
                window.windowManager.defaultDisplay.getMetrics(metrics)

                // Long Press the power button down
                val proc = Runtime.getRuntime().exec(arrayOf("su", "0", "input", "keyevent", "--longpress", "26"))
                proc.waitFor()

                handler.postDelayed(Runnable {
                    // Tap on the shutdown button on screen...
                    val proc2 = Runtime.getRuntime().exec(arrayOf("su", "0", "input", "tap", "${metrics.widthPixels/2}", "20"))
                    proc2.waitFor()
                    Timber.d("System shutdown")
                }, 2000)
            }

        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        displayProgress = configuration.appShowActivity
        zoomLevel = configuration.testZoomLevel

        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        decorView = window.decorView

        lifecycle.addObserver(dialogUtils)

        onUserInteraction()

        Thread.setDefaultUncaughtExceptionHandler(AppExceptionHandler(this))
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter()
        filter.addAction(BROADCAST_ACTION_LOAD_URL)
        filter.addAction(BROADCAST_ACTION_JS_EXEC)
        filter.addAction(BROADCAST_ACTION_CLEAR_BROWSER_CACHE)
        filter.addAction(BROADCAST_ACTION_RELOAD_PAGE)
        filter.addAction(BROADCAST_ACTION_FORCE_WEBVIEW_CRASH)
        filter.addAction(BROADCAST_ACTION_OPEN_SETTINGS)
        filter.addAction(BROADCAST_SCREEN_BRIGHTNESS_CHANGE)
        filter.addAction(BROADCAST_CLEAR_ALERT_MESSAGE)
        filter.addAction(BROADCAST_ALERT_MESSAGE)
        filter.addAction(BROADCAST_TOAST_MESSAGE)
        filter.addAction(BROADCAST_SCREEN_WAKE)
        filter.addAction(BROADCAST_SCREEN_WAKE_ON)
        filter.addAction(BROADCAST_SCREEN_WAKE_OFF)
        filter.addAction(BROADCAST_SERVICE_STARTED)
        filter.addAction(BROADCAST_SYSTEM_SHUTDOWN)
        val bm = LocalBroadcastManager.getInstance(this)
        bm.registerReceiver(mBroadcastReceiver, filter)
        resetInactivityTimer()
    }

    override fun onPause() {
        super.onPause()
        val bm = LocalBroadcastManager.getInstance(this)
        bm.unregisterReceiver(mBroadcastReceiver)
        userPresent = false
        handler.removeCallbacks(inactivityCallback)

    }

    override fun onStart() {
        super.onStart()
        if (configuration.hardwareAccelerated && Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
        }
        if (configuration.appPreventSleep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            decorView?.keepScreenOn = true
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            decorView?.keepScreenOn = false
        }
        wallPanelService = Intent(this, WallPanelService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(wallPanelService)
        } else {
            startService(wallPanelService)
        }
        resetScreenBrightness(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(inactivityCallback)
        window.clearFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onUserInteraction() {
        onWindowFocusChanged(true)
        Timber.d("onUserInteraction")
        resetInactivityTimer()
    }

    fun setDarkTheme() {
        val nightMode = AppCompatDelegate.getDefaultNightMode()
        if (nightMode == AppCompatDelegate.MODE_NIGHT_NO || nightMode == AppCompatDelegate.MODE_NIGHT_UNSPECIFIED) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    fun setLightTheme() {
        val nightMode = AppCompatDelegate.getDefaultNightMode()
        if (nightMode == AppCompatDelegate.MODE_NIGHT_YES || nightMode == AppCompatDelegate.MODE_NIGHT_UNSPECIFIED) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    private val inactivityCallback = Runnable {
        Timber.d("inactivityCallback")
        dialogUtils.clearDialogs()
        userPresent = false
        showScreenSaver()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val visibility: Int
        if (hasFocus && configuration.fullScreen) {
            visibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            decorView?.systemUiVisibility = visibility
        } else if (hasFocus) {
            visibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_VISIBLE
            decorView?.systemUiVisibility = visibility
        }
    }

    fun pageLoadComplete(url: String) {
        Timber.d("pageLoadComplete currentUrl $url")
        val intent = Intent(WallPanelService.BROADCAST_EVENT_URL_CHANGE)
        intent.putExtra(WallPanelService.BROADCAST_EVENT_URL_CHANGE, url)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
        complete()
    }

    protected fun resetInactivityTimer() {
        if(!userPresent) {
            val intent = Intent(WallPanelService.BROADCAST_EVENT_USER_INTERACTION)
            val bm = LocalBroadcastManager.getInstance(applicationContext)
            bm.sendBroadcast(intent)
        }
        userPresent = true
        hideScreenSaver()
        handler.removeCallbacks(inactivityCallback)
        if(!keepScreenOn) {
            handler.postDelayed(inactivityCallback, configuration.inactivityTime)
        }
    }

    open fun hideScreenSaver() {
        Timber.d("hideScreenSaver")
        if(screenSaverActive) {
            dialogUtils.hideScreenSaverDialog()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            resetScreenBrightness(false)

            screenSaverActive = false

            val intent = Intent(BROADCAST_EVENT_SCREENSAVER_CHANGE)
            intent.putExtra("active", false)
            val bm = LocalBroadcastManager.getInstance(applicationContext)
            bm.sendBroadcast(intent)
        }
    }

    /**
     * Show the screen saver only if the alarm isn't triggered. This shouldn't be an issue
     * with the alarm disabled because the disable time will be longer than this.
     */
    open fun showScreenSaver() {
        if ((configuration.hasClockScreenSaver
                    || configuration.webScreenSaver
                    || configuration.hasScreenSaverWallpaper
                    || configuration.hasDimScreenSaver)
            && !isFinishing
        ) {
            handler.removeCallbacks(inactivityCallback)
            if(!configuration.hasDimScreenSaver) {
                dialogUtils.showScreenSaver(
                    this@BaseBrowserActivity,
                    {
                        resetInactivityTimer()
                    },
                    configuration.webScreenSaver,
                    configuration.webScreenSaverUrl,
                    configuration.hasScreenSaverWallpaper,
                    configuration.hasClockScreenSaver,
                    configuration.imageRotation.toLong(),
                    configuration.appPreventSleep
                )
            }
            resetScreenBrightness(true)
            screenSaverActive = true

            val intent = Intent(BROADCAST_EVENT_SCREENSAVER_CHANGE)
            intent.putExtra("active", true)
            val bm = LocalBroadcastManager.getInstance(applicationContext)
            bm.sendBroadcast(intent)
        }
    }

    open fun resetScreenBrightness(screenSaver: Boolean = false) {
        screenUtils.resetScreenBrightness(screenSaver)
    }

    protected abstract fun configureWebSettings(userAgent: String)
    protected abstract fun loadWebViewUrl(url: String)
    protected abstract fun evaluateJavascript(js: String)
    protected abstract fun clearCache()
    protected abstract fun reload()
    protected abstract fun forceWebViewCrash()
    protected abstract fun complete()
    protected abstract fun openSettings()

    companion object {
        const val BROADCAST_ACTION_LOAD_URL = "BROADCAST_ACTION_LOAD_URL"
        const val BROADCAST_ACTION_JS_EXEC = "BROADCAST_ACTION_JS_EXEC"
        const val BROADCAST_ACTION_CLEAR_BROWSER_CACHE = "BROADCAST_ACTION_CLEAR_BROWSER_CACHE"
        const val BROADCAST_ACTION_RELOAD_PAGE = "BROADCAST_ACTION_RELOAD_PAGE"
        const val BROADCAST_ACTION_FORCE_WEBVIEW_CRASH = "BROADCAST_ACTION_FORCE_WEBVIEW_CRASH"
        const val BROADCAST_ACTION_OPEN_SETTINGS = "BROADCAST_ACTION_OPEN_SETTINGS"
        const val REQUEST_CODE_PERMISSION_AUDIO = 12
        const val REQUEST_CODE_PERMISSION_CAMERA = 13
    }
}