请将控制台下载的 aliyun-emas-services.json 复制到本目录，文件名为：
  aliyun-emas-services.json

不要将该文件提交到公开 Git 仓库（已在 android/.gitignore 中忽略）。
可参考同目录下的 aliyun-emas-services.json.example 结构。

OneSDK 离线包：若 aliyun-emas-services.json 中 "use_maven": true，一般只需 Maven 依赖即可；
若需离线 AAR，可将 OneSDK 目录内 aar 放入 app/libs 并在 build.gradle 中 flatDir 引用（见官方文档）。
