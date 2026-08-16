# GhostLoc Offline 模块说明

这份文档面向模块使用者，说明这个模块能做什么、如何操作，以及当前的适用边界。

## 1. 模块定位

`GhostLoc Offline` 是一个运行在 LSPosed/libxposed 环境下的离线定位控制模块，用于**自有测试设备**上提供可控、可离线工作的定位环境。不联网授权、不云端下发。

当前版本：`0.3.0`（全 Kotlin、实时状态同步、Material 3 界面）。

验证环境：OnePlus 7 / Android 15 / LSPosed(Vector) API 100。

## 2. 模块能做什么

- 指定经纬度作为当前位置（速度、海拔、精度、方向可调）
- **实时切换**：开始后改坐标立即生效，无需重启手机或目标应用
- 地图选点：单击放点、长按放点并立即开始；高德/OSM 双地图源
- 高德源拾取坐标自动 GCJ-02→WGS-84 纠偏（可关）
- 地点搜索（配置高德 Web 服务 Key，或系统 Geocoder）
- 多点路线移动、循环、暂停/继续、自动朝向、速度波动
- 精度/海拔抖动，让输出更像真实轨迹
- 收藏点（命名/分组、JSON 导入导出）
- Wi-Fi/蓝牙信息屏蔽、基站合成（LTE+NR、运营商按坐标自动匹配）
- GNSS 星座与 NMEA 语句合成
- 应用内诊断（框架状态、最近命中、一键 probe 自测）

## 3. 界面（0.3.0）

顶部青绿色状态卡：模块服务连接状态 + 当前运行状态（运行中为绿色加粗）。下方四个页签：

### 3.1 地图

- 搜索框 + 搜索按钮
- 地图源下拉（高德地图 / 高德卫星 / OpenStreetMap）
- 地图：**长按目标位置 = 放点并立即开始**，单击仅放点
- “拾取坐标纠偏”开关（默认开，只对高德源生效）
- 底部大按钮：**开始**（实心主按钮）/ 停止 / 保存配置

### 3.2 参数

- **位置**卡片：纬度 / 经度 / 速度 / 海拔 / 精度 / 方向
- **策略**卡片（4 个常用开关）：路线移动、Mock 标记屏蔽、Wi-Fi 屏蔽、基站屏蔽
- **高级设置**（默认折叠，点“高级设置 ▾”展开）：5G/NR、NMEA、AGPS、厂商 Hook 开关 + 蜂窝参数（MCC/MNC/国家/运营商）+ GNSS 星数/速度波动/精度抖动/海拔抖动

### 3.3 路线·收藏

- **路线**卡片：循环、自动方向角开关；路线 JSON 编辑；添加点位 / 保存路线 / 使用起点 / 清空路线 / 暂停 / 继续 / 保存配置
- **收藏**卡片：收藏 JSON 编辑 + 加入当前点 / 保存 JSON / 使用最近项 / 清空收藏

### 3.4 诊断

- 诊断信息卡（框架状态、运行状态、最近进程、probe 报告、最近命中/错误）
- 按钮：刷新 / 重载 / **应用并验证**（主按钮）/ 仅启动验证 / 恢复默认
- 高德 Web 服务 Key 输入卡（用于搜索，本地保存，选填）

## 4. 典型用法

1. LSPosed 启用模块（作用域含模块自身），重启设备
2. 打开 App，地图上**长按**目标位置 → 立即开始，状态条变绿
3. 想换地方：再长按一次即可，**立即生效**
4. 要路线：参数页打开“路线移动”，路线页加点（或粘 JSON）→ 保存 → 开始
5. 诊断页“应用并验证”一键自测，probe 全绿即正常
6. 停止：地图页“停止”

**重要：路线移动开着时，位置以路线为准**（忽略坐标输入框）。想固定在一个点，请关闭“路线移动”或清空路线，再设置坐标。

## 5. 自动化入口（Intent）

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

示例（浮点用 `--ef`，JSON 建议 base64；加 `-f 0x20000000` 可送达已打开的实例）：

```bash
adb shell am start -n dev.ghostloc.offline/.MainActivity \
  --ez dev.ghostloc.offline.extra.AUTO_APPLY true \
  --ez dev.ghostloc.offline.extra.ACTIVE true \
  --ef dev.ghostloc.offline.extra.LAT 35.6895 \
  --ef dev.ghostloc.offline.extra.LON 139.6917
```

## 6. 注意事项

- **模块 APK 更新后必须重启设备**；只 `adb install -r` 时 Hook 仍运行旧版本。
- **不要与原版 GhostMapX 同时启用**（互相干扰）。验证机上 GhostMapX 当前禁用。
- 高德瓦片与搜索需要设备能上网；地图空白先查网络。
- `getCurrentLocation` 这个 API 在伪造期间返回空，应用会自动回退到
  `getLastKnownLocation`/位置监听（正常返回伪造值）。
- 高级设置里的开关（GNSS 星数、Wi-Fi 屏蔽等）修改后，需要**重启目标应用**才对客户端侧 Hook 生效；定位坐标本身是实时的。
- 作用域建议：`system_server(android)`、`com.android.phone`、
  `com.android.bluetooth`、模块自身、`dev.ghostloc.probe`、`dev.ghostloc.demo`。
