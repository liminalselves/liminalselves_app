# Release 包去掉 Log.d / Log.v 调用（路由上报、WS 连接等高频调试日志），
# 保留 Log.i / Log.w 以便排查推送与下载问题。
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
