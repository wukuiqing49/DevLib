package com.wkq.dev.feature.app.catalog
import androidx.annotation.StringRes
import com.wkq.dev.feature.app.R
import com.wkq.dev.demo.document.R as DocumentR
import com.wkq.dev.demo.google.R as GoogleR
import com.wkq.dev.demo.image.R as ImageR
import com.wkq.dev.demo.foundation.R as FoundationR
import com.wkq.dev.demo.files.R as FilesR
data class DemoEntry(val module:String, val route:String, val category:String, @StringRes val title:Int, @StringRes val intro:Int, @StringRes val availability:Int)
object DemoCatalog {
 val categories=linkedMapOf("google" to R.string.catalog_google,"foundation" to R.string.catalog_foundation,"files" to R.string.catalog_files,"image" to R.string.catalog_image,"document" to R.string.catalog_document)
 val entries=listOf(
  DemoEntry("core_google","/google/core_google","google",GoogleR.string.demo_google_title,GoogleR.string.demo_google_intro,R.string.catalog_external),
  DemoEntry("core_pdf","/document/core_pdf","document",DocumentR.string.demo_pdf_title,DocumentR.string.demo_pdf_intro,R.string.catalog_local),
  DemoEntry("lib_document","/document/lib_document","document",DocumentR.string.demo_office_title,DocumentR.string.demo_office_intro,R.string.catalog_local),
  DemoEntry("core_image_render","/image/core_image_render","image",ImageR.string.demo_watermark_title,ImageR.string.demo_watermark_intro,R.string.catalog_local),
  DemoEntry("core_qrcode","/image/core_qrcode","image",ImageR.string.demo_qr_title,ImageR.string.demo_qr_intro,R.string.catalog_local),
  DemoEntry("lib_metadata","/image/lib_metadata","image",ImageR.string.demo_metadata_title,ImageR.string.demo_metadata_intro,R.string.catalog_local),
  DemoEntry("core_base","/foundation/core_base","foundation",FoundationR.string.demo_base_title,FoundationR.string.demo_base_intro,R.string.catalog_local),
  DemoEntry("core_utils","/foundation/core_utils","foundation",FoundationR.string.demo_utils_title,FoundationR.string.demo_utils_intro,R.string.catalog_local),
  DemoEntry("lib_xpopup","/foundation/lib_xpopup","foundation",FoundationR.string.demo_popup_title,FoundationR.string.demo_popup_intro,R.string.catalog_local),
  DemoEntry("core_network","/foundation/core_network","foundation",FoundationR.string.demo_network_title,FoundationR.string.demo_network_intro,R.string.catalog_local),
  DemoEntry("core_router_api","/foundation/core_router_api","foundation",FoundationR.string.demo_router_title,FoundationR.string.demo_router_intro,R.string.catalog_local),
  DemoEntry("core_router_annotation","/foundation/core_router_annotation","foundation",FoundationR.string.demo_annotation_title,FoundationR.string.demo_annotation_intro,R.string.catalog_local),
  DemoEntry("core_router_processor","/foundation/core_router_processor","foundation",FoundationR.string.demo_processor_title,FoundationR.string.demo_processor_intro,R.string.catalog_build_only),
  DemoEntry("core_router_gradle_plugin","/foundation/core_router_gradle_plugin","foundation",FoundationR.string.demo_plugin_title,FoundationR.string.demo_plugin_intro,R.string.catalog_build_only),
  DemoEntry("core_archive","/files/core_archive","files",FilesR.string.demo_archive_title,FilesR.string.demo_archive_intro,R.string.catalog_local),
  DemoEntry("lib_upload","/files/lib_upload","files",FilesR.string.demo_upload_title,FilesR.string.demo_upload_intro,R.string.catalog_external),
  DemoEntry("lib_transfer","/files/lib_transfer","files",FilesR.string.demo_transfer_title,FilesR.string.demo_transfer_intro,R.string.catalog_local),
  DemoEntry("lib_drop","/files/lib_drop","files",FilesR.string.demo_drop_title,FilesR.string.demo_drop_intro,R.string.catalog_external),
 )
}
