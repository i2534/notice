# Tauri 应用构建镜像
# 用于交叉编译 Linux 版本

FROM rust:latest AS builder

# 安装 Tauri 依赖
RUN apt-get update && apt-get install -y \
    libgtk-3-dev \
    libwebkit2gtk-4.1-dev \
    libayatana-appindicator3-dev \
    librsvg2-dev \
    curl \
    xdg-utils \
    file \
    && rm -rf /var/lib/apt/lists/*

# 安装 Node.js
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y nodejs \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 复制项目文件并安装依赖 (包含 @tauri-apps/cli)
COPY package*.json ./
RUN npm install

COPY . .

# 构建参数
ARG VERSION=0.1.0

# 更新版本号
RUN sed -i "s/\"version\": \".*\"/\"version\": \"${VERSION}\"/" tauri/tauri.conf.json \
    && sed -i "s/^version = \".*\"/version = \"${VERSION}\"/" tauri/Cargo.toml

# 构建 Release 版本
RUN npx tauri build --config tauri/tauri.conf.json --no-bundle

# 打包阶段
RUN apt-get update && apt-get install -y zip \
    && mkdir -p /output \
    && cd /app/tauri/target/release \
    && zip -r /output/notice-gui-linux-amd64.zip notice-gui \
    && rm -rf /var/lib/apt/lists/*

# 输出阶段
FROM scratch AS export
COPY --from=builder /output/ /
