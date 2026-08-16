package com.deepseekharness.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/** 首次启动引导：3 页说明一体式安装流程，结束后进入主界面 */
class WelcomeActivity : AppCompatActivity() {

    private val layouts = intArrayOf(
        R.layout.welcome_page1,
        R.layout.welcome_page2,
        R.layout.welcome_page3
    )

    private lateinit var pager: ViewPager2
    private lateinit var nextBtn: Button
    private lateinit var skipBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        pager = findViewById(R.id.welcome_pager)
        nextBtn = findViewById(R.id.welcome_btn)
        skipBtn = findViewById(R.id.welcome_skip)

        // 页面指示器圆点（当前页高亮）
        val dots = findViewById<LinearLayout>(R.id.welcome_dots)
        val dotViews = arrayOfNulls<View>(layouts.size)
        for (i in layouts.indices) {
            val dot = View(this)
            val size = dp(8)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.setMargins(dp(4), 0, dp(4), 0)
            dot.layoutParams = lp
            dotViews[i] = dot
            dots.addView(dot)
        }

        pager.adapter = PagerAdapter()
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                nextBtn.text = if (position == layouts.size - 1) "开始使用" else "下一步"
                for (i in dotViews.indices) {
                    dotViews[i]?.setBackgroundResource(
                        if (i == position) R.drawable.page_dot_active else R.drawable.page_dot
                    )
                }
            }
        })

        nextBtn.setOnClickListener {
            val cur = pager.currentItem
            if (cur < layouts.size - 1) {
                pager.setCurrentItem(cur + 1)
            } else {
                finishWelcome()
            }
        }

        skipBtn.setOnClickListener { finishWelcome() }
    }

    private fun dp(value: Int): Int =
        Math.round(resources.displayMetrics.density * value)

    private fun finishWelcome() {
        getSharedPreferences("deepseekharness", MODE_PRIVATE)
            .edit().putBoolean("welcomed", true).apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private inner class PagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(layouts[viewType], parent, false)
            // 最后一页：关于入口（GitHub / QQ 群）
            if (viewType == layouts.size - 1) {
                val aboutBtn = v.findViewById<Button>(R.id.welcome_about)
                if (aboutBtn != null) {
                    aboutBtn.setOnClickListener { AboutDialog.show(this@WelcomeActivity) }
                }
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}

        override fun getItemCount(): Int = layouts.size

        override fun getItemViewType(position: Int): Int = position
    }
}
