# TV Bro 通用全屏播放实机验收（2026-09-30）

## 实际交付

独立 **TV Bro Playback 2.1.6-playback.2 / 70** 已签名安装。沿用网站原有的播放/全屏操作自动进入电视播放器，没有新增网页按钮，没有番站域名白名单。浏览器设置可关闭自动优化；原版 TV Bro 2.1.6/69 和数据保留。

正式包支持 Android8/API26，APK仅含ARMv7。原版更新时间仍为2026-09-26 14:31:46。没有root、修改系统WebView/固件/面板或删除系统应用。最终调试socket关闭，APK没有DEBUGGABLE标志。

## 同片源持续播放

创维7S77_G650P，Android8，1938768KiB物理内存，系统ABI armeabi-v7a,armeabi。《鬼灭之刃》第03集，1920×1080，HEVC，约23.976fps，硬件解码器OMX.MS.HEVC.Decoder。播放器保留原始媒体，未转码或降低该片清晰度；继承网站音量70%。

最终播放器自然结束。有效样本时间 **2026-09-30T18:00:58.104000 至 2026-09-30T18:24:34.095000**：

| 指标 | 最终正式包 |
|---|---:|
| 有效采样墙钟 | 1415.991秒 |
| 媒体进度前进 | 1415.995秒 |
| 区间输出帧 | 33949 |
| 视频丢帧 | 0 |
| 再缓冲 | 0 |
| 解码/播放器错误 | 0 |
| 音频欠载 | 0 |
| 分辨率 | 始终1920×1080 |
| 启动后新增跳过帧 | 0 |

启动时跳过44帧对应进入原生播放器前网页已经播放的媒体区间，后续计数不增长；不把该项隐去。启动加载约7.349秒，与持续播放中的再缓冲分开统计。

采样中应用及其子进程总PSS约339.2–460.8MiB，系统MemAvailable约406.8–558.9MiB；CPU原始采样保存在本地release-final-resources.json。不能由这些数据宣称某次卡顿是GPU、MEMC或GC导致。

## 验证中实际修复的问题

第一轮正式包同一集虽然0视频丢帧，仍有3次再缓冲，共14.970秒，及1次音频欠载，所以该轮未通过。缓冲低水位由15秒提高到30秒、目标由45秒提高到60秒，allocator仍以24MiB为目标；最终全片复测才通过。

长时间暂停后，Gecko的MediaSession控制器失活，原先SDK返回定位无法执行。应用浏览器内部的媒体actor现在按文档URI和媒体来源匹配，用正常媒体seek/play/pause恢复原视频；不改网站播放器脚本或添加DOM控件。75秒返回和96秒结束/自动下一段均验证同一document、定位完成、无HTML视频错误；全片后的返回另有正式包日志。

Gecko147原媒体全屏通知只有一次100ms重试，电视上激活常在约1.6秒后；补丁改为5秒限时重试及playing事件刷新。捕获实际全屏元素的状态，避免iframe全局MediaSession旧位置导致跳转。暂停解码器立即休眠、保留文档，并在旧Surface移除后恢复浏览器，避免抢占同一硬件解码器。

公共HLS对照还发现跨网站共用带宽估计导致过高初始码率；每次播放使用独立估计。持续缓冲15秒自动回到网页，用户暂停会取消该计时；错误回退不循环重试。

## 跨站及边界测试

| 场景 | 实测结果 |
|---|---|
| HTTP MP4、HLS、DASH、跨域iframe | 原有全屏动作自动进入原生播放，返回保持页面和定位 |
| 标准zh字幕、1.25倍速、音量.35 | 原生选中字幕轨道，速度/音量继承，返回不重建页面 |
| Cookie鉴权、HTTP302跨域、Range | 成功播放；sink的3次请求无Cookie/Auth泄漏，含2次Range请求 |
| HLS清单重定向 | 最终URL解析相对路径、alias关系保持，原生播放成功 |
| 播放结束下一段、75秒后返回、96秒后换段 | seeked/ready>=2/!seeking/无error/同document均通过 |
| HTTP503、持续缓冲、缓冲中用户暂停 | 自动回退且无循环；用户暂停不被强制恢复播放 |
| tab/frame/私密隔离、歧义与DRM标记 | 13项逻辑测试通过；9项actor时序/只读状态/匹配恢复测试通过 |

独立公开站点：[hls.js基本播放器](https://hlsjs.video-dev.org/demo/basic-usage)及[DASH-IF官方v4.7.4播放器](https://reference.dashif.org/dash.js/v4.7.4/samples/getting-started/auto-load-single-video.html)已在电视播放。HLS自适应480p起步后到1080p；DASH自适应360p/720p。公共流会随网络改变码率，不能把这些测试描述成所有站点始终1080p、不掉帧的保证。

不支持或不能明确关联的来源、已识别DRM继续使用网页。Gecko没有激活媒体控制器的默认静音视频也保留网页。非标准HTML字幕/弹幕叠加不会转移到原生播放器。不同网站的下一集仍由其自身逻辑决定；保留网站行为，不能承诺任意MSE/DRM播放器都能原生接管。

## 构建、提交和证据

分支feat/universal-tv-playback；网络层d9b9835，清单/活动tab修复4361b6c，自动全屏dd7d441，缓冲修复0eba4e9，休眠媒体返回bd2b72d。源码按功能原子提交。

APK `artifacts/tvbro-2.1.6-playback.2-armv7-release.apk`，SHA256 **f79a1e47ad622cf3703fe62af478d2b6c976495e6500eb302c317bc31bc6c51a**；签名证书SHA256 `6294550c13c1135ecdd0ed90742391e9c573c05dc8f2b3f232520bbba22e91fb`，源码提交bd2b72dde907574e1568caea198f531e303c69d1。签名密钥、密码不入Git。

实际assemble Release成功。全lint仍有原仓库HistoryActivity.kt:176的GestureBackNavigation错误（Android16返回手势），未掩盖该问题、未宣称全lint通过；没有新增错误。

本地完整证据目录`D:/program/TV/tasks/20260926-1754-universal-playback/evidence`，包括失败基线与最终日志/summary、资源、surface、各协议结果、签名和构建日志。原始媒体签名URL/账户页面不提交Git。结论限于本次设备、片源及测量时段。
