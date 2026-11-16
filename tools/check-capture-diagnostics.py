#!/usr/bin/env python3
"""Guard diagnostic independence from user-visible DNG output and GL timer enums.
The Android full-renderer regression is tools/check-renderer-device.cpp.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
controller = (root / 'app/src/main/java/com/filmkit/app/camera/CameraController.kt').read_text()
start = controller.index('        logRawMeta(dngImg, dngResult, meta)')
end = controller.index('        // 帧 0 拷贝并 close', start)
block = controller[start:end]
assert 'if (dngOn && ch != null)' in block
assert 'if (burstDump' not in block
assert 'saveDng(im,' not in block
assert 'dumpBurstFrame(slot, buf' in controller  # raw diagnostics remain available privately

timer = (root / 'core/engine/src/log/GpuTimer.cpp').read_text()
assert '#include <GLES2/gl2ext.h>' in timer
assert '#define GL_GPU_DISJOINT_EXT' not in timer
assert 'glGetIntegerv(GL_GPU_DISJOINT_EXT, &disjoint)' in timer
assert 'glGetIntegerv(0x8FBD' not in timer
renderer = (root / 'app/src/main/java/com/filmkit/app/camera/RenderClient.kt').read_text()
screen = (root / 'app/src/main/java/com/filmkit/app/ui/CameraScreen.kt').read_text()
assert 'pausePreview(' not in controller + renderer
assert 'resumePreviewNow(' not in controller + screen
assert 'resumeCover' not in screen
print('PASS diagnostics do not publish DNG without user opt-in; GPU enums use platform definitions')
print('PASS shutter animation does not suspend or drain live preview')
