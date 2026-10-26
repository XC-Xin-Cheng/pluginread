# Plugin Read

**A client-side Fabric mod for Minecraft 26.1 that reads mods & plugins shared by the server.**

Plugin Read lets players browse, download and inspect the mod/plugin jars that a server distributes — entirely in-game. **No server-side installation required**: any web server (nginx, a panel, static hosting) can serve the file manifest.

- Minecraft: 26.1 (`~26.1`)
- Fabric Loader: >= 0.20.1
- Fabric API: 0.144.0+26.1 or newer
- Java: >= 25

## Features

| Feature | Description |
| --- | --- |
| In-game file browser | Press **V** (rebindable) or run `/pluginread` to open the UI |
| One-click download | Click any entry to download it, or use **Download All** |
| Live progress | Per-file download percentage shown in the list |
| Metadata parsing | Automatically reads `fabric.mod.json`, `plugin.yml` and `mods.toml` from downloaded jars and shows type ([Mod] / [Plugin]), name and version |
| SHA-256 verification | File integrity is checked when the manifest provides a hash |
| Server mod list | Shows the running mod list broadcast by Forge / NeoForge servers via Server List Ping |
| Auto-probe | On join, common manifest paths on the server host are probed automatically |
| Client commands | `/pluginread` (open UI), `/pluginread refresh` (re-probe), `/pluginread folder` (open download folder) |

Downloaded files are saved to `.minecraft/pluginread/<server-address>/`. They are only saved and parsed — **never loaded or executed** by the game.

## Server side: sharing files

Put a manifest and your jars on any web server, e.g. `http://your-server/pluginread/manifest.json`:

```json
{
  "files": [
    {
      "name": "fabric-api-0.155.3+26.1.2.jar",
      "size": 2413567,
      "sha256": "hex-encoded sha256 (optional)",
      "url": "fabric-api-0.155.3+26.1.2.jar"
    },
    {
      "name": "SomeServerPlugin.jar",
      "url": "https://cdn.example.com/plugins/SomeServerPlugin.jar"
    }
  ]
}
```

- `url` may be relative to the manifest location, site-absolute (`/...`), or a full URL.
- The client automatically probes `http://<server>/pluginread/manifest.json`, `https://<server>/pluginread/manifest.json` and `http://<server>/manifest.json` (configurable).
- You can also set explicit manifest URLs per server in the client config.

## Client config: `config/pluginread-client.json`

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

- `autoProbe` — probe common manifest paths when joining a server.
- `servers.<address>.manifestUrls` — explicit manifest URLs (supports the `*` wildcard).
- `autoDownload` — automatically download all files on join for that server.
- `maxFileMb` — per-file size limit.

## Building

Requires JDK 25 (Gradle JVM):

```bash
./gradlew build
# Output: build/libs/pluginread-1.0.0.jar
```

On Windows, if your default JDK is not 25:

```bash
JAVA_HOME="/c/Program Files/Zulu/zulu-25" ./gradlew build
```

This repository bundles an **offline Maven repository** (`local-fabric-maven/`) with loom, loader, intermediary and all required Fabric API modules, and prefers it plus the Aliyun Maven mirror. This keeps builds working on networks where `maven.fabricmc.net` is throttled or unreachable; rebuild scripts live in `tools/`.

## Security notes

- Downloads are size-limited and SHA-256 verified when the manifest provides a hash.
- Downloaded jars are only saved and parsed for metadata — the game never loads or executes them. To actually enable a client mod, review it yourself and move it into your `mods` folder.
- Auto-probe only contacts the hostname of the server you join (plain HTTP/HTTPS), nothing else.

## License

Licensed under the [GNU General Public License v3.0](LICENSE).
