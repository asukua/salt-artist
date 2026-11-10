# Salt Artist

[![Build](https://github.com/asukua/salt-artist/actions/workflows/build.yml/badge.svg)](https://github.com/asukua/salt-artist/actions/workflows/build.yml)

给 **Salt Player for Windows** 换一套 Apple Music 风格的艺人页。

[下载最新版本](https://github.com/asukua/salt-artist/releases/latest) · [兼容说明](docs/host-compatibility.md) · [设计与技术说明](docs/artist-design.md)

## 功能

- Apple Music 风格艺人页，支持动态头图、取色背景和滚动虚化。
- 浏览本地歌曲和专辑，支持翻页、搜索、播放和随机播放。
- 展示艺人简介与图片，支持多地区匹配和手动修正。
- 艺人列表支持多种视图、搜索排序、收藏、移除与恢复。
- 支持自定义头图、艺术字、艺人名称与歌曲信息。
- 支持批量下载艺人资料并缓存到本地，便于离线查看。

## 预览

[![bbno$ 动态头图](docs/previews/motion-bbnos.gif)](https://github.com/asukua/salt-artist/releases/download/v0.2.4/preview-bbnos.mp4)

[![Marshmello 动态头图](docs/previews/motion-marshmello.gif)](https://github.com/asukua/salt-artist/releases/download/v0.2.4/preview-marshmello.mp4)

[完整视频：bbno$](https://github.com/asukua/salt-artist/releases/download/v0.2.4/preview-bbnos.mp4) · [Marshmello](https://github.com/asukua/salt-artist/releases/download/v0.2.4/preview-marshmello.mp4)

![Adele 艺人页](docs/previews/artist-adele.png)

![AC/DC 艺人页](docs/previews/artist-acdc.png)

![艺人列表](docs/previews/artist-directory.png)

<details>
<summary>更多预览</summary>

![艺人列表视图与排序](docs/previews/artist-directory-options.png)

![艺人资料](docs/previews/artist-details.png)

![手动匹配 Apple Music 艺人](docs/previews/artist-match.png)

![自定义艺术字](docs/previews/custom-artist-logo.png)

![编辑艺人](docs/previews/edit-artist.png)

![初音ミク艺人页](docs/previews/artist-hatsune-miku.png)

![Nirvana 艺人页](docs/previews/artist-nirvana.png)

![Rag'n'Bone Man 艺人页](docs/previews/artist-ragnbone-man.png)

![Sia 艺人页](docs/previews/artist-sia.png)

</details>

## 安装与设置

1. 从 [Releases](https://github.com/asukua/salt-artist/releases/latest) 下载 `.spmod`，在椒盐音乐「模组管理」中导入并启用。
2. 在「Salt Artist → 配置」中调整头图、资料、背景和匹配方式。

支持 SPW 1.19.0 至 1.19.3。具体兼容条件和功能接入方式见[兼容说明](docs/host-compatibility.md)。

## 开发

使用 JDK 25，运行 `./gradlew test testHostRuntime plugin`。构建产物位于 `build/libs/`。更多实现细节见[设计与技术说明](docs/artist-design.md)。

## 致谢

感谢 Salt Player for Windows 和 [Moriafly/spw-workshop-api](https://github.com/Moriafly/spw-workshop-api) 提供插件支持。Salt Artist 是第三方社区项目，与 Salt Player、Apple 无官方关联。

源码采用 [Apache License 2.0](LICENSE)。预览中的第三方素材见[第三方声明](THIRD_PARTY_NOTICES.md)。
