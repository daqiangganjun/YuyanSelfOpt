# 语燕SelfOpt

**语燕SelfOpt - 基于 Rime 定制的语燕输入法个人自用优化版**

> 本项目 fork 自 [gurecn/YuyanIme](https://github.com/gurecn/YuyanIme)，仅作个人自用优化，不接受面向公众的支持承诺。
> 原项目的全部设计理念、功能实现与词库方案版权归原作者所有，详见 [LICENSE](./LICENSE)。
> 子模块 `yuyansdk` fork 自 [gurecn/yuyansdk](https://github.com/gurecn/yuyansdk)。

雨燕以其敏捷、优雅的飞行姿态，在雨天依然够飞翔、不畏艰险、勇往直前的飞翔态度，被赋予**灵巧、聪明、伶俐、积极、创新**的寓意。语言是人类交流的基本工具，是最重要的文化载体，输入方式是语言交流和信息传递的重要环节，使语言交流变得更加**高效、便捷**。
语燕输入法秉承这些特点，以"**易用、快速、准确**"为核心理念，追求极致、卓越、流畅的输入体验。在设计上，语燕输入法借鉴主流的谷歌拼音、微信输入法等主流输入法精华，追求整体简洁大方，易于上手。支持多种输入方式，规划包括拼音、手写、语音等，满足不同用户的输入需求。支持丰富的个性化设置选项，用户可以根据自己的喜好进行自定义设置，让输入更加符合个人习惯。

## 安装使用：
可从 [Releases](https://github.com/daqiangganjun/YuyanSelfOpt/releases) 下载预编译的 APK，或参照下方[构建项目](#构建项目)章节自行编译。

请注意：

- 本分支的 `applicationId` 为 `com.yuyan.selfopt`，与原版是两个独立应用，可同时安装；
- 本分支为个人自用版本，按实际需要不定期发布，**不保证发布频率，也不接受面向公众的支持承诺**；
- 手写功能依赖 Google ML Kit 的识别模型，需在设置中联网下载一次（约 20MB），无 Google Play 服务的设备无法使用手写。
- 手写停笔后自动显示可改选的首选字，点击候选、空格或回车确认；识别失败或超时会在候选栏提示，提示不会写入输入框。从书写区落下第一笔后，连续书写期间的后续落笔也会采集为笔画，底部按键、侧边符号与候选工具栏均不会响应点击；达到停笔时间、触发上屏流程后恢复按钮操作。停笔等待时间与手写设置中的毫秒值一致。

如需使用原版语燕输入法（含应用市场上架版本），请前往[上游项目](https://github.com/gurecn/YuyanIme)。

## 与上游的差异：
+ 应用显示名称改为`语燕SelfOpt`，`applicationId`改为`com.yuyan.selfopt`，可与原版共存；
+ 「关于」页新增`上游项目`条目，指向 fork 来源；「源代码」与「吐槽与反馈」指向本仓库；
+ 新增可选的`build_mirror.init.gradle`构建脚本，用于依赖下载较慢时的镜像加速。
+ 设置页统一使用 Material 3 风格的紧凑分组、开关、滑块和弹窗；花漾字提供样式示例与按钮状态反馈，语音面板跟随键盘主题。键盘沿用原有配色，保留 Canvas 缓冲与局部重绘，按压时保留键面底色和轮廓；高度调节原位更新键盘，不增加逐键动画或新的 UI 框架。

## 设计原则：
### 纯输入功能，主打轻快。
喜欢简洁的我看到一个个拼音输入法工具软件逐渐趋向繁杂，软件内各种眼花缭乱的无用功能以及烦人的广告让我无法忍受。
**因此我想要定制出一款简洁、实用、好用的输入法；**
### 最小、必要的权限原则，更安全。
当前主流输入法获取各类非必要权限，无视用户隐私，随意上传、分析用户数据。虽然大数据不会区别对待，但我仍然希望自己的数据只在自己的手机里，不要在我不知情、无意识的情况下，把所有数据上传。
**语燕输入法谨遵循必要、最小化权限，只为输入而存在，纯净、安全、更高效。**
普通键盘输入在本机完成。可选的语音输入使用网络和麦克风权限，仅在用户长按空格触发时录音并发送给所选识别供应商，不保存录音文件；手写功能首次下载模型需要网络，下载后在本机识别。不访问位置、联系人或共享存储。
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
+ 英文输入：智能全键英文输入；中文模式下无匹配的字母串保留完整预览，按回车直接上屏；
+ 终端模式：从键盘「更多」页进入，英文逐键发送，保留中英切换和当前中文方案；顶部提供 ESC、TAB、CTRL、ALT、PGUP、PGDN 和「更多」，方向、Home/End、粘贴复用编辑小键盘。CTRL/ALT/SHIFT 支持单次粘滞和长按锁定，可作用于字母、符号、空格、Tab、回车和方向键，支持 Alt+Enter；切换符号或数字布局保留待用修饰键。终端「更多」直接展开/收起功能键并记忆状态，扩展行依次为 SHIFT、DEL、F(n)、^Z、^C、^L、退出；终端模式的编辑小键盘将删除位置改为回车。F(n)：点击自动进入临时数字键盘，输入1到12会实时更新按钮，再点发送功能键并恢复原键盘；退格修改编号，空编号再点或长按取消。退出后恢复原输入模式。
+ 花漾字处理：在「更多」启用花漾字后，原文处理栏持续显示在候选栏上方，单行高度与工具栏接近，支持点击定位光标和闪烁光标，原文区最多显示三行，更多内容可滚动编辑；样式与完成按钮字号统一。普通输入框实时显示转换后的预输入。「完成」提交当前一段并清空原文，保留模式和处理框焦点以继续输入；空内容时「取消」关闭模式。点击宿主输入框可临时使用原始输入，点击处理框继续转换。Termux 等终端在处理栏中预览，完成后发送。模式和上次样式自动保存，后续翻译可复用转换会话接口。
+ 词库拓展：支持雾凇词库、白霜词库等多种词库拓展，输入体验良好；
+ 符号输入：中文、英文、数学、颜文字、EMOJI表情输入、微信特效表情；
+ 数字输入：数字键盘输入、键盘数字行输入；「输入设置 → 数字输入」提供默认开启的「数字键盘兼容输入」，开启时数字作为文本提交，关闭时沿用数字按键发送；终端修饰键组合仍走按键事件；
+ 语音输入：在「语音识别」设置中启用、授权麦克风并填写自己的 API Key；点击「检查连接」可发送内置测试语音，验证当前密钥、资源和参数并查看识别结果，无需麦克风权限。长按空格立即开始录音，连接建立前的声音先暂存在本机，连接后整段发出，无需等待连接提示；松开输入；上滑进入「松开取消」状态并暂停识别，下滑恢复继续识别，在取消状态下松手才清除本次内容；提前到达的最终结果等待松开，取消后的迟到结果不再上屏。最终结果与剪贴板条目点击共用文本提交路径；结束帧无文本时保留本次最近的非空识别结果。语音面板只覆盖到空格行上方，空格行保持可见；悬浮模式下限制在当前键盘宽度内。当前支持豆包流式识别 2.0/1.0，可调节书面格式、标点、语义顺滑、说话人、方言、繁体、二遍识别、分句和热词等参数，点击参数旁的圆圈叹号可查看用途、默认值、示例及注意事项；高级 JSON 可扩展请求参数。密钥在本机加密保存，不随 APK 分发或备份。
+ 键盘自定义：自定义菜单栏、主题、深色模式、键盘调节、键盘数字行、键盘位置移动；自定义主题支持独立调节背景图片亮度和按键背景蒙版强度（0% 透明、100% 不透明），不改变文字颜色，并保留旧主题原有透明度；图片选框用于确定中心点，原图等比覆盖键盘，键盘增高时尽量展开上下内容；明暗模式状态独立于图片主题配色；工具栏按钮跟随按键背景与蒙版，拼音气泡动态采样背景左上角并保持与按键相同的文字色，必要时修正底色明度以保证可读性；
+ 单手键盘：左、右手模式切换；
+ 按键效果：声音和触感分别由顶部开关控制，关闭时保留强度、置灰并禁用滑条，重新开启恢复原设置；滑条最左端为跟随系统，其余位置启用自定义效果，没有关闭档位；
+ 键盘位置：普通模式的移动手柄和悬浮模式的拖动条支持按像素连续上下移动，松手保存位置；
+ 英文大小写：保留逐字输入的大小写，英文补全和选词不覆盖中途手动输入的大写；连续两次点击 Shift 可锁定大写，再次点击解锁；
+ 悬浮键盘：悬浮键盘模式，键盘拖拽、移动；横屏时位置与高度限制在窗口可用范围内，不会被状态栏区域挤压而裁掉底行；横屏普通键盘铺满屏幕宽度；
+ 花漾字输入：火星文（焱暒妏）、 花藤字（ζั͡花ั͡藤ั͡字ั͡✾）、凌乱字（"҉҉҉凌҉҉҉乱҉҉҉字҉҉҉）、发芽字（发ོ芽ོ字ོ）、雾霾字（҈҈҈҈雾҈҈҈҈霾҈҈҈҈字҈҈҈҈）、禁止查看（禁⃠止⃠查⃠看⃠）、长草字（"҈长҉҉҈草҉҉҈字҉）、起风了（=͟͟͞͞风=͟͟͞͞太=͟͟͞͞大=͟͟͞͞）花漾输入； 
+ 拼音输入扩展：支持繁体、简体，支持中英文混输，支持表情描述输入；全拼沿用引擎原生分词边界，避免将 `v` 的拼写额外拆分；
+ 剪切板：条目左对齐，支持关键词搜索、大小写不敏感筛选、清除条件及清空操作；工具栏删除键左侧为搜索按钮，搜索框与花漾字处理栏样式一致，支持光标定位和清空，点击搜索返回结果，关键词不会输入到当前应用；有搜索条件时列表顶部显示条件和「清除」；终端模式下搜索时保留终端按键行；右下角悬浮回车键可在剪贴板设置中开关（默认开启），锁定剪贴板后可连续粘贴并发送；
+ 常用语：支持自定义常用语、常用语快捷输入、编辑、删除等操作；
+ 底部快捷栏：默认使用系统底栏，可选增加输入法切换、剪贴板、常用语和滑动光标功能；
+ 隐藏输入法图标：支持隐藏输入法图标功能。

从 5.0.2 起，默认使用系统底栏，不主动隐藏系统导航栏；原「全面屏键盘优化」更名为「底部快捷栏」，可按需增加快捷按钮和滑动光标功能。已保存的快捷栏选择在升级时保留，未配置用户使用新的默认值。系统底栏的按钮样式及输入法切换入口取决于系统、导航方式和已启用的输入法数量。

## 开发环境：
> Android SDK: minSdk 23、targetSdk 36、compileSdk 37  
> 工具链与第三方库版本: [gradle/libs.versions.toml](./gradle/libs.versions.toml)  
> JDK: 运行 Gradle 需 JDK 17 及以上；编译统一使用 Java 17 toolchain，本机缺少时由 Gradle 自动下载

## 构建项目：
以下为在 **Windows** 下不依赖 Android Studio、纯命令行完成构建的完整过程。使用 Android Studio 时可直接导入项目。

### 1. 克隆项目并拉取子模块
```sh
git clone git@github.com:daqiangganjun/YuyanSelfOpt.git
cd YuyanSelfOpt
git submodule update --init --recursive
```
若 GitHub 直连失败，可临时经 HTTP 代理拉取（不写入全局配置）：
```sh
git -c http.proxy=http://<代理地址>:<端口> submodule update --init --recursive
```

### 2. 配置 Android SDK 路径
在项目根目录创建 `local.properties`（已被 `.gitignore` 忽略）：
```properties
sdk.dir=C\:\\Users\\<用户名>\\AppData\\Local\\Android\\Sdk
```

**必需的 SDK 组件**：
| 组件 | 说明 |
|---|---|
| Platform android-37.0 | 对应 `compileSdk 37`（OkHttp 5.5 起的要求）；运行时行为仍以 `targetSdk 36` 为准 |
| Build-Tools 36.0.0 | 两个模块显式指定的构建版本，也用于 `apksigner`、`aapt2`、`zipalign` 验证 |
| NDK 28.2.13676358 / CMake 3.31.6 | 编译开源 librime 与本项目 JNI，版本固定在 `yuyansdk/native/dependencies.lock.json` |
| Python 3.12 与 PyYAML | 准备固定版本源码、生成白霜与雾凇方案包 |

首次构建前安装 Python 构建依赖：
```powershell
python -m pip install -r .\yuyansdk\native\requirements.txt
```
Gradle 的 `buildRimeNative` 任务会自动获取锁定版本的源码、校验 Boost 压缩包并编译 `libselfopt_rime.so`。源码缓存位于 `yuyansdk/native/.cache/`，生成的动态库与词库包位于 `yuyansdk/build/generated/rime/`；均不入库。首次构建需要能够访问 GitHub 的 HTTPS 连接，后续构建复用本地缓存，不依赖 `tmp_rime_jni/`。

构建工具链为 AGP 9.5.1（使用内置 Kotlin 支持与新 DSL）、Gradle 9.9.0（wrapper 校验官方 SHA256）、Kotlin 2.4.21 与 KSP 2.3.13，所有版本集中在 `gradle/libs.versions.toml`；仓库统一在 `settings.gradle` 声明。部分 AndroidX 新版本要求 minSdk 24，目录中已注明并停留在仍支持 minSdk 23 的版本。Gradle 会自动安装缺失的 SDK 平台与 Build-Tools，该过程访问 `dl.google.com`；无法访问时可用 SDK Manager 手动安装（需要时附加 `--proxy=http --proxy_host=<地址> --proxy_port=<端口>`）：
```powershell
& '<SDK>/cmdline-tools/latest/bin/sdkmanager.bat' 'platforms;android-37.0' 'build-tools;36.0.0'
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
仓库根目录的 `build_mirror.init.gradle` 是一个可选的 Gradle init script，将 `settings.gradle` 中声明的插件与依赖仓库替换为阿里云镜像，适用于访问 Google Maven、Maven Central 较慢的网络。网络通畅时无需使用。

### 5. 执行构建
```sh
# Debug 版本
./gradlew assembleOfflineDebug

# Release 版本（R8 代码与资源优化，体积更小）
./gradlew assembleOfflineRelease
```
需要镜像加速时在命令末尾附加 `--init-script build_mirror.init.gradle`。Windows 下使用 `gradlew.bat`。

产物路径（打包完成时自动复制并命名，AGP 原始产物仍位于 `app/build/outputs/apk/offline/<构建类型>/`）：
```
app/build/outputs/selfopt/offlineDebug/yuyanSelfOpt_<versionName>_debug.apk
app/build/outputs/selfopt/offlineRelease/yuyanSelfOpt_<versionName>_release.apk
```
Release 的应用级混淆规则位于 `app/src/main/keepRules/app.keep`，yuyansdk 的规则经 `consumerProguardFiles` 传入。
版本号采用三段式语义化版本，唯一来源为根 `build.gradle` 的 `ext` 块（`versionMajor` / `versionMinor` / `versionPatch`），递增规则见 `CLAUDE.md`「版本号规范」。

### 6. 验证与安装
```sh
# 校验签名
<SDK>/build-tools/36.0.0/apksigner.bat verify --print-certs <apk路径>

# 查看包信息与 ABI
<SDK>/build-tools/36.0.0/aapt2.exe dump badging <apk路径>

# 校验 APK 中未压缩原生库的 16 KB ZIP 对齐
<SDK>/build-tools/36.0.0/zipalign.exe -c -P 16 4 <apk路径>

# 安装到设备
adb install -r <apk路径>
```
构建产物仅包含 `arm64-v8a`（见 `app/build.gradle` 的 `abiFilters`），不支持 32 位设备；x86 模拟器需要支持 ARM64 二进制转换。

从 5.0.0 起，APK 使用自行构建的 `libselfopt_rime.so`，不再打包旧 `libyuyanime.so` 及其预编译词库。从 5.3.0 起仅内置白霜，雾凇只保留下载配置，在「词库与备份 → 词库 → 当前词库」中选择并获取后启用；未选择、未下载时不打包或部署雾凇基础词库。全拼、九键和双拼共用相应的用户词库，模糊音通过方案重新部署生效。

「词库与备份」下分别提供「词库」与「云同步」入口，更新与 WebDAV 同步各自设置频率：关闭、每天、每周、每月，默认关闭；按本地自然周期首次打开键盘触发，每周从周一开始，也可手动立即执行。词库仅对当前选中项后台拉取、构建与测试，全部通过后在键盘空闲时启用，成功后的下一次打开显示更新详情；失败恢复上一版，下载中断的任务在后续使用时重试；部署中断先回退并等待手动重试，避免同一失败版本循环部署。更新包可通过 `.github/workflows/dictionaries.yml` 检查上游并发布到 `dictionaries-v1` 词库频道，工作流需推送并发布资产后才生效。默认使用白霜/雾凇官方 GitHub 源规则，手机固定上游提交、校验资源后在独立进程构建，使用临时学习数据测试候选与提交结果；支持手动输入/粘贴 JSON 规则、网络规则及兼容词库包索引。设置页只显示当前更新方式需要的选项；源码拉取时显示按字节计算的文件总进度、完成文件数及当前文件百分比。只更新所选词库，雾凇复用本机公共资源。构建或测试失败保持旧版，同一失败版本等待新版或手动重试。也可使用「导入本地词库包」选择 `native/publish_dictionaries.py` 生成的白霜或雾凇独立 ZIP，离线校验并启用。

WebDAV 支持多设备双向合并、删除传播及冲突处理，默认保留最新，也可保留本机、保留远端或手动选择。在「云同步 → 参与同步的数据」弹窗中选择应用设置、学习记录、常用语、剪贴板、主题与背景、符号与菜单、基础词库、词库更新规则；剪贴板默认关闭，其余默认开启。关闭的类别不读取或写入对应云端数据，也不应用远端修改。各类别使用独立清单，文件按校验值去重，每台设备每类保留最近五份云端变更记录；本机还保留上次同步或导入前的完整恢复快照。未选择的雾凇不会因云同步而自动下载。规则同步独立于词库数据同步，不包含构建缓存和本机进度。同步仅在键盘空闲时读取和应用引擎数据，遇到设备时间明显超前会要求校时或明确选择冲突策略。

「恢复默认词库与预设」停止更新并恢复当前安装包内置白霜、输入方案及默认更新规则，清除编译缓存；引擎初始化失败时也可操作，保留学习记录、主题与其他个人设置。

「导出用户数据」先选择数据类别，默认全选并记住上次选择，独立于云同步范围。可导出学习词条和词频、已安装的基础词库与必要资源、词库更新规则、常规设置、剪贴板、常用语、符号菜单和自定义主题；数据包带有校验清单。导入前显示备份信息及所含类别，分类备份未包含的类别保留本机现有数据。兼容旧格式并将旧数据库和学习词库转换到当前结构；恢复过程保留快照并支持中断恢复。语音密钥、WebDAV 凭据及更新/同步执行频率等本机控制项不加入数据包或云同步；凭据使用 Android Keystore 加密保存。

手动和网络规则使用相同 JSON 格式，可直接复制设置页提供的完整模板。单条规则也可导入；`repository` 为 GitHub 的“所有者/仓库”，`ref` 可填写分支、标签或提交，实际下载前固定到提交 SHA。当前适配器支持白霜/雾凇及沿用其目录结构的派生词库：
```json
{"format":1,"rules":[{"id":"custom_frost","name":"自定义白霜源","family":"frost","repository":"gaboolic/rime-frost","ref":"HEAD","dictionary":"rime_frost","schemaPrefix":"rime_frost_"}]}
```

首次启动及修改模糊音时会在后台准备词库，键盘会显示准备状态，失败后可点击重试。覆盖安装相同包名、相同签名的 APK 会保留原设置；首次升级会备份旧 `pinyin.userdb`、`english.userdb`、`stroke.userdb`，通过 librime 文本导入导出迁移学习记录，再一次性启用新用户目录。旧词库保留在原位置，备份与新用户词库保存在应用内部的 `files/rime-selfopt/`。请使用覆盖安装完成升级，卸载会删除应用数据。

从 5.0.1 起，ML Kit 手写识别升级至 19.0.0，`libdigitalink.so` 与自编译的 `libselfopt_rime.so` 均满足 16 KB ELF 对齐要求；Debug/Release APK 中的原生库采用 16 KB ZIP 对齐，Release AAB 配置为 `PAGE_ALIGNMENT_16K`。手写识别仍隔离在 `:hw` 进程中。相关依赖的许可证随词库包中的 `licenses/` 一同分发。

已在支持 ARM64 转换的 Android 15、16 KB 页面 x86_64 模拟器上通过 13 项集成回归，并验证 Release 包触屏手写识别及候选上屏；尚未在 16 KB 真机上验证。

集成回归使用真实词库、数据库与手写服务。安装 Debug APK 和测试 APK 后，可在设备上执行：
```powershell
adb shell getconf PAGE_SIZE
adb shell am instrument -w -e require16Kb true -e downloadHandwritingModel true com.yuyan.selfopt.offline.debug.test/androidx.test.runner.AndroidJUnitRunner
```
`require16Kb=true` 要求新增回归运行在 16 KB 页面环境；普通 4 KB 设备上应省略此参数。手写模型未下载时，默认跳过识别回归；显式传入 `downloadHandwritingModel=true` 允许首次联网下载，再验证离线识别及手写进程重启后的模型复用。测试 APK 通过 `:app:assembleOfflineDebugAndroidTest` 构建，路径为 `app/build/outputs/apk/androidTest/offline/debug/app-offline-debug-androidTest.apk`。

### 常见构建问题
| 现象 | 原因与处理 |
|---|---|
| 依赖或插件下载失败 | 网络无法访问 Google Maven、Maven Central 或 Gradle 插件门户；可附加镜像 init script |
| 下载 Gradle 发行包时 `Connection reset` | 偶发网络中断；wrapper 已配置重试 3 次，重新执行即可 |
| `Failed to install platforms;android-37.0` 或 build-tools | Gradle 自动安装走 `dl.google.com`，参照步骤 2 手动安装 |
| `Can't connect to SOCKS proxy: Network is unreachable` | Java 的 `-DsocksProxyHost` 与仅监听 IPv6 的代理不兼容，改用镜像方案 |
| `SDK XML versions up to 3 ... version 4 was encountered` | 无害警告，AGP 与 cmdline-tools 版本不一致所致，可忽略 |
| 雷电模拟器中选择语燕无效，`ime set` 报成功但仍为内置输入法 | 已在雷电 14 验证：`adb -s <设备标识> shell settings put secure default_input_method com.yuyan.selfopt.offline.release/com.yuyan.imemodule.service.ImeService`；再查询 `settings get secure default_input_method` 并实际输入验证。Debug 包应将包名末尾改为 `.debug` |

## 键盘预览：

| 普通键盘 | 悬浮键盘 |
| --- | --- |
| ![普通键盘](./images/普通键盘.jpg) | ![悬浮键盘](./images/悬浮键盘.jpg) |

## 鸣谢：
感谢以下优秀的开源社区贡献：
- [语燕输入法](https://github.com/gurecn/YuyanIme) —— 本项目的上游来源
- [RIME](http://rime.im)
- [同文输入法](https://github.com/osfans)
- [小企鹅输入法](https://github.com/fcitx5-android/fcitx5-android)
- [雾凇拼音](https://github.com/iDvel/rime-ice)
- [白霜拼音](https://github.com/gaboolic/rime-frost)
