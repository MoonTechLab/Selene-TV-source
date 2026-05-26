第三方原生库许可证说明
========================

本应用通过 dev.jdtech.mpv:libmpv 版本 1.0.0 动态加载 mpv 与 FFmpeg 原生库。
这些库遵循各自许可证（见同目录 mpv-LICENSE.txt / ffmpeg-LICENSE.txt）。
仅当用户在「设置 → 播放器」选择 MPV 时才会加载这些原生库（且要求 Android 8.0 / API 26 及以上）。

来源
----
- mpv：https://github.com/mpv-player/mpv
  许可证：GNU General Public License version 2（见 mpv-LICENSE.txt）

- FFmpeg：https://ffmpeg.org
  许可证：GNU Lesser General Public License version 2.1（见 ffmpeg-LICENSE.txt）

- libmpv-android 打包：https://github.com/jarnedemeulemeester/libmpv-android
  本应用所使用的 libmpv AAR 包由此项目构建并发布至 Maven，
  Artifact 坐标：dev.jdtech.mpv:libmpv:1.0.0
