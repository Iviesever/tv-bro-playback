# TV Bro 创维实机播放验证

## 结果

在创维 7S77_G650P 上已实测改善。独立应用 **TV Bro Playback** 已安装，原版 `com.phlox.tvwebbrowser` 和原数据保留。当前正在电视上播放《鬼灭之刃》第01集。

最终签名 Release 连续采样 **675.474 秒**，区间输出 **16195 帧**（播放器累计 16301 帧），**0 视频丢帧、0 再缓冲、0 解码错误、0 音频欠载**。区间媒体进度前进 675.475 秒。分辨率始终 1920×1080，帧率23.976，未转码或降低清晰度。

验收时间：2026-09-26，2026-09-26T17:29:56.890000 至 2026-09-26T17:41:12.364000（电视本地时间，UTC+8）。结论限于本次设备、片源和测试时段。

## 使用

打开 **TV Bro Playback** → 进入动漫集数页面 → 选择页面右上角固定的 **原生播放**。返回键回网页选集并恢复播放进度。播放器也保留了控制条内的原生入口。

该入口目前针对 `cycani.org` / `www.cycani.org` 页面和 `*.cycstream.com` 的 HTTPS MP4。其他媒体格式继续使用网页。原生模式不显示网站弹幕，换集需返回网页。

## 核实的环境与片源

- 原版：TV Bro 2.1.6，versionCode69；从实机 APK 内核资源核实 GeckoView **147.0.4 / build 20260212191108**。
- 系统：Android8.0/API26，约2GB物理内存，系统ABI `armeabi-v7a,armeabi`。最终 APK 只含 `armeabi-v7a`。
- 原片：HEVC Main，8bit yuv420p，1920×1080，24000/1001 fps，视频约2.807Mbps；AAC-LC双声道48kHz。
- 网页与原生均使用 **OMX.MS.HEVC.Decoder**。没有把软件解码误判为硬解，也没有通过降低画质获得结果。
- 系统输出约60Hz。23.976fps源在60Hz输出中的33/50ms交替属于正常呈现节奏。

## 对照数据

| 指标 | 网页全屏、关闭弹幕 | 原生播放 |
|---|---|---|
| 原版初次视频质量计数 | 243.9秒，5848帧，1丢帧，无缓冲事件 | — |
| 独立包网页复测 | 82.46秒媒体进度，1978帧，3丢帧，无缓冲事件 | 最终Release 675.47秒，16195帧，0丢帧 |
| 表面呈现采样 | 末60秒1553次更新；160个>60ms间隔；最长200.44ms | 64.10秒1538次更新；12个>60ms间隔；最长67.83ms |
| 应用全部进程PSS快照 | 约490.0MiB | Release约258.2MiB |
| 应用CPU快照（单核100%） | 合计约140% | Release约13% |
| 输出图层 | RGB浏览器表面，HWC Client合成 | YV12视频表面，HWC Device；UI Skip Client |
| 最终缓冲余量 | — | 7.227～47.156秒 |

SurfaceFlinger统计包含网页重绘，不能把每次表面更新当作一帧视频，也不能把长间隔直接计为视频丢帧。PSS/CPU为短时采样；PSS不等于全部硬件视频内存。网页质量计数在临时调试下采集，最终Release的网页调试已关闭。最终事件日志同时记录状态变化，避免仅依赖五秒快照判断短暂再缓冲。

数据支持改善浏览器呈现与资源开销这条路径；没有测得GPU饱和、MEMC行为或GC导致原微卡的因果证据，不据此下结论。

## 已修复的问题及失败实验

1. **图标读取OOM**：直接视频实验触发原版一次分配281296904字节的StringBuilder扩容。`FaviconExtractor`原来将任意URL按HTML无界读取。已检查响应类型、限制头部/manifest读取为64Ki字符并加网络超时。这个新触发的崩溃不作为原网页轻微卡顿的原因。
2. **独立原生播放路径**：Media3使用原始HTTPS MP4、桌面UA与原页面Referer，使用SurfaceView输出；缓冲目标24MiB、15～45秒。没有root或修改系统WebView、固件、面板设置，没有删除系统应用。
3. **独占解码交接**：首版原生实验约11秒失败。日志显示后台Gecko标签被回收后立即被TV Bro重开，重新申请HEVC资源，紧接着出现CMA分配失败和OMX错误。修复为原生开始前关闭并保存Gecko会话，后台/原生期间不自动重开；返回后恢复会话和播放位置。第二版连续约5分钟0丢帧，并通过暂停、快进、继续播放与返回进度恢复测试。
4. **入口与发布**：独立包名、独立标签、禁用原版自动更新；固定原生入口避免依赖自动隐藏控制条；首次Release升级关闭临时网页调试。原版也已恢复到动漫页，关闭临时调试并停止其进程。

## 构建、测试和源码

- 分支：`fix/skyworth-playback`；基线：`6cb4b7c`；本次验收源码：`60ae85aec0697f53676f124fb87b98aa97f95b88`。
- 原子提交分别覆盖图标读取修复、独立构建配置、原生播放与会话交接、Release调试恢复、固定入口、状态/音频事件记录。
- `:app:assemblePlaybackGeckoIncludedRelease` 实际构建成功；R8开启；`apksigner verify` 的V2签名通过；APK minSdk26、ARMv7、未设置debuggable；已实机安装。
- `FaviconExtractorTest` 在Robolectric API26下：6项通过，1项原有外网集成测试按原设置跳过；JavaScript语法检查、Git diff检查通过。
- Gecko单测任务为NO-SOURCE，不计作测试通过。
- 全量lint报告一个仓库基线已有的 `HistoryActivity.kt:176 / GestureBackNavigation` 错误；未屏蔽检查。它涉及较新Android返回手势，Android8实机返回已验证。
- Windows构建使用官方微软JDK21运行Gradle9.4.1、现有JDK17编译buildSrc。项目JBR下载地址403，构建脚本临时放宽厂商约束，finally恢复原文件；未提交构建环境性修改。

## 交付

- APK：`D:/program/TV/artifacts/tvbro-2.1.6-playback.1-armv7-release.apk`
- 包名：`com.phlox.tvwebbrowser.playback`，版本 `2.1.6-playback.1`。
- APK SHA256：`a3b6d311c0603a5bb492f2b2ccbbb569999a0f9b1158428f7f6fe5132446599d`。
- 签名证书SHA256：`6294550c13c1135ecdd0ed90742391e9c573c05dc8f2b3f232520bbba22e91fb`。
- 本地签名密钥保存在 `scratch/playback-signing/`，不纳入Git或报告；后续更新须继续使用同一密钥。
- 任务与证据：`tasks/20260926-1617-tvbro-playback/`。复现构建使用该目录 `build.py`；采样工具为 `rdp.py`、`monitor.js`、`surface_sample.py`、`analyze-native.py`。

核心证据：`release-final.txt`、`release-final-summary.json`、`release-final-surface-frames.json`、`release-final-memory.txt`、`final-signature.txt`、`final-apk-badging.txt`；对照与失败分别在 `baseline-playback.json`、`candidate-web-playback.json`、`native-failure-logcat.txt`、`direct-logcat.txt`。原始证据含临时媒体URL，仅保留本地，不提交Git。
