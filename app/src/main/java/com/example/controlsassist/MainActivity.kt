package com.example.controlsassist

import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
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

    private var brightnessTarget = 128
    private var brightnessLocked = false
    private var brightnessRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        sharedPref = getSharedPreferences("ControlPrefs", MODE_PRIVATE)
        val isDarkMode = sharedPref.getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!Settings.System.canWrite(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }

        val serviceIntent = Intent(this, ControlService::class.java)
        startForegroundService(serviceIntent)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        setupToolbarAndDrawer()
        setupDrawerMenu()

        setupVolumeControl(AudioManager.STREAM_MUSIC, binding.seekMusic, binding.toggleMusic)
        setupVolumeControl(AudioManager.STREAM_RING, binding.seekRingtone, binding.toggleRingtone)
        setupVolumeControl(AudioManager.STREAM_ALARM, binding.seekAlarm, binding.toggleAlarm)
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
                R.id.nav_toggle_dark_mode -> {
                    toggleTheme()
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
            if (!isDarkMode)
                AppCompatDelegate.MODE_NIGHT_YES
            else
                AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun setupVolumeControl(streamType: Int, seekBar: SeekBar, toggle: ToggleButton) {
        val max = audioManager.getStreamMaxVolume(streamType)
        seekBar.max = 100
        seekBar.progress = (audioManager.getStreamVolume(streamType) * 100) / max

        val control = StreamControl(streamType, max)
        streamData[streamType] = control

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val safeProgress = if (streamType == AudioManager.STREAM_RING && progress == 0) 1 else progress
                    val volume = (safeProgress * max) / 100
                    audioManager.setStreamVolume(streamType, volume, 0)
                    control.targetVolume = volume
                    seekBar.progress = (volume * 100) / max
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        toggle.setOnCheckedChangeListener { _, isChecked ->
            control.locked = isChecked
            val safeProgress = if (streamType == AudioManager.STREAM_RING && seekBar.progress == 0) 1 else seekBar.progress
            control.targetVolume = (safeProgress * max) / 100

            if (isChecked) {
                enforceVolume(streamType)
            } else {
                enforcementRunnables[streamType]?.let { handler.removeCallbacks(it) }
            }
        }
    }

    private fun enforceVolume(streamType: Int) {
        val control = streamData[streamType] ?: return

        val runnable = object : Runnable {
            override fun run() {
                val currentVolume = audioManager.getStreamVolume(streamType)
                if (control.locked && currentVolume != control.targetVolume) {
                    audioManager.setStreamVolume(streamType, control.targetVolume, 0)
                }
                if (control.locked) {
                    handler.postDelayed(this, 1000)
                }
            }
        }

        enforcementRunnables[streamType] = runnable
        handler.post(runnable)
    }

    private fun setupBrightnessControl() {
        binding.seekBrightness.max = 100

        // Get current brightness
        try {
            val current = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            brightnessTarget = current
            binding.seekBrightness.progress = (current * 100) / 255
        } catch (e: Settings.SettingNotFoundException) {
            e.printStackTrace()
        }

        binding.seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val brightness = (progress * 255) / 100
                    setSystemBrightness(brightness)
                    brightnessTarget = brightness
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.toggleBrightness.setOnCheckedChangeListener { _, isChecked ->
            brightnessLocked = isChecked
            if (isChecked) {
                enforceBrightness()
            } else {
                brightnessRunnable?.let { handler.removeCallbacks(it) }
            }
        }
    }

    private fun setSystemBrightness(value: Int) {
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        val lp = window.attributes
        lp.screenBrightness = value / 255f
        window.attributes = lp
    }

    private fun enforceBrightness() {
        brightnessRunnable = object : Runnable {
            override fun run() {
                try {
                    val current = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                    if (current != brightnessTarget && brightnessLocked) {
                        setSystemBrightness(brightnessTarget)
                    }
                } catch (e: Settings.SettingNotFoundException) {
                    e.printStackTrace()
                }

                if (brightnessLocked) {
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

    data class StreamControl(
        val streamType: Int,
        val maxVolume: Int,
        var targetVolume: Int = 0,
        var locked: Boolean = false
    )
}
