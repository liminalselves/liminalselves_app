# Aliyun Push / anet optional peers (not on classpath); R8 otherwise fails release minify.
# See build/app/outputs/mapping/release/missing_rules.txt after a failed build.
-dontwarn com.alibaba.wireless.security.open.SecurityGuardManager
-dontwarn com.alibaba.wireless.security.open.SecurityGuardParamContext
-dontwarn com.alibaba.wireless.security.open.securesignature.ISecureSignatureComponent
-dontwarn org.android.netutil.PingEntry
-dontwarn org.android.netutil.PingResponse
-dontwarn org.android.netutil.PingTask

# Aliyun Push discovers these entry points by reflection.
-keep class com.alibaba.sdk.android.push.** { *; }
-keep class com.alibaba.sdk.android.push.noonesdk.** { *; }
-keep class com.taobao.accs.** { *; }
-keep class anet.channel.** { *; }

# Optional Aliyun ecosystem integrations referenced by ACCS/ANet but not shipped.
-dontwarn com.alibaba.mtl.appmonitor.**
-dontwarn com.alibaba.wireless.security.open.dynamicdatastore.IDynamicDataStoreComponent
-dontwarn com.alibaba.wireless.security.open.staticdataencrypt.IStaticDataEncryptComponent
-dontwarn com.aliyun.ams.emas.push.data.NotificationDataManager
-dontwarn com.taobao.alivfssdk.cache.**
-dontwarn com.taobao.analysis.**
-dontwarn com.taobao.orange.**
-dontwarn com.taobao.tlog.adapter.AdapterForTLog
-dontwarn org.android.netutil.NetUtils
