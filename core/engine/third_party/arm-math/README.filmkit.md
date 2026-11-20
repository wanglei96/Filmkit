# Arm Vector Math

Unmodified subset of Arm Optimized Routines, revision
`4be260a5117480382690c6d8c300bc784e927d76`.
Upstream: https://github.com/ARM-software/optimized-routines

Only FP64 AdvSIMD exp10, pow, atan2, hypot, log1p, expm1 and their dependency
headers/tables are included.
The original LICENSE is retained. This backend is used by Spektrafilm only;
it does not change compile flags or arithmetic in the existing RAW pipeline.
