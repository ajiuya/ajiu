# 在容器中编译 Android 应用

在 AiCode 的 Linux 容器里配置好 JDK 与 Android SDK，即可直接从源码编译 Android 应用。本文以 Debian 12 (bookworm) aarch64 为例，Ubuntu、Arch 等发行版同样适用。

::: tip 建议使用自定义 Debian / Ubuntu 镜像
内置的 Alpine 容器使用 musl libc，在 PRoot 下编译 Android 应用需要额外处理（见文末补充）。导入自定义镜像的方法见「自定义容器镜像」。
:::

## 环境基线

| 项 | 值 |
| --- | --- |
| 系统 | Debian GNU/Linux 12 (bookworm) aarch64 |
| JDK | OpenJDK 17 |
| Gradle | 项目 wrapper 自带，无需系统安装 |
| SDK | Android 36 / build-tools 35.0.0 |
| aapt2 | ARM64 静态编译版 35.0.2（社区构建） |

## 1. 安装基础依赖

```bash
apt update
apt install -y openjdk-17-jdk-headless curl wget unzip ripgrep
```

验证 JDK：

```bash
/usr/lib/jvm/java-17-openjdk-arm64/bin/java -version
# 应输出 openjdk version "17..."
```

## 2. 安装 Android SDK

默认使用国内镜像安装（下载更快、更稳）。除 sdkmanager 本体（cmdline-tools）需手工下载解压外，其余组件通过 `SDK_TEST_BASE_URL` 环境变量覆盖 sdkmanager 的默认仓库根 URL（需以 `/` 结尾，镜像需提供 `repository2-3.xml`），一条命令全部装完（`--list` 与 `--install` 均已实测可用）：

```bash
# 唯一手工步骤：获取 sdkmanager 本体（从镜像下载）
mkdir -p ~/android/sdk && cd ~/android/sdk
curl -fL -C - -o clt.zip https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-13114758_latest.zip
unzip -q clt.zip
mkdir -p cmdline-tools/latest && mv cmdline-tools/* cmdline-tools/latest/

# 组件全部走镜像安装（自动处理目录布局与许可接受）
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=$HOME/android/sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
export SDK_TEST_BASE_URL=https://mirrors.cloud.tencent.com/AndroidSDK/

yes | sdkmanager --install "platforms;android-36" "build-tools;35.0.0" "platform-tools"
```

::: tip 版本说明
按目标项目的 `compileSdk` 和 `buildToolsVersion` 选择组件版本，本文示例为 Android 36 / build-tools 35.0.0。
:::

::: tip 镜像与官方源
`SDK_TEST_BASE_URL` 虽为测试用途的环境变量（非官方文档化的常规配置），但已被镜像方案广泛使用。镜像安装的 SDK 与官方安装完全等价：即使网络可直接访问 `dl.google.com`，也无需还原为官方流程。若该方式在特定场景下失效，可改用官方源（见下）或手工组装。
:::

### 可选：官方源安装

若网络可直连 `dl.google.com`，也可使用官方标准流程：

```bash
mkdir -p ~/android/sdk && cd ~/android/sdk
curl -O https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q commandlinetools-linux-*.zip
mkdir -p cmdline-tools/latest && mv cmdline-tools/* cmdline-tools/latest/

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=$HOME/android/sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

yes | sdkmanager --install "platforms;android-36" "build-tools;35.0.0" "platform-tools"
```

### 备选：从镜像手工组装 SDK

若 `SDK_TEST_BASE_URL` 方式不可用，可从腾讯云镜像下载组件 zip 手工组装，最终得到 `build-tools/`、`platforms/`、`platform-tools/` 三个目录，效果与 sdkmanager 一致：

| 组件 | 镜像地址 |
| --- | --- |
| commandlinetools | `https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-13114758_latest.zip` |
| platform-36 | `https://mirrors.cloud.tencent.com/AndroidSDK/platform-36_r01.zip` |
| build-tools 35.0.0 | `https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r35_linux.zip` |
| platform-tools | `https://mirrors.cloud.tencent.com/AndroidSDK/platform-tools_r35.0.2-linux.zip` |

```bash
cd /workspace
curl -L -O https://mirrors.cloud.tencent.com/AndroidSDK/platform-36_r01.zip
curl -L -O https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r35_linux.zip
curl -L -O https://mirrors.cloud.tencent.com/AndroidSDK/platform-tools_r35.0.2-linux.zip

# 1) build-tools：zip 解压出的目录是 android-15/，把内容移到 build-tools/35.0.0/
unzip build-tools_r35_linux.zip -d ~/android/sdk
mkdir -p ~/android/sdk/build-tools/35.0.0
mv ~/android/sdk/android-15/* ~/android/sdk/build-tools/35.0.0/

# 2) platform：解压出 android-36/
unzip platform-36_r01.zip -d ~/android/sdk
mkdir -p ~/android/sdk/platforms
mv ~/android/sdk/android-36 ~/android/sdk/platforms/android-36

# 3) platform-tools：解压即得 platform-tools/，无需移动
unzip platform-tools_r35.0.2-linux.zip -d ~/android/sdk

ls ~/android/sdk   # 应看到 build-tools/ platforms/ platform-tools/ 三个目录
```

## 3. 替换 ARM64 原生二进制

Google 官方的 `aapt2`、`adb` 等工具是 x86_64 编译的，在 aarch64 上无法运行，需要换成社区维护的 ARM64 静态编译版本。

从 `https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip` 下载。下载缓慢或失败时，可以在原链接前加 GitHub 代理前缀（实测可用，2026-08）：

| 加速服务 | 用法 |
| --- | --- |
| gh-proxy.com | `https://gh-proxy.com/https://github.com/...` |
| ghproxy.vip | `https://ghproxy.vip/https://github.com/...` |

```bash
# 示例：走 gh-proxy.com 加速下载
curl -L -O https://gh-proxy.com/https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip
# 容器能直连 GitHub 时可直接下载
# curl -L -O https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip
unzip /workspace/android-sdk-tools-static-aarch64.zip -d ~/armtools35
```

替换 SDK 里的二进制：

```bash
cp -p ~/armtools35/build-tools/* ~/android/sdk/build-tools/35.0.0/
cp -p ~/armtools35/platform-tools/* ~/android/sdk/platform-tools/
```

验证 `aapt2` 可执行：

```bash
~/android/sdk/build-tools/35.0.0/aapt2 version
# 应输出版本号，不报错
```

## 4. 配置

### local.properties（项目根目录）

```bash
echo "sdk.dir=/root/android/sdk" > /workspace/local.properties
```

这个文件通常已被 `.gitignore` 忽略，不会污染仓库。

### 全局 Gradle 配置

aapt2 覆盖配置写入全局文件，不要写进项目的 `gradle.properties`：

```bash
mkdir -p ~/.gradle
echo "android.aapt2FromMavenOverride=/root/android/sdk/build-tools/35.0.0/aapt2" > ~/.gradle/gradle.properties
```

### apt 源（可选）

容器默认可能连不上官方源，建议换国内镜像：

```
URIs: http://mirrors.ustc.edu.cn/debian
URIs: http://mirrors.ustc.edu.cn/debian-security
```

### 下载 Gradle 发行版（可选）

项目使用 Gradle wrapper 时不需要系统 Gradle，但从零搭建新项目时要先有一个发行版。官方源 `https://services.gradle.org/distributions/gradle-8.11.1-bin.zip` 下载缓慢时可用镜像：

- 华为云：`https://mirrors.huaweicloud.com/gradle/gradle-8.11.1-bin.zip`
- 腾讯云：`https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip`

```bash
curl -L -o /workspace/gradle.zip https://mirrors.huaweicloud.com/gradle/gradle-8.11.1-bin.zip
unzip gradle.zip -d /workspace/tools
/workspace/tools/gradle-8.11.1/bin/gradle --version   # 用它执行构建或生成 wrapper
```

## 5. 编译

### 设置中启用手机构建

在「设置 → 软件权限 → 手机构建」开启开关后，使用项目原有的 `./gradlew` 或 `gradle` 命令即可默认读取手机档，不需要每个项目都添加脚本。可在「构建参数」面板选择 Gradle 堆 2 GB / 2.5 GB / 3 GB，并调整常驻进程、并行、worker 和单元测试堆；保存后下一次本地构建生效。

关闭开关会恢复项目与原有用户级默认配置，保留参数选择，不影响 SSH 或 CI；用户主动指定其他 Gradle 用户目录或命令行覆盖时仍以显式配置为准。堆上限不是总内存上限，常驻也不保证进程不被 PRoot 或系统清理。完整范围见 [手机构建](/guide/app-permissions#手机构建)。

### 本仓库：使用手机低内存档

在本仓库根目录（以下假设为 `/workspace`）运行：

```bash
cd /workspace
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 \
ANDROID_HOME=/root/android/sdk \
./scripts/gradle-mobile.sh :app:assembleUniversalDebug
```

以下脚本固定手机档只影响本次命令，不修改项目或全局 `gradle.properties`；脚本自身不会改变普通 `./gradlew` 和 CI 的默认配置。脚本显式指定的 Gradle 选项覆盖对应面板选择，但已开启面板的测试堆可能被托管初始化脚本后置设置。严格按固定脚本的测试堆验证时，在本次命令加 `-PaicodeMobileBuild=false`，不需要关闭面板或修改全局文件；希望使用面板参数时，改用普通 `./gradlew`：

| 项目 | 手机档默认值 |
| --- | --- |
| Gradle 最大堆 | `3g` |
| Gradle 最大元空间 | `512m` |
| Gradle worker | `1` |
| 并行、常驻 daemon、配置缓存 | `--no-parallel --no-daemon --no-configuration-cache` |
| Kotlin 编译 | 保持 `in-process`，不另开 Kotlin 编译 daemon |
| 本仓库 app JVM 单测 | 每个测试进程最大堆 `1g`，最多同时运行 `1` 个测试进程，每处理 20 个测试类重启 worker |

例如，只运行通用 debug 变体的单元测试：

```bash
./scripts/gradle-mobile.sh -PaicodeMobileBuild=false :app:testUniversalDebugUnitTest
```

脚本以 `-PmobileBuild=true` 启用 app 单测的手机档；未传该属性或其值不是 `true` 时，不设置这些测试限制。脚本不指定应用版本，版本仍由 tag / 提交推导，或由调用方显式传入 `-PappVersionName` 与 `-PappVersionCode`。

如果设备资源允许，可仅为一次命令调整 Gradle 或测试堆大小：

```bash
MOBILE_GRADLE_HEAP=2560m MOBILE_TEST_HEAP=768m \
./scripts/gradle-mobile.sh -PaicodeMobileBuild=false :app:testUniversalDebugUnitTest \
  --tests 'com.aicode.feature.agent.domain.provider.GeminiAdapterTest'
```

两个变量均只接受**无空格、无前导零的正整数加 `m` 或 `g`**（单位不区分大小写），例如 `3072m`、`3g`；空值、`0g`、小数、裸数字或附带 JVM 参数都会被拒绝。只想调整其中一个时可省略另一个。脚本会原样转发任务及参数，包括引号包住的含空格参数；内存只能通过上述环境变量调整，不能另传 JVM / worker / 手机档属性覆盖。与手机档冲突的 daemon、并行、配置缓存参数，以及停止 daemon、重定向项目或受保护选项的缩写也会被明确拒绝。

::: warning 内存与进程边界
`--no-daemon` 不代表绝不创建新的 JVM：Gradle 为匹配 JVM 参数仍可能启动本次构建专用、结束后退出的 single-use daemon。脚本不会停止、重配或清理已经运行的其他 Gradle daemon。旧 daemon、其他构建、测试 JVM、原生工具与系统自身仍会占用内存；`3g` / `1g` 只是各自的堆上限，不是总内存上限，也不能保证 Android 不回收构建进程。避免同时启动多次构建，按设备余量选择堆大小；出现 Java 堆不足时再评估是否上调。
:::

其他 Android 项目没有本仓库的手机脚本时，使用各自的 wrapper 和任务；不要直接假定支持 `mobileBuild` 或上述脚本。

### 本仓库的测试运行环境

测试依赖采用官方 Robolectric 4.18，原生 SQLite 与原生图形运行时支持 Linux ARM64。旧 4.15.1 在该架构缺少原生运行时，可能在进入 Room 业务断言前就失败；仅升级 Java 不能补齐该原生库。首次运行新版测试需要从项目既有仓库准备对应测试依赖与 Android SDK JAR，不需要为此更换系统或安装非官方原生补丁。

布局测试中，正常字号的几何检查采用原生字体度量；旧图形模式仍用于明确的模拟度量及异常增高覆盖，不把35px模拟字体当作真实设备字号。持续动画的测试使用暂停的模拟帧调度和明确的时间步，避免立即续帧让消息循环无法结束；产品动画不会因此关闭。

项目保留 Jetifier，仅对不引用 Android 支持库的纯 JVM 多版本依赖使用转换例外。Bouncy Castle 1.86 包含 Java25 专用类，旧 Jetifier 不能解析这些条目；保留官方 JAR 原字节即可，不需要把手机 Java 升到25、删除条目或关闭所有依赖的转换。

手机脚本启用的测试档按 **20 个测试类**回收并重新启动测试 worker，保持单进程并发和原堆上限，避免整套测试长时间复用一个 JVM；不是每 20 个测试方法，也不会减少或跳过测试。这个设置仅作用于本仓库显式手机档，不改变其他项目、普通未启用该档的构建或 CI 默认值。手机档还会仅从测试 worker 移除继承的 Android 系统目录变量，避免它们覆盖 Robolectric 自带的 ICU 数据位置；不会修改 App、终端、构建主进程或全局环境。分批进程会增加启动耗时，仍不能保证手机系统不回收构建。

### Debug 设备验证页

使用本仓库专用的 chroot 设备验证包时，测试会自动打开验证页。请先解锁设备，并在本轮验证期间保持该页可见；切换应用、锁屏或返回退出会使本轮验证失去前台条件，不能算作通过。验证结束或失败后页面会自动关闭。

页面只在可见期间保持屏幕点亮，不会解除锁屏、修改全局屏幕超时、电池白名单或系统冻结策略，也不会让正式版增加测试入口。可见页面并不保证手机厂商绝不冻结或回收进程；如果系统强制中断，测试可能来不及写入完整结果，应保留报告与待恢复现场，不能凭进程消失判断任务已清理。

设备验证仍需按本次范围确认 Root 和网络操作。不要为了运行测试关闭全局冻结或删除未确认清理的容器目录；测试包安装成功、命令返回或单项通过，都不等于整套功能已经验收。

### 依赖下载与输出

依赖下载慢时，可以在 `settings.gradle.kts` 的 `dependencyResolutionManagement.repositories` 里改用阿里云镜像（AGP 默认从 `google()` / `mavenCentral()` 拉依赖）：

```kotlin
maven { url = uri("https://maven.aliyun.com/repository/google") }
maven { url = uri("https://maven.aliyun.com/repository/central") }
maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
```

首次构建需要下载依赖并编译 Kotlin，耗时受网络、设备内存与任务范围影响；手机档的单 worker 和关闭配置缓存也可能增加耗时。这个档位用于降低并发内存压力，不会修复依赖下载的 HTTP 502、网络中断或工具链安装问题，这些问题仍需分别排查。

成功后 APK 的输出路径取决于项目配置，通常是：

```
app/build/outputs/apk/<flavor>/debug/app-<flavor>-debug.apk
```

## 常见问题

| 现象 | 原因 | 解决 |
| --- | --- | --- |
| `JAVA_HOME is not set` | 未设环境变量 | 命令行加 `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64` |
| `SDK location not found` | 缺 local.properties | 创建 `local.properties` 写入 `sdk.dir=/root/android/sdk` |
| `aapt2 syntax error` | 用了 x86 版 aapt2 | 确认 `~/.gradle/gradle.properties` 里的 `aapt2FromMavenOverride` 指向 ARM 版 |
| `Failed to install build-tools` | AGP 联网验证失败 | 确认本地已装项目所需的 build-tools 版本 |
| apt 下载 403 | 镜像源临时不可用 | 换镜像源（清华 → 中科大） |
| Gradle 锁文件冲突 | 其他构建仍占用目录，或上次构建异常退出 | 先检查占用进程；不要删除活跃构建的锁文件。手机脚本不会停止已有 daemon，`--no-daemon` 也不保证消除锁冲突 |

## 补充：内置 Alpine 容器的修复

在内置 Alpine 容器里编译 Android 应用，可能遇到 `Failed to delete /tmp/tempdir_*`。原因是 AGP 的 apkzlib 里 `TemporaryFile` 用 `File.delete()` 删临时目录，这个操作在 PRoot 环境下可能失败。自定义 Debian 镜像不受影响。

::: warning 版本相关
下述代码针对 Gradle 8.11.1 / AGP 8.x 中的 apkzlib（包名 `com.android.tools.build.apkzlib.bytestorage.TemporaryFile`）。不同版本的包路径与内部结构可能不同，请按实际使用的 jar 反编译结构调整。
:::

把 `File.delete()` 换成 `Files.delete()`（走 unlinkat 绕过底层删除失效）：

```java
package com.android.tools.build.apkzlib.bytestorage;

import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;

public class TemporaryFile implements Closeable {
    private boolean deleted;
    private final File file;

    public TemporaryFile(File file) {
        this.deleted = false;
        this.file = file;
    }

    public File getFile() {
        Preconditions.checkState(!this.deleted, "File already deleted");
        return this.file;
    }

    @Override
    public void close() throws IOException {
        if (this.deleted) {
            return;
        }
        this.deleted = true;
        deleteFile(this.file);
    }

    private void deleteFile(File f) throws IOException {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    deleteFile(kid);
                }
            }
        }
        if (f.exists()) {
            try {
                Files.delete(f.toPath());
            } catch (NoSuchFileException e) {
                // 已删除则忽略
            } catch (IOException e) {
                if (!f.delete()) {
                    throw new IOException("Failed to delete '" + f.getAbsolutePath() + "'", e);
                }
            }
        }
    }
}
```

编译这段源码，把 `.class` 文件替换回 Gradle 缓存中的 `instrumented-apkzlib-*.jar` 即可。
