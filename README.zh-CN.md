# Plugin Read

**MC 26.1 Fabric 纯客户端模组**：在游戏内读取服务器转载的 Mod 或插件。

进服后自动探测服务器提供的文件清单（manifest.json），在游戏内界面里浏览、下载，并自动解析 jar 的元数据（fabric.mod.json / plugin.yml / mods.toml）；同时通过标准 Server List Ping 读取 Forge / NeoForge 服务器广播的运行模组列表。**服务器端不需要安装任何东西。**

- Minecraft：26.1（~26.1）
- Fabric Loader：≥ 0.20.1
- Fabric API：0.144.0+26.1（本仓库内置离线副本，见下）
- Java：≥ 25

## 功能

| 功能 | 说明 |
| --- | --- |
| 文件清单浏览 | 按 `V`（可在按键设置里改）或 `/pluginread` 打开界面 |
| 一键 / 单个下载 | 点击条目下载，或"全部下载" |
| 实时进度 | 列表中显示每个文件的下载百分比 |
| 元数据解析 | 自动读取 jar 内的 fabric.mod.json / plugin.yml / mods.toml，显示类型（[Mod]/[插件]）、名称、版本 |
| SHA-256 校验 | 清单里提供 `sha256` 时自动校验完整性 |
| 服务器模组列表 | 通过 Server List Ping 读取 Forge/NeoForge 服务器的 modinfo 并展示 |
| 客户端指令 | `/pluginread`（打开界面）、`/pluginread refresh`（重新探测）、`/pluginread folder`（打开下载目录） |

下载的文件保存在 `.minecraft/pluginread/<服务器地址>/`，只保存和解析，**不会被自动加载执行**。

## 服务器侧：如何"转载"文件

在任意网页服务器（比如服务器主机上的 nginx、面板自带网页）放一个清单文件，例如 `http://你的服务器/pluginread/manifest.json`：

```json
{
  "files": [
    {
      "name": "fabric-api-0.155.3+26.1.2.jar",
      "size": 2413567,
      "sha256": "文件sha256十六进制（可省略）",
      "url": "fabric-api-0.155.3+26.1.2.jar"
    },
    {
      "name": "EssentialsX-2.20.1.jar",
      "url": "https://cdn.example.com/plugins/EssentialsX-2.20.1.jar"
    }
  ]
}
```

- `url` 可以是相对路径（相对清单所在目录）、站内绝对路径（`/`开头）或完整 URL。
- 客户端会依次尝试：`http://服务器地址/pluginread/manifest.json` → `https://...` → `http://服务器地址/manifest.json`（可用配置关闭）。
- 也可以在配置里给指定服务器填 URL（见下）。

## 客户端配置：`config/pluginread-client.json`

```json
{
  "autoProbe": true,
  "notifyOnJoin": true,
  "probeTimeoutMs": 3000,
  "maxFileMb": 64,
  "servers": {
    "mc.example.com:25565": {
      "manifestUrls": ["http://mc.example.com/pluginread/manifest.json"],
      "autoDownload": false
    },
    "*": { "manifestUrls": [] }
  }
}
```

- `autoProbe`：进服时自动探测常见清单路径。
- `servers.<地址>.manifestUrls`：手动指定清单 URL（支持 `*` 通配所有服务器）。
- `autoDownload`：对该服务器开启后，进服自动下载全部文件。
- `maxFileMb`：单个文件大小上限。

## 构建

需要 JDK 25（Gradle JVM）：

```bash
./gradlew build
# 产物：build/libs/pluginread-1.0.0.jar
```

Windows 下若默认 JDK 不是 25：

```bash
JAVA_HOME="/c/Program Files/Zulu/zulu-25" ./gradlew build
```

### 网络受限环境说明

`maven.fabricmc.net` 在部分网络下被严重限速。本仓库自带 `local-fabric-maven/` 离线仓库（loom、loader、fabric-api 及全部子模块，fabric-api 提取自 Modrinth 发布 jar），并在 `settings.gradle`/`build.gradle` 中优先使用它和阿里云 Maven 镜像；Gradle 发行包走腾讯镜像（见 `gradle/wrapper/gradle-wrapper.properties`）。若要重建离线仓库，可使用 `tools/` 下的脚本。

## 安全说明

- 下载前会检查大小上限；清单提供 `sha256` 时强制校验。
- 下载的 jar 仅保存到本地并读取元数据，**不会**被游戏加载或执行；如需启用客户端 mod，请自行检查后手动放入 `mods` 文件夹。
- 自动探测只访问当前连接服务器的主机名（http/https 80/443 端口），不发其他请求。

## 技术参考

- [fabric-example-mod](https://github.com/FabricMC/fabric-example-mod)（26.1 分支模板：Loom 1.19 / 无 yarn / Java 25）
- [Fabric API networking 源码](https://github.com/FabricMC/fabric)（0.145.1+26.1：PayloadTypeRegistry / KeyMappingHelper / ClientCommandRegistrationCallback）
- Server List Ping 协议与 Forge modinfo 字段
- 类似思路的项目：[dark-yoddha/ModSync-Server](https://github.com/dark-yoddha/ModSync-Server)、[GTNewHorizons/ModsSync](https://github.com/GTNewHorizons/ModsSync)

## License

基于 [GNU General Public License v3.0](LICENSE) 开源。
