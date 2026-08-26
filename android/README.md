# 樱媞 Bridge Android

单 APK 直接连接 SVAKOM SX589B，并作为 Signal Bridge Remote 的 phone relay。

## 当前 MVP

- HTTPS 登录并用 EncryptedSharedPreferences 保存 JWT
- 原生 Android BLE 扫描 SX/SL 系列设备，连接 FFE0/FFE1
- SX589B 震动 0–10 档、吮吸 0–6 档与启动即停
- WSS `phone_auth`、心跳响应、命令 ACK、device_list
- direct vibrate/constrict、双通道 pulse/wave/escalate、stop、scan
- Relay 断线本地 emergency stop
- 前台服务、WakeLock、通知栏 STOP ALL
- Compose 登录页与控制台

## 构建（GitHub Actions）

push 到 main 即自动构建：

- debug job：`gradle testDebugUnitTest` + `assembleDebug`，APK 上传为 artifact
- release job：配置 `KEYSTORE_B64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` Secrets 后自动签名；未配置自动跳过

也可在仓库 Actions 页手动 `workflow_dispatch` 触发。

## 本地构建（可选）

需要 JDK 17 与 Android SDK（platform 35；target 34）：

```bash
printf 'sdk.dir=/opt/android-sdk\n' > local.properties
./gradlew testDebugUnitTest assembleDebug
```

Release 签名需在本地/CI 注入 keystore，不要把口令提交到仓库。
