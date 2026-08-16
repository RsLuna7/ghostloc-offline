# GhostLoc Offline 模块说明

这份文档面向模块使用者，说明这个模块能做什么、如何工作、如何操作，以及它当前的适用边界。

## 1. 模块定位

`GhostLoc Offline` 是一个运行在 LSPosed/libxposed 环境下的离线定位控制模块。

它的目标不是联网授权，也不是云端下发，而是在**自有测试设备**上，提供一个可控、可重复、可离线工作的定位环境。

当前版本：`0.2.0`（全 Kotlin 重写，含高德直连地图与 GCJ-02 纠偏）。

当前验证环境：

- 设备：`OnePlus7 / GM1901`
- 系统：`Android 15 / SDK 35`
- 框架：`LSPosed API 100`（Vector）

## 2. 模块能做什么

- 指定经纬度作为当前位置（速度、海拔、精度、方向可调）
- 地图选点：单击放点，长按放点并立即启动
- 高德地图/高德卫星/OpenStreetMap 三种瓦片源切换，国内直连无需代理
- 高德源拾取坐标自动 GCJ-02→WGS-84 纠偏（可关）
- 地点搜索（配置高德 Web 服务 Key 或使用系统 Geocoder）
- 多点路线移动、循环、暂停/恢复、自动朝向、速度波动
- 精度/海拔抖动，让输出更接近真实轨迹
- 收藏点管理（命名/分组，JSON 导入导出，兼容旧分号格式）
- Wi‑Fi 信息清理、蓝牙地址屏蔽
- 基站/网络类型伪装（LTE+NR 合成、运营商预设、ServiceState）
- GNSS 星座状态与 NMEA 语句合成
- `fused` provider 取点伪装、system_server 深层路径覆盖
- 应用内诊断（框架状态、最近命中/错误、一键 probe 自测）

## 3. 界面布局（0.2.0 四页签）

顶部常驻两行：模块服务连接状态、当前运行状态（空闲/已启用/路线移动中/已暂停）。

### 3.1 地图页

- 搜索框 + 搜索按钮
- 地图源下拉（高德地图 / 高德卫星 / OpenStreetMap）
- 全幅地图：单击放点、长按放点并立即开始
- “拾取坐标纠偏”开关：对高德源生效，默认开
- 快捷按钮：开始 / 停止 / 保存配置

### 3.2 参数页

- 位置：纬度 / 经度 / 速度 / 海拔 / 精度 / 方向
- 策略开关：Wi‑Fi 屏蔽、基站屏蔽、Mock 标记屏蔽、路线移动、5G/NR、NMEA、AGPS、厂商/Fused Hook
- 蜂窝与抖动：MCC / MNC / 国家 / 运营商 / GNSS 星数 / 速度波动 / 精度抖动 / 海拔抖动

### 3.3 路线/收藏页

- 路线：循环开关、自动方向角开关、路线 JSON 编辑
- 路线按钮：添加点位 / 保存路线 / 使用起点 / 清空路线 / 暂停 / 继续 / 保存配置
- 收藏：收藏 JSON 编辑 + 加入当前点 / 保存 JSON / 使用最近项 / 清空收藏

### 3.4 诊断页

- 框架服务、运行状态、最近进程、收藏/路线数量
- 最近一次 probe 验证结果与完整报告
- 最近 Hook 命中 / 错误记录
- 按钮：刷新 / 重载 / 仅启动验证 / 应用并验证 / 恢复默认
- 高德 Web 服务 Key 输入（用于搜索，本地保存，选填）

## 4. 典型使用流程

1. 在 LSPosed 中启用模块，勾选作用域，重启设备
2. 打开 GhostLoc，地图页选择瓦片源（默认高德地图）
3. 长按地图目标位置 → 自动放点并开始
4. 需要路线时：参数页打开“路线移动”，路线页编辑路线 JSON（或连续“添加点位”），开始
5. 诊断页“应用并验证”一键跑 probe，全绿即生效
6. 停止：地图页“停止”按钮

## 5. 自动化入口（Intent）

`MainActivity` 支持通过 Intent 传参自动应用（外部脚本/任务自动化）：

```text
dev.ghostloc.offline.extra.AUTO_APPLY      true 时自动提交
dev.ghostloc.offline.extra.ACTIVE          true/false 启动或停止
dev.ghostloc.offline.extra.LAT / LON       目标坐标
dev.ghostloc.offline.extra.MOVEMENT        路线移动开关
dev.ghostloc.offline.extra.ROUTE_JSON(_B64)        路线 JSON（可 base64）
dev.ghostloc.offline.extra.FAVORITES_JSON(_B64)    收藏 JSON（可 base64）
dev.ghostloc.offline.extra.ROUTE_LOOP / ROUTE_AUTO_BEARING / ROUTE_SPEED_VARIATION
dev.ghostloc.offline.extra.NMEA_ENABLED / VENDOR_HOOKS / NR_ENABLED
dev.ghostloc.offline.extra.FINISH          应用完成后自动退出
```

示例（shell 传浮点用 `--ef`，传 JSON 建议 base64）：

```bash
adb shell am start -n dev.ghostloc.offline/.MainActivity \
  --ez dev.ghostloc.offline.extra.AUTO_APPLY true \
  --ez dev.ghostloc.offline.extra.ACTIVE true \
  --ef dev.ghostloc.offline.extra.LAT 35.6895 \
  --ef dev.ghostloc.offline.extra.LON 139.6917
```

## 6. 重要注意事项

- **模块 APK 更新后必须重启设备**，框架才会加载新代码；只 `adb install -r` 不重启时，Hook 仍运行旧版本，UI 的停止/配置写入也会落到本地回退存储，出现“停不下来”的假象。
- **不要与原版 GhostMapX 模块同时启用**：两者 Hook 同一批 system_server 定位接口，会互相干扰。验证机上 GhostMapX 当前处于禁用状态。
- 高德瓦片与高德搜索需要设备能访问互联网；地图页空白时先检查网络。
- 作用域建议：`system_server(android)`、`com.android.phone`、`com.android.location.fused`（如存在）、`com.android.bluetooth`、`dev.ghostloc.probe`、`dev.ghostloc.demo`、模块自身。
