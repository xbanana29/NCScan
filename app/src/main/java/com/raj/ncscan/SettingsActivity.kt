package com.raj.ncscan

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.MaterialAutoCompleteTextView

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val prefs = Prefs(this)

        val useNc = findViewById<SwitchMaterial>(R.id.use_nextcloud)
        val ncFields = findViewById<LinearLayout>(R.id.nc_fields)
        val server = findViewById<EditText>(R.id.server)
        val user = findViewById<EditText>(R.id.username)
        val pw = findViewById<EditText>(R.id.password)
        val folder = findViewById<EditText>(R.id.folder)
        val quality = findViewById<MaterialAutoCompleteTextView>(R.id.quality)
        val gray = findViewById<SwitchMaterial>(R.id.grayscale)
        val selfSigned = findViewById<SwitchMaterial>(R.id.self_signed)

        val qualityItems = resources.getStringArray(R.array.quality_options)
        var qualityIdx = prefs.quality.coerceIn(0, 2)
        quality.setSimpleItems(qualityItems)
        quality.setText(qualityItems[qualityIdx], false)
        quality.setOnItemClickListener { _, _, pos, _ -> qualityIdx = pos }

        useNc.isChecked = prefs.useNextcloud
        ncFields.visibility = if (prefs.useNextcloud) View.VISIBLE else View.GONE
        useNc.setOnCheckedChangeListener { _, on ->
            ncFields.visibility = if (on) View.VISIBLE else View.GONE
        }
        server.setText(prefs.serverUrl)
        user.setText(prefs.username)
        pw.setText(prefs.appPassword)
        folder.setText(prefs.folder)
        gray.isChecked = prefs.grayscale
        selfSigned.isChecked = prefs.trustSelfSigned

        findViewById<Button>(R.id.save).setOnClickListener {
            val url = server.text.toString().trim()
            if (useNc.isChecked) {
                if (url.isNotEmpty() && !url.startsWith("http")) {
                    Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (url.isBlank() || user.text.isBlank() || pw.text.isBlank()) {
                    Toast.makeText(this, R.string.nc_required, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }
            prefs.useNextcloud = useNc.isChecked
            prefs.serverUrl = url
            prefs.username = user.text.toString().trim()
            prefs.appPassword = pw.text.toString()
            prefs.folder = folder.text.toString().trim()
            prefs.quality = qualityIdx
            prefs.grayscale = gray.isChecked
            prefs.trustSelfSigned = selfSigned.isChecked
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
