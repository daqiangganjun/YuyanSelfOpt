# 开发记录

本文件为变更的简要索引，按时间倒序排列。每条记录的完整明细（根因分析、涉及文件、验证方式、可复用知识）见 `change_details.json` 中对应 `id` 的条目。

## 2026-08-21

### `20260821-05` fix：字号调整，版本升至 4.1.0

剪贴板与常用语沿用候选词字号（按候选栏高度换算，为单字短词好点而定），用在整段文本上过大，
另立 `clipboardTextSize` 收至 0.7 倍。按键附带的灰色符号基准由 0.035 收小，并按键高压一道
字号上限、基线不高于「上沿加一个字高」——它字号固定而落点是键高比例，键盘调矮会顶出按键。

- 影响文件：`EnvironmentSingleton.kt`、`ClipBoardAdapter.kt`、`TextKeyboard.kt`

### `20260821-04` fix：上划清除删不尽

原实现只取一次 1000 字符，且删除长度写死为 1000 而非实际取到的长度，光标前更长时一次删不完，
看着就像「有时不生效」。改为分批删到取不出内容为止，每批按实际长度删除并逐批压入回退栈。

- 影响文件：`InputView.kt`

### `20260821-03` fix：修复符号栏刷新时崩溃

`ClassCastException: LinearLayout$LayoutParams cannot be cast to RecyclerView$LayoutParams`。
为撑起「符号设置」那一格的高度，在 footer 已被 RecyclerView 接管后替换了它的 layoutParams 类型，
而 RecyclerView 会将 itemView 的 layoutParams 直接强转。凡走 `onUpdateSelection` 刷新符号栏即崩，
上屏字符、移动光标都会触发。4.0.0 已带此缺陷。

- 影响文件：`SideSymbolBar.kt`

### `20260821-02` feat：文本编辑键盘增加剪切并修正光标与全选

末行由三等分改为四列、加入剪切键。全选原是开关式，状态与实际选区不同步时要按两次，改为每次直接
全选，并优先用 setSelection 而非 performContextMenuAction——后者紧接粘贴触发会让部分应用失焦收起键盘。
行首/行末改发 MOVE_HOME/MOVE_END，既去掉「先全选再移动」的闪烁，也让长文本的视角跟随光标。
方向键在有选区时先折叠选区：上、左归选区首，下、右归选区尾。

- 影响文件：`InputView.kt`、`ImeService.kt`、`KeyboardData.kt`、`KeyPreset.kt`、`KeyboardLoaderUtil.kt`

### `20260821-01` refactor：模糊音独立成页，侧符号栏排布统一

模糊音十余条规则平铺在输入设置里会把其它项淹没，移入独立子页。侧符号栏原按阈值在「均分」与
「内容自适应」间二选一，项数一多就整体翻转、「符号设置」那格随之塌陷；改为始终均分且不低于可点下限。
三处符号栏（九宫格、候选界面、手写）的排布规则抽出共用——手写那处此前一直沿用旧效果。

- 影响文件：新增 `SideSymbolBar.kt`、`FuzzyPinyinFragment.kt`；`AppPrefs.kt`、`InputSettingsFragment.kt`、
  `T9TextContainer.kt`、`CandidatesContainer.kt`、`HandwritingContainer.kt`、`settings_nav.xml`、`strings.xml`

## 2026-08-20

### `20260820-04` feat：模糊音支持，版本升至 4.0.0

Rime 的模糊音须靠 `speller/algebra` 重编译 prism，随包分发的只有编译产物，设备上无法重建。
改在输入串层面处理：按规则改写拼音再查一遍，所得候选与原候选交替排列，模糊串首选落在第二位。
输入设置新增「模糊音」分类，默认开 zh=z、ch=c、sh=s，另有九条内置规则与自定义项。仅全键拼音生效。

- 影响文件：新增 `FuzzyPinYinUtils.kt`；`RimeEngine.kt`、`Kernel.kt`、`DecodingInfo.kt`、
  `InputView.kt`、`AppPrefs.kt`、`ManagedPreferenceUi.kt`、`ManagedPreferenceCategory.kt`、`strings.xml`

### `20260820-03` feat：拼音气泡可点击编辑

点击拼音气泡即把插入点移到点中的字符处，输入的字母插在该处并由引擎重新分词，输入分词符可强制断开。
引擎自身的插入点始终留在串尾，编辑只改按键序列再整串同步过去，候选因而一直覆盖完整编码。
编辑态下气泡放大 1.6 倍、插入点闪动；九键不适用（键上的字母是按键代号而非拼音）。
气泡原先浮在 `contentTopInsets` 之外不接收触摸，须在 `onComputeInsets` 中并入可触摸区域。

- 影响文件：`InputView.kt`、`ImeService.kt`、`RimeEngine.kt`、`KeyRecordStack.kt`、`Kernel.kt`、`DecodingInfo.kt`

### `20260820-02` feat：中文下滤除与编码相同的单字母候选

词库收录了字母条目，中文输入单个字母时它排在候选首位，把汉字挤到后面；要输出该字母回车即可。
过滤会打乱下标，故同时建立展示候选到引擎候选的映射，选词、翻页均按映射换算。

- 影响文件：`RimeEngine.kt`

### `20260820-01` fix：数字键盘侧符号栏留白过大

符号栏按内容高度排布，数字键盘只有两三个符号时符号与「符号设置」之间、以及栏底都空一大片。
改为按项数均分栏高铺满，项数多到均分高度不足以点按时退回自适应并滚动。

- 影响文件：`PrefixAdapter.kt`、`T9TextContainer.kt`、`CandidatesContainer.kt`

## 2026-08-06

### `20260806-04` fix：修复 release 包打开剪贴板崩溃，版本升至 3.0.1

项目有两处反射访问 androidx 私有字段，R8 混淆字段名后失效：`AdapterWrapper` 反射替换
`ViewHolder.itemView`，失败被 catch 吞掉，RecyclerView 随即因 itemView 已有父视图而抛
`IllegalStateException`；`Functions.kt` 反射读 `Preference.mDefaultValue` 则无保护会直接崩。
已补 keep 规则。二者仅在 release 暴露，debug 不混淆故一直正常。

- 提交：yuyansdk `191560c`
- 影响文件：`yuyansdk/proguard.cfg`、`build.gradle`

### `20260806-03` feat：手写改用组合文本并补充读音气泡

手写原先直接上屏、无法撤回，为此维护了两个补丁标志对抗联想覆盖与重复上屏。现改用 Android 标准的
`setComposingText`：识别结果带下划线待定，选定候选即落定并转入联想，退格可整字撤销，两个标志一并删除。
读音改以《现代常用多音字表》为准，避免 pinyin4j 的古音混入（「吃」不再出现 qī）。

- 提交：yuyansdk `4361c96`
- 影响文件：`InputView.kt`、`HandwritingKeyboard.kt`、`HandwritingContainer.kt`、`ImeService.kt`、
  新增 `PolyphoneReading.kt` 与 `assets/pinyindb/polyphone.txt`

### `20260806-02` chore：限制构建并发度为逻辑核心数的 2/3

构建占满 CPU 影响日常使用。`org.gradle.workers.max` 与 Kotlin daemon 的 `ActiveProcessorCount`
须分别设置——后者不受 Gradle worker 数约束，会自行按核心数开线程池。本机 8 核取 5。

- 影响文件：`gradle.properties`

### `20260806-01` refactor：手写识别隔离至 :hw 进程并阻断遥测上传，版本升至 3.0.0

ML Kit 在进程启动时无条件初始化，连带 GMS dynamite、WorkManager 与 Google 遥测常驻输入法主进程，
即便从不使用手写。现将其全部收拢到独立的 `:hw` 进程，切离手写键盘即解绑并终止该进程；同时移除
遥测的两个调度组件，使事件无法上传。主进程 `Other mmap` 与 `Unknown` 合计减少约 42MB。

- 提交：yuyansdk `bb89e97`
- 影响文件：`handwriting/`（新增三文件）、`HandwritingKeyboard.kt`、`KeyboardManager.kt`、
  `HandwritingModelTipView.kt`、`HandwritingSettingsFragment.kt`、两处 `AndroidManifest.xml`、
  `proguard.cfg`、`build.gradle`

## 2026-08-05

### `20260805-04` fix：修复手写自动上屏后点符号出现重复字，版本升至 2.5.2

手写自动上屏后为便于查看而保留了候选，但 `InputView` 中五处「有候选待确认时先上屏首选再输入」的
逻辑并不知道该候选已上过屏，点符号时又上屏一次，同一个字出现两遍。现收敛为统一入口
`commitPendingCandidate()`，已自动上屏时只清空候选。

- 提交：主仓库见本次记录，yuyansdk `4cd41e6`
- 影响文件：`InputView.kt`

### `20260805-03` feat：顶栏加入切换键盘、底栏高度可控与默认值调整，版本升至 2.5.0

上一版调小 `heightForFullDisplayBar` 无效——它只作用于 `minimumHeight`，底栏实际高度由自身内边距
与图标撑开，现直接收紧内边距。顶栏默认加入「切换键盘」并置于最左；因菜单列表为 reverseLayout 且
原先没有排序，一并加上 `ORDER BY position`，库升至版本 7 为老库补齐。键盘高度 0.28、剪贴板默认 400
条上限 1000 条。

底栏高度改为由配置单点控制（此前始终被内容撑开）并再减半；空格键恢复白底；修复模型状态查询的异步
竞态——过期结果会把「下载中」冲掉，致进度圈凭空消失。

- 提交：主仓库见本次记录，yuyansdk `eb18c4e`、`bb00665`
- 影响文件：`FullDisplayKeyboardBar.kt`、`SkbFunDao.kt`、`DataBaseKT.kt`、`AppPrefs.kt`、`InputView.kt`、`TextKeyboard.kt`、`MlKitHandwritingModel.kt`

### `20260805-02` feat：手写模型提示常驻键盘与字号解耦，版本升至 2.4.0

手写模型未下载时改为在手写区域常驻提示，直接提供下载按钮、不确定进度条与失败原因，取代原先的
一次性 Toast；模型状态回调改为多监听以便设置页与键盘并存。字号原以键盘实际高度为基准，导致拉伸
高度连带改变字号，现改用固定默认比例解耦。默认值：不再加粗、键盘高度 0.3→0.27、全面屏底栏减半。

- 提交：主仓库见本次记录，yuyansdk `af76305`
- 影响文件：新增 `HandwritingModelTipView.kt`；改动 `HandwritingContainer.kt`、`HandwritingKeyboard.kt`、`MlKitHandwritingModel.kt`、`EnvironmentSingleton.kt`、`ThemePrefs.kt`、`AppPrefs.kt`

### `20260805-01` fix：修复空格滑动移光标导致失焦，悬浮键盘气泡圆角，版本升至 2.3.4

空格滑动移光标是向应用下发真实的 DPAD_LEFT/RIGHT 按键。光标已在文本首末端时，越界的方向键会被
应用当作焦点导航，焦点跳到相邻控件致输入框失焦（光标消失），若新焦点不接受输入则键盘被收起——
是否发生取决于该应用有无相邻可聚焦控件。现下发前先判断边界，到边界即不再下发。

另：拼音气泡下沿取直是为与贴底键盘齐平，悬浮模式下键盘四角皆圆则显突兀，现按悬浮与否分别处理。

- 提交：主仓库见本次记录，yuyansdk `2d93ee0`、`0bcaecb`
- 影响文件：`InputView.kt`、`ImeService.kt`

## 2026-08-04

### `20260804-05` feat：手写停笔自动上屏与按键字号修正，版本升至 2.3.2

手写原先只在「下次落笔且距上次抬笔超时」才提交上一个字，写完即停手则永不上屏，故时灵时不灵；
改为停笔定时到点即自动上屏首选并保留候选。功能键字号判定由标签长度改为按键码，九键与乱序17 的
字母恢复与全拼一致；主标签恢复垂直居中。拼音气泡收紧并贴边。默认字体 140%、间距 8/15、圆角 22dp。

手写上屏后候选曾被联想词覆盖，已加一次性抑制；拼音气泡改用键盘底色、仅保留上方圆角。

- 提交：主仓库见本次记录，yuyansdk `6c49b46`、`99b01bb`
- 影响文件：`HandwritingKeyboard.kt`、`InputView.kt`、`TextKeyboard.kt`、`ThemePrefs.kt`

### `20260804-04` feat：手写改用 ML Kit 并移除搜狗库，版本升至 2.3.0

手写始终无候选的根因是搜狗商业库的授权：`libhandwriting.so` 内硬编码四个包名
`com.yuyan.pinyin.{offline,online}.{debug,release}`，本分支改名为 `com.yuyan.selfopt` 后
license 校验失败（真机日志确认 init/mode/reset/input 全为 false）。改用 ML Kit 数字墨水识别，
其模型需运行时下载，故在手写设置页提供手动下载与五种状态显示，模型未就绪时键盘给出明确提示。

搜狗那套在本分支包名下完全不可用，且不宜私自占用他人授权，连同四个预编译库与手写词库一并移除，
APK 由 91.8MB 降至 67.4MB。

- 提交：主仓库见本次记录，yuyansdk `a806081`、`9ab6357`
- 影响文件：新增 `MlKitHandwritingModel.kt`/`MlKitHandwritingEngine.kt`；改动 `HandwritingKeyboard.kt`、`HandwritingSettingsFragment.kt`、`Launcher.kt`、`CustomConstant.kt`、`yuyansdk/build.gradle`；删除 `HWEngine.kt`、`core/HandWriting.kt`、四个搜狗 `.so` 与 `assets/hw`

### `20260804-03` feat：拼音浮动气泡与搜狗风格界面，版本升至 2.2.0

拼音串原与候选词按 1:1.9 瓜分候选栏，致候选词被压在下半区且随拼音有无跳动；现移出候选栏改为
键盘上方的浮动气泡，候选词独占整条。附带符号回退为上部居中并全量对齐搜狗映射，亮色主题配色与
功能键上色范围一并对齐。移除数字行功能，角标固定使用数字版表；库升至版本 6 清理残留菜单记录。

- 提交：主仓库见本次记录，yuyansdk `ef6c8ae`
- 影响文件：`InputView.kt`、`CandidatesBar.kt`、`TextKeyboard.kt`、`KeyPreset.kt`、`ThemePreset.kt`、`KeyboardLoaderUtil.kt`、`DataBaseKT.kt` 等 16 个

### `20260804-02` feat：界面调整，版本升至 2.1.0

顶栏因 `CandidatesBar` 为 wrap_content 而内容紧贴上沿、下方却隔着按键的 `keyYMargin`，补等值顶部
内边距使其视觉居中；附带符号统一为右上角半透明角标，功能键字号按 0.75 缩放，主标签改用当前字号度量
居中。悬浮键盘补圆角（`ViewOutlineProvider` 裁剪），并修正纵向拖动 clamp 上界恒等于当前值导致的锁死。

- 提交：主仓库见本次记录，yuyansdk `2a3faf7`
- 影响文件：`CandidatesBar.kt`、`TextKeyboard.kt`、`InputView.kt`、`BaseContainer.kt`

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
