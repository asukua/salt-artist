# 宿主兼容与复核

## 兼容策略

`HookTargets.forHost()` 按版本选择已核实目标，支持 1.19.0、1.19.1、1.19.2 及其 `-` 后缀；未知版本不注册 Hook，保留原生页。当前字节码证据来自 Steam 版，同版本其他渠道仍需核查。

混淆名会随升级变化，旧类也可能仍然存在。1.19.1 中仍可找到 1.19.0 的旧艺人页和 ViewModel 名称，但它们已不对应艺人页，因此不使用“兼容整个 1.19.x”或“类存在即兼容”的判断。

| 目标 | 1.19.0 | 1.19.1 / 1.19.2 |
| --- | --- | --- |
| 艺人页 | `androidx.compose.ui.ķ` / U+0137 | `androidx.compose.ui.θ` / U+03B8 |
| ViewModel | `androidx.compose.ui.Ź` / U+0179 | `androidx.compose.ui.Ⴉ` / U+10A9 |
| 头像 | `androidx.compose.ui.ș` / U+0219 | `androidx.compose.ui.ࡃ` / U+0843 |
| 方法名 | `Ϳ` / U+037F | `Ϳ` / U+037F |

导航调用者为 `com.xuncorp.voxzen.ui.navigation.ComposableSingletons$AppNavigation_desktopKt`。艺人页 JVM 描述符为 `(ScreenRoute$ArtistContent, 对应版ViewModel, Composer, int, int) -> void`；Workshop 参数列表只传前两个业务参数。头像描述符为 `(Artist, Modifier, Composer, int, int) -> void`。

## 界面与播放接入

主页面采用 `afterComposable` 整页覆盖，位于原生艺人内容之上、宿主迷你播放器之下。相同调用点的 `replaceComposable` 曾注册成功却从不组合插件内容；`afterComposable` 已有实际渲染证据，所以保留覆盖方案。更换方案前必须重新确认组合行为，不能仅看注册结果。

艺人页的原生圆头像只在该页作用域内隐藏，以避免入场动画时透出；主覆盖注册失败时关闭图片资源并返回，不单独隐藏原生头像。头像 Hook 失败仅降级，`stop()` 清理已注册句柄与后台资源。艺人列表头像与排序是独立接入，见 [列表扩展设计](artist-design.md#艺人总列表扩展)。

注册前不能用 `Class.forName` 预加载转换目标，`initialize=false` 也会加载类，可能错过首次转换。播放与导航反射维持延迟执行。dev23 的 `HookRegistrar` 无匹配次数或签名验证 API，`HookHandle` 只能证明注册成功。

播放使用 `PlaybackController.setPlaybackQueue`。专辑点击直接播放整张：手工以名称构造 `AlbumContent` 曾导航成功却只显示空白页，需取得可靠的宿主 Album 实体来源并验证后，才能恢复专辑导航。

## 权限与打包

编译期 Workshop Gradle 插件与 API 为 dev22，宿主 1.19.1 / 1.19.2 内 API 为 dev23；编译通过不能替代宿主联动验证。Kotlin、Compose 与 Workshop API 使用 `compileOnly`，由宿主提供运行时。

`.spmod` 必须在**插件根目录**的 `META-INF/MANIFEST.MF` 声明 `Plugin-Permissions: class-transform,library-read`。`classes/META-INF/` 不能替代根描述符。目录插件若用 `plugin.properties`，宿主 properties 描述符会丢弃自定义权限字段；授权仍由模组管理控制。

打包时将 Manifest 同时放入包根与 `classes/`，保持版本、权限一致，不打入 Kotlin、Compose、JUnit 或测试类。

## 1.19.1 字节码证据

- 配置 `app/Salt Player for Windows.cfg`：`-Djpackage.app-version=1.19.1`。
- classpath 归档为 `app/ffmpeg-x64.dll`（ZIP/JAR），110,981,532 字节，SHA256：`16100f0f90534b748366fa0974ac3eebb3c87de8e8cf29eb2685a96479e9becf`。
- 导航类 `lambda_778954608$lambda$0` 的位置 103 有唯一艺人页调用：`invokestatic #399` → `θ.Ϳ(ArtistContent, Ⴉ, Composer, int, int)`。
- 艺人页有一条头像调用（常量池 #560）；艺人列表类 `Ⴅ` 也调用同一头像（#149，位置 694），页内隐藏必须限定 `inClass = θ`。
- `PlaybackController.INSTANCE`、`setPlaybackQueue(List, int)` 和单项重载存在。
- `PiscesMediaItem` 的六个 String 构造参数、`AppNavigationKt.getLocalNavigator()`、`Navigator.back()` / `navigate(ScreenRoute)`、`ArtistContent(String)` / `getName()`、`AlbumContent(String, String)` 均存在。

## 1.19.2 字节码证据

- 配置 `app/Salt Player for Windows.cfg`：`-Djpackage.app-version=1.19.2`。
- classpath 归档 `app/ffmpeg-x64.dll` 为 110,975,334 字节，SHA256：`e616a7b0b297a5843695b4bfb5da9c1cc859a71682cc23674963a7b27b9032c1`。
- 复核脚本提取的导航调用者、艺人页、ViewModel、头像、列表项、播放控制器、媒体项与导航路由等 12 个 class 均与 1.19.1 字节完全一致，原有唯一调用点和播放、导航签名保持不变。
- 独立核对目录 screen `З` / U+0417、item `Ⴅ` / U+10A5 及其 renderer、ViewModel、状态、分组与 Compose grid/builder/interval/lambda，共 14 个 class 也与 1.19.1 字节完全一致。继续使用既有列表接入，不改排序或头像布局算法。
- 另核对实际使用的 14 个 Workshop API 类，其公开成员名称、描述符与静态标记均保持编译依赖 ABI。基础复核脚本不覆盖这些 API、目录内部结构或运行时回调派发，不能把脚本通过视为全功能运行验收。
- 原始曲库 Library 实现的唯一 `Function1` 访问器、Continuation 查询及 `Track.getId()` / `getAddedTime()` 保持不变，相关 8 个 class 与 1.19.1 字节一致。查询服务 `androidx.compose.ui.ۋ` 整类有变化，实际使用的查询方法仍直接委托同一 DAO；不据此认定整个服务未变。
- 0.1.6 显式登记 1.19.2，保留旧版映射；该结果不扩展到尚未核实的 1.19.3 或其他版本。

复核脚本只读取归档与 class，并通过 `javap` 反汇编，不加载目标进宿主 JVM。设置 `JAVA_HOME` 为 JDK 25，将路径改为自己的宿主归档：

```powershell
$env:SPW_JAR = "C:\path\to\Salt Player for Windows\app\ffmpeg-x64.dll"
python scripts/verify-host.py --jar $env:SPW_JAR --java-home $env:JAVA_HOME --output build/reports/host-compatibility/1.19.2
```

输出包含 `evidence.json`、提取的 class 与反汇编；契约不满足时返回非零。新增版本仍需人工对照 `HookTargets` 并做宿主运行检查。提取的宿主 class 不随插件分发。

## 验证范围

0.1.6 已通过 81 项自动测试及 `test plugin` 构建，覆盖逐版兼容映射、身份、语言缓存、配置、添加时间查询与真实 Compose interval builder。安装包根目录与 `classes/` 的 Manifest 已核对版本和权限一致。旧版 0.1.1/0.1.2 曾在 Steam 1.19.1 验证页面渲染、菜单、滚动、播放、返回及停用恢复；本轮未操作宿主界面，1.19.2 的渲染与交互仍由用户手动验收。

列表增强依赖 Compose 内部契约，具体证据与守卫见 [设计记录](artist-design.md)。窗口缩放、深浅色、快速切艺人、缺图、大量曲目及不同宿主渠道仍需运行验证。Release 包与校验文件以 [Releases](https://github.com/asukua/salt-artist/releases) 为准。

## 历史渲染修正

以下约束保留为后续维护依据：

- 下缘取色曾因正亮度偏移与逐通道截顶变成灰色，现使用统一亮度缩放保留色相；次要文字提高到 0.70 不透明度。
- `fillMaxSize().width(96.dp)` 会被前者约束为整页宽，左缘遮罩必须限制真实宽度并放在照片层，避免压暗文字。
- 顶栏折叠参数需要 0～1 进度，不能直接传滚动像素。
- 模糊层也必须在底缘淡出，否则会覆盖清晰层的淡出并重新产生硬边。
