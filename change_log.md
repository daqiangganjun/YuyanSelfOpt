# 开发记录

本文件为变更的简要索引，按时间倒序排列。每条记录的完整明细（根因分析、涉及文件、验证方式、可复用知识）见 `change_details.json` 中对应 `id` 的条目。

## 2026-08-03

### `20260803-02` fix：修复输入视图未创建时按键事件导致的崩溃

`ImeService` 的 `mInputView` / `mCandidateView` 为 `lateinit`，仅在 `onCreateInputView` / `onCreateCandidatesView` 中赋值，而 `isSoftKeyboard` 在 `onStartInput` 阶段即被置位。视图创建前到达的按键事件会触发 `UninitializedPropertyAccessException`。已将两者改为可空类型并对全部调用点统一加防护。

- 提交：主仓库 `963468e`，yuyansdk `a34bf34`
- 影响文件：`yuyansdk/src/main/java/com/yuyan/imemodule/service/ImeService.kt`

### `20260803-01` chore：搭建本机离线构建环境，产出首个自签名 debug 包

受限于国内网络与本机仅有 JDK 21，完成子模块拉取、SDK 组件补齐、镜像加速、签名生成与 jvmTarget 对齐，成功打出可安装的 debug APK。

- 涉及：`local.properties`、`keystore/`、`build_mirror.init.gradle`、build-tools 34.0.0 手动安装
- 产物：`app/build/outputs/apk/offline/debug/yuyanSelfOpt_<versionCode>_debug.apk`
