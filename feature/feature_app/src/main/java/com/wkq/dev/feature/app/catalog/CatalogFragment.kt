package com.wkq.dev.feature.app.catalog
import android.view.View
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.wkq.base.adapter.BaseRecyclerViewAdapter
import com.wkq.base.fragment.BaseVMFragment
import com.wkq.dev.feature.app.R
import com.wkq.dev.feature.app.databinding.FragmentCatalogBinding
import com.wkq.dev.feature.app.databinding.ItemDemoEntryBinding
import com.wkq.router.api.Router
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
class CatalogFragment:BaseVMFragment<FragmentCatalogBinding,CatalogViewModel>(){
 override fun initView(){
  val adapter=object:BaseRecyclerViewAdapter<ItemDemoEntryBinding,DemoEntry>(requireContext(),ItemDemoEntryBinding::inflate){
   override fun convert(binding:ItemDemoEntryBinding,item:DemoEntry,position:Int){
    binding.title.setText(item.title);binding.description.setText(item.intro)
    binding.meta.text=getString(R.string.catalog_item_meta,getString(if(item.category=="foundation")R.string.catalog_foundation else R.string.catalog_functional),item.module,getString(item.availability))
    binding.root.setOnClickListener{Router.navigate(requireContext(),item.route)}
   }
  }
  binding.list.layoutManager=LinearLayoutManager(requireContext());binding.list.adapter=adapter
  linkedMapOf("" to R.string.catalog_all,"foundation" to R.string.catalog_foundation,"functional" to R.string.catalog_functional).forEach{(key,label)->
   val chip=Chip(requireContext()).apply{id=View.generateViewId();tag=key;setText(label);isCheckable=true;isChecked=key==viewModel.filter.value.tier;setOnClickListener{viewModel.tier(key)}}
   binding.tiers.addView(chip)
  }
  val categories=linkedMapOf("" to R.string.catalog_all).also{it.putAll(DemoCatalog.categories)}
  categories.forEach{(key,label)->val chip=Chip(requireContext()).apply{id=View.generateViewId();tag=key;setText(label);isCheckable=true;isChecked=key==viewModel.filter.value.category;setOnClickListener{viewModel.category(key)}};binding.categories.addView(chip)}
  binding.search.setText(viewModel.filter.value.query)
  binding.search.doAfterTextChanged{viewModel.search(it.toString())}
  viewLifecycleOwner.lifecycleScope.launch{viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED){viewModel.filter.collect{filter->
   for(index in 0 until binding.categories.childCount){val chip=binding.categories.getChildAt(index) as Chip;chip.isChecked=chip.tag==filter.category}
   val items=DemoCatalog.entries.filter{(filter.tier.isEmpty() || (it.category=="foundation")== (filter.tier=="foundation")) && (filter.category.isEmpty() || it.category==filter.category) && (filter.query.isBlank() || it.module.contains(filter.query,true) || getString(it.title).contains(filter.query,true) || getString(it.intro).contains(filter.query,true))}
   adapter.setData(items);binding.count.text=getString(R.string.catalog_count,items.size);binding.empty.visibility=if(items.isEmpty())View.VISIBLE else View.GONE
  }}}
 }
 override fun initData()=Unit
 override fun onDestroyView(){binding.list.adapter=null;super.onDestroyView()}
}
