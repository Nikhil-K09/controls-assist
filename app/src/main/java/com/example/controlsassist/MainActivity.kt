package com.example.controlsassist

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import com.example.controlsassist.databinding.ActivityMainBinding
import com.google.android.material.navigation.NavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPref: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved theme before setting content view
        sharedPref = getSharedPreferences("ControlPrefs", MODE_PRIVATE)
        val isDarkMode = sharedPref.getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )


        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val serviceIntent = Intent(this, ControlService::class.java)
        startForegroundService(serviceIntent)


        setupToolbarAndDrawer()
        setupDrawerMenu()
    }

    private fun setupToolbarAndDrawer() {
        // Set up custom menu icon click
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

                // Add other menu actions here as needed

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

    override fun onBackPressed() {
        // Close drawer if open
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}
