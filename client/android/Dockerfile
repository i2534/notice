# Android SDK Docker 构建镜像
FROM eclipse-temurin:17-jdk-jammy

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=${ANDROID_SDK_ROOT}
ENV GRADLE_USER_HOME=/gradle-cache
ENV PATH=${PATH}:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools

# 安装依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
    wget \
    unzip \
    && rm -rf /var/lib/apt/lists/*

# 下载 Android Command Line Tools (使用稳定版本)
# 版本列表: https://developer.android.com/studio#command-line-tools-only
ARG CMDLINE_TOOLS_VERSION=11076708

RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools && \
    cd /tmp && \
    wget -q --show-progress https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip -O cmdline-tools.zip || \
    wget -q --show-progress https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip -O cmdline-tools.zip && \
    unzip -q cmdline-tools.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools && \
    mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest && \
    rm cmdline-tools.zip

# 接受许可证并安装 SDK 组件
RUN yes | sdkmanager --licenses && \
    sdkmanager \
    "platform-tools" \
    "platforms;android-35" \
    "build-tools;35.0.0"

WORKDIR /app

# 复制 Gradle 配置 (用于预下载依赖)
COPY gradle gradle
COPY --chmod=755 gradlew .
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY gradle.properties .
COPY app/build.gradle.kts app/build.gradle.kts

# 运行时执行构建 (源码和缓存通过 volume 挂载)
# Gradle 和依赖会在首次运行时下载到挂载的缓存目录
CMD ["./gradlew", "assembleDebug", "--no-daemon", "--console=plain", "--info"]
