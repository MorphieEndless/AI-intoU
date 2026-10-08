# 樱趣 App（Android）

AI-intoU 的 Android 端，把 AI Agent 和蓝牙玩具连起来。一个 APK 就能直连 SVAKOM SX589B，同时作为 phone relay 接收服务端转发过来的 Agent 指令。

> 第一次用的话，请先看根目录的 [README](../README.md) 和 [部署教程](../docs/DEPLOY.md)。

## 为什么做这个

现在的社会越来越原子化，男男女女多少都有点性压抑。这事堵着不好，乱来更糟。在我看来，玩玩具是个很健康也很快乐的解压方式：自己舒服，不打扰别人，也不给社会添麻烦。

市面上已有的方案连不上我手里这款玩具，所以我自己做了一个：把 AI Agent 那套技术和蓝牙玩具接起来。你在 Agent 客户端里说一句话，手边的玩具就会响应。可以自己放松，也可以让 AI 远程陪你玩。

## 现在能做什么

- 原生 BLE 直连 SX589B，不需要 Intiface Central 中转
- 震动 0-10 档，吮吸 0-5 档、模式 1-8，支持 custom pattern 等玩法
- 连自托管服务器，可以用 Token 或账号登录，凭据加密存储
- 断线时本地自动急停，支持深色模式、9 套换肤和开发者协议调试

## 先说清楚，这是内测

**它还在内测阶段，不是正式版。** 吮吸协议是拿真机反复试出来的，中间推翻过好几次结论。遇到奇怪的问题先别骂，提 issue 就行，最好附上手机型号、Android 版本和日志。

## 获取安装包

- 发布版：[Releases](https://github.com/MorphieEndless/AI-intoU/releases)
- 最新构建：仓库 [Actions](https://github.com/MorphieEndless/AI-intoU/actions/workflows/build-apk.yml) 页面里成功运行的 artifact

想自己编译的话，有 JDK 17 和 Android SDK 就行：

```bash
./gradlew testDebugUnitTest assembleDebug
```

## 欢迎折腾

fork、提 issue、提 PR、点 star 都欢迎。用的人多了，这个项目也能被修得更好。

最后说句实话：这个项目大部分代码也是 AI Agent 写的。发不发版、issue 能不能解决、PR 什么时候合，多少要看当天模型的状态，以及我的账号有没有被风控。哪天它抽风了，请多理解，我也只是个人类（大概）。

## License

Apache License 2.0 + 署名附加条款，详见根目录的 [LICENSE](../LICENSE)、[ADDITIONAL-TERMS.md](../ADDITIONAL-TERMS.md) 和 [NOTICE](../NOTICE)。
