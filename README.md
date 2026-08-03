# 语燕SelfOpt

**语燕SelfOpt - 基于 Rime 定制的语燕输入法个人自用优化版**

> 本项目 fork 自 [gurecn/YuyanIme](https://github.com/gurecn/YuyanIme)，仅作个人自用优化，不提供公开分发渠道，不接受面向公众的支持承诺。
> 原项目的全部设计理念、功能实现与词库方案版权归原作者所有，详见 [LICENSE](./LICENSE)。
> 子模块 `yuyansdk` fork 自 [gurecn/yuyansdk](https://github.com/gurecn/yuyansdk)。

雨燕以其敏捷、优雅的飞行姿态，在雨天依然够飞翔、不畏艰险、勇往直前的飞翔态度，被赋予**灵巧、聪明、伶俐、积极、创新**的寓意。语言是人类交流的基本工具，是最重要的文化载体，输入方式是语言交流和信息传递的重要环节，使语言交流变得更加**高效、便捷**。
语燕输入法秉承这些特点，以"**易用、快速、准确**"为核心理念，追求极致、卓越、流畅的输入体验。在设计上，语燕输入法借鉴主流的谷歌拼音、微信输入法等主流输入法精华，追求整体简洁大方，易于上手。支持多种输入方式，规划包括拼音、手写、语音等，满足不同用户的输入需求。支持丰富的个性化设置选项，用户可以根据自己的喜好进行自定义设置，让输入更加符合个人习惯。

## 安装使用：
本分支为个人自用版本，**不提供任何预编译下载渠道**，请参照下方[构建项目](#构建项目)章节自行编译安装。

如需使用原版语燕输入法（含应用市场上架版本），请前往[上游项目](https://github.com/gurecn/YuyanIme)。

## 与上游的差异：
+ 应用显示名称改为`语燕SelfOpt`，`applicationId`改为`com.yuyan.selfopt`，可与原版共存；
+ 「关于」页新增`上游项目`条目，指向 fork 来源；「源代码」与「吐槽与反馈」指向本仓库；
+ 新增`build_mirror.init.gradle`构建脚本，用于国内网络环境下的镜像加速与 JDK 版本适配。

## 设计原则：
### 纯输入功能，主打轻快。
喜欢简洁的我看到一个个拼音输入法工具软件逐渐趋向繁杂，软件内各种眼花缭乱的无用功能以及烦人的广告让我无法忍受。
**因此我想要定制出一款简洁、实用、好用的输入法；**
### 最小、必要的权限原则，更安全。
当前主流输入法获取各类非必要权限，无视用户隐私，随意上传、分析用户数据。虽然大数据不会区别对待，但我仍然希望自己的数据只在自己的手机里，不要在我不知情、无意识的情况下，把所有数据上传。
**语燕输入法谨遵循必要、最小化权限，只为输入而存在，纯净、安全、更高效。**
语燕输入法仅使用系统默认为输入法开启的`剪贴板`（剪贴板功能）、`设备运动与方向`（屏幕方向变更）、`媒体音控制`（按键音效）、`振动`（按键振动）权限，不获取网络、存储、位置、辅助功能等其他权限，完全离线不上传云端，输入数据不采集、不记录，不访问任何个人、终端、位置、存储等信息。
### 基于Rime引擎，但更易上手。
当前开放的输入法引擎中，[Rime引擎](https://github.com/rime/librime)已经趋向完善。然后对于小白用户来说，上手却并不容易：各种输入方案定制及兼容问题，各种键盘的界面效果优化问题。
**因此我想要定制出一款基于Rime引擎的安装即用，哪怕没时间研究也能好用的输入法；**
### 输入模式更完善。
最早接触安卓平台的[同文输入法](https://github.com/osfans)，后面接触[小企鹅输入法](https://github.com/fcitx5-android/fcitx5-android)，均采用Rime方案进行定制，在输入层面已经满足大部分需求。但是小企鹅输入法九宫格键盘不支持，同文输入法候选词选择不便且无法选择拼音组合，使用起来确实需要勇气。
语燕输入法内置多套优秀词库，优化Rime九宫输入方案、乱序输入方案，支持绝大部分输出场景，提升输入效率。
**因此我想定制出一款支持对小白用户来说使用更普及的九宫格，同时结合全键、双拼、手写、语音等多种方案的输入法。**
### 个性化定制更贴心。
手机屏幕越来越大，但是在走路时，一手提东西，一手打字回复消息对我来说是个头疼地问题，选择候选词够不到、选择出错屡屡出现，因此我定制了单手模式、悬浮键盘。
输入数字要么切换到数字键盘，要么长按按键输入，对输入来说都不便捷，因此我定制了键盘数字行。
夜间输入时，屏幕刺眼，因此我定制了深色主题自动切换功能。更多贴心定制项正在进行中。

## 实现功能：
+ 方案内置：全拼（九宫格、全键）、双拼(小鹤、智能ABC、自然码、紫光、微软、搜狗、乱序17)、手写、五笔画；支持简拼、全拼；
+ 英文输入：智能全键英文输入；
+ 词库拓展：支持雾凇词库、白霜词库等多种词库拓展，输入体验良好；
+ 符号输入：中文、英文、数学、颜文字、EMOJI表情输入、微信特效表情；
+ 数字输入：数字键盘输入、键盘数字行输入； 
+ 键盘自定义：自定义菜单栏、主题、深色模式、键盘调节、键盘数字行、键盘位置移动； 
+ 单手键盘：左、右手模式切换；
+ 悬浮键盘：悬浮键盘模式，键盘拖拽、移动；
+ 花漾字输入：火星文（焱暒妏）、 花藤字（ζั͡花ั͡藤ั͡字ั͡✾）、凌乱字（"҉҉҉凌҉҉҉乱҉҉҉字҉҉҉）、发芽字（发ོ芽ོ字ོ）、雾霾字（҈҈҈҈雾҈҈҈҈霾҈҈҈҈字҈҈҈҈）、禁止查看（禁⃠止⃠查⃠看⃠）、长草字（"҈长҉҉҈草҉҉҈字҉）、起风了（=͟͟͞͞风=͟͟͞͞太=͟͟͞͞大=͟͟͞͞）花漾输入； 
+ 拼音输入扩展：支持繁体、简体，支持中英文混输，支持表情描述输入；
+ 剪切板：支持剪切板联想显示、剪切板及清空操作；
+ 常用语：支持自定义常用语、常用语快捷输入、编辑、删除等操作；
+ 全面屏键盘优化：支持全面屏键盘优化导航栏功能；
+ 隐藏输入法图标：支持隐藏输入法图标功能。

## 已知问题：
* 小米手机中键盘菜单点击设置等无反应:  
  由于小米手机中键盘跳转应用界面需借助`后台弹出界面`权限，该权限需用户手动开启：设置-应用管理-语燕SelfOpt-权限管理-开启`后台弹出界面`权限即可。
* 三星手机按键音量调节无效:  
  语燕输入法使用系统`通知`音量作为按键默认音量，但不同手机表现不同。输入法会以手机系统音量设置为前提，当手机静音时，无输入法按键音。当手机未静音时，以`通知`音量大小为基准进行调节。在三星手机中，基于`系统`音量大小进行调解。
* 在输入一半内容时切换横竖屏，较大概率导致横屏模式屏幕触摸无效，仅能点击键盘按键。
  临时方案：切换横竖屏前，确保输入框内容为空。

## 开发环境：
> Android SDK: minSdk 23, [app/build.gradle](./app/build.gradle)  
> 第三方库: [build.gradle](./build.gradle)  
> JDK: 官方推荐 OpenJDK 17；本仓库通过 `build_mirror.init.gradle` 额外兼容 JDK 21

## 构建项目：
以下为在 **Windows + JDK 21 + 国内网络环境** 下，不依赖 Android Studio、纯命令行完成构建的完整过程。若你使用 Android Studio 且网络通畅，可直接沿用上游 README 的常规导入方式。

### 1. 克隆项目并拉取子模块
```sh
git clone git@github.com:daqiangganjun/YuyanSelfOpt.git
cd YuyanSelfOpt
git submodule update --init --recursive
```
若 GitHub 直连失败，可临时经代理拉取（不写入全局配置）：
```sh
git -c http.proxy=socks5://127.0.0.1:10808 -c https.proxy=socks5://127.0.0.1:10808 submodule update --init --recursive
```

### 2. 配置 Android SDK 路径
在项目根目录创建 `local.properties`（已被 `.gitignore` 忽略）：
```properties
sdk.dir=C\:\\Users\\<用户名>\\AppData\\Local\\Android\\Sdk
```

**必需的 SDK 组件**：
| 组件 | 说明 |
|---|---|
| Platform android-36 | 对应 `compileSdk 36` |
| Build-Tools 34.0.0 | AGP 8.3.2 的默认版本，**必需** |
| Build-Tools 36.0.0 | 可选，用于 `apksigner`、`aapt2` 等验证工具 |
| NDK / CMake | **不需要**，`yuyansdk/libs` 下为预编译 `.so` |

Gradle 会尝试自动安装缺失的 Build-Tools 34.0.0，但该过程走 `dl.google.com` 且不遵循 init script 中的镜像配置，国内网络下会长时间挂起直至失败。建议手动安装：
```sh
curl -L -x socks5://127.0.0.1:10808 -o build-tools_r34-windows.zip \
  https://dl.google.com/android/repository/build-tools_r34-windows.zip
# 解压后将其中的 android-14 目录重命名为 34.0.0，放入 <SDK>/build-tools/
```

### 3. 生成签名文件并配置
本项目的 `debug` 与 `release` 两个 buildType 均绑定了 `signingConfigs.release`，因此**即使只构建 debug 版本也必须提供签名文件**，否则构建失败。

在项目根目录新建 `keystore` 文件夹（`*.jks` 与 `keystore.properties` 均已被 `.gitignore` 忽略）：
```sh
keytool -genkeypair -v -keystore keystore/yuyan.jks -storetype PKCS12 \
  -keyalg RSA -keysize 2048 -validity 10000 -alias yuyan \
  -storepass <你的密码> -keypass <你的密码> \
  -dname "CN=YuyanSelfOpt, OU=Dev, O=YuyanSelfOpt, L=Beijing, ST=Beijing, C=CN"
```
随后创建 `keystore/keystore.properties`：
```properties
storeFile=keystore/yuyan.jks
storePassword=<你的密码>
keyAlias=yuyan
keyPassword=<你的密码>
```

### 4. 构建辅助脚本 build_mirror.init.gradle
仓库根目录的 `build_mirror.init.gradle` 是一个 Gradle init script，用于在**不修改项目任何构建脚本**的前提下解决两个本地环境问题：

1. **依赖下载**：将 `mavenCentral()`、`google()`、`gradlePluginPortal()` 全部重定向至阿里云镜像。直连 `dl.google.com` 在国内网络下极易超时，实测无镜像时下载 AGP 依赖失败耗时超过 10 分钟；使用镜像后单个构件响应稳定在 0.3 秒内。
2. **JVM target 对齐**：项目 `compileOptions` 指定 Java 17，而 JDK 21 环境下 Kotlin/KSP 默认取 jvmTarget 21，会触发 `Inconsistent JVM-target compatibility` 构建失败。脚本统一将 Kotlin 与 KSP 任务的 `jvmTarget` 设为 17。

若你使用 JDK 17 且网络通畅，可省略该脚本。

### 5. 执行构建
```sh
# Debug 版本
./gradlew assembleOfflineDebug --init-script build_mirror.init.gradle

# Release 版本（含 R8 混淆与资源压缩，体积更小）
./gradlew assembleOfflineRelease --init-script build_mirror.init.gradle
```
Windows 下使用 `gradlew.bat`。

产物路径：
```
app/build/outputs/apk/offline/debug/yuyanSelfOpt_<versionName>_debug.apk
app/build/outputs/apk/offline/release/yuyanSelfOpt_<versionName>_release.apk
```
版本号采用三段式语义化版本，唯一来源为根 `build.gradle` 的 `ext` 块（`versionMajor` / `versionMinor` / `versionPatch`），递增规则见 `CLAUDE.md`「版本号规范」。

### 6. 验证与安装
```sh
# 校验签名
<SDK>/build-tools/36.0.0/apksigner.bat verify --print-certs <apk路径>

# 查看包信息与 ABI
<SDK>/build-tools/36.0.0/aapt2.exe dump badging <apk路径>

# 安装到设备
adb install -r <apk路径>
```
构建产物仅包含 `arm64-v8a`（见 `app/build.gradle` 的 `abiFilters`），不支持 32 位设备与模拟器 x86 镜像。

### 常见构建问题
| 现象 | 原因与处理 |
|---|---|
| `Could not download gradle-8.3.2.jar` | 未使用镜像 init script，或代理不可达 |
| `Failed to install build-tools;34.0.0` | Gradle 自动安装走 `dl.google.com`，参照步骤 2 手动安装 |
| `Inconsistent JVM-target compatibility (17 / 21)` | 使用了 JDK 21 但未加载 init script |
| `Can't connect to SOCKS proxy: Network is unreachable` | Java 的 `-DsocksProxyHost` 与仅监听 IPv6 的代理不兼容，改用镜像方案 |
| `SDK XML versions up to 3 ... version 4 was encountered` | 无害警告，AGP 与 cmdline-tools 版本不一致所致，可忽略 |
| `AGP 8.3.2 was tested up to compileSdk = 34` | 无害警告，实测 compileSdk 36 可正常构建 |

## 键盘预览：
| 九宫键盘 | 全拼键盘 | 乱序17 |
| - | - | - |
| ![九宫格拼音键盘](./images/t9_pinyin.jpg) | ![全键拼音键盘](./images/qwerty_pinyin.jpg) | ![乱序17拼音](./images/double_lx17.jpg) |

| 双拼键盘 | 笔画键盘 | 手写键盘 |
| - | - | - |
| ![双拼键盘](./images/double_pinyin.jpg) | ![笔画键盘](./images/stroke_pinyin.jpg) | ![手写键盘](./images/writing_pinyin.jpg) |

| 英语键盘 | 数字键盘 | 编辑键盘 |
| - | - | - |
| ![英语键盘](./images/qwerty.jpg) |  ![数字键盘](./images/number.jpg) | ![编辑键盘](./images/textedit.jpg) |

| 剪切板 | 单手键盘 | 悬浮键盘 |
| - | - | - |
| ![剪切板](./images/clipboard.jpg) | ![单手键盘](./images/onehand.jpg) | ![悬浮键盘](./images/float.jpg) |

| 表情键盘 | 微信特效 | 数字行 |
| - | - | - |
| ![表情键盘](./images/emoji.jpg) | ![微信特效](./images/emoji_wechat.jpg) | ![数字行](./images/number_line.jpg) |

| 深色主题 | 设置菜单 | 物理键盘 |
| - | - | - |
| ![深色主题](./images/dark.jpg) | ![设置菜单](./images/setting.jpg) | ![物理键盘](./images/hardware_keyboard.jpg) |

## 鸣谢：
感谢以下优秀的开源社区贡献：
- [语燕输入法](https://github.com/gurecn/YuyanIme) —— 本项目的上游来源
- [RIME](http://rime.im)
- [同文输入法](https://github.com/osfans)
- [小企鹅输入法](https://github.com/fcitx5-android/fcitx5-android)
- [雾凇拼音](https://github.com/iDvel/rime-ice)
- [白霜拼音](https://github.com/gaboolic/rime-frost)
