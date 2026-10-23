package com.wkq.dev
import com.wkq.base.activity.BaseTitleActivity
import com.wkq.dev.databinding.ActivityMainBinding
class MainActivity:BaseTitleActivity<ActivityMainBinding>(){
 override fun initView(){setPageTitle(getString(com.wkq.dev.feature.app.R.string.catalog_title));setLeftVisible(false)}
 override fun initData()=Unit
}
