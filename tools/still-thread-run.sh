#!/usr/bin/env bash
# 拍照独立线程真机验收（v0.10.85）：把"装包 / 清日志 / 拉日志+分析 / 内存"四步
# 各压成一条命令，省掉手敲长路径。
#
#   用法： tools/still-thread-run.sh install [apk]   # 装包并清空设备日志
#          tools/still-thread-run.sh pull            # 拉回日志并跑验收分析
#          tools/still-thread-run.sh mem             # 取内存（开关前后各一次对比）
#          tools/still-thread-run.sh logs            # 只看设备上有哪些日志文件
#
# 验收动作本身（关/开开关各拍一张、拍完立刻挪手机）仍需手动：
#   1) 设置页「拍照独立渲染线程」关 → 拍 1 张
#   2) 打开该开关 → 拍 1 张，拍完立刻挪手机（看预览是否还冻）；冷机连拍 3 张
#   3) 再打开「独立线程 A/B 诊断」→ 拍 1 张（同一帧数据跑两遍，验画质等价）
#   4) tools/still-thread-run.sh pull
# pull 会自动按 settings.still_thread 的切换点分组，输出「关 vs 开」的对比
# （预览停更中位/最大、burst 各路耗时中位、CRC 等价性、落盘计数）。
set -uo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=com.filmkit.app
DEV_LOGS="/storage/emulated/0/Android/data/$PKG/files/logs"
OUT="${OUT:-/tmp/filmkit-logs}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"

die() { echo "错误：$*" >&2; exit 1; }
need_dev() {
  "$ADB" get-state >/dev/null 2>&1 || die "没有设备（adb devices 为空；先连 USB / 开无线调试）"
}

case "${1:-}" in
  install)
    need_dev
    APK="${2:-$HERE/filmkit-0.10.85-m4.apk}"
    [ -f "$APK" ] || die "找不到 APK：$APK"
    "$ADB" install -r "$APK" || die "安装失败"
    "$ADB" shell rm -f "$DEV_LOGS"/*.jsonl 2>/dev/null
    echo "已装 $(basename "$APK")，设备日志已清空 —— 现在按上面 1)~3) 拍照"
    ;;
  logs)
    need_dev
    "$ADB" shell ls -l "$DEV_LOGS"
    ;;
  pull)
    need_dev
    rm -rf "$OUT"; mkdir -p "$OUT"
    "$ADB" pull "$DEV_LOGS" "$OUT" >/dev/null || die "拉日志失败"
    # 设备目录里可能还有 audit/ 等子目录，分析脚本只认 JSONL 行，多拉了也无害
    echo "已拉到 $OUT"
    # 分析前先跑 JSON 守卫（check-log-json 只收文件，这里把目录展开）
    find "$OUT" -type f \( -name "*.txt" -o -name "*.jsonl" \) -print0 \
      | xargs -0 python3 "$HERE/tools/check-log-json.py" || true
    python3 "$HERE/tools/still-thread-verify.py" "$OUT"
    ;;
  mem)
    need_dev
    "$ADB" shell dumpsys meminfo "$PKG" | head -22
    ;;
  *)
    sed -n '2,20p' "$0"
    exit 2
    ;;
esac
