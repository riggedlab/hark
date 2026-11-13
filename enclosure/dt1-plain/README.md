# Hark DT-1 外壳 · 简易款

平面小牌，没有图案，推荐用**黑色普通 PLA** 打印。外形 23.7 × 32.8 × 15 mm。

| 文件 | 内容 |
|---|---|
| `parts/case-front.stl` | 面盖 |
| `parts/case-back.stl` | 底盒 |
| `hark-dt1-plain-K3.3mf` | Creality Print 工程：K3、0.4mm 喷嘴、普通 PLA、首层 0.2mm 之后 0.16mm、3 墙、100% 填充、3mm brim、无支撑。估算约 24 分钟、5.8g |
| `previews/` | 渲染图（渲染颜色不代表实际耗材颜色） |

用 Creality Print 打开 3MF 时选「打开工程」，保持 100% 尺寸。其他打印机直接导入两个 STL：面盖正面朝下，底盒背面朝下。

接线、装配和注意事项见 [docs/hardware.md](../../docs/hardware.md)。

状态：第一版实物试装左右合适，但面盖会前后滑。现在的文件已在底盒里加挡块、加宽撬口，这一版还没重新打印验证。

## 自己改尺寸 / 重新生成

使用 **Blender 5.2.2 LTS**（已实测），不需要另外安装 Python 包。在仓库根目录运行：

```sh
blender -b --factory-startup --python enclosure/build_dt1.py -- --variant plain --check --blend --out /tmp/hark-dt1-build
```

两个 STL 在 `/tmp/hark-dt1-build/dt1-plain/parts/`，同级还有装配状态的 `.blend` 和安全检查结果 `checks.json`。省略 `--blend` 就只输出 STL 和检查结果；省略 `--out` 会新建临时目录并打印位置。脚本拒绝覆盖仓库里的两款成品目录。

主要参数在 [`../build_dt1.py`](../build_dt1.py) 开头：`PACK_*` 是含电池、线和胶带的实测包装尺寸，`FIT_*` 是总余量，`WALL_SIDE` / `FRONT` / `LID_T` 是壁厚。默认内腔 21.3 × 23.3 × 11.8 mm，由 20.5 × 24.5 × 11.0 mm 加余量、再扣掉 USB 伸出量得到。孔位、插舌和防滑挡块也在同一区域；它们不会随内腔等比缩放，换板后要按实际位置调整。

`--check` 会和仓库现有 STL 比较：量化到 0.001 mm 后，每个三角面都必须相同；有差异会打印 `DIFFERENT` 并返回失败。自己改尺寸后应去掉 `--check`，流形（表面封闭、每条边恰好接两个面）、单一连通、壁厚和装配不干涉检查仍会运行。其他 Blender 版本可能改变布尔运算和三角化结果，先用默认参数跑 `--check` 确认。把 `plain` 换成 `all` 可以一次生成两款。
