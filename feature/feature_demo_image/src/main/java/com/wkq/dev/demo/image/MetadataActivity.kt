package com.wkq.dev.demo.image
import android.content.Context
import kotlinx.coroutines.flow.collect
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.image.databinding.DemoMetadataBinding
import com.wkq.router.annotation.Route
import com.wkq.util.exif.ImageMetadataInspector
import android.media.ExifInterface
@Route(path = "/image/lib_metadata")
class MetadataActivity : DemoActivity<DemoMetadataBinding, MetadataViewModel>() {
    override fun initView() {
        bindDemo(contentBinding.result, getString(R.string.demo_metadata_title), contentBinding.choose, contentBinding.sample, contentBinding.inspect, contentBinding.write)
        contentBinding.choose.setOnClickListener { pickImages() }
contentBinding.sample.setOnClickListener { viewModel.sample(applicationContext) }
contentBinding.inspect.setOnClickListener { viewModel.inspect(applicationContext) }
contentBinding.write.setOnClickListener { viewModel.write(applicationContext,contentBinding.artist.text.toString(),contentBinding.description.text.toString()) }

    }

}
class MetadataViewModel : DemoViewModel() {
    fun inspect(context:Context)=execute(context){ val data=ImageMetadataInspector.inspect(input()).getOrThrow(); val exif=ExifInterface(input().path); DemoResult("EXIF=${data.exifCount} · IPTC=${data.iptcCount} · XMP=${data.xmpCount} · Other=${data.otherCount}\nArtist=${exif.getAttribute(ExifInterface.TAG_ARTIST).orEmpty()}\n${exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION).orEmpty()}") }
fun write(context:Context,artist:String,description:String)=execute(context){
val output=DemoFiles.output(context,input().extension); input().copyTo(output,true)
ExifInterface(output.path).apply { setAttribute(ExifInterface.TAG_ARTIST,artist);setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION,description);saveAttributes() }
check(ExifInterface(output.path).getAttribute(ExifInterface.TAG_ARTIST).orEmpty()==artist)
val data=ImageMetadataInspector.inspect(output).getOrThrow(); DemoResult("EXIF=${data.exifCount} · IPTC=${data.iptcCount} · XMP=${data.xmpCount}",output,output)
}
}
