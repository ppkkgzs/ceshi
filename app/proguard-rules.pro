# app 混淆规则
# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keepclassmembers class ** { @androidx.room.* <methods>; }

# 保留 POJO / 数据模型
-keep class com.alltoolbox.**.model.** { *; }
-keep class com.alltoolbox.**.entity.** { *; }

# Commons Compress 反射
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**