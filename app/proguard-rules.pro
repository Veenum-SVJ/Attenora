# FocusGuard release rules.
# AGP's default optimize file already handles Compose/Room/Kotlin idioms.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
