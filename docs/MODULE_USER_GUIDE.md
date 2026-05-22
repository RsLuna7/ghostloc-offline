# GhostLoc Offline 模块说明

这份文档面向模块使用者，说明这个模块能做什么、如何工作、如何操作，以及它当前的适用边界。

## 1. 模块定位

`GhostLoc Offline` 是一个运行在 LSPosed/libxposed 环境下的离线定位控制模块。

它的目标不是联网授权，也不是云端下发，而是在**自有测试设备**上，提供一个可控、可重复、可离线工作的定位环境。

当前验证环境：

- 设备：`OnePlus7 / GM1901`
- 系统：`Android 15 / SDK 35`
- 框架：`LSPosed API 100`

## 2. 模块能做什么

当前版本已经具备这些主要功能：

- 指定经纬度作为当前位置
- 设置速度、海拔、精度、方向
- 多点路线移动
- 路线循环
- 路线暂停 / 恢复
- 自动朝向计算
- 收藏点管理
- 收藏点 JSON 导入 / 导出
- 路线 JSON 导入 / 导出
- Wi‑Fi 信息清理
- 基站 / 网络类型伪装
- GNSS 状态和 NMEA 语句伪装
- `fused` provider 取点伪装
- 应用内诊断
- 一键启动 probe 自测

## 3. 用户看到的主要界面

主界面分成几个区域：

### 3.1 Location

用于设置基础位置参数：

- 纬度
- 经度
- 速度
- 海拔
- 精度
- 方向

地图支持：

- 单击选点
- 长按选点并立即启动

### 3.2 Policies

用于控制伪装策略：

- `Wi‑Fi shield`
- `Cell shield`
- `Mock shield`
- `Route movement`
- `Allow 5G/NR`
- `NMEA callbacks`
- `AGPS reported`
- `Vendor/fused hooks`

### 3.3 Radio + Jitter

用于控制无线侧和波动参数：

- MCC
- MNC
- 国家码
- 运营商名称
- GNSS 卫星数
- 速度波动
- 精度抖动
- 海拔抖动

### 3.4 Route

用于路线控制：

- 编辑路线 JSON
- 追加当前点
- 保存路线
- 使用路线起点
- 清空路线
- 启动
- 暂停
- 恢复
- 停止

### 3.5 Favorites

用于收藏点管理：

- 编辑收藏 JSON
- 添加当前点
- 保存 JSON
- 使用最后一个收藏
- 清空收藏

### 3.6 Diagnostics

用于查看模块状态：

- LSPosed 服务连接状态
- 当前是否激活
- 最近命中的 hook
- 最近错误
- 最近一次 probe 结果
- 预期 scope 列表

## 4. 工作原理概览

这个模块不是单纯改一个公开 API 的返回值，而是分几层工作。

### 4.1 控制层

主 app 负责：

- 保存配置
- 维护路线和收藏
- 把当前状态写入 `settings`
- 通过 `sendExtraCommand("ghostloc", ...)` 触发模块侧更新

### 4.2 应用层 Hook

模块会先覆盖常见公开接口，例如：

- `getLastKnownLocation`
- `getCurrentLocation`
- `requestLocationUpdates`
- provider 查询
- Geocoder
- Wi‑Fi / Telephony 相关公开读取

这一层保证普通 app 直接调用 Android 标准定位接口时，就能读到伪装后的结果。

### 4.3 system_server 服务层 Hook

为了覆盖更深的链路，模块还会进入 `system_server`：

- `LocationManagerService`
- `ILocationManager$Stub`
- `LocationProviderManager`

这一层的作用是：即使 app 不是只读公开 API，而是经过 binder/service 路径请求位置，也尽量拿到同一套伪装结果。

### 4.4 环境一致性层

如果只改一个坐标，很多 app 很快就会出现环境不一致。

所以模块还会同步这些信息：

- 基站信息
- 网络类型
- 运营商信息
- GNSS 卫星状态
- NMEA 数据
- Wi‑Fi 标识

这样做的目标是让“位置”和“位置环境”尽量一致。

## 5. 路线模式怎么工作

路线模式下，模块会根据：

- 起始时间
- 路径点序列
- 速度
- 波动参数

实时计算当前应处于路线的哪一段、哪一个插值位置。

如果开启：

- `Loop route`：到终点后从起点继续
- `Auto bearing`：自动根据路径方向生成朝向

所以你看到的位置不是固定点，而是随时间推进的轨迹。

## 6. Probe 是做什么的

`probe-app` 是自带的测试客户端。

它会主动读取：

- 公开位置
- `fused` 位置
- 监听器回调
- 基站信息
- ServiceState
- GNSS 状态
- NMEA
- Wi‑Fi 信息

然后把结果回传给主 app。

主 app 会记录：

- 最近一次 probe 是否通过
- 最近一次 probe 的文本结果

这让你不需要每次都手动看 logcat。

## 7. 普通 app 是否能用

在当前已验证设备上，答案是：

- 我们自己的 `probe-app` 可以
- 我们自己的普通 `demo-app` 可以

也就是说，**走标准 Android 定位链路的 app，在当前验证设备上通常可以读到伪装位置**。

但这不等于：

- 所有第三方 app
- 所有 ROM
- 所有风控场景

都能 100% 保证一致。

## 8. 当前边界

当前版本的边界主要有这些：

- 当前完整验证集中在 `OnePlus7 / Android 15 / LSPosed API 100`
- 不同 ROM 的 fused / vendor 实现差异很大
- 某些 app 可能还有额外检测：
  - 服务端轨迹风控
  - 设备完整性检测
  - Google Play 服务私有链路
  - 传感器交叉校验
- `LocationManager.getGpsStatus(null)` 在当前 ROM 上仍然可能返回 `null`
- Android 15 对部分隐藏 API 的直接反射有限制

## 9. 推荐使用方式

建议操作顺序：

1. 打开主 app
2. 选择坐标或路线
3. 按需设置 Wi‑Fi / Cell / GNSS / Route 参数
4. 点击启动
5. 运行内置 probe 检查状态
6. 再打开目标 app 做测试

如果想做静态点测试：

- 关闭 `Route movement`

如果想做轨迹测试：

- 打开 `Route movement`
- 配置多点路线
- 开启 `Loop route` 或 `Auto bearing`

## 10. 何时看 Diagnostics

遇到这些情况时，先看 Diagnostics：

- 应用取不到位置
- route 没有动
- `fused` 没有返回
- probe 失败
- 某个 scope 可能没生效

优先关注：

- 是否连接到 LSPosed 服务
- 最近命中的 hook
- 最近错误
- 最近一次 probe 结果
- 预期 scope 是否齐全

## 11. 适用范围

这个模块当前定位是：

- 自有测试设备
- 自有测试 app
- 本地离线定位环境控制

它不是：

- 联网授权产品
- 云控系统
- 跨所有 ROM 的完全兼容方案

## 12. 相关文档

- 项目说明：[README.md](../README.md)
- 设备测试报告：[DEVICE_TEST_REPORT.md](./DEVICE_TEST_REPORT.md)
- 变更追踪：[TRACEABILITY.md](./TRACEABILITY.md)
- 兼容性和后续验证说明：[REPLICATION_PLAN.md](./REPLICATION_PLAN.md)
