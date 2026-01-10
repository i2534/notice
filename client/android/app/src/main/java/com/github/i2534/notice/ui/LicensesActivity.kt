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
import org.json.JSONArray

class LicensesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLicensesBinding

    data class License(
        val name: String,
        val author: String,
        val license: String,
        val url: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLicensesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupLicenses()
    }

    private fun loadLicenses(): List<License> {
        return try {
            val json = assets.open("licenses.json").bufferedReader().use { it.readText() }
            val jsonArray = JSONArray(json)
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                License(
                    name = obj.getString("name"),
                    author = obj.getString("author"),
                    license = obj.getString("license"),
                    url = obj.getString("url")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun setupLicenses() {
        val inflater = LayoutInflater.from(this)
        val licenses = loadLicenses()

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
