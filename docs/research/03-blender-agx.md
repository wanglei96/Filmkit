# Blender AgX Tone Mapping 完整算法 — 独立源码取证报告

> 任务性质：独立源码调研。每条结论附逐字证据（`文件路径:行号` + 原文，或 URL + 原文）。
> 标签约定：**【源码事实】** = 从本地 clone 的源码/配置文件逐字读到的；**【作者论述】** = 作者本人（Troy Sobotka / Eary Zhou）在仓库 README、PR 描述中的表述；**【推断】** = 我的推理，非直接证据；**【未找到】** = 明确检索后没有命中。
> 逐字原文一律不翻译（含注释、描述、报错）。

---

## 0. 取证环境、版本与 commit

### 0.1 Blender 仓库

```
$ cd /private/tmp/blender-agx/bsrc && git log -1 --format=%H
a7e89d18c15dbd688f532a24aca8352b94b9ace6
```

```
$ git log -1 --format="%H%n%ci%n%s"
a7e89d18c15dbd688f532a24aca8352b94b9ace6
2026-09-22 11:39:37 +0200
Fix #163744: Workbench: Wrong volume draw order
```

Blender 版本号（该文件不在 sparse checkout 内，用 `git show HEAD:<path>` 读取）：

```
$ git show HEAD:source/blender/blenkernel/BKE_blender_version.h | grep -n -E "define BLENDER_VERSION"
23:#define BLENDER_VERSION 503
25:#define BLENDER_VERSION_PATCH 0
27:#define BLENDER_VERSION_CYCLE alpha
```

**【源码事实】** `BLENDER_VERSION 503` + `BLENDER_VERSION_CYCLE alpha` ⇒ **Blender 5.3.0 alpha**（开发分支 HEAD，非发布版）。

clone 方式（本报告全部本地路径均基于此）：

```
git clone --depth 1 --filter=blob:none --sparse https://github.com/blender/blender.git bsrc
cd bsrc && git sparse-checkout set release/datafiles/colormanagement \
    source/blender/imbuf source/blender/gpu source/blender/editors \
    source/blender/sequencer source/blender/compositor intern
```

### 0.2 参考实现仓库（全部为本报告实际 clone 并逐字读取）

| 仓库 | commit | 日期 | 说明 |
|---|---|---|---|
| `sobotka/AgX` | `a66ad4b1e74952531d95634d60fe25de0025462e` | 2025-03-26 | 原始 AgX config |
| `sobotka/AgX-S2O3` | `7f329dd10b276e87c302d0fe3d4f565da922fb43` | 2025-07-01 | 作者的设计说明 + 生成器 |
| `sobotka/SB2383-Configuration-Generation` | `af3ef81a2eafdf06002cc20447e644d7ffedf082` | 2025-05-05 | 生成器（sigmoid / working space） |
| `EaryChow/AgX` | `74852bd3ca64e6592c2f6315fda4bcb1f8821ac2` | 2026-01-18 | Blender 实际采用的版本（README 自述 "sync with Blender main"） |

**【未找到】** `https://github.com/sobotka/AgX-Sandbox` 与 `https://github.com/sobotka/agx-base-research` 均已不存在：

```
$ git ls-remote --heads https://github.com/sobotka/AgX-Sandbox
remote: Repository not found.
fatal: repository 'https://github.com/sobotka/AgX-Sandbox/' not found
$ git ls-remote --heads https://github.com/sobotka/agx-base-research
remote: Repository not found.
fatal: repository 'https://github.com/sobotka/agx-base-research/' not found
```

`sobotka/AgX` 的 README 把设计理论指向另外两个仓库（见 §3.1）。

---

## 1. AgX 在 Blender 中的实现载体

### 1.1 结论（一句话）

**【源码事实】Blender 的 AgX 完全是 OpenColorIO v2 config（`release/datafiles/colormanagement/config.ocio`）+ 预先烘焙的 3D/1D LUT 文件；Blender 源码树中不存在任何 C++ / GLSL / OSL 形式的 AgX 算法实现。**

### 1.2 证据 A：全仓 grep，唯一源码命中是测试文件里的字符串

```
$ cd /private/tmp/blender-agx/bsrc && grep -ril "agx" . --exclude-dir=.git
./source/blender/imbuf/opencolorio/intern/view_specific_look_test.cc
./release/datafiles/colormanagement/config.ocio
./release/datafiles/colormanagement/luts/AgX_Base_P3.cube
./release/datafiles/colormanagement/luts/AgX_Base_Rec2020.cube
./release/datafiles/colormanagement/luts/AgX_Rec2100-HLG_p3_lim.cube
./release/datafiles/colormanagement/luts/AgX_Base_sRGB.cube
./release/datafiles/splash_template.xcf
```

唯一命中的源码文件 `view_specific_look_test.cc` 只是把 `"AgX - Punchy"` 当作**字符串**来测试 look 名称拆分逻辑（与算法无关）：

```cpp
// source/blender/imbuf/opencolorio/intern/view_specific_look_test.cc:27-32
  {
    StringRef view, ui_name;
    EXPECT_TRUE(split_view_specific_look("AgX - Punchy", view, ui_name));
    EXPECT_EQ(view, "AgX");
    EXPECT_EQ(ui_name, "Punchy");
  }
```

其实现本体（同样是纯字符串处理）：

```cpp
// source/blender/imbuf/opencolorio/intern/view_specific_look.cc:9-22
bool split_view_specific_look(const StringRef look_name, StringRef &view, StringRef &ui_name)
{
  const int64_t separator_offset = look_name.find(" - ");
  if (separator_offset == -1) {
    view = {};
    ui_name = look_name;
    return false;
  }

  view = look_name.substr(0, separator_offset);
  ui_name = look_name.substr(separator_offset + 3);

  return true;
}
```

`source/blender/gpu/shaders/` 下**没有**任何 agx shader（该目录文件清单中无 agx，见 §1.6）。

### 1.3 证据 B：引入 AgX 的 commit 只改了 config 与 LUT

```
$ curl -s "https://api.github.com/repos/blender/blender/commits/a9053f7efbe8b25429b11bccd679dbe81ea60e3f"
SHA: a9053f7efbe8b25429b11bccd679dbe81ea60e3f
DATE: 2023-08-22T10:53:15Z
AUTHOR: Zijun Zhou eary@noreply.localhost
--- MESSAGE ---
Color management: Add AgX view transform and make it default

On a user level this view transform provides much better handling of colors in
the over-exposed areas.

With this configuration the following display devices are available, including
AgX view transform for them:
* sRGB
* Display P3
* Rec.1886
* Rec.2020

NOTE: There is no Filmic view transform available for the newly added display
devices.

AgX also brings an implementation of False Colors view transform, which replaces
Filmic-based, and is available for all display devices.

The backward compatibility is preserved. The new files will default to AgX view
transform, which makes it non-forward compatible.

More technical details is available in the original PR #106355.
Please note that the PR has been split into more incremental changes when
was landing.

Pull Request: https://projects.blender.org/blender/blender/pulls/111099
--- FILES CHANGED ---
modified release/datafiles/colormanagement/config.ocio +325 -16
removed release/datafiles/colormanagement/filmic/filmic_false_color.spi3d +0 -274628
added release/datafiles/colormanagement/luts/AgX_Base_P3.cube +50660 -0
added release/datafiles/colormanagement/luts/AgX_Base_Rec2020.cube +50660 -0
added release/datafiles/colormanagement/luts/AgX_Base_sRGB.cube +50660 -0
added release/datafiles/colormanagement/luts/AgX_False_Color.spi1d +4102 -0
added release/datafiles/colormanagement/luts/Guard_Rail_Shaper_EOTF.spi1d +4102 -0
added release/datafiles/colormanagement/luts/Inverse_AgX_Base_Rec2020.cube +46660 -0
added release/datafiles/colormanagement/luts/luminance_compensation_bt2020.cube +50659 -0
added release/datafiles/colormanagement/luts/luminance_compensation_p3.cube +50659 -0
added release/datafiles/colormanagement/luts/luminance_compensation_srgb.cube +50659 -0
modified release/datafiles/colormanagement/luts/xyz_E_to_D65.spimtx +3 -3
modified scripts/startup/bl_ui/properties_render.py +2 -1
modified source/blender/blenloader/intern/versioning_defaults.cc +3 -0
modified tests/python/bl_imbuf_save.py +4 -0
```

**【源码事实】** 该 commit 的源码改动只有 `versioning_defaults.cc`（+3）与 `properties_render.py`（+2/-1）——都是"把默认 view transform 切成 AgX"，**没有任何算法代码**。算法全部由 `config.ocio`（+325/-16）与 `.cube`/`.spi1d` LUT 承担。

### 1.4 `config.ocio` 中与 AgX 相关的全部条目

文件：`release/datafiles/colormanagement/config.ocio`（共 1559 行）。

#### 1.4.1 文件头：AgX 出处声明

```
# release/datafiles/colormanagement/config.ocio:11-13
# Based on original AgX by Troy Sobotka: https://github.com/sobotka/AgX https://github.com/sobotka/SB2383-Configuration-Generation
# Further Developed by Zijun Eary Zhou法纤净, Mark Faderbauer, and Sakari Kapanen
# Repository for this version: https://github.com/EaryChow/AgX
```

#### 1.4.2 roles（AgX Log 被指定为 OCIO 的 log 角色）

```
# release/datafiles/colormanagement/config.ocio:51-53
  # Specified by OCIO, not used in Blender
  color_timing: AgX Log
  compositing_log: AgX Log
```

#### 1.4.3 displays 中的 AgX view（逐字）

```
# release/datafiles/colormanagement/config.ocio:58-68
displays:
  sRGB:
    - !<View> {name: Standard, view_transform: Standard, display_colorspace: sRGB}
    - !<View> {name: ACES 1.3, view_transform: ACES 1.3 Rec.1886, display_colorspace: sRGB}
    - !<View> {name: ACES 2.0, view_transform: ACES 2.0 Rec.1886, display_colorspace: sRGB}
    - !<View> {name: Khronos PBR Neutral, view_transform: Khronos PBR Neutral sRGB, display_colorspace: sRGB}
    - !<View> {name: AgX, view_transform: AgX Base Rec.1886, display_colorspace: sRGB}
    - !<View> {name: Filmic, view_transform: Filmic sRGB, display_colorspace: sRGB}
    - !<View> {name: Filmic Log, colorspace: Filmic Log}
    - !<View> {name: False Color, view_transform: AgX False Color Rec.709, display_colorspace: sRGB}
    - !<View> {name: Raw, colorspace: Non-Color}
```

```
# release/datafiles/colormanagement/config.ocio:69-89
  Display P3:
    - !<View> {name: Standard, view_transform: Standard, display_colorspace: Display P3}
    - !<View> {name: ACES 1.3, view_transform: ACES 1.3 Display P3, display_colorspace: Display P3}
    - !<View> {name: ACES 2.0, view_transform: ACES 2.0 Display P3, display_colorspace: Display P3}
    - !<View> {name: AgX, view_transform: AgX Base Display P3, display_colorspace: Display P3}
    - !<View> {name: False Color, view_transform: AgX False Color Rec.709, display_colorspace: Display P3}
    - !<View> {name: Raw, colorspace: Non-Color}
  Rec.1886:
    - !<View> {name: Standard, view_transform: Standard, display_colorspace: Rec.1886}
    - !<View> {name: ACES 1.3, view_transform: ACES 1.3 Rec.1886, display_colorspace: Rec.1886}
    - !<View> {name: ACES 2.0, view_transform: ACES 2.0 Rec.1886, display_colorspace: Rec.1886}
    - !<View> {name: AgX, view_transform: AgX Base Rec.1886, display_colorspace: Rec.1886}
    - !<View> {name: False Color, view_transform: AgX False Color Rec.709, display_colorspace: Rec.1886}
    - !<View> {name: Raw, colorspace: Non-Color}
  Rec.2020:
    - !<View> {name: Standard, view_transform: Standard, display_colorspace: Rec.2020}
    - !<View> {name: ACES 1.3, view_transform: ACES 1.3 Display P3, display_colorspace: Rec.2020}
    - !<View> {name: ACES 2.0, view_transform: ACES 2.0 Display P3, display_colorspace: Rec.2020}
    - !<View> {name: AgX, view_transform: AgX Base Rec.2020, display_colorspace: Rec.2020}
    - !<View> {name: False Color, view_transform: AgX False Color Rec.709, display_colorspace: Rec.2020}
    - !<View> {name: Raw, colorspace: Non-Color}
```

```
# release/datafiles/colormanagement/config.ocio:101-114
    - !<View> {name: AgX - HDR 1000 nits, view_transform: AgX Rec.2100-HLG - HDR 1000 nits (P3 D65), display_colorspace: Rec.2100-PQ}
    - !<View> {name: AgX - SDR, view_transform: AgX Base Display P3, display_colorspace: Rec.2100-PQ}
    - !<View> {name: False Color, view_transform: AgX False Color Rec.709, display_colorspace: Rec.2100-PQ}
    - !<View> {name: Raw, colorspace: Non-Color}
  Rec.2100-HLG:
    - !<View> {name: Standard, view_transform: Standard, display_colorspace: Rec.2100-HLG}
    - !<View> {name: ACES 1.3 - HDR 1000 nits, view_transform: ACES 1.1 Rec.2100-PQ - HDR 1000 nits, display_colorspace: Rec.2100-HLG}
    - !<View> {name: ACES 1.3 - SDR, view_transform: ACES 1.3 Rec.1886, display_colorspace: Rec.2100-HLG}
    - !<View> {name: ACES 2.0 - HDR 1000 nits, view_transform: ACES 2.0 Rec.2100-PQ - HDR 1000 nits, display_colorspace: Rec.2100-HLG}
    - !<View> {name: ACES 2.0 - SDR, view_transform: ACES 2.0 Rec.1886, display_colorspace: Rec.2100-HLG}
    - !<View> {name: AgX - HDR 1000 nits, view_transform: AgX Rec.2100-HLG - HDR 1000 nits (P3 D65), display_colorspace: Rec.2100-HLG}
    - !<View> {name: AgX - SDR, view_transform: AgX Base Display P3, display_colorspace: Rec.2100-HLG}
    - !<View> {name: False Color, view_transform: AgX False Color Rec.709, display_colorspace: Rec.2100-HLG}
    - !<View> {name: Raw, colorspace: Non-Color}
```

```
# release/datafiles/colormanagement/config.ocio:116-117
active_views: [Standard, ACES 1.3, ACES 1.3 - HDR 1000 nits, ACES 1.3 - HDR 2000 nits, ACES 1.3 - HDR 4000 nits, ACES 1.3 - SDR, ACES 2.0, ACES 2.0 - HDR 500 nits, ACES 2.0 - HDR 1000 nits, ACES 2.0 - HDR 2000 nits, ACES 2.0 - HDR 4000 nits, ACES 2.0 - SDR, Khronos PBR Neutral, AgX, AgX - HDR 1000 nits, AgX - SDR, Filmic, Filmic Log, False Color, Raw]
inactive_colorspaces: [Luminance Compensation Rec.2020]
```

#### 1.4.4 `AgX Base sRGB` —— 位于 `display_colorspaces`（作为 View Inverse）

```
# release/datafiles/colormanagement/config.ocio:242-254
  - !<ColorSpace>
    name: AgX Base sRGB
    family: View Inverse
    description: |
      AgX view transform for sRGB.
      May be used to convert backplates or matte paintings to the working space.
    interop_id: srgb_rec709_display
    from_display_reference: !<GroupTransform>
      children:
        - !<ColorSpaceTransform> {src: cie_xyz_d65_interchange, dst: Linear FilmLight E-Gamut}
        - !<AllocationTransform> {allocation: lg2, vars: [-12.47393, 12.5260688117]}
        - !<FileTransform> {src: AgX_Base_sRGB.cube, interpolation: tetrahedral}
        - !<ColorSpaceTransform> {src: Rec.1886, dst: sRGB}
```

#### 1.4.5 `view_transforms` 中的全部 AgX view transform（逐字全文）

```
# release/datafiles/colormanagement/config.ocio:327-338
  - !<ViewTransform>
    name: AgX Base Rec.2020
    family: AgX
    description: |
      AgX Base Image Encoding for display with Gamma 2.4 and Rec.2020 gamut
    from_scene_reference: !<GroupTransform>
      children:
        - !<ColorSpaceTransform> {src: ACES2065-1, dst: Linear FilmLight E-Gamut}
        - !<AllocationTransform> {allocation: lg2, vars: [-12.47393, 12.5260688117]}
        - !<FileTransform> {src: AgX_Base_Rec2020.cube, interpolation: tetrahedral}
        - !<ColorSpaceTransform> {src: Rec.2020, dst: cie_xyz_d65_interchange}
```

```
# release/datafiles/colormanagement/config.ocio:339-349
  - !<ViewTransform>
    name: AgX Base Rec.1886
    family: AgX
    description: |
      AgX Base Image Encoding for sRGB and Rec.1886 Display
    from_scene_reference: !<GroupTransform>
      children:
        - !<ColorSpaceTransform> {src: ACES2065-1, dst: Linear FilmLight E-Gamut}
        - !<AllocationTransform> {allocation: lg2, vars: [-12.47393, 12.5260688117]}
        - !<FileTransform> {src: AgX_Base_sRGB.cube, interpolation: tetrahedral}
        - !<ColorSpaceTransform> {src: Rec.1886, dst: cie_xyz_d65_interchange}
```

```
# release/datafiles/colormanagement/config.ocio:351-362
  - !<ViewTransform>
    name: AgX Base Display P3
    family: AgX
    description: |
      AgX Base Image Encoding for Display P3 Display
    from_scene_reference: !<GroupTransform>
      children:
        - !<ColorSpaceTransform> {src: ACES2065-1, dst: Linear FilmLight E-Gamut}
        - !<AllocationTransform> {allocation: lg2, vars: [-12.47393, 12.5260688117]}
        - !<FileTransform> {src: AgX_Base_P3.cube, interpolation: tetrahedral}
        - !<ExponentTransform> {value: 2.4}
        - !<ColorSpaceTransform> {src: Linear DCI-P3 D65, dst: cie_xyz_d65_interchange}
```

```
# release/datafiles/colormanagement/config.ocio:364-374
  - !<ViewTransform>
    name: AgX Rec.2100-HLG - HDR 1000 nits (P3 D65)
    family: AgX
    description: |
      AgX 1000 nits peak Image Encoding for BT.2100-HLG P3 limited Display. Reference white is 100nits
    from_scene_reference: !<GroupTransform>
      children:
        - !<ColorSpaceTransform> {src: ACES2065-1, dst: Linear FilmLight E-Gamut}
        - !<AllocationTransform> {allocation: lg2, vars: [-12.47393, 12.5260688117]}
        - !<FileTransform> {src: AgX_Rec2100-HLG_p3_lim.cube, interpolation: tetrahedral}
        - !<ColorSpaceTransform> {src: Rec.2100-HLG, dst: cie_xyz_d65_interchange}
```

```
# release/datafiles/colormanagement/config.ocio:376-390
  - !<ViewTransform>
    name: AgX False Color Rec.709
    family: AgX
    description: |
      A heat-map-like image formed from AgX Base for sRGB and Rec.1886 displays
    from_scene_reference: !<GroupTransform>
      children:
        - !<ColorSpaceTransform> {src: ACES2065-1, dst: Linear FilmLight E-Gamut}
        - !<AllocationTransform> {allocation: lg2, vars: [-12.47393, 12.5260688117]}
        - !<FileTransform> {src: AgX_Base_Rec2020.cube, interpolation: tetrahedral}
        - !<ColorSpaceTransform> {src: Rec.2020, dst: Linear Rec.2020}
        - !<MatrixTransform> {matrix: [0.2589235355689848, 0.6104985346066525, 0.13057792982436284, 0, 0.2589235355689848, 0.6104985346066525, 0.13057792982436284, 0, 0.2589235355689848, 0.6104985346066525, 0.13057792982436284, 0, 0, 0, 0, 1]}
        - !<ExponentTransform> {value: 2.5, direction: inverse}
        - !<FileTransform> {src: AgX_False_Color.spi1d, interpolation: linear}
        - !<ColorSpaceTransform> {src: Rec.1886, dst: cie_xyz_d65_interchange}
```

#### 1.4.6 负值处理：`Luminance Compensation Rec.2020`

```
# release/datafiles/colormanagement/config.ocio:1222-1238
  - !<ColorSpace>
    name: Luminance Compensation Rec.2020
    aliases: [blender:luminancecompensation_rec2020_scene, Luminance Compensation BT.2020]
    family: Utility
    equalitygroup:
    bitdepth: 32f
    description: |
      Offset the negative values in BT.2020 and compensate for luminance, ensuring there is no negative values in Rec.2020
    isdata: false
    interop_id: blender:luminancecompensation_rec2020_scene
    from_scene_reference: !<GroupTransform>
      children:
        - !<ColorSpaceTransform> {src: ACES2065-1, dst: Linear FilmLight E-Gamut}
        - !<AllocationTransform> {allocation: lg2, vars: [-12.47393, 12.5260688117]}
        - !<FileTransform> {src: luminance_compensation_bt2020.cube, interpolation: tetrahedral}
        - !<AllocationTransform> {allocation: lg2, vars: [-12.47393, 12.5260688117], direction: inverse}
        - !<ColorSpaceTransform> {src: Linear FilmLight E-Gamut, dst: Linear Rec.2020}
```

#### 1.4.7 `AgX Log` colorspace（含 inset 矩阵常量）

```
# release/datafiles/colormanagement/config.ocio:1240-1260
  - !<ColorSpace>
    name: AgX Log
    aliases: [blender:agxlog_rec2020_scene]
    family: Log
    equalitygroup:
    bitdepth: 32f
    description: |
      Log Encoding with Chroma inset and rotation of primaries, and with 25 Stops of Dynamic Range
    isdata: false
    interop_id: blender:agxlog_rec2020_scene
    from_scene_reference: !<GroupTransform>
      children:
        - !<ColorSpaceTransform> {src: ACES2065-1, dst: Luminance Compensation Rec.2020}
        # Rec.2020 generated parameters rotate = [2.13976149, -1.22827335, -3.05174246], inset = [0.32965205, 0.28051336, 0.12475368]
        - !<MatrixTransform> {matrix: [0.856627153315983, 0.0951212405381588, 0.0482516061458583, 0, 0.137318972929847, 0.761241990602591, 0.101439036467562, 0, 0.11189821299995, 0.0767994186031903, 0.811302368396859, 0, 0, 0, 0, 1]}
        - !<AllocationTransform> {allocation: lg2, vars: [-12.47393, 12.5260688117]}
    to_scene_reference: !<GroupTransform>
      children:
        - !<AllocationTransform> {allocation: lg2, vars: [-12.47393, 12.5260688117], direction: inverse}
        - !<MatrixTransform> {matrix: [0.856627153315983, 0.0951212405381588, 0.0482516061458583, 0, 0.137318972929847, 0.761241990602591, 0.101439036467562, 0, 0.11189821299995, 0.0767994186031903, 0.811302368396859, 0, 0, 0, 0, 1], direction: inverse}
        - !<ColorSpaceTransform> {src: ACES2065-1, dst: Linear Rec.2020, direction: inverse}
```

#### 1.4.8 looks：`AgX - Punchy` 及全部 AgX look

```
# release/datafiles/colormanagement/config.ocio:1343-1351
  - !<Look>
    name: AgX - Punchy
    process_space: AgX Log
    description: A darkening punchy look
    transform: !<GroupTransform>
      children:
        - !<GradingToneTransform>
         shadows: {rgb: [0.2, 0.2, 0.2], master: 0.35, start: 0.4, pivot: 0.1}
        - !<CDLTransform> {power: [1.0912, 1.0912, 1.0912]}
```

```
# release/datafiles/colormanagement/config.ocio:1353-1361
  - !<Look>
    name: AgX - Greyscale
    process_space: AgX Log
    description: A Black and White Look
    transform: !<GroupTransform>
      children:
        - !<AllocationTransform> {allocation: lg2, vars: [-12.47393, 12.5260688117], direction: inverse}
        - !<MatrixTransform> {matrix: [0.2589235355689848, 0.6104985346066525, 0.13057792982436284, 0, 0.2589235355689848, 0.6104985346066525, 0.13057792982436284, 0, 0.2589235355689848, 0.6104985346066525, 0.13057792982436284, 0, 0, 0, 0, 1]}
        - !<AllocationTransform> {allocation: lg2, vars: [-12.47393, 12.5260688117]}
```

七个对比度 look（`AgX - Very High Contrast` / `High` / `Medium High` / `Base` / `Medium Low` / `Low` / `Very Low`），全部 `process_space: AgX Log`、全部用 `GradingPrimaryTransform`，`pivot: {contrast: -0.2}`。逐字示例（其余同构，仅 `contrast`/`saturation` 数值不同）：

```
# release/datafiles/colormanagement/config.ocio:1363-1373
  - !<Look>
    name: AgX - Very High Contrast
    process_space: AgX Log
    description: A Very High Contrast Look
    transform: !<GroupTransform>
      children:
        - !<GradingPrimaryTransform>
          style: log
          contrast: {rgb: [1.57, 1.57, 1.57], master: 1}
          saturation: 0.9
          pivot: {contrast: -0.2}
```

```
# release/datafiles/colormanagement/config.ocio:1399-1408
  - !<Look>
    name: AgX - Base Contrast
    process_space: AgX Log
    description: A Base Contrast Look
    transform: !<GroupTransform>
      children:
        - !<GradingPrimaryTransform>
          style: log
          contrast: {rgb: [1, 1, 1], master: 1}
          pivot: {contrast: -0.2}
```

**【源码事实】** 完整 look 名称与行号索引（`grep -n -i agx config.ocio`）：
`AgX - Punchy`(1344)、`AgX - Greyscale`(1354)、`AgX - Very High Contrast`(1364)、`AgX - High Contrast`(1376)、`AgX - Medium High Contrast`(1388)、`AgX - Base Contrast`(1400)、`AgX - Medium Low Contrast`(1411)、`AgX - Low Contrast`(1423)、`AgX - Very Low Contrast`(1435)，以及对应的 `False Color - *` 一套（1447–1547）。全部 `process_space: AgX Log`。

#### 1.4.9 AgX 链路依赖的 primaries 空间：`Linear FilmLight E-Gamut`

```
# release/datafiles/colormanagement/config.ocio:600-613
  - !<ColorSpace>
    name: Linear FilmLight E-Gamut
    aliases: [blender:lin_egamut_scene, Linear E-Gamut I-D65, "FilmLight: Linear - E-Gamut"]
    family: Linear
    equalitygroup:
    bitdepth: 32f
    description: |
      Linear E-Gamut with illuminant D65 white point
    isdata: false
    interop_id: blender:lin_egamut_scene
    from_scene_reference: !<GroupTransform>
      children:
        - !<ColorSpaceTransform> {src: ACES2065-1, dst: Linear CIE-XYZ I-D65}
        - !<MatrixTransform> {matrix: [1.5250528, -0.3159135, -0.1226583, 0, -0.5091526, 1.3333274, 0.1382844, 0, 0.0957153, 0.0508974, 0.7879558, 0, 0, 0, 0, 1]}
```

### 1.5 `luts/` 下 AgX 相关文件列表（`ls` 输出）

```
$ cd /private/tmp/blender-agx/bsrc/release/datafiles/colormanagement && ls -la luts/
total 35848
drwxr-xr-x  11 wl  wheel      352  9 22 17:58 .
drwxr-xr-x   6 wl  wheel      192  9 22 17:58 ..
-rw-r--r--   1 wl  wheel  2740073  9 22 17:58 AgX_Base_P3.cube
-rw-r--r--   1 wl  wheel  2809107  9 22 17:58 AgX_Base_Rec2020.cube
-rw-r--r--   1 wl  wheel  2716206  9 22 17:58 AgX_Base_sRGB.cube
-rw-r--r--   1 wl  wheel   127040  9 22 17:58 AgX_False_Color.spi1d
-rw-r--r--   1 wl  wheel  2878474  9 22 17:58 AgX_Rec2100-HLG_p3_lim.cube
-rw-r--r--   1 wl  wheel    45766  9 22 17:58 Guard_Rail_Shaper_EOTF.spi1d
-rw-r--r--   1 wl  wheel  1519956  9 22 17:58 luminance_compensation_bt2020.cube
-rw-r--r--   1 wl  wheel  5495377  9 22 17:58 pbrNeutral.cube
-rw-r--r--   1 wl  wheel      115  9 22 17:58 xyz_E_to_D65.spimtx
```

AgX 直接使用：`AgX_Base_sRGB.cube`、`AgX_Base_Rec2020.cube`、`AgX_Base_P3.cube`、`AgX_Rec2100-HLG_p3_lim.cube`、`AgX_False_Color.spi1d`、`luminance_compensation_bt2020.cube`。
（`Guard_Rail_Shaper_EOTF.spi1d`、`pbrNeutral.cube`、`xyz_E_to_D65.spimtx` 服务于 Guard Rail / Khronos PBR Neutral，非 AgX 链路。）

**是否存在解析这些 LUT 的代码？** —— **【源码事实】没有 AgX 专用解析代码。** LUT 由 OpenColorIO 库的 `FileTransform` 通用机制加载，Blender 侧只通过 `source/blender/imbuf/opencolorio/` 调用 OCIO：

```
$ grep -ril "lut" source/blender/imbuf source/blender/gpu | head
source/blender/imbuf/movie/intern/movie_write.cc
source/blender/imbuf/movie/intern/movie_read.cc
source/blender/imbuf/opencolorio/shaders/gpu_shader_display_transform_frag.glsl
source/blender/imbuf/opencolorio/intern/libocio/libocio_scope.cc
source/blender/imbuf/opencolorio/intern/libocio/libocio_gpu_shader_binder.cc
...
```

```
$ grep -n -i "lut" source/blender/imbuf/intern/colormanagement.cc
(无输出)
```

即 `colormanagement.cc`（4067 行）里没有任何 "lut" 字样；命中的都是 GPU 纹理 LUT / 图像缩略图等无关实现。**AgX 的数值全部来自 `.cube` 文件本身，没有任何 Blender 源码重新实现 AgX 数学。**

### 1.6 `source/blender/gpu/shaders/` 无 AgX shader

```
$ ls source/blender/gpu/shaders/
bsl_shader_linting.cc
CMakeLists.txt
common
compat
gpu_shader_annotation.bsl.hh
gpu_shader_area_borders.bsl.hh
gpu_shader_codegen_lib.glsl
gpu_shader_colorspace.bsl.hh
gpu_shader_display_fallback.bsl.hh
gpu_shader_icon.bsl.hh
gpu_shader_image.bsl.hh
gpu_shader_index_gen.bsl.hh
gpu_shader_keyframe_shape.bsl.hh
gpu_shader_node_socket.bsl.hh
gpu_shader_nodelink.bsl.hh
gpu_shader_polyline.bsl.hh
gpu_shader_sequencer_scope.bsl.hh
gpu_shader_sequencer_widget.bsl.hh
gpu_shader_sequencer_zebra.bsl.hh
gpu_shader_simple.bsl.hh
gpu_shader_text.bsl.hh
gpu_shader_update_mipmaps.bsl.hh
gpu_shader_widget_base.bsl.hh
gpu_shader_widget_shadow.bsl.hh
infos
material
nodes
python
```

**【源码事实】** 无 agx shader。Blender 的显示变换 GPU shader 在 `source/blender/imbuf/opencolorio/shaders/gpu_shader_display_transform_frag.glsl`，它是**通用 OCIO shader**（由 OCIO 运行时生成/绑定），不含 AgX 数学。`intern/opencolorio/` 目录**不存在**（OCIO 集成在 `source/blender/imbuf/opencolorio/`）：

```
$ ls intern/opencolorio/
ls: intern/opencolorio/: No such file or directory
```

---

## 2. 完整算法链路（逐步骤 + 证据）

### 2.0 总览（**【推断】**，基于 §1.4.5 的 `from_scene_reference` 逐字定义 + OCIO v2 语义）

以 sRGB 显示 + `AgX` view 为例，`view_transform: AgX Base Rec.1886`（config.ocio:64, 339-349）：

```
scene-linear (config reference = ACES2065-1)
  → [looks（若有，process_space = AgX Log）]        ← Blender 在 view transform 之前应用 look
  → ColorSpaceTransform: ACES2065-1 → Linear FilmLight E-Gamut      (primaries 变换到 E-Gamut)
  → AllocationTransform: lg2, vars [-12.47393, 12.5260688117]       (log2 编码, 25 stops)
  → FileTransform: AgX_Base_sRGB.cube  (tetrahedral)                (inset + sigmoid + outset + power2.4 编码)
  → ColorSpaceTransform: Rec.1886 → cie_xyz_d65_interchange         (解码 2.4 幂 + 转 XYZ D65)
  → [display colorspace sRGB: cie_xyz_d65_interchange → Linear Rec.709 → sRGB piecewise 编码]
  → display
```

**【源码事实】关键点：`AllocationTransform lg2` 与 `AgX_Base_*.cube` 之间没有单独的 inset 矩阵。** 因为 **inset / rotation / sigmoid / outset 全部已烘焙进 `.cube`**（见 §2.2、§2.3 的 LUT 头注释）。独立的 inset 矩阵只出现在 `AgX Log` colorspace（config.ocio:1254）中，供 look 的 `process_space` 与"可完全撤销"的技术转换使用。

### 2.1 (a) log 编码

**【源码事实】** 编码器是 OCIO 的 `AllocationTransform`，`allocation: lg2`，参数常量在 config.ocio 中反复出现：

```
# release/datafiles/colormanagement/config.ocio:347
        - !<AllocationTransform> {allocation: lg2, vars: [-12.47393, 12.5260688117]}
```

同一常量还出现在：`config.ocio:252`（AgX Base sRGB inverse）、`335`（AgX Base Rec.2020）、`347`（AgX Base Rec.1886）、`359`（AgX Base Display P3）、`372`（AgX HDR）、`384`（AgX False Color）、`1235/1237`（Luminance Compensation）、`1255/1258`（AgX Log）、`1359/1361`（AgX - Greyscale look）、`1462/1464`（False Color - Greyscale look）。

**【源码事实】"25 Stops" 直接写在 `AgX Log` 的 description 里：**

```
# release/datafiles/colormanagement/config.ocio:1246-1247
    description: |
      Log Encoding with Chroma inset and rotation of primaries, and with 25 Stops of Dynamic Range
```

**【作者论述】Blender PR #106355 描述（Eary）明确给出该对数区间与 stops 的对应：**

> URL: https://projects.blender.org/blender/blender/pulls/106355
> 原文：
> ```
> - `AgX Log` The Log encoding with chroma-inset and rotation of primaries included. Uses BT.2020 primaries with Log 2 encoding from `-12.47393` to `12.5260688117`(25 stops of dynamic range) and I-D65 white point.
> ```

**【作者论述】EaryChow/AgX README（Blender 实际采用版本的仓库）给出另一种 stops 表述（-10 → +15 stops，也是 25 stops）：**

> URL: https://github.com/EaryChow/AgX/blob/main/README.md
> 原文（第 126-127 行）：
> ```
>  - `AgX Log`
>   Blender AgX Log has a log curve of pure log2, normalized to cover the exposure range from -10 stops to +15 stops.
> ```

**【源码事实】LUT 头注释给出的 stops 表述（-10 → +15 stops）：**

```
# release/datafiles/colormanagement/luts/AgX_Base_sRGB.cube:3
# This LUT expects input to be E Gamut Log2 encoding from -10 stops to +15 stops
```

**【源码事实】原始 Troy Sobotka AgX 用的是另一个上界 `+4.026069`（16.5 stops）：**

```
# sobotka/AgX @ a66ad4b1e74952531d95634d60fe25de0025462e : config.ocio:80-84
  - !<ColorSpace>
    name: AgX Log (Kraken)
    aliases: [Log, AgX Log, Kraken, AgX Kraken Log]
    family: Log Encodings
    equalitygroup: ""
    bitdepth: unknown
    description: AgX Log, (Kraken)
    isdata: false
    allocation: uniform
    from_scene_reference: !<GroupTransform>
      children:
        - !<RangeTransform> {min_in_value: 0, min_out_value: 0}
        - !<MatrixTransform> {matrix: [0.842479062253094, 0.0784335999999992, 0.0792237451477643, 0, 0.0423282422610123, 0.878468636469772, 0.0791661274605434, 0, 0.0423756549057051, 0.0784336, 0.879142973793104, 0, 0, 0, 0, 1]}
        - !<AllocationTransform> {allocation: lg2, vars: [-12.47393, 4.026069]}
```

**【作者论述】生成器源码给出 `calculate_OCIO_log2` 的定义（log2 以 0.18 中灰为 EV 零点）：**

```
# sobotka/SB2383-Configuration-Generation @ af3ef81a2eafdf06002cc20447e644d7ffedf082 : AgX.py:54-56
# Calculate OpenColorIO allocation for log2 from open domain tristimulus value.
def calculate_OCIO_log2(in_ev, od_middle_grey=0.18):
    return numpy.log2(numpy.power(2.0, in_ev) * od_middle_grey)
```

同样的函数在 `sobotka/AgX-S2O3 @ 7f329dd10b276e87c302d0fe3d4f565da922fb43 : AgX.py:64-68`。

**【推断】`-12.47393 / 12.5260688117` 与 "-10 / +15 stops" 的一致性：**
`log2(0.18) = -2.4739311883…`；`-10 + log2(0.18) = -12.4739311…`；`+15 + log2(0.18) = +12.5260688…`。
因此 `vars: [-12.47393, 12.5260688117]` = 以 0.18 中灰为 EV 0 的 **-10 EV 到 +15 EV**（共 25 stops），与 §2.1 的 LUT 注释、README、PR 描述三方一致。**这是推断，但由 `calculate_OCIO_log2` 的定义（上）可直接推出。**
同理原始 AgX 的 `4.026069` 对应 `+6.5 EV`：`6.5 + log2(0.18) = 4.0260688…`，即 16.5 stops —— 与 `AgX-S2O3/generate_config.py:37-38` 的 `AgX_min_EV = -10.0` / `AgX_max_EV = +6.5` 一致：

```
# sobotka/AgX-S2O3 @ 7f329dd10b276e87c302d0fe3d4f565da922fb43 : generate_config.py:37-40
AgX_min_EV = -10.0
AgX_max_EV = +6.5
AgX_x_pivot = numpy.abs(AgX_min_EV / (AgX_max_EV - AgX_min_EV))
AgX_y_pivot = 0.50
```

### 2.2 (b) primaries 变换 / inset matrix

**【源码事实】Blender `AgX Log` 的 inset 矩阵（row-major，OCIO 展开为 16 元素）：**

```
# release/datafiles/colormanagement/config.ocio:1253-1254
        # Rec.2020 generated parameters rotate = [2.13976149, -1.22827335, -3.05174246], inset = [0.32965205, 0.28051336, 0.12475368]
        - !<MatrixTransform> {matrix: [0.856627153315983, 0.0951212405381588, 0.0482516061458583, 0, 0.137318972929847, 0.761241990602591, 0.101439036467562, 0, 0.11189821299995, 0.0767994186031903, 0.811302368396859, 0, 0, 0, 0, 1]}
```

写成 3×3：

```
[0.856627153315983   0.0951212405381588  0.0482516061458583]
[0.137318972929847   0.761241990602591   0.101439036467562]
[0.11189821299995    0.0767994186031903  0.811302368396859]
```

**【作者论述】EaryChow/AgX README 逐字给出同一矩阵，并说明它就是 "Blender AgX Inset Matrix"：**

> URL: https://github.com/EaryChow/AgX/blob/main/README.md
> 原文（第 138-151 行）：
> ```
>       Blender AgX Log XYZ to RGB matrix:
>       ```
>       [[ 1.40797 -0.15298 -0.17008]
>       [-0.26999  1.17735  0.07278]
>       [ 0.1552   0.04965  0.73719]]
>       ```
>
>       Blender AgX Log XYZ to RGB matrix = Blender AgX Inset Matrix * Rec.2020 XYZ to RGB matrix.
>       This means the Inset matrix can be isolated by: Blender AgX Log XYZ to RGB matrix * Rec.2020 RGB to XYZ matrix:
>       ```
>       [[ 0.85663  0.09512  0.04825]
>       [ 0.13732  0.76124  0.10144]
>       [ 0.1119   0.0768   0.8113 ]]
>       ```
>       The two above matrices are written in row-major fashion.
> ```

**【源码事实】`AgX_Base_*.cube` 使用的 inset 参数（在 E-Gamut 工作空间上）写在 LUT 头注释里：**

```
# release/datafiles/colormanagement/luts/AgX_Base_sRGB.cube:5
#  rotate = [3.0, -1, -2.0], inset = [0.4, 0.22, 0.13], outset = [0.4, 0.22, 0.04]
```

同一行在 `AgX_Base_Rec2020.cube:5`、`AgX_Base_P3.cube:5` 逐字相同。

**【源码事实】HDR LUT 给出另一组（Rec.2020 工作空间）参数：**

```
# release/datafiles/colormanagement/luts/AgX_Rec2100-HLG_p3_lim.cube:5-6
# Inset matrix can be generated in Rec.2020 with rotation [2.13976149, -1.22827335, -3.05174246],
# Inset: [0.32965205, 0.28051336, 0.12475368], outset = [0.32317438, 0.28325605, 0.0374326].
```

**【作者论述】inset 的几何含义 —— "向白点收窄 primaries"：** 由生成器源码逐字可见：primaries 先绕白点旋转、与工作空间色域边界求交，再以 `(1.0 - scale)` 绕白点缩放。

```
# sobotka/SB2383-Configuration-Generation @ af3ef81a2eafdf06002cc20447e644d7ffedf082 : working_space.py:131-152
    # Inset according to the desired inset scales. Insetting controls the rate
    # of attenuation.
    rotated_inset_red = shapely.affinity.scale(
        hull_red,
        xfact=(1.0 - scale_red_in),
        yfact=(1.0 - scale_red_in),
        origin=point_achromatic,
    )

    rotated_inset_green = shapely.affinity.scale(
        hull_green,
        xfact=(1.0 - scale_green_in),
        yfact=(1.0 - scale_green_in),
        origin=point_achromatic,
    )

    rotated_inset_blue = shapely.affinity.scale(
        hull_blue,
        xfact=(1.0 - scale_blue_in),
        yfact=(1.0 - scale_blue_in),
        origin=point_achromatic,
    )
```

```
# sobotka/SB2383-Configuration-Generation : working_space.py:70-84
    # Rotate the primaries. Positive values are counter clockwise.
    rotate_red = primaries_rotate[0]
    rotate_green = primaries_rotate[1]
    rotate_blue = primaries_rotate[2]
    rotate_achromatic = achromatic_rotate

    rotated_out_red = shapely.affinity.rotate(
        scaled_red, rotate_red, origin=point_achromatic
    )
    rotated_out_green = shapely.affinity.rotate(
        scaled_green, rotate_green, origin=point_achromatic
    )
    rotated_out_blue = shapely.affinity.rotate(
        scaled_blue, rotate_blue, origin=point_achromatic
    )
```

**【作者论述】早期 Troy Sobotka 版本的等价做法（把 sRGB primaries 绕白点压缩）：**

```
# sobotka/AgX-S2O3 @ 7f329dd10b276e87c302d0fe3d4f565da922fb43 : AgX.py:19-54
def AgX_compressed_matrix(compression=0.20):
    sRGB_Colourspace = colour.RGB_COLOURSPACES["sRGB"]

    # Individual attenuation channel compressions. There Be Dragons.
    compression_red = 0.00
    compression_green = 0.00
    compression_blue = 0.00

    compressions = numpy.asarray(
        [compression_red, compression_green, compression_blue]
    )

    # Global attenuation compression. Will be applied equally to
    # all three channels.

    scale_factor = numpy.ma.divide(
        1.0,
        (1.0 - compressions) * (1.0 - compression)
    ).filled(fill_value=0.0)[..., numpy.newaxis]

    adjusted_primaries = (
        (sRGB_Colourspace.primaries - sRGB_Colourspace.whitepoint)
        * scale_factor
    ) + sRGB_Colourspace.whitepoint

    adjusted_Colourspace = sRGB_Colourspace.copy()
    adjusted_Colourspace.primaries = adjusted_primaries
    adjusted_Colourspace.name = "Adjusted Colourspace"

    adjusted_Colourspace.use_derived_matrix_RGB_to_XYZ = True
    adjusted_Colourspace.use_derived_matrix_XYZ_to_RGB = True
    adjusted_Colourspace.use_derived_transformation_matrices = True
    adjusted_Colourspace.matrix_RGB_to_XYZ = adjusted_Colourspace.matrix_RGB_to_XYZ
    adjusted_Colourspace.matrix_XYZ_to_RGB = adjusted_Colourspace.matrix_XYZ_to_RGB

    return colour.matrix_RGB_to_RGB(sRGB_Colourspace, adjusted_Colourspace)
```

### 2.3 (c) sigmoid / tone curve

**【源码事实】Blender 中不存在 sigmoid 的公式实现；它是 57³ 3D LUT（`LUT_3D_SIZE 57`），输入已 log2 归一化到 [0,1]：**

```
# release/datafiles/colormanagement/luts/AgX_Base_sRGB.cube:1-9
TITLE "AgX_Formation sRGB"
# AgX Base sRGB Formation LUT
# This LUT expects input to be E Gamut Log2 encoding from -10 stops to +15 stops
# But the end image formation will be from -10 to 6.5 encoded in power 2.4
#  rotate = [3.0, -1, -2.0], inset = [0.4, 0.22, 0.13], outset = [0.4, 0.22, 0.04]
# The image formed has 40% per-channel shifts
DOMAIN_MIN 0 0 0
DOMAIN_MAX 1 1 1
LUT_3D_SIZE 57
```

**【源码事实】Blender 中加载该 LUT 的语句（`interpolation: tetrahedral`）：**

```
# release/datafiles/colormanagement/config.ocio:348
        - !<FileTransform> {src: AgX_Base_sRGB.cube, interpolation: tetrahedral}
```

**【源码事实/作者论述】LUT 的生成器代码不在 Blender 仓库、也不在 EaryChow/AgX 仓库中。**
- `EaryChow/AgX` 仓库文件全集（`find . -type f`）：只有 `config.ocio`、`README.md`、`filmic/`、`icc/`、`luts/` —— **没有生成器 `.py`**。
- Blender 仓库中全仓 grep `AgX` 的命中列表（§1.2）也不含生成器。
⇒ **【未找到】Blender 版 AgX 的 sigmoid 曲线生成源码（未公开）。**

**【作者论述】原始 AgX 的 sigmoid 函数形式（Jed Smith 可调 sigmoid）在两代生成器中都逐字可查。**

第一代（`AgX-S2O3`，Troy Sobotka 手写）：

```
# sobotka/AgX-S2O3 @ 7f329dd10b276e87c302d0fe3d4f565da922fb43 : AgX.py:129-148
# The following is a completely tunable sigmoid function compliments
# of the incredible hard work of Jed Smith. He's an incredible peep,
# but don't let anyone know that I said that.
def equation_scale(x_pivot, y_pivot, slope_pivot, power):
    x_pivot = numpy.asarray(x_pivot)
    y_pivot = numpy.asarray(y_pivot)
    slope_pivot = numpy.asarray(slope_pivot)
    power = numpy.asarray(power)

    return (
        ((slope_pivot * x_pivot)**-power) *
        (((slope_pivot * (x_pivot / y_pivot))**power) - 1.0)
    )**(-1.0 / power)


def equation_hyperbolic(x, power):
    x = numpy.asarray(x)
    power = numpy.asarray(power)

    return x / ((1.0 + x**power)**(1.0 / power))
```

```
# sobotka/AgX-S2O3 : AgX.py:192-218
def equation_full_curve(x, x_pivot, y_pivot, slope_pivot, power):
    x = numpy.asarray(x)
    x_pivot = numpy.tile(numpy.asarray(x_pivot), len(x))
    y_pivot = numpy.tile(numpy.asarray(y_pivot), len(x))
    slope_pivot = numpy.tile(numpy.asarray(slope_pivot), len(x))
    power = numpy.tile(numpy.asarray(power), len(x))

    scale_x_pivot = numpy.where(
        x >= x_pivot, 1.0 - x_pivot, x_pivot
    )

    scale_y_pivot = numpy.where(
        x >= x_pivot, 1.0 - y_pivot, y_pivot
    )

    toe_scale = equation_scale(
        scale_x_pivot, scale_y_pivot, slope_pivot, power[..., 0]
    )
    shoulder_scale = equation_scale(
        scale_x_pivot, scale_y_pivot, slope_pivot, power[..., 1]
    )

    scale = numpy.where(
        x >= x_pivot, shoulder_scale, -toe_scale
    )

    return equation_curve(x, x_pivot, y_pivot, slope_pivot, power, scale)
```

**【作者论述】第一代 sigmoid 的实际调用参数（默认对比度 LUT）：**

```
# sobotka/AgX-S2O3 : generate_config.py:492-508
    x_input = numpy.linspace(0.0, 1.0, 4096)
    limits_contrast = [3.0, 3.25]
    general_contrast = 2.0
    y_LUT = AgX.equation_full_curve(
        x_input,
        AgX_x_pivot,
        AgX_y_pivot,
        general_contrast,
        limits_contrast
    )

    aesthetic_LUT_name = "AgX Default Contrast"
    aesthetic_LUT_safe = aesthetic_LUT_name.replace(" ", "_")
    aesthetic_LUT = colour.LUT1D(
        table=y_LUT,
        name="AgX Default Contrast"
    )
```

第二代（`SB2383`，重写为 `sigmoid.py`）：

```
# sobotka/SB2383-Configuration-Generation @ af3ef81a2eafdf06002cc20447e644d7ffedf082 : sigmoid.py:18-20
# This module is entirely based on Jed Smith's amazing tunable sigmoid. Originating
# math located at https://www.desmos.com/calculator/yrysofmx8h. Python port by
# yours truly.
```

```
# sobotka/SB2383-Configuration-Generation : sigmoid.py:132-157
def exponential(x_in, power):
    return numpy.ma.divide(
        x_in,
        numpy.ma.power(
            numpy.ma.add(1.0, numpy.ma.power(x_in, power)),
            numpy.ma.divide(1.0, power),
        ),
    )


def exponential_curve(x_in, scale, slope, power, transition_x, transition_y):
    return numpy.ma.add(
        numpy.ma.multiply(
            scale,
            exponential(
                numpy.ma.divide(
                    numpy.ma.multiply(
                        slope, numpy.ma.subtract(x_in, transition_x)
                    ),
                    scale,
                ),
                power,
            ),
        ),
        transition_y,
    )
```

```
# sobotka/SB2383-Configuration-Generation : sigmoid.py:160-173
def calculate_sigmoid(
    # Input x
    x_in,
    # Pivot coordinates x and y for the fulcrum.
    pivots=[0.5, 0.5],
    # Slope of linear portion.
    slope=2.0,
    # Length of transition toward the toe and shoulder.
    lengths=[0.0, 0.0],
    # Exponential power of the toe and shoulder regions.
    powers=[1.0, 1.0],
    # Intersection limit coordinates x and y for the toe and shoulder.
    limits=[[0.0, 0.0], [1.0, 1.0]],
):
```

**【作者论述】第二代默认参数（与 README 的 CLI 文档对应）：**

```
# sobotka/SB2383-Configuration-Generation : generate_config.py:65-90
    default_x_pivot = numpy.abs(default_normalized_log2_minimum) / (
        default_normalized_log2_maximum - default_normalized_log2_minimum
    )

    # The output, or ordinate, value of the transformation. We use a simple bog
    # standard display encoding value as our final "middle" perceptual anchor.
    # Maximization of the signal to the quantised expression range should be
    # considered.
    default_y_pivot = 0.18 ** (1.0 / 2.2)

    # Power represents the tension in the toe and shoulder of the log encoding,
    # with the slope indicating the general slope of the linear segment.
    default_exponent = [1.5, 1.5]
    default_slope = 2.4
    argparser = argparse.ArgumentParser(
        description="Generates an OpenColorIO configuration",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )

    # Rotation and scale of the individual working space primaries. Rotate is
    # in degrees, where negative is clockwise, and positive is counterclockwise.
    # Scale is in percentage relative to the primary chromaticity purity, where
    # 0.0 represents no inset scale.
    default_rotate = [4.5, -0.5, -2.0]
    default_inset = [0.15, 0.10, 0.10]
    default_outset = default_inset
```

```
# sobotka/SB2383-Configuration-Generation : generate_config.py:503-508
    y_LUT = sigmoid.calculate_sigmoid(
        x_input,
        pivots=[args.fulcrum_input, args.fulcrum_output],
        slope=args.fulcrum_slope,
        powers=[args.exponent_toe, args.exponent_shoulder],
    )
```

**【作者论述】CLI 文档给出的 fulcrum 默认值：**

> URL: https://github.com/sobotka/SB2383-Configuration-Generation/blob/main/README.md
> 原文（第 38-63 行）：
> ```
> ### **-fs FULCRUM_SLOPE, --fulcrum_slope FULCRUM_SLOPE**
> Set central section rate of change as rise over run slope (default: 2.4)
> ...
> ### **-fi FULCRUM_INPUT, --fulcrum_input FULCRUM_INPUT**
> Input fulcrum point relative to the normalized log2 range (default: 0.606060606061)
> ...
> ### **-fo FULCRUM_OUTPUT, --fulcrum_output FULCRUM_OUTPUT**
> Output fulcrum point relative to the normalized log2 range (default: 0.4586564468643811)
> ```

### 2.4 (d) look（Punchy 等）

**【源码事实】Blender 的 `AgX - Punchy` look 内容（Ocio 原语，非手写数学）：**

```
# release/datafiles/colormanagement/config.ocio:1343-1351
  - !<Look>
    name: AgX - Punchy
    process_space: AgX Log
    description: A darkening punchy look
    transform: !<GroupTransform>
      children:
        - !<GradingToneTransform>
         shadows: {rgb: [0.2, 0.2, 0.2], master: 0.35, start: 0.4, pivot: 0.1}
        - !<CDLTransform> {power: [1.0912, 1.0912, 1.0912]}
```

**【源码事实】look 的介入点 = `process_space: AgX Log`**（config.ocio:1345）。即 look 在 **AgX Log 编码域**（= primaries inset + log2 编码之后）执行；对比度 look 用 `GradingPrimaryTransform` + `style: log` + `pivot: {contrast: -0.2}`（config.ocio:1369-1373 等）。

**【源码事实】Blender 侧 look 的解析：`"AgX - Punchy"` 被拆成 view = `"AgX"`、look UI 名 = `"Punchy"`**（`view_specific_look.cc:11-19`，见 §1.2），因此 look 是"绑定到 view 的 look"，只在该 view 下可选。`colormanagement.cc` 中的兼容性判定：

```cpp
// source/blender/imbuf/intern/colormanagement.cc:478-492
static bool colormanage_compatible_look(const ocio::Look *look, StringRef view_filter)
{
  if (look->is_noop) {
    return false;
  }

  /* Skip looks only relevant to specific view transforms.
   * If the view transform has view-specific look ignore non-specific looks. */
  return view_filter.is_empty() ? look->view().is_empty() : look->view() == view_filter;
}

static bool colormanage_compatible_look(const ocio::Look *look, const char *view_name)
{
  const StringRef view_filter = view_filter_for_look(view_name);
  return colormanage_compatible_look(look, view_filter);
}
```

**【作者论述】look 的设计意图：**

> URL: https://github.com/EaryChow/AgX/blob/main/README.md
> 原文（第 81-88 行）：
> ```
> **Looks**
> "Looks" are artistic adjustments to the image formation chain. 
>
> - `Punchy` A contrast look that makes the image look more “punchy” by darkening the entire image.
>
> - `Greyscale` Turn the image into greyscale. Luminance coefficients are BT.2020’s CIE 2012 values, evaluated in Linear state.
>
> -  Seven Contrast Looks. Similar to Filmic’s contrast looks. All operates in AgX Log, with pivot set in 0.18 middle grey. All using OCIO v2’s `Grading Primary Transform` feature, meaning you can customize your contrast just by editing the values in the config.
> ```

### 2.5 (e) 显示变换 / output transform

**【源码事实】AgX view transform 是 `from_scene_reference` 类型 ⇒ 它是 scene-referred → display-referred 的完整变换**（config.ocio:332, 344, 356, 369, 381）。终点是 `cie_xyz_d65_interchange`（OCIO v2 的 display linear 中间空间）：

```
# release/datafiles/colormanagement/config.ocio:349   (AgX Base Rec.1886)
        - !<ColorSpaceTransform> {src: Rec.1886, dst: cie_xyz_d65_interchange}
# release/datafiles/colormanagement/config.ocio:337   (AgX Base Rec.2020)
        - !<ColorSpaceTransform> {src: Rec.2020, dst: cie_xyz_d65_interchange}
# release/datafiles/colormanagement/config.ocio:361-362 (AgX Base Display P3)
        - !<ExponentTransform> {value: 2.4}
        - !<ColorSpaceTransform> {src: Linear DCI-P3 D65, dst: cie_xyz_d65_interchange}
# release/datafiles/colormanagement/config.ocio:374   (AgX HDR HLG)
        - !<ColorSpaceTransform> {src: Rec.2100-HLG, dst: cie_xyz_d65_interchange}
```

**【源码事实】最终的 display 编码（OETF）由 display colorspace 的 `from_display_reference` 完成：**

```
# release/datafiles/colormanagement/config.ocio:132-147
  - !<ColorSpace>
    name: sRGB
    aliases: [srgb_rec709_display, srgb_rec709_scene, sRGB I-D65, srgb_display, sRGB - Display, Utility - sRGB - Texture, sRGB - Texture, srgb_tx, srgb_texture, Input - Generic - sRGB - Texture, "sRGB Display: 2.2 Gamma - Rec.709"]
    family: Display
    equalitygroup:
    bitdepth: 32f
    description: |
      sRGB IEC 61966-2-1 compound (piece-wise) encoding
    isdata: false
    interop_id: srgb_rec709_display
    interchange:
      icc_profile_name: srgb_rec709_display.icc
    from_display_reference: !<GroupTransform>
      children:
        - !<ColorSpaceTransform> {src: cie_xyz_d65_interchange, dst: Linear Rec.709}
        - !<ExponentWithLinearTransform> {gamma: 2.4, offset: 0.055, direction: inverse}
```

```
# release/datafiles/colormanagement/config.ocio:166-181
  - !<ColorSpace>
    name: Rec.1886
    aliases: [g24_rec709_display, BT.1886, BT.1886 2.4, BT.1886 EOTF, BT.1886 I-D65, Rec.1886 / Rec.709 Video - Display, rec1886_rec709_display, rec1886_rec709_video_display, Rec.1886 Rec.709 - Display, "Rec1886: 2.4 Gamma - Rec.709"]
    family: Display
    equalitygroup:
    bitdepth: 32f
    description: |
      BT.1886 2.4 Exponent EOTF Display, commonly used for TVs
    isdata: false
    interop_id: g24_rec709_display
    interchange:
      icc_profile_name: g24_rec709_display.icc
    from_display_reference: !<GroupTransform>
      children:
        - !<ColorSpaceTransform> {src: cie_xyz_d65_interchange, dst: Linear Rec.709}
        - !<ExponentTransform> {value: 2.4, direction: inverse}
```

```
# release/datafiles/colormanagement/config.ocio:183-198
  - !<ColorSpace>
    name: Rec.2020
    aliases: [g24_rec2020_display, blender:g24_rec2020_display, BT.2020, BT.2020 2.4, BT.2020 I-D65, Rec.1886 / Rec.2020 Video - Display, rec1886_rec2020_display, rec1886_rec2020_video_display, Rec.1886 Rec.2020 - Display, "Rec1886: 2.4 Gamma - Rec.2020"]
    family: Display
    equalitygroup:
    bitdepth: 32f
    description: |
      BT.2020 2.4 Exponent EOTF Display
    isdata: false
    interop_id: blender:g24_rec2020_display
    interchange:
      icc_profile_name: g24_rec2020_display.icc
    from_display_reference: !<GroupTransform>
      children:
        - !<ColorSpaceTransform> {src: cie_xyz_d65_interchange, dst: Linear Rec.2020}
        - !<ExponentTransform> {value: 2.4, direction: inverse}
```

```
# release/datafiles/colormanagement/config.ocio:149-164
  - !<ColorSpace>
    name: Display P3
    aliases: [srgb_p3d65_display, Display P3 2.2, Display P3 I-D65, P3-D65 - Display, p3_d65_display, p3d65_display, AppleP3 sRGB OETF]
    family: Display
    equalitygroup:
    bitdepth: 32f
    description: |
      Apple's Display P3 with sRGB compound (piece-wise) encoding transfer function, common on Mac devices
    isdata: false
    interop_id: srgb_p3d65_display
    interchange:
      icc_profile_name: srgb_p3d65_display.icc
    from_display_reference: !<GroupTransform>
      children:
        - !<ColorSpaceTransform> {src: cie_xyz_d65_interchange, dst: Linear DCI-P3 D65}
        - !<ExponentWithLinearTransform> {gamma: 2.4, offset: 0.055, direction: inverse}
```

**【源码事实】LUT 的输出本身就是 power 2.4 编码的显示信号：**

```
# release/datafiles/colormanagement/luts/AgX_Base_sRGB.cube:4
# But the end image formation will be from -10 to 6.5 encoded in power 2.4
```

（`AgX_Base_Rec2020.cube:4`、`AgX_Base_P3.cube:4` 逐字相同；HDR 版本改为 `encoded in Rec.2100-HLG`，见 `AgX_Rec2100-HLG_p3_lim.cube:4`。）

**【源码事实】因此 `AgX Base Display P3` 在 cube 之后显式加了一步 `ExponentTransform {value: 2.4}`**（config.ocio:361），把 cube 输出的 power-2.4 编码 P3 信号**解码**回 `Linear DCI-P3 D65`，再由最后一步转 XYZ。这与 `AgX_Base_P3.cube:4` 的 "encoded in power 2.4" 自洽。

**【推断】display 侧存在"二次编码"：** 以 sRGB 显示 + `AgX Base Rec.1886` 为例：cube 输出 power-2.4 编码的 Rec.709 信号 → `ColorSpaceTransform {src: Rec.1886, dst: cie_xyz_d65_interchange}` 将其按 Rec.1886 解码为线性 → 转 XYZ D65 → display colorspace `sRGB` 再按 sRGB 分段函数（gamma 2.4 + 0.055 offset）编码。即"先按 BT.1886 编码烘焙，再重编码为 sRGB"。这是从 config 逐字结构推出的，非文档明述。

---

## 3. 原作者的参考实现与设计说明

### 3.1 `sobotka/AgX` README（作者指向理论文档）

> URL: https://github.com/sobotka/AgX/blob/main/README.md
> 原文（全文，第 1-12 行）：
> ```
> # Information
>
> Configuration generator with information and
> explanations for the curious, can be found
> [here](https://github.com/sobotka/AgX-S2O3).
> The theory is important if one wants to try
> and understand what specific experimental
> problem the approach outlined in this
> repository sought to overcome.
>
> A demonstration, with tunings, can be found
> [here](https://github.com/sobotka/SB2383-Configuration-Generation).
> ```

### 3.2 `sobotka/AgX-S2O3` README：为什么先做 primaries inset / rotation

> URL: https://github.com/sobotka/AgX-S2O3/blob/main/README.md
> 原文（第 55-90 行）：
> ```
> # Rotations, Insets, and Outsets
>
> The key part of the mechanisms in this mausoleum can be distilled down to
> two crucial parts:
> * Additive stimuli
> * Rotation and control over the coordinates
>
> The effort within this vault was an experiment to address *cognitive* issues
> with pictorial depictions, and [the way we use algorithms to form those
> pictorial depictions](https://github.com/sobotka/scise/wiki/Picture-Formation).
>
> ## Additive Stimuli
>
> By using a simplistic matrix approach, we can add additive energy to the opponent stimuli. This relates to our segmentation-decomposition cognitive mechanisms, and formulates a cognitive "grammar" when a channel by channel lookup or formula is employed.
>
> ## Rotation and Control of the Basis Vectors
>
> Permitting an author to control the basis coordinates is key for authorship.
> Our cognitive machinations that yield segmentation-decomposition are, at best, poorly understood.
> Worse, our existing stimuli-centric models are extremely impoverished. As such, it is absolutely imperative
> that the picture formation approach allows an author to control the nature of
> the formation for their specific pictorial needs. At the same time, ergonomics
> are crucially important.
>
> The technique employed herein attempts to balance ergonomics with adjustment.
> Authorial control is key, and this is lost when an author is faced with a
> jet cockpit level of controls that ultimately fail to affect the territory
> in question. The foolish would model the universe in an attempt to emulate
> creative chemical film, only to discover that a well designed matrix will
> exceed the performance of the Universe Emulator, with a much higher probability
> of fewer cognitive errors.
>
> Any attempt to harness the ideas within this archive should expose these
> rotation and inset parameters. If one does not understand why, one would do
> well to try and understand how they interact with the channel by channel 
> mechanisms.
> ```

同 README 关于"曲线本身不重要"（即 sigmoid 形式不是设计核心）：

> URL: https://github.com/sobotka/AgX-S2O3/blob/main/README.md
> 原文（第 26-53 行节选）：
> ```
> # Curves are Meaningless
>
> Generally speaking, the nature of the structure of some random curve
> is completely inconsequential in terms of an *affecting* force. ...
> ...
> The original sigmoidal curve within this repository was originally authored by
> Jed Smith. It can be swapped out with anything that patterns a general
> sigmoidal response in theory. The interactions that create the final stimuli triplet are complexly intertwined with several variables, and as such, no degree of "precision" can afford much utility.
> ```

同 README 列出的采用方（含 Blender）：

> URL: https://github.com/sobotka/AgX-S2O3/blob/main/README.md
> 原文（第 8-16 行）：
> ```
> The concepts in this repository are found in:
> * Adobe Substance Painter
> * Autodesk VRed
> * Autodesk Alias Studio
> * Marmoset Toolbag
> * Godot Game Engine
> * Blender
> * Avoyd Voxel Editor
> * Etc.
> ```

### 3.3 `SB2383-Configuration-Generation` README：inset / rotate 的作用（"为什么"）

> URL: https://github.com/sobotka/SB2383-Configuration-Generation/blob/main/README.md
> 原文（第 83-99 行）：
> ```
> ### **-pi PRIMARIES_INSET PRIMARIES_INSET PRIMARIES_INSET, --primaries_inset PRIMARIES_INSET PRIMARIES_INSET PRIMARIES_INSET**
> Percentage of scaling inset for the primaries (default: [0.15, 0.15, 0.1])
>
> #### **Description**
> Three values that dictate how much each "primary" is inset relative to the specified working space.
>
> #### **Visual Impact**
> Primarily influences the chromatic attenuation / amplification. Higher values toward one will yield more rapid attenuation of chroma, while lower values will relax, potentially to the point of posterization and picture breakup.
>
> ### **-pr PRIMARIES_ROTATE PRIMARIES_ROTATE PRIMARIES_ROTATE, --primaries_rotate PRIMARIES_ROTATE PRIMARIES_ROTATE PRIMARIES_ROTATE**
> Rotational adjustment in degrees for each of the RGB primaries, positive counterclockwise, negative clockwise (default: [1.75, -0.5, -1.0])
>
> #### **Description**
> Three values that dictate how much each "primary" is rotated in relation to the resulting working space.
>
> #### **Visual Impact**
> Primarily influences the flight of hue toward compliments, and the rate at which the hues traverse.
> ```

### 3.4 `SB2383` README：为什么用 log 编码（interstitial log2 encoding）

> URL: https://github.com/sobotka/SB2383-Configuration-Generation/blob/main/README.md
> 原文（第 47-57 行）：
> ```
> ### **-fi FULCRUM_INPUT, --fulcrum_input FULCRUM_INPUT**
> Input fulcrum point relative to the normalized log2 range (default: 0.606060606061)
>
> #### **Description**
> The input value relative to the interstitial log2 encoding range. Given an achromatic sweep of values, input "middle grey".
>
> #### **Visual Impact**
> Influences value mapped to "middle grey" in relation to the output value.
> ```

同 README 关于曝光范围（`-ll` / `-lh`）：

> URL: https://github.com/sobotka/SB2383-Configuration-Generation/blob/main/README.md
> 原文（第 65-81 行节选）：
> ```
> ### **-ll LIMIT_LOW, --limit_low LIMIT_LOW**
> Lowest value of the normalized log2 range (default: -10.0)
> ...
> ### **-lh LIMIT_HIGH, --limit_high LIMIT_HIGH**
> Highest value of the normalized log2 range (default: 6.5)
> ```

生成器源码里的同义注释（log 编码"创建一组新的色度比值"）：

```
# sobotka/SB2383-Configuration-Generation : generate_config.py:60-73
    # The input pivot point of the interstitial encoding. That is, the first stage
    # is to take the input data to a normalized log2 encoding, which creates a new
    # set of colourimetric ratios. This position is classically considered the
    # "middle" range of the soon-to-be picture / image. Here it is simply set to
    # be the "exposure" zero point of the incoming signal.
    default_x_pivot = numpy.abs(default_normalized_log2_minimum) / (
        default_normalized_log2_maximum - default_normalized_log2_minimum
    )

    # The output, or ordinate, value of the transformation. We use a simple bog
    # standard display encoding value as our final "middle" perceptual anchor.
    # Maximization of the signal to the quantised expression range should be
    # considered.
    default_y_pivot = 0.18 ** (1.0 / 2.2)
```

### 3.5 Troy Sobotka 的 Picture Formation 定义（scise wiki）

> URL: https://github.com/sobotka/scise/wiki/Picture-Formation
> 原文（节选）：
> ```
> ## Picture Formation
> The *creation* and *integration* of [distal stimuli](https://github.com/sobotka/scise/wiki/Glossary#distal-stimuli) within a stimuli specification [encoding](https://github.com/sobotka/scise/wiki/Glossary#encoding), to be [presented](#presented) as a uniquely transformed [distal stimuli](https://github.com/sobotka/scise/wiki/Glossary#distal-stimuli) object embedded in a broader [spatiotemporal](https://github.com/sobotka/scise/wiki/Glossary#spatiotemporal) [articulation](https://github.com/sobotka/scise/wiki/Glossary#articulation).
> ```

### 3.6 Blender 引入 AgX 的 PR 描述

**原始 PR（设计目标、"为什么"）：**

> URL: https://projects.blender.org/blender/blender/pulls/106355 （`Replace Default OCIO config with AgX (Filmic v2)`，作者 Eary，创建于 2023-03-31）
> 原文（节选）：
> ```
> **What?**
>
> Eary's AgX is an OCIO v2 configuration made with the intention to replace Blender's default config, to give Blender a better color management and image formation for the upcoming Spectral Cycles.
> ...
> `AgX` Image formation does two things
> - It forms a [0.0, 1.0] closed domain image from the unbounded radiometric-like tristimulus data that modern 3D render engines like Cycles and Eevee produce. 
> - It provides smooth chromatic attenuation in the image across challenging use cases including wider gamut rendering, real-camera-produced colorimetry etc.
> ...
> **Why?**
>
> Because the current Filmic has issues like the Notorious Six, meaning Filmic collapses all colors into six before attenuating to white. Filmic also doesn't have the capability to handle wider gamut render produced by wider working space, spectral rendering, real-camera-produced colorimetry etc. 
> ...
> - `AgX Log` The Log encoding with chroma-inset and rotation of primaries included. Uses BT.2020 primaries with Log 2 encoding from `-12.47393` to `12.5260688117`(25 stops of dynamic range) and I-D65 white point. 
> ...
> - `Punchy` A contrast look that makes the image look more “punchy” by darkening the image. 
> - `Greyscale` Turn the image into greyscale. Luminance coefficients are BT.2020’s CIE 2012 values, evaluated in Linear state.
> -  Seven Contrast Looks. Similar to Filmic’s contrast looks. All operates in AgX Log, with pivot set in 0.18 middle grey. All using OCIO v2’s `Grading Primary Transform` feature, meaning you can customize your contrast just by editing the values in the config. 
> ```

**落地 PR（Step 5，含负值处理/显示设备/False Color 替换）：**

> URL: https://projects.blender.org/blender/blender/pulls/111099 （`AgX-Step5: Add AgX and its components`，作者 Eary，创建 2023-08-14，合并 2023-08-22）
> 原文（节选）：
> ```
> This is the step 5 of the AgX implementation as discussed in the [original AgX PR](https://projects.blender.org/blender/blender/pulls/106355#issuecomment-984699)
>
> > step 5. Add AgX view and its components.
> > Some components are added as reusable utility parts for view transforms to use, will use OCIOv2's inactive_colorspaces to hide them from UI.
> ...
> This PR adds the following:
>
> - Negative value handling (lower Guard Rails):
>   - Luminance Compensation Rec.2020
>   - Luminance Compensation sRGB
>   - Luminance Compensation P3
>
>   All of the above are hidden from the colorspace list UI by listing them under `inactive_colorspaces`
> ...
> - AgX Log
>
> - AgX View Transforms for the different display devices
>   - AgX Base Rec.2020
>   - AgX Base sRGB
>   - AgX Base Display P3
>   - AgX Base Rec.1886
> ...
> - Change default startup to use AgX instead of Filmic by default.
> ```

**AgX HDR 补充（当前 HEAD 的 `AgX - HDR 1000 nits` / `AgX - SDR`）：**

> URL: https://api.github.com/repos/blender/blender/commits/b6ffbc5b38d080597f1b038aec909f7e711a834a
> 原文：
> ```
> SHA: b6ffbc5b38d080597f1b038aec909f7e711a834a
> DATE: 2025-08-22T15:51:36Z
> AUTHOR: Zijun Zhou
> --- MESSAGE ---
> Color Management: Add AgX HDR view transform
>
> For the Rec.2100 PQ and HLG displays. There are also AgX SDR versions for
> these displays.
>
> Pull Request: https://projects.blender.org/blender/blender/pulls/142758
> --- FILES ---
> modified release/datafiles/colormanagement/config.ocio +63 -2
> added release/datafiles/colormanagement/luts/AgX_Rec2100-HLG_p3_lim.cube +185204 -0
> ```

### 3.7 EaryChow/AgX README：AgX 做什么（Blender 采用版的作者论述）

> URL: https://github.com/EaryChow/AgX/blob/main/README.md
> 原文（第 22-24 行）：
> ```
> `AgX` Image formation does two things
> - It forms a [0.0, 1.0] closed domain image from the unbounded radiometric-like tristimulus data that modern 3D render engines like Cycles and Eevee produce. 
> - It provides smooth chromatic attenuation in the image across challenging use cases including wider gamut rendering, real-camera-produced colorimetry etc.
> ```

同 README（第 92-94 行）关于 reference space 与 image formation 空间：

> URL: https://github.com/EaryChow/AgX/blob/main/README.md
> 原文：
> ```
> - `Reference` Every OCIO config has their own reference space, all other spaces are defined with how they transform “from” and/or “to” the reference space.  While Blender’s previous config has been using `Linear Rec.709` as reference, this config uses `1931 CIE XYZ E white point chromaticity` as reference. This is a sane decision, since CIE XYZ is the root for everything else color management related. FilmLight’s TCAMv2 config also has CIE XYZ as reference. 
>
> - `AgX Base image formation space` The AgX in this config has one single image formed in the BT.2020 display medium, then the images for other mediums are produced from the formed image in BT.2020.
> ```

**【未找到】** `sobotka/AgX-Sandbox`、`sobotka/agx-base-research`（题目点名的两个仓库）在 GitHub 上均不存在（§0.2）。**未找到** Troy Sobotka 在 AcademySoftwareFoundation 组织下的公开 AgX 讨论原文（未在 ASWF 仓库/讨论中找到以 AgX 为主题的作者帖；本报告未做 Slack 检索）。**未找到** Blender devtalk 官方帖的逐字引用（本报告未检索 devtalk，仅使用 PR 描述与作者仓库 README）。

---

## 4. 关键定位结论

### 4.1 AgX 是 "scene-referred → display-referred" 的完整显示变换吗？

**【源码事实】是。** 证据链：

1. 它被声明为 OCIO v2 的 `!<ViewTransform>`，且用 `from_scene_reference`（config.ocio:331-332, 343-344, 355-356, 368-369, 380-381）——OCIO 语义即 scene reference → display reference。
2. 链路终点是 `cie_xyz_d65_interchange`（display linear 中间空间），再由 display colorspace 完成最后的显示编码（§2.5）。
3. Blender 手册对 View 的定位：

> URL: https://projects.blender.org/blender/blender-manual/raw/branch/main/manual/render/color_management/displays_views.rst
> 原文：
> ```
> Overview
> ========
>
> To display an image, it must be converted from
> :ref:`scene linear <color-management-linear-space>` to a display color space.
> This involves both technical and artistic choices.
> ```

### 4.2 AgX 内部是否包含 OETF/gamma？

**【源码事实】包含。** 两层证据：

**证据 1 —— LUT 头注释自述输出为 power 2.4 编码：**

```
# release/datafiles/colormanagement/luts/AgX_Base_sRGB.cube:4
# But the end image formation will be from -10 to 6.5 encoded in power 2.4
```

```
# release/datafiles/colormanagement/luts/AgX_Rec2100-HLG_p3_lim.cube:4
# But the end image formation will be from -10 to 6.5 encoded in Rec.2100-HLG
```

**证据 2 —— `AgX Base Display P3` 在 cube 之后用 `ExponentTransform {value: 2.4}` 把该编码解码回线性**（config.ocio:360-362）；`AgX Base Rec.1886` / `AgX Base Rec.2020` 则用 `ColorSpaceTransform {src: Rec.1886|Rec.2020, dst: cie_xyz_d65_interchange}` 完成同样的解码（config.ocio:348-349, 336-337）。若 cube 输出仍是线性，这两步在数值上不成立。

**证据 3 —— `AgX Base Rec.2020` 的 description 直接写 "Gamma 2.4"：**

```
# release/datafiles/colormanagement/config.ocio:330-331
    description: |
      AgX Base Image Encoding for display with Gamma 2.4 and Rec.2020 gamut
```

**【源码事实】注意分工**：cube 内部烘焙的是 **power 2.4 编码**；最终的 **sRGB 分段 OETF / Display P3 sRGB OETF / BT.1886 2.4 OETF / HLG / PQ** 由 display colorspace 的 `from_display_reference` 施加（config.ocio:146-147 [sRGB], 163-164 [Display P3], 180-181 [Rec.1886], 197-198 [Rec.2020]）。所以"AgX 内部含 gamma"成立，但"AgX 单独决定最终显示器编码"不成立——后者由 display colorspace 决定。

### 4.3 用户 3D LUT（display-referred .cube）按设计应该在 AgX 之前还是之后？

**【未找到】Blender 官方手册/源码中未找到关于"用户 display-referred 3D LUT 应放在 AgX 之前还是之后"的明确说明。**

检索证据：

```
$ cd /private/tmp/blender-agx/manual && grep -rn -i -E "3d lut|3d-lut|\.cube|color lookup|lut file|apply.*lut" manual --include=*.rst
manual/modeling/meshes/uv/editing.rst:334:- :ref:`bpy.ops.uv.cube_project`
manual/modeling/meshes/editing/uv.rst:247:.. _bpy.ops.uv.cube_project:
... (其余为 "resolution"/"solution" 等词形命中，无 LUT 语义)
```

```
$ cd /private/tmp/blender-agx/bsrc && grep -rn "\.cube" --exclude-dir=.git .
./release/datafiles/colormanagement/config.ocio:253:  ... AgX_Base_sRGB.cube ...
./release/datafiles/colormanagement/config.ocio:305:  ... pbrNeutral.cube ...
... （全部命中都在 config.ocio 的 FileTransform 里，外加 intern/dualcon/intern/Projections.h:62 的一条无关注释 "CubeTriangleIsect.cubeProj"）
```

即：**Blender 源码中不存在"用户加载 .cube 文件作为 look/LUT"的代码路径**；`.cube` 只作为 OCIO config 内部 `FileTransform` 的资源出现。Color Management UI 的 Look 只是一个枚举：

```cpp
// source/blender/editors/interface/templates/interface_template_color_management.cc:65
  col->prop(&view_transform_ptr, "look", UI_ITEM_NONE, IFACE_("Look"), ICON_NONE);
```

**【文档事实】但 Blender 手册明确给出了各 Color Management 项的先后顺序（这是最接近"LUT 位置"的官方陈述）：**

> URL: https://projects.blender.org/blender/blender-manual/raw/branch/main/manual/render/color_management/displays_views.rst
> 原文：
> ```
> Look
> ----
>
> Choose an artistic effect from a set of measured film response data
> which roughly emulates the look of certain film types. Applied before color space conversion.
> ```
> ```
> Gamma
> -----
>
> Extra gamma correction applied after color space conversion.
> Note that the default display transforms already perform the appropriate conversion,
> so this mainly acts as an additional effect for artistic tweaks.
> ```
> ```
> Curves
> ------
>
> Adjust RGB Curves to control the image colors before the color space conversion.
> Read more about using the :ref:`ui-curve-widget`.
> ```
> ```
> Exposure
> --------
>
> Used to control the image brightness (in stops) applied before color space conversion.
> ```

**【推断】（标注为推断）** 结合上述事实：
1. Blender 中"look"（含 LUT 型 look）的官方位置是 **view transform（= AgX）之前**，处在 scene-linear / AgX Log 域。
2. AgX 的输出已经是 power-2.4 / display-referred 编码信号（§4.2），其后再经 display colorspace 完成最终 OETF。
3. 因此一个**输入/输出都假定为 display-referred 的 .cube**，若按 Blender 的 Look 位置放在 AgX **之前**，其输入域（scene-linear / log）与 LUT 假定域不匹配；把它放在 AgX **之后**在色彩上自洽，但 Blender 没有提供"在 AgX 之后插入用户 LUT"的官方 UI 入口（源码中无 `.cube` 加载路径）。
4. 若必须用 Blender 内置链路，可行做法是：把 LUT 注册进 OCIO config 作为一个 look（`process_space` 设为 AgX Log 或 display 空间），或用 `Standard` view transform（不做 tone mapping）+ 在显示后处理阶段套用 LUT —— 但这是**工程推断，不是 Blender 文档的规定**。

**【结论】按 Blender 的设计，用户 LUT 作为 look 的位置是 AgX 之前（官方手册明述 "Applied before color space conversion"）；而"display-referred LUT 应该在 AgX 之后"这一命题在 Blender 文档/源码中未找到明确说明，只能由 §4.2 的事实推出（推断）。**

---

## 附录 A：证据索引（文件 → 关键行）

| 文件 | 行 | 内容 |
|---|---|---|
| `release/datafiles/colormanagement/config.ocio` | 11-13 | AgX 出处声明 |
| 同上 | 51-53 | roles: color_timing / compositing_log = AgX Log |
| 同上 | 58-114 | displays 中全部 AgX view 绑定 |
| 同上 | 116-117 | active_views / inactive_colorspaces |
| 同上 | 132-198 | display colorspaces: sRGB / Display P3 / Rec.1886 / Rec.2020 |
| 同上 | 242-254 | `AgX Base sRGB`（View Inverse，display_colorspaces） |
| 同上 | 327-338 | `AgX Base Rec.2020` view transform |
| 同上 | 339-349 | `AgX Base Rec.1886` view transform |
| 同上 | 351-362 | `AgX Base Display P3` view transform |
| 同上 | 364-374 | `AgX Rec.2100-HLG - HDR 1000 nits (P3 D65)` |
| 同上 | 376-390 | `AgX False Color Rec.709` |
| 同上 | 600-613 | `Linear FilmLight E-Gamut` |
| 同上 | 1222-1238 | `Luminance Compensation Rec.2020` |
| 同上 | 1240-1260 | `AgX Log`（含 inset 矩阵） |
| 同上 | 1343-1444 | AgX looks（Punchy / Greyscale / 7×Contrast） |
| `release/datafiles/colormanagement/luts/AgX_Base_sRGB.cube` | 1-9 | TITLE / 注释 / DOMAIN / LUT_3D_SIZE 57 |
| 同上（Rec2020 / P3） | 1-9 | 同上 |
| `luts/AgX_Rec2100-HLG_p3_lim.cube` | 1-11 | HDR 参数注释 |
| `luts/luminance_compensation_bt2020.cube` | 1-6 | Guard Rail 下半段说明，LUT_3D_SIZE 37 |
| `source/blender/imbuf/opencolorio/intern/view_specific_look.cc` | 9-22 | `split_view_specific_look` 实现 |
| `source/blender/imbuf/opencolorio/intern/view_specific_look_test.cc` | 27-39 | `"AgX - Punchy"` 字符串测试 |
| `source/blender/imbuf/intern/colormanagement.cc` | 478-492 | look 与 view 的兼容性判定 |
| `source/blender/editors/interface/templates/interface_template_color_management.cc` | 65 | Look 只是 enum prop |
| `source/blender/blenkernel/BKE_blender_version.h` | 23,25,27 | `BLENDER_VERSION 503` / `PATCH 0` / `CYCLE alpha` |

## 附录 B：明确"未找到"清单

1. **【未找到】** Blender 源码中任何 C++/GLSL/OSL 形式的 AgX 算法实现（全仓 `grep -ril agx` 只命中 1 个测试文件）。
2. **【未找到】** Blender 版 AgX 的 sigmoid / inset 曲线**生成器源码**（Blender 仓库与 `EaryChow/AgX` 仓库均无 `.py` 生成器；只有烘焙好的 `.cube`）。
3. **【未找到】** `sobotka/AgX-Sandbox` 与 `sobotka/agx-base-research` 仓库（GitHub 返回 Repository not found）。
4. **【未找到】** Blender 官方手册/源码中关于"用户 display-referred 3D LUT 应在 AgX 之前还是之后"的明确说明。
5. **【未找到】** Blender 源码中"加载用户 `.cube` 作为 look"的代码路径（`.cube` 仅出现在 config.ocio 的 `FileTransform`）。
6. **【未找到】** Troy Sobotka 在 AcademySoftwareFoundation 组织下的 AgX 专题公开讨论原文（本报告未检索 ASWF Slack，也未在 ASWF 公开仓库中找到以 AgX 为主题的作者帖）。
7. **【未找到】** Blender devtalk 官方帖的逐字引用（本报告未检索 devtalk；使用 PR 描述 + 作者仓库 README 替代）。
