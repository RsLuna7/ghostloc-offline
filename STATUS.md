# GhostLoc Offline — 当前状态

- **位置**：P0 五项 + P1#6/#7（微抖动 / GNSS extras / makeComplete）已落地并真机验证（2026-08-16 深夜，两轮 install/reboot/probe）。
- **本轮附带修复**：`remap()` 防重入守卫（对齐原版 `d()`，防微抖动叠加）；`overwrite()` 保留原 extras；`getCurrentLocation` 的 `ILocationCallback.onLocationChanged(LocationResult, IRemoteCallback)` 分发形态（此前 gps/fused 均返回 null，已验证修复）。
- **BLE**：蓝牙开启时 `ble service hooks installed=8`（Android 15 上 `ScanManager` 实际在 `le_scan` 包，由别名表命中；`GattService`/`GattNativeInterface` 方法名漂移装 0 个，入口+投递链路已封住）；验证后蓝牙已恢复关闭。
- **卡住**：无。
- **下一步**：P1 剩余——#8 第三方 SDK hook（腾讯/高德/百度，scoped 进程）、#9 `TelephonyRegistry#notifyCellLocation*` + `listen` 监听器包装、#10 收尾（`PassiveLocationProvider#updateLocation`）、#11 SystemProperties 状态镜像；之后 P2 可选项。
- **追加（同夜第三轮）**：#10 的 `isScanAlwaysAvailable`→false 与 `requestListenerFlush` 拒绝已移植并真机验证（wifi 服务端 6 hooks、LMS 15 hooks、probe PASS）。
