// 服务端日志：六级、多行格式、终端 ANSI 着色、全英文。
// 格式: [YYYY-MM-DD HH:MM:SS LEVEL] summary
//       - field: value
// 规则: 一条日志占多行；字段每行一个；无空值占位；ERROR/CRITICAL 附 error.type/message/stack。
// 着色只影响终端展示，不写文件；本服务端只写终端与 stderr，便于采集。
#ifndef FR_SERVER_LOGGER_H
#define FR_SERVER_LOGGER_H

#include <chrono>
#include <cstdio>
#include <ctime>
#include <string>
#include <utility>
#include <vector>

namespace frlog {

enum Level { TRACE = 0, DEBUG = 1, INFO = 2, WARN = 3, ERROR = 4, CRITICAL = 5 };

const char* levelName(Level lv) {
    switch (lv) {
        case TRACE: return "TRACE";
        case DEBUG: return "DEBUG";
        case INFO:  return "INFO";
        case WARN:  return "WARN";
        case ERROR: return "ERROR";
        case CRITICAL: return "CRITICAL";
    }
    return "INFO";
}

// ANSI 颜色：TRACE/DEBUG 灰，INFO 绿，WARN 黄，ERROR 红，CRITICAL 红底白字加粗。
const char* levelColor(Level lv) {
    switch (lv) {
        case TRACE: case DEBUG: return "\033[90m";
        case INFO:  return "\033[32m";
        case WARN:  return "\033[33m";
        case ERROR: return "\033[31m";
        case CRITICAL: return "\033[1;41;97m";
    }
    return "";
}

// 单条日志条目：首行概要 + 若干字段；字段值不允许为空。
struct Entry {
    Level level = INFO;
    std::string summary;
    // 有序字段列表，避免 unordered_map 造成字段顺序不稳定。
    std::vector<std::pair<std::string, std::string> > fields;
    std::string traceId;
    std::string service = "forest-racer-server";
    std::string module;
    int pid = 0;
    // 错误三件套（ERROR/CRITICAL 必填）。
    std::string errorType;
    std::string errorMessage;
    std::string errorStack;
};

class Logger {
public:
    // minLevel 以下不输出；color 控制 ANSI；pid 由进程注入。
    explicit Logger(Level minLevel = INFO, bool color = true, int pid = 0)
        : minLevel_(minLevel), color_(color), pid_(pid) {}

    void setMinLevel(Level lv) { minLevel_ = lv; }
    void setColor(bool enabled) { color_ = enabled; }
    Level minLevel() const { return minLevel_; }

    void log(const Entry& e) {
        if (e.level < minLevel_) return;
        char buf[32];
        std::snprintf(buf, sizeof(buf), "%d", pid_);
        // 必带字段：timestamp, level, trace_id, service, module, pid, message。
        std::string line = "[" + timestamp() + " " + levelName(e.level) + "] " + e.summary + "\n";
        line += "- timestamp: " + isoTimestamp() + "\n";
        line += "- level: " + std::string(levelName(e.level)) + "\n";
        if (!e.traceId.empty()) line += "- trace_id: " + e.traceId + "\n";
        line += "- service: " + e.service + "\n";
        if (!e.module.empty()) line += "- module: " + e.module + "\n";
        line += "- pid: " + std::string(buf) + "\n";
        line += "- message: " + e.summary + "\n";
        for (size_t i = 0; i < e.fields.size(); ++i) {
            const std::pair<std::string, std::string>& f = e.fields[i];
            if (f.first.empty() || f.second.empty()) continue;
            line += "- " + f.first + ": " + f.second + "\n";
        }
        if (e.level >= ERROR) {
            line += "- error.type: " + (e.errorType.empty() ? "unknown" : e.errorType) + "\n";
            line += "- error.message: " + (e.errorMessage.empty() ? "unspecified" : e.errorMessage) + "\n";
            line += "- error.stack: " + (e.errorStack.empty() ? "unavailable" : e.errorStack) + "\n";
        }
        if (color_) {
            std::fputs(levelColor(e.level), stderr);
            std::fputs(line.c_str(), stderr);
            std::fputs("\033[0m", stderr);
        } else {
            std::fputs(line.c_str(), stderr);
        }
        std::fflush(stderr);
    }

    // 便捷构造：概要 + 若干 "key=value" 字段（内部拆成多行，禁止用 | 拼接）。
    void simple(Level lv, const std::string& module, const std::string& traceId,
                const std::string& summary, const std::vector<std::string>& fields) {
        Entry e;
        e.level = lv;
        e.module = module;
        e.traceId = traceId;
        e.summary = summary;
        for (size_t i = 0; i < fields.size(); ++i) {
            const std::string& kv = fields[i];
            size_t eq = kv.find('=');
            if (eq == std::string::npos) continue;
            e.fields.push_back(std::make_pair(kv.substr(0, eq), kv.substr(eq + 1)));
        }
        log(e);
    }

    // 浮点日志统一两位小数，调用方须在 value 字符串里写单位。
    static std::string f2(float v, const char* unit) {
        char buf[64];
        std::snprintf(buf, sizeof(buf), "%.2f %s", static_cast<double>(v), unit);
        return std::string(buf);
    }

private:
    static std::string timestamp() {
        std::time_t t = std::time(NULL);
        std::tm tm;
        localtime_r(&t, &tm);
        char buf[64];
        std::snprintf(buf, sizeof(buf), "%04d-%02d-%02d %02d:%02d:%02d",
                      tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday,
                      tm.tm_hour, tm.tm_min, tm.tm_sec);
        return std::string(buf);
    }
    static std::string isoTimestamp() {
        std::chrono::system_clock::time_point now = std::chrono::system_clock::now();
        std::time_t t = std::chrono::system_clock::to_time_t(now);
        int ms = static_cast<int>(
            std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count() % 1000);
        std::tm tm;
        localtime_r(&t, &tm);
        char buf[64];
        std::snprintf(buf, sizeof(buf), "%04d-%02d-%02dT%02d:%02d:%02d.%03d+08:00",
                      tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday,
                      tm.tm_hour, tm.tm_min, tm.tm_sec, ms);
        return std::string(buf);
    }

    Level minLevel_;
    bool color_;
    int pid_;
};

} // namespace frlog

#endif // FR_SERVER_LOGGER_H
