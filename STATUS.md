# GhostLoc Offline — 当前状态

> 本文件为进度快照，2026-08-17 凌晨更新。P1 收尾轮（#8/#9/#10/#11）实现完成，
> 真机验证进行中（详见「进行中」）。

## 本轮（P1 收尾）已完成

- **#10 PassiveLocationProvider**：`updateLocation(LocationResult)` 加入 DeepServiceHooks
  目标类，复用 `ServiceReportLocationHooker`（before 替换 Location/LocationResult 参数）。
  真机确认 `deep hooks ...PassiveLocationProvider installed=1`。
- **#9 TelephonyRegistry 推送包装**：
  - `notifyCellLocation(ForSubscriber)` → `TelephonyCellLocationNotifyHooker`，Bundle 与
    CellIdentity 两种载荷形态都替换（本 ROM 实际签名是 `(int, CellIdentity[, boolean])`，
    发现 Bundle 形态不存在后已覆盖）；
  - `listen` / `listenWithEventList` → `TelephonyListenHooker`，对 IPhoneStateListener 参数
    的类按名包装 `onCellLocationChanged`（1 参），安装 `CellLocationListenerHooker`（按载荷
    运行时类型替换：Bundle→GSM 风格合成 Bundle，CellIdentity→`CellSynth.currentLteCellIdentity`）；
  - `CellSynth.makeCellLocationBundle()`（lac/cid/psc，GSM 键格式）与 `currentLteCellIdentity()`
    新增；phone 进程 `getCellLocation` 由返回 null 改为返回同一合成 Bundle（推拉一致）。
  - 真机：TelephonyRegistry installed=6；boot 后自动包装了 3 个真实监听器类
    （`TelephonyCallback$IPhoneStateListenerStub` / `PhoneStateListener$IPhoneStateListenerStub`
    / `IPhoneStateListener$Stub$Proxy`）；`test_cell_push` 驱动下
    `telephony cell location push replaced` 两种签名均命中并投递成功。
- **#11 跨进程实时状态**：
  - `PropMirror`（persist.ghostloc.* 镜像）已实现，但 Android 15 OnePlus ROM 的 SELinux
    **拒绝 system_server 写 default_prop**（dmesg avc denied，root 可写）→ 写入侧 3 次失败后
    自动停用（保留读侧，供允许的 ROM 使用）。
  - 替代通道（真机验证通过）：**Bridge `query` 命令** —— system_server 侧
    `sendExtraCommand("ghostloc","query")` 把状态文件全量写回 inout Bundle；
    `LiveState`（core/LiveState.kt）在非 system 进程带 1.5s TTL 缓存拉取；
    `MergedPrefs` 覆盖链：状态文件 → LiveState 桥拉取 → PropMirror → 远程 prefs。
  - 实证：探针进程（非 system）实时跟随位置 —— 上海 ci=48535307、北京 ci=260788382，
    均与离线推导值精确一致；模块日志 `bridge query answered` ×19。
- **#8 第三方 SDK hook 组**：`hooks/SdkHooks.kt`（对应原版 `Jo.l`）—— 腾讯
  `TencentNLPManager` 盲挂全部返回/接收 Location 的方法（after 改写返回、before 改写参数，
  复用 `remap` 防叠加）；高德 `NetworkLocationManager#onSendExtraCommand`/`updateOffLocEnable`
  中性拒绝（与原版差异：仅在 active 时生效，避免模块关闭时劣化 SDK）；百度
  `LocationClient#requestNLPNormal` after 改坐标 + 清 buildingID/addrStr。
  `GhostLocModule.onPackageLoaded` 全包安装（findClassIfExists 形态，无 SDK 的包零命中）。
  **设备上无第三方 SDK 目标，待用户把目标 app 加入 scope 后验证**。
- 版本升 0.4.0（versionCode 4）。

## 进行中（下一步从这里继续）

1. **探针编译修复**：`maybeTestCellPush` 里 `onCellLocationChanged` 的 override 签名错误——
   API 33+ 的 `PhoneStateListener` 只剩 `onCellLocationChanged(android.telephony.CellLocation)`
   （Bundle/CellIdentity 形态均不存在于 android-36 桩）。改为 override `CellLocation` 形态
   （import `android.telephony.CellLocation`），或改用 `TelephonyCallback` 的
   `onCellLocationChanged(CellIdentity)` 注册路径。
2. 重新构建 app + probe，安装，重启；
3. `am start -n dev.ghostloc.probe/.ProbeActivity --ez test_cell_push true` 跑完整链路：
   预期看到 `cell location listener wrapped`（新注册的探针监听器）、`delivery replaced`、
   `cell_push_observation=identity=...`（探针侧收到合成身份）；
4. 最终回归：probe PASS + 位置切换后 CI 实时变化；
5. 文档收尾：TRACEABILITY.md 加本轮章节、GHOSTMAPX_COMPARISON.md 更新 #8/#9/#10/#11 状态、
   README 版本提及；git 提交（工作树目前全部未提交）。

## 设备状态（验证中断时的现场）

- OnePlus 7 / Android 15，adb 设备 87d5ddd6，已 root（Magisk + Zygisk Vector 框架）。
- 当前安装：app 0.4.0 调试版（含 query 通道 + test_cell_push + trace 日志）、
  probe/demo 为上一轮版本（probe 缺 CellLocation override 修复）。
- 状态：active=true，北京 39.9042/116.4074（状态文件与远程 prefs 均为此值，
  跨重启持久验证通过）；探针 `probe_status=PASS`，cells 服务端合成 CI=260788382。
- 蓝牙保持关闭；`persist.ghostloc.probe_root` 测试属性残留（root setprop，可忽略或清理）。

## 关键实验结论（本轮实测）

- `SystemProperties.set("persist.ghostloc.*")` 在 system_server：avc denied
  `{ set }` `tcontext=u:object_r:default_prop:s0` `permissive=0` —— 属性镜像在本 ROM 不可用。
- `TelephonyRegistry#notifyCellLocationForSubscriber` 实际签名为
  `(int, CellIdentity)` 与 `(int, CellIdentity, boolean)`（2 参内部委托 3 参）。
- 远程 prefs（Vector 框架 DB）更新不可靠：UI 仅写本地 settings.xml，hook 进程视图滞后
  （一次实测 47s 未跟上）；桥 query 通道解决此问题。
- 本 ROM 在小区身份不变时不推送 cell location（飞行模式/数据开关均不触发）——
  `test_cell_push` 桥命令是确定性验证手段。
- P1 其余各项（#6 微抖动 / #7 extras / P0 五项）此前已真机验证，见 TRACEABILITY.md。
