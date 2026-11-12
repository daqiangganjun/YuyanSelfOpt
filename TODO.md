# 待办

## 启用 CI 自动构建（待决定）

现状：`.github/workflows/build.yml` 从未运行。它只在推送 `v*` 标签时触发，与本分支的 `selfopt-v*` 标签不匹配；仓库也未配置签名所需的 Secrets。仓库为公开仓库，GitHub Actions 标准运行器不计费。

待用户决定的事项：是否同意把签名密钥（keystore 的 base64、存储密码、别名、密钥密码）存入 GitHub 仓库 Secrets。密钥加密保存、日志自动打码，但会离开本机。

同意后的实施方案：

1. `build.yml` 改为推送 `selfopt-v*` 标签时触发，构建并签名后把 APK 上传到**草稿 Release**，由用户确认后再公开；发布说明仍单独编写。
2. 用 `gh secret set` 配置 `KEYSTORE_BASE64`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`、`RELEASE_STORE_FILE`，取值只在本机读取后直接上传，不打印、不落盘。
3. 新增推送到 master 时的编译检查（不签名、不发布），可选再跑不依赖设备的测试，及早发现构建或混淆问题。

预期收益：发布时无需本机执行 librime 原生编译和 Release 打包；真机测试与发布说明仍需人工完成。
