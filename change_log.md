# 开发记录

本文件为变更的简要索引，按时间倒序排列。每条记录的完整明细（根因分析、涉及文件、验证方式、可复用知识）见 `change_details.json` 中对应 `id` 的条目。

## 2026-08-04

### `20260804-01` fix：修复手写识别空指针闪退，版本升至 2.0.2

`HWEngine` 把 `HandWriting.init` 放在 Kotlin object 的 init 块中且不检查返回值，而该块只执行
一次。首次安装时若词库尚未复制完成，初始化失败后再无重试机会，识别调用未就绪的 native 导致
`getCandidates()` 返回 null 而抬笔即崩。改为惰性初始化并可重试，同时补齐 native 返回值空防护。

- 提交：主仓库见本次记录，yuyansdk `615153b`
- 影响文件：`HWEngine.kt`、`core/HandWriting.kt`

## 2026-08-03

### `20260803-05` perf：全量性能与内存优化，版本升至 2.0.1

按四维度审查清单全量优化：消除按键路径的全表扫描与重复 JNI 编组、键盘改脏区重绘、修复
两条视图树泄漏、补齐会话释放、背景图加解码缓存、精简线程池与启动链路、收紧混淆规则。
清单中 noCompress 与 strip .so 经实测证伪；后续另修正禁用 EmojiCompatInitializer 导致的
输入闪退、默认数据初始化时序、菜单空结果缓存与背景图色深四处。

- 提交：主仓库见本次记录，yuyansdk `e4c1905`/`ee5151b`/`3b011d8`/`0337187`/`5c6267f`/`0395292`
- 影响文件：见 `change_details.json`

### `20260803-04` chore：改用语义化版本，起始 1.0.0

版本号原由构建时间生成（`yyyyMMddHH`），无法表达变更性质。改为三段式语义化版本，唯一来源为根 `build.gradle` 的 `ext` 块，`app` 与 `yuyansdk` 共用，递增规则写入 `CLAUDE.md`。注意 `versionCode` 由约 20 亿降至 10000，覆盖安装会被拒绝，需先卸载旧包。

- 影响文件：`build.gradle`、`app/build.gradle`、`yuyansdk/build.gradle`、`CLAUDE.md`、`README.md`

### `20260803-03` fix：修复输入法导致系统返回键失效

`InputView.processKeyDown` 对 `KEYCODE_BACK` 无条件返回 true，消费了 DOWN 却使基类的 `event.startTracking()` 从未执行；键盘不可见时 UP 又被放行，DOWN/UP 消费状态不一致，应用侧收到孤立 UP 从而无法触发返回。已将返回键交还 `InputMethodService` 基类处理，并修正 `onKeyDown` / `onKeyUp` 中回落到对方基类方法的复制粘贴错误。

- 影响文件：`yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/InputView.kt`、`yuyansdk/src/main/java/com/yuyan/imemodule/service/ImeService.kt`

### `20260803-02` fix：修复输入视图未创建时按键事件导致的崩溃

`ImeService` 的 `mInputView` / `mCandidateView` 为 `lateinit`，仅在 `onCreateInputView` / `onCreateCandidatesView` 中赋值，而 `isSoftKeyboard` 在 `onStartInput` 阶段即被置位。视图创建前到达的按键事件会触发 `UninitializedPropertyAccessException`。已将两者改为可空类型并对全部调用点统一加防护。

- 提交：主仓库 `963468e`，yuyansdk `a34bf34`
- 影响文件：`yuyansdk/src/main/java/com/yuyan/imemodule/service/ImeService.kt`

### `20260803-01` chore：搭建本机离线构建环境，产出首个自签名 debug 包

受限于国内网络与本机仅有 JDK 21，完成子模块拉取、SDK 组件补齐、镜像加速、签名生成与 jvmTarget 对齐，成功打出可安装的 debug APK。

- 涉及：`local.properties`、`keystore/`、`build_mirror.init.gradle`、build-tools 34.0.0 手动安装
- 产物：`app/build/outputs/apk/offline/debug/yuyanSelfOpt_<versionCode>_debug.apk`
