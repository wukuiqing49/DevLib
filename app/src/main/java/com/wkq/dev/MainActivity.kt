package com.wkq.dev

import androidx.activity.enableEdgeToEdge

import com.wkq.base.activity.BaseActivity
import com.wkq.dev.databinding.ActivityMainBinding

class MainActivity : BaseActivity<ActivityMainBinding>() {

    override fun initView() {
        enableEdgeToEdge()
    }

    override fun initData() = Unit
}
