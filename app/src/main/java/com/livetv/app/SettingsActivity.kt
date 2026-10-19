package com.livetv.app

import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.livetv.app.databinding.ActivitySettingsBinding
import kotlin.math.max

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        b.urlInput.setText(prefs.playlistUrl)
        b.backupsInput.setText(prefs.backupPlaylists.joinToString("\n"))
        b.dimSwitch.isChecked = prefs.dimPhoneInTvMode
        b.dimSwitch.setOnCheckedChangeListener { _, checked -> prefs.dimPhoneInTvMode = checked }

        b.saveButton.setOnClickListener {
            val url = b.urlInput.text?.toString()?.trim().orEmpty()
            if (!isHttpUrl(url)) {
                b.urlLayout.error = getString(R.string.invalid_url)
                return@setOnClickListener
            }
            b.urlLayout.error = null
            val backups = b.backupsInput.text?.toString().orEmpty()
                .lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            backups.firstOrNull { !isHttpUrl(it) }?.let {
                b.backupsLayout.error = getString(R.string.invalid_backup_url, it)
                return@setOnClickListener
            }
            b.backupsLayout.error = null
            prefs.playlistUrl = url
            prefs.backupPlaylists = backups - url
            Toast.makeText(this, R.string.playlist_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
        b.resetButton.setOnClickListener {
            b.urlInput.setText(Prefs.DEFAULT_PLAYLIST_URL)
            b.backupsInput.setText(Prefs.DEFAULT_BACKUP_PLAYLISTS.joinToString("\n"))
            b.urlLayout.error = null
            b.backupsLayout.error = null
        }
        b.exampleCountry.setOnClickListener {
            val cc = java.util.Locale.getDefault().country.lowercase().ifEmpty { "us" }
            b.urlInput.setText("https://iptv-org.github.io/iptv/countries/$cc.m3u")
            b.urlLayout.error = null
        }
    }

    private fun isHttpUrl(url: String) =
        Patterns.WEB_URL.matcher(url).matches() && (url.startsWith("http://") || url.startsWith("https://"))

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
