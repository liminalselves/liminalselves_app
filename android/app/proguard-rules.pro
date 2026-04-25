# Aliyun Push / anet optional peers (not on classpath); R8 otherwise fails release minify.
# See build/app/outputs/mapping/release/missing_rules.txt after a failed build.
-dontwarn com.alibaba.wireless.security.open.SecurityGuardManager
-dontwarn com.alibaba.wireless.security.open.SecurityGuardParamContext
-dontwarn com.alibaba.wireless.security.open.securesignature.ISecureSignatureComponent
-dontwarn org.android.netutil.PingEntry
-dontwarn org.android.netutil.PingResponse
-dontwarn org.android.netutil.PingTask
