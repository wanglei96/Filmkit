#include "Logger.h"

#include <android/log.h>

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>

#include <vector>

namespace filmkit {

namespace {
constexpr const char* kTag = "filmkit";

std::string timestampIso8601() {
  using namespace std::chrono;
  const auto now = system_clock::now();
  const time_t secs = system_clock::to_time_t(now);
  const auto ms = duration_cast<milliseconds>(now.time_since_epoch()).count() % 1000;
  tm t{};
  localtime_r(&secs, &t);
  char buf[40];
  const long gmtoff = t.tm_gmtoff;
  const long off_min = std::labs(gmtoff) / 60;
  snprintf(buf, sizeof(buf), "%04d-%02d-%02dT%02d:%02d:%02d.%03lld%c%02ld:%02ld",
           t.tm_year + 1900, t.tm_mon + 1, t.tm_mday, t.tm_hour, t.tm_min,
           t.tm_sec, static_cast<long long>(ms), gmtoff >= 0 ? '+' : '-',
           off_min / 60, off_min % 60);
  return buf;
}

std::string trim(const std::string& s) {
  size_t b = 0, e = s.size();
  while (b < e && std::isspace(static_cast<unsigned char>(s[b]))) ++b;
  while (e > b && std::isspace(static_cast<unsigned char>(s[e - 1]))) --e;
  return s.substr(b, e - b);
}
}  // namespace

std::string jsonEscape(const std::string& s) {
  std::string out;
  out.reserve(s.size() + 8);
  for (const char ch : s) {
    switch (ch) {
      case '"': out += "\\\""; break;
      case '\\': out += "\\\\"; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      case '\t': out += "\\t"; break;
      default:
        if (static_cast<unsigned char>(ch) < 0x20) {
          char buf[8];
          snprintf(buf, sizeof(buf), "\\u%04x", ch);
          out += buf;
        } else {
          out += ch;
        }
    }
  }
  return out;
}

Logger& Logger::instance() {
  static Logger inst;
  return inst;
}

const char* Logger::levelName(LogLevel level) {
  switch (level) {
    case LogLevel::kDebug: return "debug";
    case LogLevel::kInfo: return "info";
    case LogLevel::kWarn: return "warn";
    case LogLevel::kError: return "error";
  }
  return "info";
}

// 日志目录（init 设定；空 = 目录不可写/仅内存模式）。
// v0.10.163：诊断 dump 复用同一目录（<dir>/diag），不再另开一套路径下发通道。
std::string Logger::dir() const {
  std::lock_guard<std::mutex> lk(mu_);
  return dir_;
}

bool Logger::init(const std::string& dir) {
  std::lock_guard<std::mutex> lk(mu_);
  dir_ = dir;
  file_ = nullptr;
  file_bytes_ = 0;
  day_.clear();
  seq_ = 0;
  return true;  // 惰性打开：首次写入时创建文件
}

void Logger::rollIfNeededLocked(size_t incoming) {
  char daybuf[16];
  {
    const time_t now = time(nullptr);
    tm t{};
    localtime_r(&now, &t);
    snprintf(daybuf, sizeof(daybuf), "%04d%02d%02d", t.tm_year + 1900,
             t.tm_mon + 1, t.tm_mday);
  }
  const std::string today = daybuf;

  const bool needRoll = !file_ || today != day_ ||
                        file_bytes_ + incoming > kRollBytes;
  if (!needRoll) return;

  if (file_) {
    fclose(file_);
    file_ = nullptr;
  }
  if (today != day_) {
    seq_ = 0;
    day_ = today;
  } else {
    ++seq_;
  }

  dir_ = dir_.empty() ? "." : dir_;
  mkdir(dir_.c_str(), 0755);
  current_path_ = newLogPathLocked();
  file_ = fopen(current_path_.c_str(), "a");
  file_bytes_ = 0;
  if (file_) pruneOldFilesLocked();
}

std::string Logger::newLogPathLocked() const {
  char buf[512];
  if (seq_ > 0) {
    snprintf(buf, sizeof(buf), "%s/filmkit-engine-%s-%03d.log",
             dir_.c_str(), day_.c_str(), seq_);
  } else {
    snprintf(buf, sizeof(buf), "%s/filmkit-engine-%s.log", dir_.c_str(),
             day_.c_str());
  }
  return buf;
}

void Logger::pruneOldFilesLocked() const {
  DIR* d = opendir(dir_.c_str());
  if (!d) return;
  std::vector<std::string> files;
  while (const dirent* ent = readdir(d)) {
    const std::string name = ent->d_name;
    if (name.rfind("filmkit-engine-", 0) == 0 &&
        name.size() > 4 && name.compare(name.size() - 4, 4, ".log") == 0) {
      files.push_back(name);
    }
  }
  closedir(d);
  if (files.size() <= static_cast<size_t>(kKeepFiles)) return;
  std::sort(files.begin(), files.end());  // 文件名含日期序号，字典序即时间序
  for (size_t i = 0; i + kKeepFiles < files.size(); ++i) {
    std::string full = dir_ + "/" + files[i];
    unlink(full.c_str());
  }
}

void Logger::log(LogLevel level, const std::string& module,
                 const std::string& event, const std::string& kv_json) {
  std::string kv = trim(kv_json);
  if (kv.empty()) kv = "{}";
  if (kv.front() != '{') {
    kv = "{\"value\":\"" + jsonEscape(kv) + "\"}";
  }

  std::string line;
  line.reserve(96 + kv.size());
  line += "{\"ts\":\"" + timestampIso8601() + "\"";
  line += ",\"level\":\"" + std::string(levelName(level)) + "\"";
  line += ",\"module\":\"" + jsonEscape(module) + "\"";
  line += ",\"event\":\"" + jsonEscape(event) + "\"";
  line += ",\"kv\":" + kv + "}\n";

  __android_log_print(level == LogLevel::kError ? ANDROID_LOG_ERROR
                        : level == LogLevel::kWarn ? ANDROID_LOG_WARN
                        : level == LogLevel::kDebug ? ANDROID_LOG_DEBUG
                                                    : ANDROID_LOG_INFO,
                      kTag, "%s", line.c_str());

  std::lock_guard<std::mutex> lk(mu_);
  ring_.push_back(line);
  while (ring_.size() > kRingCapacity) ring_.pop_front();

  rollIfNeededLocked(line.size());
  if (file_) {
    if (fwrite(line.data(), 1, line.size(), file_) == line.size()) {
      file_bytes_ += line.size();
    } else {
      ++dropped_;
      fclose(file_);
      file_ = nullptr;
    }
  } else {
    ++dropped_;
  }
}

std::string Logger::recentLines(int max_lines) const {
  std::lock_guard<std::mutex> lk(mu_);
  std::string out;
  const size_t n = ring_.size();
  const size_t first = max_lines <= 0 ? 0
                                      : n > static_cast<size_t>(max_lines)
                                            ? n - static_cast<size_t>(max_lines)
                                            : 0;
  for (size_t i = first; i < n; ++i) out += ring_[i];
  return out;
}

bool Logger::flush() {
  std::lock_guard<std::mutex> lk(mu_);
  return file_ ? fflush(file_) == 0 : false;
}

size_t Logger::droppedCount() const {
  std::lock_guard<std::mutex> lk(mu_);
  return dropped_;
}

}  // namespace filmkit
