package logger

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"
)

// Config 日志配置
type Config struct {
	ConsoleLevel   string        // console 日志级别: debug, info, warn, error, off
	FileLevel      string        // 文件日志级别: debug, info, warn, error, off
	FilePath       string        // 日志文件路径，为空则不写入文件
	Pretty         bool          // console 是否美化输出
	FileBufferSize int           // 文件缓冲区大小（字节），0 表示不缓冲，默认 4096
	FlushInterval  time.Duration // 自动刷新间隔，默认 5 秒
	RotateDays     int           // 日志轮转天数，0 表示不轮转，默认 1
	MaxFiles       int           // 保留的日志文件数量，0 表示不限制，默认 7
}

var (
	defaultLogger *slog.Logger
	fileWriter    *rotatingFileWriter
)

// rotatingFileWriter 支持日志轮转的文件写入器
type rotatingFileWriter struct {
	basePath       string        // 基础日志路径（如 logs/app.log）
	rotateDays     int           // 轮转天数
	maxFiles       int           // 保留文件数
	bufferSize     int           // 缓冲区大小
	file           *os.File      // 当前文件
	writer         *bufio.Writer // 缓冲写入器
	nextRotateTime int64         // 下次轮转的 Unix 时间戳
	mu             sync.Mutex
}

func newRotatingFileWriter(basePath string, rotateDays, maxFiles, bufferSize int) (*rotatingFileWriter, error) {
	w := &rotatingFileWriter{
		basePath:   basePath,
		rotateDays: rotateDays,
		maxFiles:   maxFiles,
		bufferSize: bufferSize,
	}

	if err := w.openFile(); err != nil {
		return nil, err
	}

	return w, nil
}

// getLogFilePath 获取当前应该使用的日志文件路径
func (w *rotatingFileWriter) getLogFilePath() string {
	if w.rotateDays <= 0 {
		return w.basePath
	}

	dir := filepath.Dir(w.basePath)
	ext := filepath.Ext(w.basePath)
	base := strings.TrimSuffix(filepath.Base(w.basePath), ext)

	// 格式: app-2026-01-08.log
	date := time.Now().Format("2006-01-02")
	return filepath.Join(dir, fmt.Sprintf("%s-%s%s", base, date, ext))
}

// calcNextRotateTime 计算下次轮转时间（明天零点）
func (w *rotatingFileWriter) calcNextRotateTime() int64 {
	if w.rotateDays <= 0 {
		return 0
	}
	now := time.Now()
	// 计算明天零点
	tomorrow := time.Date(now.Year(), now.Month(), now.Day()+1, 0, 0, 0, 0, now.Location())
	return tomorrow.Unix()
}

// openFile 打开或创建日志文件
func (w *rotatingFileWriter) openFile() error {
	logPath := w.getLogFilePath()

	// 确保目录存在
	dir := filepath.Dir(logPath)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return err
	}

	file, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err != nil {
		return err
	}

	w.file = file
	w.writer = bufio.NewWriterSize(file, w.bufferSize)
	w.nextRotateTime = w.calcNextRotateTime()

	return nil
}

// rotate 检查并执行轮转（使用时间戳比较，避免每次格式化时间）
func (w *rotatingFileWriter) rotate() error {
	if w.rotateDays <= 0 || w.nextRotateTime == 0 {
		return nil
	}

	// 快速时间戳比较
	if time.Now().Unix() < w.nextRotateTime {
		return nil
	}

	// 需要轮转
	if err := w.writer.Flush(); err != nil {
		return err
	}
	if err := w.file.Close(); err != nil {
		return err
	}

	// 打开新文件
	if err := w.openFile(); err != nil {
		return err
	}

	// 清理旧文件
	go w.cleanOldFiles()

	return nil
}

// cleanOldFiles 清理旧日志文件
func (w *rotatingFileWriter) cleanOldFiles() {
	if w.maxFiles <= 0 {
		return
	}

	dir := filepath.Dir(w.basePath)
	ext := filepath.Ext(w.basePath)
	base := strings.TrimSuffix(filepath.Base(w.basePath), ext)
	pattern := filepath.Join(dir, base+"-*"+ext)

	files, err := filepath.Glob(pattern)
	if err != nil || len(files) <= w.maxFiles {
		return
	}

	// 按修改时间排序（旧的在前）
	sort.Slice(files, func(i, j int) bool {
		fi, _ := os.Stat(files[i])
		fj, _ := os.Stat(files[j])
		if fi == nil || fj == nil {
			return files[i] < files[j]
		}
		return fi.ModTime().Before(fj.ModTime())
	})

	// 删除多余的旧文件
	for i := 0; i < len(files)-w.maxFiles; i++ {
		os.Remove(files[i])
	}
}

func (w *rotatingFileWriter) Write(p []byte) (n int, err error) {
	w.mu.Lock()
	defer w.mu.Unlock()

	// 检查是否需要轮转
	if err := w.rotate(); err != nil {
		return 0, err
	}

	return w.writer.Write(p)
}

func (w *rotatingFileWriter) Flush() error {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.writer.Flush()
}

func (w *rotatingFileWriter) Close() error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if err := w.writer.Flush(); err != nil {
		return err
	}
	return w.file.Close()
}

// Init 初始化日志系统
func Init(cfg Config) (*slog.Logger, error) {
	var handlers []slog.Handler

	// 默认值
	if cfg.FileBufferSize == 0 {
		cfg.FileBufferSize = 4096
	}
	if cfg.FlushInterval == 0 {
		cfg.FlushInterval = 5 * time.Second
	}

	// Console Handler（级别为 off 时不创建）
	consoleLevel := parseLevel(cfg.ConsoleLevel, slog.LevelInfo)
	if consoleLevel < LevelOff {
		var consoleHandler slog.Handler
		if cfg.Pretty {
			consoleHandler = newPrettyHandler(os.Stdout, consoleLevel)
		} else {
			consoleHandler = slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
				Level:     consoleLevel,
				AddSource: true,
			})
		}
		handlers = append(handlers, consoleHandler)
	}

	// File Handler（级别为 off 或路径为空时不创建）
	fileLevel := parseLevel(cfg.FileLevel, slog.LevelDebug)
	if cfg.FilePath != "" && fileLevel < LevelOff {
		// 默认值
		rotateDays := cfg.RotateDays
		if rotateDays == 0 {
			rotateDays = 1 // 默认每天轮转
		}
		maxFiles := cfg.MaxFiles
		if maxFiles == 0 {
			maxFiles = 7 // 默认保留 7 个文件
		}

		var err error
		fileWriter, err = newRotatingFileWriter(cfg.FilePath, rotateDays, maxFiles, cfg.FileBufferSize)
		if err != nil {
			return nil, err
		}

		// 启动自动刷新
		go func() {
			ticker := time.NewTicker(cfg.FlushInterval)
			defer ticker.Stop()
			for range ticker.C {
				if fileWriter != nil {
					fileWriter.Flush()
				}
			}
		}()

		fileHandler := slog.NewJSONHandler(fileWriter, &slog.HandlerOptions{
			Level:     fileLevel,
			AddSource: true,
		})
		handlers = append(handlers, fileHandler)
	}

	// 多输出 Handler
	var handler slog.Handler
	if len(handlers) == 1 {
		handler = handlers[0]
	} else {
		handler = &multiHandler{handlers: handlers}
	}

	defaultLogger = slog.New(handler)
	slog.SetDefault(defaultLogger)

	return defaultLogger, nil
}

// Flush 刷新文件缓冲区
func Flush() error {
	if fileWriter != nil {
		return fileWriter.Flush()
	}
	return nil
}

// Close 关闭日志文件
func Close() error {
	if fileWriter != nil {
		return fileWriter.Close()
	}
	return nil
}

// prettyHandler 美化输出 Handler
type prettyHandler struct {
	level  slog.Level
	out    io.Writer
	mu     sync.Mutex
	attrs  []slog.Attr
	groups []string
}

func newPrettyHandler(out io.Writer, level slog.Level) *prettyHandler {
	return &prettyHandler{
		level: level,
		out:   out,
	}
}

func (h *prettyHandler) Enabled(_ context.Context, level slog.Level) bool {
	return level >= h.level
}

func (h *prettyHandler) Handle(_ context.Context, r slog.Record) error {
	// 时间
	timeStr := r.Time.Format("15:04:05")

	// 级别
	levelStr := levelString(r.Level)

	// 源码位置
	var source string
	if r.PC != 0 {
		frames := runtime.CallersFrames([]uintptr{r.PC})
		frame, _ := frames.Next()
		source = filepath.Base(frame.File) + ":" + itoa(frame.Line)
	}

	// 构建输出
	h.mu.Lock()
	defer h.mu.Unlock()

	// 格式: 10:15:21 INFO source > message key=value
	fmt.Fprintf(h.out, "%s %s %s > %s", timeStr, levelStr, source, r.Message)

	// 输出预设属性
	for _, a := range h.attrs {
		fmt.Fprintf(h.out, " %s=%v", a.Key, a.Value.Any())
	}

	// 输出记录属性
	r.Attrs(func(a slog.Attr) bool {
		fmt.Fprintf(h.out, " %s=%v", a.Key, a.Value.Any())
		return true
	})

	fmt.Fprintln(h.out)
	return nil
}

func (h *prettyHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	newAttrs := make([]slog.Attr, len(h.attrs)+len(attrs))
	copy(newAttrs, h.attrs)
	copy(newAttrs[len(h.attrs):], attrs)
	return &prettyHandler{
		level:  h.level,
		out:    h.out,
		attrs:  newAttrs,
		groups: h.groups,
	}
}

func (h *prettyHandler) WithGroup(name string) slog.Handler {
	newGroups := make([]string, len(h.groups)+1)
	copy(newGroups, h.groups)
	newGroups[len(h.groups)] = name
	return &prettyHandler{
		level:  h.level,
		out:    h.out,
		attrs:  h.attrs,
		groups: newGroups,
	}
}

func levelString(level slog.Level) string {
	switch {
	case level >= slog.LevelError:
		return "ERROR"
	case level >= slog.LevelWarn:
		return "WARN "
	case level >= slog.LevelInfo:
		return "INFO "
	default:
		return "DEBUG"
	}
}

// multiHandler 多输出 Handler
type multiHandler struct {
	handlers []slog.Handler
}

func (h *multiHandler) Enabled(ctx context.Context, level slog.Level) bool {
	for _, handler := range h.handlers {
		if handler.Enabled(ctx, level) {
			return true
		}
	}
	return false
}

func (h *multiHandler) Handle(ctx context.Context, record slog.Record) error {
	for _, handler := range h.handlers {
		if handler.Enabled(ctx, record.Level) {
			if err := handler.Handle(ctx, record); err != nil {
				return err
			}
		}
	}
	return nil
}

func (h *multiHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	handlers := make([]slog.Handler, len(h.handlers))
	for i, handler := range h.handlers {
		handlers[i] = handler.WithAttrs(attrs)
	}
	return &multiHandler{handlers: handlers}
}

func (h *multiHandler) WithGroup(name string) slog.Handler {
	handlers := make([]slog.Handler, len(h.handlers))
	for i, handler := range h.handlers {
		handlers[i] = handler.WithGroup(name)
	}
	return &multiHandler{handlers: handlers}
}

// LevelOff 关闭日志输出
const LevelOff = slog.Level(100)

// parseLevel 解析日志级别字符串
func parseLevel(level string, defaultLevel slog.Level) slog.Level {
	switch strings.ToLower(level) {
	case "off", "none", "disabled":
		return LevelOff
	case "debug":
		return slog.LevelDebug
	case "info":
		return slog.LevelInfo
	case "warn", "warning":
		return slog.LevelWarn
	case "error":
		return slog.LevelError
	default:
		return defaultLevel
	}
}

// itoa 简单的整数转字符串
func itoa(i int) string {
	if i < 0 {
		return "-" + itoa(-i)
	}
	if i < 10 {
		return string(rune('0' + i))
	}
	return itoa(i/10) + string(rune('0'+i%10))
}

// Get 获取默认日志器
func Get() *slog.Logger {
	return defaultLogger
}

// 全局便捷方法 (使用 LogAttrs 跳过包装层，获取正确的调用位置)
func Debug(msg string, args ...any) { logWithCaller(slog.LevelDebug, msg, args...) }
func Info(msg string, args ...any)  { logWithCaller(slog.LevelInfo, msg, args...) }
func Warn(msg string, args ...any)  { logWithCaller(slog.LevelWarn, msg, args...) }
func Error(msg string, args ...any) { logWithCaller(slog.LevelError, msg, args...) }

// logWithCaller 记录日志并正确获取调用者位置
func logWithCaller(level slog.Level, msg string, args ...any) {
	if !defaultLogger.Enabled(context.Background(), level) {
		return
	}
	var pcs [1]uintptr
	runtime.Callers(3, pcs[:]) // 跳过 Callers, logWithCaller, Debug/Info/Warn/Error
	r := slog.NewRecord(time.Now(), level, msg, pcs[0])
	r.Add(args...)
	_ = defaultLogger.Handler().Handle(context.Background(), r)
}

// With 创建带属性的子日志器
func With(args ...any) *slog.Logger {
	return defaultLogger.With(args...)
}

func init() {
	// 设置默认日志器（在 Init 之前使用）
	defaultLogger = slog.Default()
}
