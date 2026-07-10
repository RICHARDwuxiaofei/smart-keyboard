# 智能输入法数据辅助增强项目进度

## 项目目标

基于 `fcitx5-android` 做纯 Kotlin/Java 层增强，为老旧系统、企业内网控制台、全屏业务软件、无障碍场景提供输入法内闭环辅助能力。

核心流程设计如下：

1. 用户通过键盘手势触发。
2. 通过 `MediaProjection` 捕获单帧屏幕。
3. 使用 Google ML Kit 离线 OCR 提取屏幕文字。
4. 优先查询本地企业知识库。
5. 本地未命中时，再调用 DeepSeek 兼容的大模型 API。
6. 将结果显示为输入法候选栏第一项。
7. 用户点击候选项后，通过限流逐字 `commitText()` 平滑上屏。

当前原则：不修改 C++ 底层，不动拼音/RIME/Fcitx 内核，所有增强都在 `:app` 模块 Kotlin/Java 层实现。

## 当前已完成

### 新增 AI 扩展包

新增包：

- `org.fcitx.fcitx5.android.extension.ai`

包含文件：

- `AiCandidateOverlay.kt`
- `AiMediaProjectionStore.kt`
- `AsyncLlmClient.kt`
- `DataAssistanceCoordinator.kt`
- `LocalSkillDatabase.kt`
- `ScreenCaptureOcrPipeline.kt`
- `ScreenCapturePermissionActivity.kt`
- `SkillImportManager.kt`
- `SmoothTextInjector.kt`

### 空格长按触发

已修改：

- `CommonKeyActionListener.kt`

当前行为：

- 空格键长按不再执行原本的切换输入法或弹出选择器逻辑。
- 改为调用 `DataAssistanceCoordinator.triggerDataAssistance()`。

### 候选栏第一项注入

已修改：

- `HorizontalCandidateComponent.kt`

当前行为：

- AI 结果会插入到横向候选栏第一项。
- 用户点击该 AI 候选词时，触发平滑逐字输入。

### 双指下滑快速重置

已修改：

- `BaseKeyboard.kt`
- `KeyboardWindow.kt`

当前行为：

- 在键盘区域识别双指下滑。
- 触发后调用 `DataAssistanceCoordinator.panicReset()`。
- 会清空候选栏 AI 结果、取消当前任务、停止平滑注入。

### 屏幕捕获授权入口

新增：

- `ScreenCapturePermissionActivity.kt`
- `AiMediaProjectionStore.kt`

已修改：

- `AndroidManifest.xml`

当前行为：

- 如果触发 OCR 时还没有 `MediaProjection` 授权，会启动一个轻量 Activity 请求屏幕捕获权限。
- 授权成功后会继续执行原本的辅助流程。

### OCR 管线

新增：

- `ScreenCaptureOcrPipeline.kt`

当前能力：

- 使用 `ImageReader` 和 `VirtualDisplay` 捕获单帧屏幕。
- 使用 Google ML Kit Chinese Text Recognition 做离线 OCR。
- 图像数据不上传云端。

### 本地知识库

新增：

- `LocalSkillDatabase.kt`
- `SkillImportManager.kt`

当前能力：

- Room 数据库保存 `SkillEntity`。
- 支持本地全文检索优先、`LIKE` 兜底。
- 支持 JSON 导入：
  - JSON 数组
  - `{"skills":[...]}`
  - 单个 JSON 对象
- 支持 TXT 导入：
  - `keyword=>answer`
  - `keyword|answer`
  - `keyword:answer`
  - `keyword<TAB>answer`

说明：

- 当前实现使用 Room `@Fts4`。
- 需求文档写的是 FTS5，但 Room 原生稳定支持的是 FTS4。
- 如果必须严格使用 FTS5，后续需要改成手写 SQLite 建表和 raw query。

### LLM 网络客户端

新增：

- `AsyncLlmClient.kt`

当前能力：

- OkHttp 4.x。
- DeepSeek Chat Completion 格式。
- 5 秒连接、读取、写入超时。
- 支持重试。
- 支持非流式响应。
- 已写 SSE 流式响应方法，但还没有接入主调度流程。

### 平滑上屏器

新增：

- `SmoothTextInjector.kt`

当前能力：

- 将文本拆成单字符。
- 每个字符调用一次 `InputConnection.commitText()`。
- 每次输入间隔随机 80 到 130 毫秒。
- 遇到 `InputConnection` 失效或提交失败会停止。

## 当前已改动的重要文件

- `app/build.gradle.kts`
- `gradle/libs.versions.toml`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CommonKeyActionListener.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/input/candidates/horizontal/HorizontalCandidateComponent.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/extension/ai/*`

## 当前阻塞

### Android Studio / SDK / Gradle 环境未就绪

已确认可用 Java 路径：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
```

之前错误路径：

```powershell
C:\Program Files\Google\Android Studio\jbr
```

当前 Gradle wrapper 已下载成功，但项目构建仍卡在 Android Gradle Plugin 解析：

```text
Plugin [id: 'com.android.application', version: '9.2.0', apply: false] was not found
```

也就是说：目前还没有进入 Kotlin 编译阶段，所以新增代码还没有被编译器完整验证。

## 已知问题

### 1. `AiCandidateOverlay` listener 生命周期还需要清理

当前 `HorizontalCandidateComponent` 注册了 overlay listener，但还没有在组件销毁时注销。

风险：

- 输入视图重建后可能泄漏旧组件。
- 可能重复刷新候选栏。

### 2. AI 候选项长按索引还需要修

当前点击 AI 候选项时索引映射已处理。

但长按候选项打开 action menu 时，还需要避免 AI 候选项走 fcitx 原始候选动作。

### 3. Panic reset 是否停止 MediaProjection 需要决策

当前 `panicReset()` 会清空任务、候选栏和内存文本。

但是否每次都停止 `MediaProjection` 还需要定策略：

- 停止：隐私更强，下次需要重新授权。
- 不停止：体验更顺，但授权对象仍留在内存。

### 4. LLM 流式响应尚未接入主流程

`AsyncLlmClient.streamAiResponse()` 已存在。

但 `DataAssistanceCoordinator` 当前仍走非流式 `fetchAiResponse()`。

### 5. 设置页还没有做

还缺：

- DeepSeek API Key 设置。
- Base URL / model 设置。
- Prompt 模板编辑。
- 本地知识库导入 UI。

### 6. 候选栏 overlay 只覆盖横向候选栏

还没覆盖：

- 浮动候选窗。
- 展开候选窗。

## 后续推荐顺序

1. 先修 Android Studio SDK / Gradle 插件解析。
2. 跑：

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

3. 修 Kotlin / Room / ML Kit 编译错误。
4. 清理 `AiCandidateOverlay` listener 生命周期。
5. 修 AI 候选项长按索引问题。
6. 将 LLM 流式响应接入 `DataAssistanceCoordinator`。
7. 增加设置页：
   - API Key。
   - Prompt 模板。
   - 知识库导入。
8. 真机验证：
   - 空格长按触发。
   - 屏幕捕获授权。
   - OCR 识别。
   - 本地知识库命中。
   - 云端兜底。
   - 候选栏第一项展示。
   - 平滑逐字上屏。
   - 双指下滑重置。

## 注意事项

- 不要修改 C++ 代码。
- 不要把截图上传到云端。
- OCR 图像处理必须保持本地离线。
- 网络请求只能发送 OCR 后的文本上下文。
- 输入法主线程不能被网络、数据库或 OCR 阻塞。
