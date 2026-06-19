# AYA Android - 开发计划

## 项目概述
将 AYA 桌面端完整移植到 Android 平台，用一台 Android 设备通过 ADB 控制其他 Android 设备（车机、电视、手机）。

## 技术栈
- 语言: Kotlin
- UI: Jetpack Compose + Material 3
- 最低 API: 26 (Android 8.0)
- 目标 API: 35 (Android 15)
- 依赖注入: Hilt
- 协程: Kotlin Coroutines + Flow
- 网络: OkHttp (Webview调试)
- 视频解码: MediaCodec (scrcpy)
- 权限: Shizuku (可选) / Root (可选)

## 开发阶段

### Phase 1: 基础架构 + ADB 协议 + 设备连接 (Week 1)
- [x] 项目结构创建
- [ ] Gradle 构建配置
- [ ] ADB 协议实现 (纯 Kotlin, 不依赖 adb 二进制)
  - AdbProtocol: 消息编解码
  - AdbCrypto: RSA 密钥管理
  - AdbConnection: TCP 连接 + AUTH 认证
  - AdbStream: 数据流管理
- [ ] 设备连接 UI (WiFi IP:端口输入)
- [ ] 设备列表管理

### Phase 2: Shell 终端 + Logcat + 截图 (Week 2)
- [ ] Shell 终端 (adb shell 交互)
- [ ] xterm.js 替代: TerminalView (Compose)
- [ ] Logcat 流式查看
- [ ] 截图功能

### Phase 3: 应用管理 + 文件管理 (Week 3)
- [ ] 应用列表 (pm list packages)
- [ ] 应用安装/卸载/启动/停止
- [ ] 应用详情 (使用 aya.dex 获取更多信息)
- [ ] 文件浏览
- [ ] 文件上传/下载
- [ ] 文件删除/创建/移动

### Phase 4: 设备概览 + 进程管理 + 性能监控 (Week 4)
- [ ] 设备概览信息
- [ ] 进程列表
- [ ] CPU/内存/FPS/电量监控
- [ ] 端口转发管理

### Phase 5: 布局检查 + Webview 调试 + 远程控制 (Week 5)
- [ ] UI 层次结构 dump
- [ ] 布局可视化
- [ ] Webview 检测
- [ ] Chrome DevTools 连接
- [ ] 虚拟遥控器

### Phase 6: 屏幕镜像 (Week 6-7)
- [ ] scrcpy-server 推送和启动
- [ ] H.264/H.265 视频解码 (MediaCodec)
- [ ] 触摸事件转发
- [ ] 键盘事件转发
- [ ] 音频流播放
- [ ] 录屏功能

## 兼容性
- 优先支持天玑处理器
- 纯 Kotlin 实现 ADB 协议 (不使用预编译 adb 二进制)
- 使用标准 MediaCodec API (硬件解码，所有 SoC 通用)
- Shizuku + Root 双支持
