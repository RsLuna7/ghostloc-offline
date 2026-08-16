# GhostLoc Offline — 当前状态

- **位置**：P0 五项代码已落地；`:app:assembleDebug` + `:probe-app:assembleDebug` 已通过。
- **卡住**：真机未验证。system_server / bluetooth 要 reboot 后才加载新 hook。
- **下一步**：装包重启 → probe 看 `gnss_meas_registered` / `test_provider_present` + logcat 核对 hook 安装与命中 → 通过后开 P1（微抖动 / extras / 第三方 SDK）。
