package com.wkq.dev.feature.app.catalog
import com.wkq.base.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
data class CatalogFilter(val query:String="",val category:String="",val tier:String="")
class CatalogViewModel:BaseViewModel(){
 private val mutable=MutableStateFlow(CatalogFilter())
 val filter=mutable.asStateFlow()
 fun search(query:String){mutable.value=mutable.value.copy(query=query)}
 fun tier(tier:String){mutable.value=mutable.value.copy(tier=tier,category="")}
 fun category(category:String){mutable.value=mutable.value.copy(category=category)}
}
