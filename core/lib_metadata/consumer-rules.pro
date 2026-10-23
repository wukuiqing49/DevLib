# Apache Commons Imaging and metadata-extractor are referenced directly by this module.
# Do not keep all classes: host R8 can shrink and obfuscate unused implementation details.
-dontwarn org.apache.commons.imaging.**
-dontwarn com.drew.**

# DirectoryTiffHandler 通过 Class.newInstance() 创建 EXIF/TIFF/MakerNote 目录。
# 仅保留目录类型及无参构造；类名仍可混淆，其余成员仍可裁剪。
-keep,allowobfuscation class com.drew.metadata.** extends com.drew.metadata.Directory {
    public <init>();
}

# Gson-persisted model rules are owned by feature_app, where the persistence occurs.
-keep class com.wkq.util.exif.ImageMetadataInspector$MetadataSummary { *; }
-keepattributes Signature
-keepattributes *Annotation*
