package com.example.controlsassist

import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.SeekBar
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import com.example.controlsassist.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPref: SharedPreferences
    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val streamData = mutableMapOf<Int, StreamControl>()
    private val enforcementRunnables = mutableMapOf<Int, Runnable>()
    private lateinit var brightnessControl: BrightnessControl
    private var brightnessRunnable: Runnable? = null

    // Pref keys
    private fun volKey(stream: Int) = "vol_$stream"
    private fun lockKey(stream: Int) = "lock_$stream"
    private val keyBrightnessLocked = "brightness_locked"
    private val keyBrightnessLevel = "brightness_level"

    override fun onCreate(savedInstanceState: Bundle?) {
        sharedPref = getSharedPreferences("ControlPrefs", MODE_PRIVATE)

        // apply saved theme first
        val isDarkMode = sharedPref.getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Request write settings permission if not granted (user must allow manually)
        if (!Settings.System.canWrite(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }

        // Ensure service is running (foreground)
        val svcIntent = Intent(this, ControlService::class.java)
        startForegroundService(svcIntent)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        setupToolbarAndDrawer()
        setupDrawerMenu()

        // Setup volume controls: restore from prefs and set listeners
        setupVolumeControl(AudioManager.STREAM_MUSIC, binding.seekMusic, binding.toggleMusic)
        setupVolumeControl(AudioManager.STREAM_RING, binding.seekRingtone, binding.toggleRingtone)
        setupVolumeControl(AudioManager.STREAM_VOICE_CALL, binding.seekCall, binding.toggleCall)
        setupVolumeControl(AudioManager.STREAM_ALARM, binding.seekAlarm, binding.toggleAlarm)

        // Brightness (lock current system level)
        setupBrightnessControl()
    }

    private fun setupToolbarAndDrawer() {
        binding.menuIcon.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    private fun setupDrawerMenu() {
        binding.navigationView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_permissions -> {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                R.id.nav_toggle_dark_mode -> {
                    toggleTheme()
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleTheme() {
        val isDarkMode = sharedPref.getBoolean("dark_mode", false)
        sharedPref.edit().putBoolean("dark_mode", !isDarkMode).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (!isDarkMode) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    /**
     * Setup a volume control UI that:
     *  - Restores volume and lock state from prefs
     *  - Persists changes to prefs when user adjusts seekbar or toggles
     *  - Starts/stops local enforcement so UI remains consistent while app is foreground
     *
     * ControlService should also read prefs and enforce in background.
     */
    private fun setupVolumeControl(streamType: Int, seekBar: SeekBar, toggle: ToggleButton) {
        val max = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1) // avoid div by zero
        seekBar.max = 100

        // Read stored values (if not present, use current system)
        val storedVol = sharedPref.getInt(volKey(streamType), -1)
        val initialAbsVol = if (storedVol >= 0) storedVol else audioManager.getStreamVolume(streamType)
        val initialPercent = (initialAbsVol * 100) / max
        seekBar.progress = initialPercent

        val isLocked = sharedPref.getBoolean(lockKey(streamType), false)
        toggle.isChecked = isLocked

        // Prepare control data
        val control = StreamControl(streamType, max, targetVolume = initialAbsVol, locked = isLocked)
        streamData[streamType] = control

        // SeekBar listener (user changes)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                var volume = (progress * max) / 100

                // Safety: avoid fully silent ringtone when user didn't intend it
                if (streamType == AudioManager.STREAM_RING && volume == 0) {
                    // clamp to 1 and set ringer mode normal
                    volume = 1
                    try { audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (_: Exception) {}
                }

                try {
                    audioManager.setStreamVolume(streamType, volume, 0)
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }

                // persist absolute volume and update internal control
                control.targetVolume = volume
                sharedPref.edit().putInt(volKey(streamType), volume).apply()

                // update UI to reflect any clamping
                val syncedPercent = (volume * 100) / max
                if (seekBar.progress != syncedPercent) seekBar.progress = syncedPercent
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Toggle listener: persist and (re)start/stop local enforcement
        toggle.setOnCheckedChangeListener { _, checked ->
            control.locked = checked
            // persist lock and make sure a volume value exists
            val curVol = sharedPref.getInt(volKey(streamType), audioManager.getStreamVolume(streamType))
            sharedPref.edit()
                .putBoolean(lockKey(streamType), checked)
                .putInt(volKey(streamType), curVol.coerceIn(0, max))
                .apply()

            if (checked) {
                // start local enforcement for responsive UI
                startLocalEnforceVolume(streamType)
            } else {
                enforcementRunnables[streamType]?.let { handler.removeCallbacks(it) }
            }
        }

        // If initially locked, start enforcement locally
        if (isLocked) startLocalEnforceVolume(streamType)
    }

    private fun startLocalEnforceVolume(streamType: Int) {
        val control = streamData[streamType] ?: return

        // remove any existing runnable before starting a new one
        enforcementRunnables[streamType]?.let { handler.removeCallbacks(it) }

        val runnable = object : Runnable {
            override fun run() {
                try {
                    val currentVolume = audioManager.getStreamVolume(streamType)
                    if (control.locked && currentVolume != control.targetVolume) {
                        audioManager.setStreamVolume(streamType, control.targetVolume, 0)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                if (control.locked) handler.postDelayed(this, 800)
            }
        }
        enforcementRunnables[streamType] = runnable
        handler.post(runnable)
    }

    // Brightness lock: we lock current system brightness level (persisted)
    private fun setupBrightnessControl() {
        // initialize toggle from prefs
        val isLocked = sharedPref.getBoolean(keyBrightnessLocked, false)
        binding.toggleBrightness.isChecked = isLocked

        // ensure brightnessControl is initialized with current system brightness
        val initialBrightness = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Settings.SettingNotFoundException) {
            125
        }
        brightnessControl = BrightnessControl(initialBrightness, isLocked)

        binding.toggleBrightness.setOnCheckedChangeListener { _, checked ->
            brightnessControl.locked = checked
            if (checked) {
                // capture the current system brightness and persist
                val cur = try {
                    Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                } catch (e: Settings.SettingNotFoundException) {
                    125
                }
                brightnessControl.targetBrightness = cur.coerceIn(0, 255)
                sharedPref.edit()
                    .putBoolean(keyBrightnessLocked, true)
                    .putInt(keyBrightnessLevel, brightnessControl.targetBrightness)
                    .apply()
                enforceBrightness()
            } else {
                // disable
                sharedPref.edit().putBoolean(keyBrightnessLocked, false).apply()
                brightnessRunnable?.let { handler.removeCallbacks(it) }
            }
        }

        // If prefs say locked, start enforcement (use saved level if present)
        if (isLocked) {
            brightnessControl.targetBrightness = sharedPref.getInt(keyBrightnessLevel, initialBrightness)
            enforceBrightness()
        }
    }

    private fun enforceBrightness() {
        // remove existing runnable if any
        brightnessRunnable?.let { handler.removeCallbacks(it) }

        brightnessRunnable = object : Runnable {
            override fun run() {
                if (brightnessControl.locked && Settings.System.canWrite(this@MainActivity)) {
                    try {
                        Settings.System.putInt(
                            contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                        )

                        val current = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                        val target = brightnessControl.targetBrightness.coerceIn(0, 255)
                        if (current != target) {
                            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, target)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(brightnessRunnable!!)
    }

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    data class StreamControl(val streamType: Int, val maxVolume: Int, var targetVolume: Int = 0, var locked: Boolean = false)
    data class BrightnessControl(var targetBrightness: Int = 125, var locked: Boolean = false)
}
