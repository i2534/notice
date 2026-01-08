package com.github.i2534.notice.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.github.i2534.notice.BuildConfig
import com.github.i2534.notice.R
import com.github.i2534.notice.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    companion object {
        private const val PROJECT_URL = "https://github.com/i2534/notice"
    }

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        // 版本信息
        binding.versionName.text = getString(R.string.about_version_format, BuildConfig.VERSION_NAME)
        binding.versionCode.text = getString(R.string.about_version_code_format, BuildConfig.VERSION_CODE)

        // 项目地址点击
        binding.projectUrlCard.setOnClickListener {
            openUrl(PROJECT_URL)
        }

        binding.projectUrl.text = PROJECT_URL

        // GitHub 按钮
        binding.btnGithub.setOnClickListener {
            openUrl(PROJECT_URL)
        }

        // 问题反馈
        binding.btnIssues.setOnClickListener {
            openUrl("$PROJECT_URL/issues")
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }
}
