# Salt Artist

[![Build](https://github.com/asukua/salt-artist/actions/workflows/build.yml/badge.svg)](https://github.com/asukua/salt-artist/actions/workflows/build.yml)

为 **Salt Player for Windows** 打造的第三方沉浸式艺人页插件。以 Apple Music 艺人页为灵感，连接你的本地曲库、播放队列与导航。

[下载最新版本](https://github.com/asukua/salt-artist/releases/latest) · [兼容说明](docs/host-compatibility.md) · [设计与技术说明](docs/artist-design.md)

![AC/DC：沉浸式大图、艺术字与本地曲库](docs/previews/preview-01.png)

<details>
<summary>更多预览</summary>

![AC/DC 中文艺人资料](docs/previews/preview-02.png)

![Daft Punk 更多菜单](docs/previews/preview-03.png)

![Bruno Mars 方图羽化与艺术字](docs/previews/preview-04.png)

![周杰伦头图与三列歌曲](docs/previews/preview-05.png)

![周杰伦编辑艺人](docs/previews/preview-06.png)

![Marshmello 图片与艺术字](docs/previews/preview-07.png)

![插件设置，包含艺人别名识别](docs/previews/preview-08.png)

</details>

## 功能

- ****主要是仿am美化。****
- **沉浸式头图**：动态取色、宽渐隐、方图羽化、滚动模糊与折叠顶栏。
- **本地音乐联动**：单曲、整张专辑和随机播放，当前曲目高亮，保留宿主迷你播放器。
- **紧凑曲库浏览**：最新添加、歌曲搜索与响应式专辑网格。
- **Apple Music 资料**：头图、头像、艺术字与艺人简介；支持中文、英语、日语及其他语言回退，缺少资料时显示曲库统计。
- **艺人列表增强**：Apple Music 圆头像、四种视图、大小调整、排序、收藏优先与可恢复的移除。
- **本地自定义**：显示名、头图、透明艺术字、歌曲显隐与改名、从曲库添加歌曲、专辑改名。

自定义与收藏保存在插件配置中，不修改音乐文件。Apple Music 别名识别只影响素材匹配，不合并艺人条目或歌曲。

## 兼容与安装

当前登记兼容 **SPW 1.19.0 / 1.19.1 / 1.19.2**；艺人列表增强支持 1.19.1 / 1.19.2。其他宿主版本保留原生页面，升级宿主后可能需要更新插件。

1. 从 [Releases](https://github.com/asukua/salt-artist/releases/latest) 下载 `.spmod`；需要校验时使用同一 Release 附带的 `.sha256` 文件。
2. 在 SPW「模组管理」中导入并启用，授予相关权限。
3. 打开艺人详情页。停用或卸载插件后恢复原生页面。

## 配置

在「模组管理 → Salt Artist → 配置」中调整资料语言、其他语言回退、收藏优先、艺人别名识别和动态头图。更多菜单可编辑艺人、换头图或导入透明 PNG/WebP 艺术字；写真目录和缓存清理位于配置页。

目前艺人名要精准匹配才能显示，加了几个部分艺人的别名，如果无法匹配可以自行指定。

0.1.7 可直接从更多菜单打开「匹配 Apple Music 艺人」：输入名字查看搜索与相似候选，也可输入官方艺人链接或 ID，确认目标后绑定当前曲库原名。候选不会自动成为匹配结果；需要时可恢复自动匹配。选择目标会同时开启别名识别，不合并或重命名曲库里的艺人。

需要指定目标时，在 `artist_identity_overrides` 中填写「曲库艺人名=Apple Music 艺人链接或纯 ID」，多项用英文分号或换行分隔：

已经获取的信息会缓存，默认 30 天后按需刷新，也可以在配置里手动清除。

```text
初音未来=307078957;周杰伦=300117743
```

手动绑定需要开启 `recognize_artist_aliases`；关闭后手动和自动别名绑定都会忽略。修改绑定或切换开关会隔离旧匹配缓存，无需手动清除。详细规则见 [身份与别名设计](docs/artist-design.md#apple-music-身份与别名)。

`apple_music_storefronts` 控制 Apple Music 地区重试顺序，默认 `US,JP,CN`，可自定义最多 8 个有效地区代码。默认顺序下 US 请求失败会先尝试 CN、再 JP，并暂避 US；资料语言仍由单独的语言设置决定。同名候选出现不同艺人 ID 时继续拒绝自动选择。

动态头图使用 CPU 解码帧与普通图片绘制，不调用宿主视频 Surface 或共享 GPU 纹理。提供 24／30 帧两档目标帧率，以及 1080p／2K（1440p）／4K（2160p）三档分辨率，默认 30 帧、1080p。0.2.2 改为直接输出 CPU 视频帧，复用转换缓冲并跟随界面刷新，减少逐帧截图造成的停顿。选择不超过档位的最佳 SDR 片源，缺少对应分辨率时回退；2K、4K 可使用 HEVC。实际帧率取决于片源与电脑性能。关闭后显示当前静态封面；切页、失焦或滚出头图区后停止，音乐播放队列不受影响。

首帧显示后后台缓存完整动态片段，再次进入优先直接播放本地视频。缓存按分辨率隔离，最多保留 8 段、合计 512 MiB，单段上限 128 MiB；下载失败继续在线播放，损坏缓存会删除并回退。升级前缓存的封面和视频地址不包含视频文件，首次仍需补充下载。

「滚动时渐进模糊头图」可单独关闭。开启后，静态照片和动态视频随滚动逐渐虚化，艺人名与按钮保持清晰，回到顶部恢复清晰。

艺术家列表的搜索与排序入口位于右上角。点击当前排序名称旁的图标，菜单提供四种视图图标、三档大小，以及名称升降序、最近添加、歌曲数和专辑数排序。收藏优先开关继续生效，选择自动保存；「最近添加」沿用宿主添加时间，缺失时不补造日期。0.2.1 已修复列表挂钩有歧义而回退原生列表、无法显示入口的问题。

0.2.3 返回列表时恢复之前的搜索和滚动位置。圆头像内存缓存增至 512 项，按最近使用保留，可见卡片在缓存淘汰时继续显示已加载的头像。

菜单中的「移除」进入多选，再点「移除」确认；仅在插件视图中隐藏，不删除曲库歌曲或磁盘文件。「恢复」支持单独、多选和全部恢复。「原生界面」直接显示宿主列表，顶部居中的小箭头可返回插件视图；停用插件后恢复完整原生列表。

## 开发

使用 **JDK 25**，将 `JAVA_HOME` 指向 JDK 安装目录，然后运行：

```sh
./gradlew test plugin
```

Windows PowerShell 使用 `.\gradlew.bat test plugin`。产物位于 `build/libs/plugin-dev.saltartist-0.2.4.spmod`。首次构建需要联网获取依赖。

插件使用 Kotlin 与 Compose Multiplatform，运行时由宿主提供。接入、权限与反射约束见 [兼容记录](docs/host-compatibility.md)，身份匹配、缓存与列表 Hook 的取舍见 [设计文档](docs/artist-design.md)。

## 项目说明与致谢

Salt Artist 是第三方社区项目，不是 Salt Player 或 Apple 的官方产品。感谢 Salt Player for Windows 和 [Moriafly/spw-workshop-api](https://github.com/Moriafly/spw-workshop-api) 提供的官方插件 API。

源码采用 [Apache License 2.0](LICENSE)。预览中的第三方图片、音乐封面和商标不随源码许可证授权，详见 [第三方声明](THIRD_PARTY_NOTICES.md)。
