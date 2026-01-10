package com.github.i2534.notice.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.github.i2534.notice.R
import com.github.i2534.notice.databinding.ActivityLicensesBinding
import com.google.android.material.card.MaterialCardView

class LicensesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLicensesBinding

    data class License(
        val name: String,
        val author: String,
        val license: String,
        val url: String
    )

    private val licenses = listOf(
        License(
            "AndroidX Libraries",
            "Google",
            "Apache License 2.0",
            "https://developer.android.com/jetpack/androidx"
        ),
        License(
            "Material Components for Android",
            "Google",
            "Apache License 2.0",
            "https://github.com/material-components/material-components-android"
        ),
        License(
            "Eclipse Paho MQTT Client",
            "Eclipse Foundation",
            "Eclipse Public License 2.0",
            "https://github.com/eclipse/paho.mqtt.java"
        ),
        License(
            "Kotlin Coroutines",
            "JetBrains",
            "Apache License 2.0",
            "https://github.com/Kotlin/kotlinx.coroutines"
        ),
        License(
            "Room Persistence Library",
            "Google",
            "Apache License 2.0",
            "https://developer.android.com/jetpack/androidx/releases/room"
        ),
        License(
            "Paging Library",
            "Google",
            "Apache License 2.0",
            "https://developer.android.com/jetpack/androidx/releases/paging"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLicensesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupLicenses()
    }

    private fun setupLicenses() {
        val inflater = LayoutInflater.from(this)

        licenses.forEach { license ->
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = resources.getDimensionPixelSize(R.dimen.card_margin)
                }
                setCardBackgroundColor(getColor(R.color.surface))
                radius = resources.getDimension(R.dimen.card_radius)
                cardElevation = 0f
                isClickable = true
                isFocusable = true
                setOnClickListener { openUrl(license.url) }
            }

            val content = inflater.inflate(R.layout.item_license, card, false)
            content.findViewById<TextView>(R.id.licenseName).text = license.name
            content.findViewById<TextView>(R.id.licenseAuthor).text = license.author
            content.findViewById<TextView>(R.id.licenseType).text = license.license

            card.addView(content)
            binding.licensesContainer.addView(card)
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }
}
