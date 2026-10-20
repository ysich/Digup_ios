# llama.cpp JNI
-keep class com.digup.android.ml.LlamaBridge { *; }
# PDFBox-Android uses reflection for fonts and optional codecs
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.fontbox.**
-dontwarn com.gemalto.jp2.JP2Decoder
