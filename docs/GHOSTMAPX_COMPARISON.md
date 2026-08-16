# GhostMapX（原版）与 GhostLoc Offline（新版）结构差异对比

> 基准样本：GhostMapX v2.3.2（SHA256 `A585E91B…5823`，反编译与行为规格材料位于
> `D:\.develop\ghostmapx_analysis-agent\`）
> 对比对象：ghostloc-offline 0.3.x（本仓库，2026-08-16 状态，含当日
> "QQ 链路"服务端屏蔽补丁）
> 用途：属主自有测试设备上的 clean-room 复现，识别值得移植的机制差异。
> 除标注外，原版结论均来自 jadx 反编译源码（`jadx_out/sources/`）；
> 反编译失败的方法经 classes.dex 字节码恢复。

## 1. 总体架构对比

| 维度 | GhostMapX v2.3.2（原版） | ghostloc-offline（新版） |
|---|---|---|
| 框架 API | 传统 Xposed API 82（`IXposedHookLoadPackage`） | libxposed 现代 API 100（`XposedModule`） |
| 入口分发 | `handleLoadPackage` + `System.getProperty("ghostmapx.injected_<pkg>")` 防重入 | `onSystemServerLoaded` / `onPackageLoaded`（框架保证一次） |
| 注入目标 | system_server、com.android.phone、com.android.location.fused、com.oplus.location、com.xiaomi.location.fused、com.android.bluetooth、自身包；**第三方 app（QQ 等）一律不注入** | 同一组系统进程 + 自身/探针/演示包；第三方 app 同样不注入 |
| 授权 | 远程授权服务器 + Telegram 绑定 + Turnstile + native 校验 | 无（离线设计） |
| 状态通道 | `sendExtraCommand("ghostmapx")` 14 条命令 + `persist.ghostmapx.*` SystemProperties 跨进程镜像 + FNV 防篡改 | `sendExtraCommand("ghostloc","sync")` 单命令全量同步 → `/data/misc/ghostloc/state.properties`（system_server 写、system_server/phone 读） |
| 持久化 | SharedPreferences×7（session/auth/device/主配置/收藏/WiGLE×2） | 单一 settings.xml + 状态文件 |
| UI | 自绘 + osmdroid，对话框式交互 | Material 3 四 Tab + osmdroid 全幅地图 |
| 移动模型 | **单目标点** + speed/altitude/accuracy/bearing 标量（无路线概念） | 多点路线 + 循环 + 插值 + 暂停/继续 + 速度波动 + 海拔/精度抖动 |

## 2. 定位链路 hook 覆盖矩阵

图例：●=已覆盖且机制相当；◐=部分覆盖/形态不同；○=缺失。

### 2.1 system_server 侧

| Hook 面 | 原版 | 新版 | 差异说明 |
|---|---|---|---|
| `LocationManagerService#getLastLocation` | ● after 注入（null 也新建） | ● before 直接返回伪造 | 新版形态更强 |
| `#requestLocationUpdates` / `#registerLocationListener` | ● 监听器入队（`linkToDeath`），注册后立即推一条伪造位置 | ● after 立即派发伪造位置 | 新版无监听器队列（见 §5-B4） |
| `#getCurrentLocation` | ● before 钩住 Transport/Callback 的 `onLocation` 并当场回调 | ● after 派发 binder 监听器（`onLocationChanged(List/LocationResult, IRemoteCallback)` 两种形态；LocationResult 形态是 `getCurrentLocation` 的 `ILocationCallback` 实际形状，2026-08-16 夜修复并真机验证） | 机制相当 |
| `#removeUpdates`/`#unregisterLocationListener` | ● 出队 | ◐ 仅日志 | 新版无队列故无需出队 |
| `#sendExtraCommand`（桥） | ● 14 条命令 | ● sync/全量 | 命令模型不同（§4） |
| `#isProviderEnabled(ForUser)` | ● gps/network 恒 true；`"ghostmapx"` 恒 true；fused 看开关 | ● gps/network 恒 true（无 ghostmapx provider，fused 不强制） | 2026-08-16 P0 已移植 |
| `#registerGnssStatusCallback` | ● 强制 true（阻断真实星况下发） | ● 服务端强制 true + scoped 客户端仍合成 GnssStatus | 2026-08-16 P0 已移植 |
| `#addTestProvider`/`setTestProviderLocation`/`setTestProviderEnabled`/`setExtraLocationControllerPackage*` | ● 全部返回 null/false（封锁第三方 mock 通道） | ● 同组方法拒绝 + `setExtraLocationControllerPackageEnabled` 强制 false | 2026-08-16 P0 已移植 |
| `#requestListenerFlush` | ● 入队 + 拦截 | ● 中性拒绝（2026-08-16 已移植，真机安装验证） | 语义相当（flush 无效果） |
| `AbstractLocationProvider#reportLocation` / `LocationProviderManager#onReportLocation` | ● 改写 `LocationResult.mLocations` | ● before 替换参数中的 Location/LocationResult | 相当 |
| `LocationProviderManager` 所有返回 Location 方法 | ● after 注入 | ◐ `getLastLocation`/`getCurrentLocation` 按名匹配 | 近似相当 |
| `PassiveLocationProvider#updateLocation` | ● | ○ | P2（被动 provider 路径） |
| **binder 序列化兜底**：`Location#writeToParcel`（逐字段覆写）、`Location#set`、`LocationResult#asList`/`writeToParcel`/全部 Location 出入参方法 | ● 全套"BasicLocationHook" | ● `BasicLocationHooks`（system_server） | 2026-08-16 P0 已移植 |
| `ILocationManager$Stub#onTransact` | ● LMS 缺类时的回退安装路径 | ◐ 仅 trace 日志 | 原版用它做"晚加载补装"；新版现代 API 下必要性低，保留观察 |
| 监听器代理类 `onLocationChanged`（hookAllMethods） | ● 队列中每个监听器类 | ◐ after 派发时反射调用 | 新版无"对已注册监听器的持续改写"能力（见 §5-B4） |

### 2.2 GNSS

| Hook 面 | 原版 | 新版 | 差异说明 |
|---|---|---|---|
| `GnssManagerService#addGnssMeasurementsListener` | ● 返回 null（拒绝原始测量；`s` 开关可放行） | ● 按返回类型拒绝（boolean→false；无放行开关） | 2026-08-16 P0 已移植 |
| `GnssManagerService#addGnssAntennaInfoListener` | ● 返回 null | ○ | P1 |
| GNSS 状态/星况内容 | ◐ 只在 Location extras 里塞 satellites/Cn0 数字 | ● `GnssSynth` 合成完整 `GnssStatus`（星数/方位/俯仰/CN0/used-in-fix） | **新版更强** |
| NMEA | ◐ 协议有 `enable_nmea`（默认 false，hook 未还原） | ● `NmeaSynth` 生成 GGA/RMC/GSA/GSV | **新版更强** |
| `GnssLocationProvider#reportLocation` | ◐ 仅日志观察 | ◐ `GnssNative` 目标类仅记录 | 相当（都未改写） |

### 2.3 Wi-Fi

| Hook 面 | 原版 | 新版 | 差异说明 |
|---|---|---|---|
| `WifiServiceImpl#getScanResults`（服务端，含 ParceledListSlice） | ● | ● 2026-08-16 已补齐 | 已一致 |
| `#getMatchingScanResults` | ● 空 | ● | 已一致 |
| `#startScan` | ● false | ● | 已一致 |
| `#isScanAlwaysAvailable`（服务端） | ● false | ● 2026-08-16 已补齐（真机命中 `wifi scan-always masked`） | 已一致 |
| `#getConnectionInfo`（服务端，假 MAC/BSSID） | ● | ● | 已一致 |
| `#registerScanResultsCallback` 回调抑制 | ● | ● | 已一致 |
| WifiStack APEX classloader 解析 | ● 双路径（`sLoadedPaths` / `loadClassFromLoader` 监听） | ● 同款双路径 | 已一致 |
| 客户端 `WifiManager`/`WifiInfo` 全家（scoped 进程） | ● | ● | 相当 |

### 2.4 蓝牙 / BLE

| Hook 面 | 原版 | 新版 | 差异说明 |
|---|---|---|---|
| `GattService#startScan`/`onScanResult`/`onBatchScanResults`、`GattNativeInterface#onScan`、`ScanManager#onFoundOrLost`、`IScannerCallback$Stub(.Proxy)#onScanResult` 等、`BluetoothLeScanner#startScan`（全部返回 null 吞掉） | ●（com.android.bluetooth 进程内，服务端对所有 app 生效） | ● `BleServiceHooks`（同组候选类名，缺类则跳过） | 2026-08-16 P0 已移植 |

### 2.5 基站

| Hook 面 | 原版 | 新版 | 差异说明 |
|---|---|---|---|
| `PhoneInterfaceManager#getAllCellInfo` | ● 合成 CDMA | ● 合成 LTE(+NR) | 形态不同：原版假 CDMA 把 `baseStationLatitude/Longitude=伪造坐标×14400`（塔位置与假坐标一致）；LTE 身份无坐标字段，机制上无法复刻——新版用一致化的 MCC/MNC/TAC/CI 代替 |
| `#getCellLocation` | ● 假 Bundle/CellIdentityCdma | ● null | 语义相当（都无真实信息） |
| `#requestCellInfoUpdate(WithWorkSource)` | ● 改写回调 | ● | 一致 |
| `#getServiceState(ForSubscriber)` | ● roaming=false + regState=0 + 逐项 setCellIdentity | ● 合成 ServiceState | 相当 |
| `getNetworkType` 系 | ● 恒 4（CDMA，与假 CDMA 身份自洽） | ● LTE/NR 可配 | 新版更拟真（中国 LTE/NR 环境） |
| `TelephonyRegistry#notifyCellInfo(ForSubscriber)` | ● 参数列表替换 | ● | 一致 |
| `TelephonyRegistry#notifyCellLocation(ForSubscriber)` | ● 替换为假 CDMA Bundle（含假坐标） | ○ | P1（推送型小区位置，老 API） |
| `TelephonyRegistry#listen/listenWithEventList` 监听器包装（`onCellLocationChanged` 改写） | ● I3 包装器 | ○ | P1 |
| `getNeighboringCellInfo` | ● 假邻居 | ● 空列表 | 相当 |
| 客户端 `TelephonyManager` 全家（scoped） | ◐ 部分 | ● 更全（operator/iso/roaming 等） | 新版更全 |

### 2.6 反检测

| 机制 | 原版 | 新版 | 差异说明 |
|---|---|---|---|
| `Settings.Secure mock_location` → "0"/0 | ● | ●（客户端+system_server） | 一致 |
| `Location#isFromMockProvider`/`isMock` → false | ● | ● | 一致 |
| 注入对象显式 `setMock(false)` | ● | ●（新建即非 mock） | 一致 |
| `Geocoder` 改写 | ● 参数改坐标 / 结果改地址 | ● AddressSynth 全合成 | 新版更强（原版 getFromLocationName 实为空 hook） |
| provider 改名 network→gps | ● | ○ 按请求原 provider 返回 | P2：原版意图是统一 provider 指纹；新版按请求返回更自然，可选开关 |
| 防重复注入标记（extras `ghostmapx_faked`/`ghostloc_faked`） | ● | ● `makeLocation` 统一打标，`remap` 入口短路防叠加（2026-08-16 已移植） | 已一致；标记随 extras 对 app 可见，两版行为相同 |
| `makeComplete()`（API 33+ isComplete） | ● | ● 反射调用、失败静默（2026-08-16 已移植） | 已一致 |
| 测试 provider 通道封锁 | ● | ● | 2026-08-16 P0 已移植 |
| 第三方定位 SDK 对抗（`Jo.l`）：腾讯 `TencentNLPManager` 全 Location 方法、高德 `NetworkLocationManager#onSendExtraCommand`/`updateOffLocEnable`、百度 `LocationClient#requestNLPNormal`（改坐标+清 buildingID/addrStr） | ● | ○ | **待移植 P1**：用户把目标 app（如 QQ）加 scope 后即生效，等于 SDK 层双保险 |
| UI"回到真实位置"快照（80m/120s 污染判定） | ● | ○ | P2 产品功能 |
| 传感器 hook 占位（`SystemSensorManager#registerListenerImpl` 空挂） | ● 仅观察 | ○ | 无行为，不移植 |
| 会话状态 FNV 防篡改 | ● | ○ | 离线场景无对手模型，不移植 |

## 3. 定位生成机制（拟真度）对比

| 机制 | 原版 | 新版 | 结论 |
|---|---|---|---|
| 位置微抖动 | ● 每次输出在 `0..accuracy/15 m` 内沿 bearing±45°（随机侧）偏移，`ghostmapx_faked` 标记防二次叠加 | ● 同款语义已移植（幅度/方向/防叠加标记一致；2026-08-16 真机实测 6 采样 spread 1.3–1.4m @acc≈22.5） | 已一致 |
| 速度 | 标量 + `set_speed_amp` 均匀噪声 ±amp；合成 0 时强制 1.2 m/s（步行下限） | 路线插值速度 + `speedVariation` 正弦波动（0–0.75），无下限 | 新版模型更强；**1.2 m/s 下限可做成开关移植（P2）** |
| bearing | `h%360`；为 0 时以 1.0 充当 bearingAccuracy，避免恒零特征 | 路线自动方向角 / 手填 | P2 微调 |
| GNSS extras | `satellites`=8–45、`maxCn0`=30–50、`meanCn0`=20–30 随机写入 Location extras | ● 同区间随机写入（GnssStatus 层合成仍保留；2026-08-16 真机 6/6 采样可见） | 已一致 |
| 海拔 | 0 时回退 80m；API 34+ MSL 同步 | 可配 + 正弦抖动 | 新版更强 |
| 时间戳 | 保留原 Location 的 time/elapsedRealtimeNanos（连续性） | 恒取当前时间 | 改写真实位置的场景下原版更优（P2；纯合成场景无差） |
| 路线/移动 | ○ 单点，速度只是字段 | ● 插值引擎 | **新版独有且更强** |

## 4. 命令桥对比

| | 原版（14 条） | 新版（1 条） |
|---|---|---|
| 命令 | start / stop / update_location / move / put_config / is_start / exchange_key / set_speed / set_speed_amp / set_altitude / set_bearing / broadcast_location / start_gnss_mock / start_wifi_mock | sync（全量键值） |
| 返回值 | bundle 原地回写（is_start 回读状态） | 无回读（UI 以本地文件为准） |
| 跨进程镜像 | `persist.ghostmapx.*` SystemProperties（phone 进程可实时读） | 状态文件（system_server 权限；phone 进程读取依赖 DAC/SELinux 放行，实测 hook 生效但实时性未单独验证） |
| 会话过期 | `exp`=24h 自动复位 | 无 |

新版单命令全量模型更简单可靠（无命令时序问题），原版的 **broadcast_location（手动向全部监听器即时推送）** 与 **SystemProperties 镜像** 两个能力值得吸收（P2/P1）。

## 5. 新版独有优势（原版没有）

1. 多点路线引擎（插值/循环/暂停/速度波动/自动方向角）
2. 完整 GNSS 星况与 NMEA 报文合成（原版只有 extras 数字）
3. 结构化 LTE/NR 基站合成（原版假 CDMA）
4. GCJ-02↔WGS-84 纠偏 + 高德瓦片（中国大陆可用性）
5. probe-app 真机验证链路与诊断页
6. 收藏分组/JSON 导入导出、Material 3 UI、免重启实时切换（原版改坐标需重发 update_location，无"路线"概念）
7. 离线无授权（原版强依赖远程服务器，服务器一停就废）

## 6. 建议移植清单（按优先级）

### P0 —— 对抗面一致性（影响"所有 app 看到一致的假象"）

> 2026-08-16：五项均已落地，并当晚真机验证通过（安装计数 + 命中日志 +
> probe 行为，见 TRACEABILITY）。
> BLE 注意：本机 Android 15 上原版类名有漂移——`com.android.bluetooth.gatt.ScanManager`
> 实际位于 `com.android.bluetooth.le_scan.ScanManager`（别名表命中）；
> `GattService`/`GattNativeInterface` 方法名已变化（匹配 0 个），但扫描入口
> （`le_scan.ScanManager#startScan`）与结果投递（`IScannerCallback$Stub$Proxy` ×3）
> 已封住，蓝牙开启时共安装 8 个 hook。

| # | 项 | 原版实现锚点 | 状态 |
|---|---|---|---|
| 1 | `Location#writeToParcel`/`#set` + `LocationResult` 全套序列化兜底 hook | `AbstractC0222f4.k`（BasicLocationHook） | 已实现（system_server） |
| 2 | `GnssManagerService#addGnssMeasurementsListener` 拒绝、`#addGnssAntennaInfoListener` 拒绝、`LMS#registerGnssStatusCallback`→true（服务端） | `AbstractC0495mc.i` | 已实现 |
| 3 | BLE 扫描全链路抑制（com.android.bluetooth 进程内 GattService/GattNativeInterface/ScanManager/IScannerCallback/BluetoothLeScanner） | `L7.i`（BleHook） | 已实现（候选类名，缺类跳过） |
| 4 | 测试 provider 通道封锁（addTestProvider 系 + setExtraLocationControllerPackage* → null/false） | `C0121cf` 233-237 | 已实现 |
| 5 | `isProviderEnabled` 服务端强制（gps/network→true） | `Xe` case 10/11 | 已实现（无 ghostmapx provider，fused 不强制） |

### P1 —— 拟真细节与纵深

> 2026-08-16 深夜：#6、#7 已落地并真机验证（微抖动 spread 1.3–1.4m、
> satellites extras 6/6、makeComplete 反射接入）。

| # | 项 | 说明 |
|---|---|---|
| 6 | 输出级位置微抖动 | ✅ 已移植：`0..accuracy/15 m` 沿 bearing±45° 随机侧偏移 + `ghostloc_faked` 防叠加标记（`remap` 入口短路，等价原版 `d()` 的守卫） |
| 7 | Location extras 补齐 | ✅ 已移植：satellites/maxCn0/meanCn0 随机 + `makeComplete()`；`overwrite` 保留原 extras |
| 8 | 第三方 SDK hook 组（scoped 进程） | TencentNLPManager / AMap NetworkLocationManager / Baidu LocationClient |
| 9 | `TelephonyRegistry#notifyCellLocation*` + `listen` 监听器包装 | 推送型小区位置也合成 |
| 10 | 服务端 `isScanAlwaysAvailable`→false、`requestListenerFlush`、`PassiveLocationProvider#updateLocation` | ✅ 前两项已移植并真机验证；PassiveProvider 路径待做 |
| 11 | SystemProperties 状态镜像 | Bridge 写状态文件的同时写 `persist.ghostloc.*`，让 phone 进程开关实时生效 |

### P2 —— 产品功能（可选）

| # | 项 | 说明 |
|---|---|---|
| 12 | 会话过期自动停（原版 24h） | 防忘关 |
| 13 | 真实位置快照 + "回到真实位置"按钮 | 80m/120s 污染判定 |
| 14 | `broadcast_location` 手动推送命令 | 向全部已注册监听器即时刷一条 |
| 15 | 速度下限 1.2 m/s、bearing 恒非零开关 | 反静态指纹 |
| 16 | 坐标输入顺序切换（纬,经 / 经,纬） | UI 细节 |

### 明确不移植

- 远程授权/Turnstile/native 校验/FNV 防篡改 —— 离线设计，无对手模型
- 假 CDMA 基站 —— LTE/NR 合成对中国环境更拟真；CDMA Bundle 里嵌坐标的技巧在 LTE 身份结构中无对应物
- 单目标点移动模型 —— 路线引擎全面优于
- `ISub$Stub`/传感器空 hook 占位 —— 无行为
- 原版 UI 的公告/讨论组/WiGLE 清理 —— 服务端配套已死/无意义

## 7. 结论

两版的核心设计一致：**不注入第三方 app，把定位输入在系统服务端全部掐断/合成**。
Wi-Fi 服务端屏蔽与 P0 五项落地后，主要对抗面（LocationManager 服务路径含序列化兜底、
Wi-Fi 扫描、phone 进程基站查询、GNSS 原始测量、BLE 扫描、测试 provider）已经对齐；
剩余差异集中在两类：

1. **输出拟真度**（微抖动/extras/时间戳连续性）——P1
2. **产品细节**（过期、快照回真、手动广播）——P2

P0 已于 2026-08-16 真机验证通过；P1 的微抖动 / GNSS extras / makeComplete 同日对齐。
剩余 P1 差异集中在第三方 SDK hook（腾讯/高德/百度）、TelephonyRegistry 推送包装与
SystemProperties 状态镜像；完成后即可在保留新版路线 / GNSS 合成 / 基站合成 /
离线化优势的同时，把输出拟真度与纵深全面对齐原版。
