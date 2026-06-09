# Notice Web UI

Vite + Svelte 5 SPA，嵌入 Notice Server Go 二进制中。

## 开发

```bash
npm install           # 安装依赖
npm run dev           # 启动开发服务器 :5173（API 代理到 :9090）
npm run build         # 生产构建到 dist/
```

## 构建产物

`npm run build` 输出到 `dist/`，Go 端通过 `//go:embed web/dist/*` 嵌入。
