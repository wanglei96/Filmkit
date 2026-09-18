#pragma once

#include <cstdio>
#include <deque>
#include <mutex>
#include <string>

namespace filmkit {

enum class LogLevel : int { kDebug = 0, kInfo = 1, kWarn = 2, kError = 3 };

// JSONL 结构化日志 v1（docs/开发计划.md §8）：
//   行格式 {"ts":"...","level":"info","module":"engine","event":"x","kv":{...}}
//   内存环形缓冲（最近 500 行，供 UI 实时查看）+ 滚动文件（按天，单文件 2MB，
//   保留最近 7 个）。线程安全；目录不可写时退化为仅内存模式并计数 dropped。
class Logger {
 public:
  static Logger& instance();

  // dir 必须已存在且可写；返回 false 表示文件不可用（仅内存模式）。
  bool init(const std::string& dir);
  void log(LogLevel level, const std::string& module, const std::string& event,
           const std::string& kv_json = "{}");
  // 最近 max_lines 行，JSONL，按时间序，'\n' 连接。
  std::string recentLines(int max_lines) const;
  bool flush();

  size_t droppedCount() const;

  static const char* levelName(LogLevel level);

 private:
  Logger() = default;

  void rollIfNeededLocked(size_t incoming);
  std::string newLogPathLocked() const;
  void pruneOldFilesLocked() const;

  mutable std::mutex mu_;
  std::deque<std::string> ring_;
  static constexpr size_t kRingCapacity = 500;

  std::string dir_;
  std::string current_path_;
  FILE* file_ = nullptr;
  size_t file_bytes_ = 0;
  static constexpr size_t kRollBytes = 2 * 1024 * 1024;
  static constexpr int kKeepFiles = 7;
  std::string day_;
  int seq_ = 0;
  size_t dropped_ = 0;
};

// JSON 字符串转义（含引号、反斜杠与控制字符）。
std::string jsonEscape(const std::string& s);

}  // namespace filmkit
