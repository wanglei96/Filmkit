#!/usr/bin/env python3
"""HDR 能力探针（adb）：显示 / 编码 / 系统属性，输出待人工验证的清单。

无设备连接时只打印"需要你做的事"，不假装有结论。

用法：python3 tools/hdr-probe.py [--out docs/reports/hdr-probe-REPORT.md]
"""
import argparse
import os
import re
import shutil
import subprocess


def find_adb():
    for p in (shutil.which('adb'),
              os.path.expanduser('~/Library/Android/sdk/platform-tools/adb')):
        if p and os.path.exists(p):
            return p
    return None


def run(adb, *args, timeout=25):
    try:
        r = subprocess.run([adb, *args], capture_output=True, text=True, timeout=timeout)
        return (r.stdout or '') + (r.stderr or '')
    except Exception as e:  # 设备掉线/超时都按空处理
        return f'[probe error] {e}'


def grep(text, pattern, ctx=2):
    """返回匹配行 ± ctx 行（保留上下文，便于人读）。"""
    lines = text.splitlines()
    out = []
    rx = re.compile(pattern, re.I)
    for i, line in enumerate(lines):
        if rx.search(line):
            out.extend(lines[max(0, i - ctx):min(len(lines), i + ctx + 1)])
            out.append('---')
    return '\n'.join(out) if out else '(无匹配)'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default='docs/reports/hdr-probe-REPORT.md')
    args = ap.parse_args()

    adb = find_adb()
    lines = ['# HDR 能力探针报告\n\n']
    if adb is None:
        lines.append('未找到 adb。请安装 Android SDK platform-tools，或把 adb 加入 PATH。\n')
        print(''.join(lines))
        return

    devices = run(adb, 'devices').strip()
    lines.append('## 设备\n\n```\n%s\n```\n\n' % devices)
    if 'device' not in devices.replace('List of devices attached', ''):
        lines.append('**没有已连接设备** ⇒ 只能给出待办清单，不能给结论。\n\n')
        lines.append(manual_todo())
        _write(args.out, lines)
        print(''.join(lines))
        return

    model = run(adb, 'shell', 'getprop', 'ro.product.model').strip()
    release = run(adb, 'shell', 'getprop', 'ro.build.version.release').strip()
    sdk = run(adb, 'shell', 'getprop', 'ro.build.version.sdk').strip()
    lines.append('| 项 | 值 |\n|---|---|\n| 型号 | %s |\n| Android | %s (SDK %s) |\n\n'
                 % (model, release, sdk))

    display = run(adb, 'shell', 'dumpsys', 'display')
    lines.append('## 1. 显示 HDR 能力\n\n```\n%s\n```\n\n'
                 % grep(display, r'hdr|hdrCapabilities|supportedHdrTypes|peakRefreshRate', 3))

    codec = run(adb, 'shell', 'cmd', 'media.codec', 'list')
    if 'probe error' in codec or not codec.strip():
        codec = run(adb, 'shell', 'dumpsys', 'media.codec')
    lines.append('## 2. 编码器 HDR（HEVC Main10 / HDR profiles）\n\n```\n%s\n```\n\n'
                 % grep(codec, r'hevc|h\.265|main10|hdr|profile', 2))

    props = run(adb, 'shell', 'getprop')
    lines.append('## 3. 系统 HDR 属性\n\n```\n%s\n```\n\n'
                 % grep(props, r'hdr|hdr10|dolby|pq|hlg', 1))

    lines.append('## 4. 待人工验证（脚本无法替代）\n\n')
    lines.append(manual_todo())

    _write(args.out, lines)
    print(''.join(lines))


def manual_todo():
    return (
        '1. **系统相册**：把一张 HDR HEIC（或 HDR JPEG gainmap）拷进相册，看是否正常显示、'
        '是否亮部更亮、有无发灰/过曝。\n'
        '2. **微信**：同图发送到文件传输助手/朋友圈，看是否被转成 SDR、有无偏色。\n'
        '3. **Windows / macOS**：同图在桌面端打开，确认色彩/亮度。\n'
        '4. **App 内回看**：拍一张 HEIC 后在 App 编辑器里打开（期 A 已知缺口：'
        '`ui/ImageDecode.kt` 对 HEIC 无特判）。\n'
        '5. **gainmap**：确认系统/相册是否支持 Android Ultra HDR / ISO gain map；'
        '若不支持，HDR 交付只能走 PQ/HLG 单层。\n'
        '6. **内容端**：拍一张窗/灯的高动态范围 RAW DNG（固定机位），供 HDR 内容链评估。\n'
    )


def _write(path, lines):
    os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(''.join(lines))


if __name__ == '__main__':
    main()
