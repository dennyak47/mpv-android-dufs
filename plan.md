# 总体目标

最终实现：

```text
dufs
 │
 │ HTTP
 ▼
mpv-android
 │
 ├── 浏览 dufs 文件
 │
 ├── 视频文件显示 thumbnail
 │
 ├── 没有 thumbnail
 │      │
 │      ▼
 │   FFmpeg 抽帧
 │      │
 │      ▼
 │   缩放 + WebP
 │      │
 │      ├── 保存 Android 本地 cache
 │      │
 │      └── 可选：上传回 dufs
 │
 └── 点击视频
        ↓
      mpv 播放
```

其中第一阶段**先不要实现上传 dufs**。

先做到：

> dufs 视频 URL → FFmpeg → Bitmap/WebP → Android 缓存 → UI 显示

跑通以后，再做远程 thumbnail 存储。

---

# Phase 0：让 AI 先认识项目

第一条指令不要让 AI 改代码。

直接给 Coding Agent：

```text
You are working on an existing mpv-android project.

Before making any changes:

1. Inspect the repository structure.
2. Identify:
   - Android app module
   - Kotlin/Java source roots
   - native C/C++ code
   - bundled FFmpeg sources/libraries
   - libmpv integration
   - current file browser/media browser implementation
   - current thumbnail/image loading implementation
   - Gradle configuration
   - minimum and target Android SDK versions
3. Find how mpv-android currently opens local files and HTTP URLs.
4. Find whether FFmpeg APIs are already exposed to the Android layer.
5. Find existing caching utilities.
6. Find the UI component that renders media/file list items.

Do NOT modify any files.

Produce a concise architecture report with exact file paths and relevant classes/functions.
```

**这一步非常重要。**

因为不同版本的 mpv-android 代码结构可能变化。

不要让 AI 根据网上文章猜。

---

# Phase 1：确定 FFmpeg 接入点

第二步让 AI 专门研究：

```text
FFmpeg 到底怎么进入 Android 层
```

Prompt：

```text
Based on the repository inspection, determine the safest way to implement video frame extraction using the FFmpeg already available in this mpv-android project.

Requirements:

1. Prefer reusing the existing FFmpeg build/dependency.
2. Do NOT introduce Media3, ExoPlayer, OpenCV, or another video decoder.
3. Do NOT download or bundle a second FFmpeg.
4. Determine whether frame extraction should be implemented:
   - in native C/C++, or
   - through an existing FFmpeg Java/Kotlin binding.
5. Prefer a native implementation if that is consistent with the existing architecture.
6. Define a minimal API between Android/Kotlin and native FFmpeg.

Do not implement yet.

Produce:
- recommended architecture
- exact files to modify
- API design
- thread model
- memory ownership rules
- error handling strategy
```

这里 AI 很可能会发现：

```text
Kotlin
   ↓ JNI
C/C++
   ↓
libavformat
libavcodec
libswscale
   ↓
WebP/JPEG
```

这就是我们想要的。

---

# Phase 2：先做一个最小 FFmpeg Extractor

这一阶段不要碰 UI。

目标：

```text
video URL/path
       ↓
extractFrame()
       ↓
JPEG/WebP bytes
```

让 AI：

```text
Implement the minimal FFmpeg video frame extraction module.

Requirements:

1. Input:
   - local filesystem path
   - HTTP/HTTPS URL if supported by the existing FFmpeg build

2. Input parameters:
   - video source
   - timestamp in milliseconds
   - output width
   - output height

3. Output:
   - encoded JPEG or WebP bytes
   - do not return a full-resolution Bitmap from native code

4. FFmpeg pipeline:
   avformat_open_input
   avformat_find_stream_info
   find video stream
   avcodec_find_decoder
   avcodec_open2
   seek to timestamp
   decode frames
   swscale if required
   encode thumbnail

5. Handle:
   - invalid URL
   - unsupported codec
   - seek failure
   - no video stream
   - HTTP/network errors
   - corrupted files
   - cancellation

6. Do not block the Android main thread.

7. Keep the public API small.

8. Add unit/integration tests where practical.

Do not modify the media browser UI yet.

Build the project after implementation and fix all compilation errors.
```

---

# Phase 3：先只支持本地视频

这一阶段故意**不要 dufs**。

测试：

```text
/storage/emulated/0/Movies/test.mp4
```

调用：

```text
extractFrame(
    path,
    10_000,
    640,
    360
)
```

得到：

```text
thumbnail.webp
```

然后 AI 做一个 debug/test 页面或者测试入口。

Prompt：

```text
Add a minimal test harness for the FFmpeg thumbnail extractor.

Use a local video file.

Test timestamps:
- 0 ms
- 10000 ms
- 50% of duration

Verify:
- extraction succeeds
- output dimensions are correct
- output is a valid image
- no crash occurs
- extraction runs off the main thread

Do not modify the main media browser UI.
```

---

# Phase 4：支持 HTTP / dufs

这一步才进入你的核心需求。

目标：

```text
http://dufs/movies/a.mkv
```

直接：

```text
FFmpeg
  ↓
HTTP
  ↓
Range/seek
  ↓
frame
```

Prompt：

```text
Extend the FFmpeg thumbnail extractor to support HTTP/HTTPS media URLs.

The primary target is videos served by dufs.

Requirements:

1. Use FFmpeg's existing HTTP protocol support.
2. Do not download the entire video before decoding.
3. Allow FFmpeg/libavformat to perform network reads and seeking.
4. Preserve HTTP headers when required by the existing mpv network configuration.
5. Support URLs containing:
   - spaces
   - Unicode characters
   - URL-encoded paths
   - query parameters
6. Respect existing proxy/network configuration where possible.
7. Add configurable network timeout.
8. Add cancellation support.
9. Avoid blocking the UI thread.

Test with a real HTTP video URL.

Do not modify the UI yet.
```

这里有一个很重要的工程原则：

**不要自己用 OkHttp 下载视频然后喂 FFmpeg。**

让：

```text
libavformat
    ↓
HTTP protocol
    ↓
dufs
```

自己处理网络读取。

否则你很容易最后搞出：

```text
OkHttp
  ↓
下载
ByteArray
  ↓
FFmpeg
```

这对大视频非常糟糕。

---

# Phase 5：ThumbnailManager

现在开始做 Android 层真正的业务逻辑。

架构：

```text
ThumbnailManager
       │
       ├── MemoryCache
       │
       ├── DiskCache
       │
       └── FFmpegExtractor
```

Prompt：

```text
Implement a ThumbnailManager for mpv-android.

Responsibilities:

1. Given a media URL/path, return a thumbnail.
2. Check memory cache first.
3. Check disk cache second.
4. Generate using FFmpeg when cache misses.
5. Never block the main/UI thread.
6. Deduplicate concurrent requests for the same video.
7. Support cancellation.
8. Limit concurrent FFmpeg extraction jobs.
9. Store thumbnails as WebP.
10. Store thumbnails at approximately 320-640px wide.
11. Do not retain large video frames in memory.

Thumbnail cache key must include enough information to invalidate the thumbnail when the media changes.

At minimum consider:
- normalized media URL/path
- file size when available
- modification time when available

Implement this as an independent component before integrating it into the UI.
```

---

# Phase 6：确定封面时间

我建议第一版非常简单：

```text
duration × 0.10
```

例如：

```text
10分钟
↓
60秒

2小时
↓
12分钟
```

但有一个问题：

如果视频 duration 不可靠怎么办？

所以策略：

```text
duration available
    ↓
10%

duration unavailable
    ↓
30 seconds

video < 30 seconds
    ↓
10%
```

给 AI：

```text
Implement the default thumbnail timestamp policy.

Default:
- use 10% of media duration
- clamp the timestamp to a safe range
- never exceed duration
- for very short videos, use approximately 10%
- if duration cannot be determined, use a small fallback timestamp

Keep the timestamp policy isolated behind a small interface/function so it can be changed later.

Do not implement scene detection or black-frame detection yet.
```

---

# Phase 7：接入文件列表 UI

现在才碰 UI。

```text
FileBrowser
     ↓
MediaItem
     ↓
ThumbnailManager
     ↓
ImageView
```

Prompt：

```text
Integrate ThumbnailManager into the existing mpv-android media/file browser.

Requirements:

1. Only generate thumbnails for supported video files.
2. Load thumbnails asynchronously.
3. Never block scrolling.
4. Show an existing/default placeholder while loading.
5. Recycle/cancel requests when list items are reused or leave the viewport.
6. Use disk cache aggressively.
7. Avoid generating thumbnails for files that are not visible.
8. Avoid duplicate extraction requests.
9. Preserve existing player behavior.
10. Do not introduce a new UI framework.

Keep the UI changes minimal.
```

这一阶段完成后，你应该已经得到：

```text
dufs
 │
 ├── movie1.mkv
 ├── movie2.mp4
 └── movie3.webm

          ↓

mpv-android

┌────────────┐ ┌────────────┐
│ thumbnail  │ │ thumbnail  │
│ movie1     │ │ movie2     │
└────────────┘ └────────────┘
```

---

# Phase 8：性能优化

这个阶段非常重要。

尤其是你可能会拿它浏览：

```text
1000+ 视频
```

不要让 AI 一次启动：

```text
1000 × FFmpeg
```

给它：

```text
Optimize thumbnail generation for large media libraries.

Requirements:

1. Maximum concurrent FFmpeg extraction jobs should be small and configurable.
2. Use a bounded executor/queue.
3. Cancel requests that are no longer needed by the UI.
4. Deduplicate requests for identical cache keys.
5. Memory cache should be bounded.
6. Disk cache should have a maximum size.
7. Use WebP thumbnails.
8. Avoid decoding thumbnails at unnecessarily high resolution.
9. Avoid loading all thumbnails into memory.
10. Scrolling must remain responsive.

Add logging/metrics for:
- cache hit
- cache miss
- extraction duration
- extraction failure
- cancellation
```

---

# Phase 9：dufs 远程封面

**最后才做。**

这是第二阶段功能：

```text
本地 cache
      │
      │ miss
      ▼
dufs /.thumbnails/
      │
      │ 404
      ▼
FFmpeg
      │
      ▼
WebP
      │
      ├── local cache
      │
      └── PUT dufs
```

建议目录：

```text
.thumbnails/
```

例如：

```text
/movies/
    Interstellar.mkv

/.thumbnails/
    <hash>.webp
```

不要使用：

```text
Interstellar.webp
```

避免重名、移动文件后失效等问题。

给 AI：

```text
Implement optional remote thumbnail persistence for dufs.

Requirements:

1. Remote thumbnails are stored separately from media files.
2. Do not modify the original media file.
3. Use a deterministic thumbnail cache key.
4. Prefer:
   /.thumbnails/<hash>.webp

5. Read remote thumbnail before generating locally.
6. On remote miss:
   - generate thumbnail using FFmpeg
   - save local cache
   - optionally upload thumbnail to dufs

7. Remote upload must be optional/configurable.
8. Never make thumbnail upload block video playback.
9. Failed upload must not make thumbnail generation fail.
10. Do not overwrite media files.
11. Handle authentication using the existing dufs credentials/configuration.

Keep remote thumbnail storage behind a DufsThumbnailStore abstraction.
```

---

# Phase 10：最终架构整理

最后让 AI 做一次 refactor review：

```text
Review the complete thumbnail implementation.

Verify the architecture:

Media Browser
      ↓
ThumbnailManager
      ↓
ThumbnailCache
      ↓
FFmpegThumbnailExtractor
      ↓
DufsThumbnailStore (optional)

Requirements:

1. No UI blocking.
2. No full video download.
3. No duplicate FFmpeg dependency.
4. No Media3/ExoPlayer/OpenCV dependency.
5. No memory leaks.
6. Proper cancellation.
7. Bounded concurrency.
8. Correct cache invalidation.
9. Network failures must degrade gracefully.
10. Thumbnail failure must never prevent video playback.

Inspect for:
- unnecessary allocations
- JNI memory leaks
- AVFrame/AVPacket/AVCodecContext leaks
- thread lifecycle problems
- Android lifecycle issues
- RecyclerView/list recycling issues
- HTTP resource leaks

Do not change behavior unnecessarily.
```

---

# 我建议你的 Git 提交也严格分开

AI coding 最怕一个 commit 改 50 个地方。

建议：

```text
01 inspect
02 ffmpeg-thumbnail-core
03 local-thumbnail-test
04 http-thumbnail-support
05 thumbnail-manager
06 thumbnail-cache
07 media-browser-integration
08 performance
09 dufs-thumbnail-store
10 cleanup
```

每一步：

```text
修改
 ↓
编译
 ↓
测试
 ↓
commit
 ↓
下一步
```

---

# 最终目录大概长这样

不强迫 AI 一定采用这个目录，而是希望架构接近：

```text
mpv-android/
│
├── app/
│   └── src/main/...
│
├── thumbnail/
│   ├── ThumbnailManager.kt
│   ├── ThumbnailCache.kt
│   ├── ThumbnailKey.kt
│   ├── ThumbnailTimestamp.kt
│   │
│   ├── ffmpeg/
│   │   ├── FFmpegThumbnailExtractor.kt
│   │   └── native/
│   │       └── thumbnail.cpp
│   │
│   └── dufs/
│       └── DufsThumbnailStore.kt
│
└── ...
```

最终依赖关系：

```text
                 UI
                  │
                  ▼
          ThumbnailManager
             │         │
             │         └──────────┐
             ▼                    ▼
       ThumbnailCache      DufsThumbnailStore
             │
             ▼
    FFmpegThumbnailExtractor
             │
             ▼
      libavformat
      libavcodec
      libswscale
```

**核心原则就是：FFmpeg 是唯一的视频解码入口；dufs 只是 HTTP 文件源和可选的 thumbnail 存储。**

这样以后你甚至可以继续扩展：

```text
                   ThumbnailManager
                         │
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
       FFmpeg         Local Cache    Dufs Cache
          │
          ▼
    Scene Detection
          │
          ▼
     更好的封面
```

而不会把 mpv 播放器、dufs、UI 和 thumbnail 逻辑搅成一团。
