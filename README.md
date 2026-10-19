# ImageFrame (Fabric 服务端重实现)

> 面向 **Minecraft 26.2 / Fabric** 的服务端模组，完整对齐 Paper 插件
> [ImageFrame](https://modrinth.com/plugin/imageframe)（作者 LOOHP，GPL-3.0-only）的功能：
> 把任意图片（PNG / JPG / GIF 等）渲染成原版地图、贴满整面物品展示框墙，
> 支持动画、图层叠加、组合地图、上传服务、标记、权限、偏好与数据迁移。

- **平台**：Fabric 服务端模组（`environment: server`），**原版客户端无需安装任何模组**即可看到图片。
- **Minecraft**：26.2
- **Fabric Loader**：≥ 0.19.3
- **Fabric API**：≥ 0.156.0+26.2
- **Java**：≥ 25
- **许可证**：GPL-3.0-only（见 `LICENSE`）

> 本模组是独立重实现，不是 ImageFrame 的官方移植，与 LOOHP 无隶属关系。
> 命令、配置项、权限节点与交互方式尽量与原插件保持一致；平台差异见文末。

---

## 安装

1. 安装 Minecraft 26.2 对应的 Fabric 服务端（Fabric Loader ≥ 0.19.3）。
2. 把 `fabric-api-0.156.0+26.2.jar`（或更高版本）放入服务端 `mods/`。
3. 把本模组 `imageframe-fabric-<version>.jar` 放入服务端 `mods/`。
4. 启动服务器。首次启动会生成：
   - `config/imageframe/config.json`：配置
   - `config/imageframe/lang/en_us.json`、`zh_cn.json`：可编辑的语言文件
   - `config/imageframe/images/`：图片数据
   - `config/imageframe/players/`：玩家偏好
   - `config/imageframe/invisible_frames.json`：隐形展示框记录
5. 原版客户端直接进服即可：图片通过原版地图数据包下发，无需客户端模组。

> 单机（单人世界）不会加载服务端专用模组；如需单人使用，请开 LAN 服务器或使用专用服务端。

---

## 快速上手

```
/imageframe create <图片名> <图片地址或本地路径> <宽> <高> [抖动算法] [combined|separated]
/imageframe create <图片名> <图片地址或本地路径> selection [抖动算法] [combined|separated]
/imageframe create <图片名> upload <宽> <高>      # 使用内置上传服务
```

- `宽 x 高` 是**地图格数**（每格 128×128 像素），例如 `2 2` 表示 2×2 格。
- 图片按比例缩放并补透明边，不会拉伸变形。
- 地址可以是 `https://...` 直链，也可以是服务器工作目录下的本地文件（如 `images/cat.png`，支持绝对路径）。
- 不写 `combined|separated` 时使用配置项 `combinedByDefault`（默认 `separated`）。

### 直接贴到展示框墙上

1. 用普通物品展示框摆好一面矩形墙。
2. 执行 `/imageframe select` 进入选择模式。
3. 右键点击**左上角**展示框，再右键点击**右下角**展示框（支持墙面朝向与地板/天花板）。
4. 执行 `/imageframe create 我的图 <地址> selection`，图片会自动填入整面墙。
5. 也可用 `/imageframe select <x1> <y1> <z1> <x2> <y2> <z2>` 直接按坐标选择。

### 组合地图物品

```
/imageframe create 海报 https://example.com/a.png 3 2 nearest-color combined
```

会得到一个“组合地图”纸片物品：对着任意空展示框右键，即可把 3×2 的整张图铺到墙上；
破坏任意一格会把整张图收回成一张纸片，避免捡回一堆散地图。
`/imageframe get <图片> combined` 也能获取任意已有图片的组合物品。

### 动画 / GIF

直接把 GIF 当作图片地址即可：

```
/imageframe create 动图 https://example.com/a.gif 2 2 nearest-color
```

- 动画帧按 50ms（1 tick）粒度展开并预计算调色板。
- 通过“假地图 ID + 客户端物品切换”实现播放，不占用真实地图 ID 存档。
- 播放控制：`/imageframe playback <图片> pause [true|false]`、`/imageframe playback <图片> jumpto <秒>`。
- 玩家可用 `/imageframe preference viewAnimatedMaps false` 单独关闭动画（看到首帧静态图）。

### 图层叠加（Overlay）

把图片“画”到已存在的地图上（不新建地图物品）：

```
/imageframe overlay <名称> <地址>                          # 叠加到主手拿着的已填充地图
/imageframe overlay <名称> <地址> selection [抖动算法]     # 叠加到选区展示框里的地图
```

- 只覆盖图片中不透明的像素，透明像素保留原地图内容。
- 叠加关系会持久化，重启后自动重新应用；删除叠加图片会恢复原地图。
- 目标地图不能已经是 ImageFrame 图片地图，也不能重复。

### 内置上传服务

原插件通过外部网页服务上传图片；本模组内置了一个同功能的轻量 HTTP 上传服务：

```json
"uploadService": { "enabled": true, "host": "0.0.0.0", "port": 8163, "publicUrl": "http://你的服务器IP:8163" }
```

1. 在配置中启用并设置对外地址 `publicUrl`，`/imageframe reload`。
2. 执行 `/imageframe create 图片名 upload 2 2`。
3. 聊天栏会收到一个 5 分钟有效的私有链接，浏览器打开上传图片即可，上传完成后游戏内自动继续创建。
4. 同样支持 `/imageframe overlay 名称 upload ...`、`/imageframe refresh <图片> upload`。

- 链接一次性、随机 token、限时 5 分钟；上传文件大小受 `maxImageFileSize` 限制；仅接受图片格式。
- 未配置 `publicUrl` 时回退为 `http://127.0.0.1:<port>`（仅本机可访问，适合测试或反向代理场景）。

### 地图标记

```
/imageframe marker add <图片> <标记名> <方向0-15> <标记类型> [标题]
/imageframe marker remove <图片> <标记名>
/imageframe marker clear <图片>
/imageframe marker cancel
```

`marker add` 后，把准星移到图片上并右键即可确定标记位置；方向 0-15 与原插件一致（0=南、4=西、8=北、12=东）。
标记类型使用原版地图图标名，如 `red_marker`、`blue_marker`、`woodland_mansion`、`ocean_monument` 等（也接受插件旧别名 `mansion`、`monument`）。

### 隐形物品展示框

- `/imageframe giveinvisibleframe [invisible|glowing] [数量] [玩家]` 获取隐形展示框物品；右键方块即可放置。
- 展示框内有物品时不可见，空框时会显示（除非配置关闭 `glowEmptyFrames`）。
- 在 4 格范围内对着掉落的物品展示框投掷**隐身药水**，会将其转化为隐形展示框（数量上限 `maxConversionsPerSplash`）。

### 玩家偏好

```
/imageframe preference viewAnimatedMaps <true|false|unset> [玩家]
/imageframe preference language <语言代码|unset> [玩家]
```

- 每个玩家的偏好单独保存在 `config/imageframe/players/<uuid>.json`，未设置时使用服务器默认配置。
- 指定其他玩家需要 `preference.others` 权限；离线玩家通过服务器 `usercache.json` 解析。
- 语言文件位于 `config/imageframe/lang/<语言代码>.json`，可自行新增或覆盖任意词条，`/imageframe reload` 生效。

### 数据迁移

```
/imageframe adminmigrate <插件数据目录>     # 从原 ImageFrame 插件的文件存储迁移
/imageframe storagemigrate <数据目录>       # 从另一个 ImageFrame-Fabric 存储目录导入
```

- `adminmigrate`：读取 `data/<编号>/data.json` + 缓存的 `<格>.png`，保留原地图 ID、创建者、创建时间、
  调色板、标记与访问权限；同时导入 `players/*.json` 中的玩家偏好。图片 URL 仍可用时会优先重新下载。
- `storagemigrate`：复制另一个本模组存储目录中的图片数据；如果目录是原插件格式则自动回退到 `adminmigrate`。

### 更新检查

```json
"checkForUpdates": false,
"updateCheckUrl": "https://example.com/imageframe-latest.json"
```

Endpoint 返回 `{"version":"1.1.0","url":"下载地址","notes":"更新说明"}`。
执行 `/imageframe update` 手动检查（需要 `update` 权限）。

---

## 命令

| 命令 | 说明 |
| --- | --- |
| `/imageframe help` / `version` | 帮助 / 版本 |
| `/imageframe create <名称> <地址\|upload> selection [抖动] [模式]` | 用已选展示框创建 |
| `/imageframe create <名称> <地址\|upload> <宽> <高> [抖动] [模式]` | 按格数创建 |
| `/imageframe overlay <名称> <地址\|upload> [selection] [抖动]` | 图层叠加到已有地图 |
| `/imageframe select [x1 y1 z1 x2 y2 z2]` | 切换选择模式 / 坐标选择 |
| `/imageframe list [玩家]` | 列出自己（或他人）的图片 |
| `/imageframe info [图片]` | 查看图片信息（省略参数时读取手中地图） |
| `/imageframe get <图片> [combined\|separated\|selection] [玩家]` | 获取地图物品 / 组合物品 / 填入选区 |
| `/imageframe rename <图片> <新名称>` | 重命名 |
| `/imageframe delete <图片>` | 删除自己的图片 |
| `/imageframe clone <图片> <新名称> [combined\|separated\|selection]` | 复制图片 |
| `/imageframe refresh [图片] [地址] [抖动]` | 重新下载、替换 URL 或修改抖动算法 |
| `/imageframe playback <图片> pause [true\|false]` / `jumpto <秒>` | 动画控制 |
| `/imageframe marker add\|remove\|clear\|cancel ...` | 标记管理 |
| `/imageframe setaccess <图片> <玩家> <none\|view\|edit>` | 设置他人访问权限 |
| `/imageframe giveinvisibleframe [类型] [数量] [玩家]` | 获取隐形展示框 |
| `/imageframe preference <类型> <值> [玩家]` | 玩家偏好 |
| `/imageframe adminsetcreator <图片> <玩家>` | 修改图片创建者 |
| `/imageframe adminmigrate <目录>` | 从原插件迁移数据 |
| `/imageframe storagemigrate <目录>` | 从其他存储目录导入 |
| `/imageframe update` | 检查更新 |
| `/imageframe resync` | 重新读取磁盘数据并刷新展示框 |
| `/imageframe reload` | 重载配置与语言文件 |
| `/imageframe purge <玩家\|*>` | 清空某玩家 / 全部图片（管理员） |
| `/imageframe admindelete <图片>` | 管理员强制删除 |

别名：`/iframe`、`/frame`。

抖动算法可选：`nearest-color`、`floyd-steinberg`、`atkinson`、`ordered-bayer-4x4`、`ordered-bayer-8x8`、`sierra-lite`。

---

## 权限

Fabric 服务端没有 Bukkit 权限系统，本模组把原插件的权限节点映射为
`all`（所有人）/ `op`（管理员）/ `none`（禁止）三档，可在配置 `permissions` 中逐项修改。

| 节点 | 默认 | 说明 |
| --- | --- | --- |
| `create` / `create.animated` / `create.others` | op | 创建 / 创建动画 / 替他人创建 |
| `overlay` | op | 图层叠加 |
| `clone` | op | 复制 |
| `playback` | op | 动画播放控制 |
| `select` | op | 选择模式 |
| `marker` / `marker.unlimited` | op | 标记 / 突破标记数量上限 |
| `refresh` | op | 刷新图片 |
| `rename` | op | 重命名 |
| `info` / `list` / `list.others` | all / all / op | 信息 / 列表 / 他人列表 |
| `delete` | op | 删除自己的图片 |
| `get` / `get.others` | op | 获取地图物品 / 给其他玩家 |
| `setaccess` | op | 设置访问权限 |
| `adminbypass` / `admindelete` | op | 忽略访问限制 / 管理员删除 |
| `adminsetcreator` | op | 修改创建者 |
| `adminmigrate` / `storagemigrate` | op | 数据迁移 |
| `purge` / `purge.all` | none / none | 清空 |
| `giveinvisibleframe` | op | 隐形展示框 |
| `preference` / `preference.others` | all / op | 玩家偏好 / 设置他人偏好 |
| `reload` / `resync` | op | 重载 / 重读 |
| `update` | op | 检查更新 |
| `createlimit.unlimited` | op | 绕过创建数量上限 |

> 管理员 = 服务器 OP（`/op`）。控制台执行命令默认视为有权限。

---

## 配置（`config/imageframe/config.json`）

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `language` | `zh_cn` | 服务器默认语言（`en_us` / `zh_cn`，或自行添加的语言文件代码） |
| `dateFormat` | `yyyy-MM-dd HH:mm:ss` | 时间显示格式 |
| `mapItemFormat` | `&f{Name} &7({X}, {Y})` | 地图物品说明（lore）第一行的名称模板，占位符：`{Name}`、`{ImageID}`、`{X}`、`{Y}`、`{Width}`、`{Height}`、`{DitheringType}`、`{CreatorName}`、`{CreatorUUID}`、`{TimeCreated}`。名称显示在物品说明中（后面依次是图片编号、创建者），不会在展示框上显示悬浮名称；设为空字符串则省略名称行 |
| `requireEmptyMaps` | `true` | 生存模式创建需消耗空地图 |
| `maxSize` | `100` | 单张图片最大格数（宽×高） |
| `restrictImageUrl` | `false` | 是否仅允许白名单地址 |
| `urlWhitelist` | 常见图床 | URL 前缀白名单 |
| `maxImageFileSize` | 52428800 | 最大下载/上传字节数 |
| `maxProcessingTime` | `60` | 下载/处理超时（秒） |
| `parallelProcessingLimit` | `1` | 并行处理线程数 |
| `playerCreationLimit` | `{"default": 10}` | 每玩家图片数量上限 |
| `mapMarkerLimit` | `20` | 每格标记数量上限 |
| `combinedByDefault` | `false` | 默认给出组合地图物品 |
| `handleAnimatedMapsOnMainThread` | `true` | 动画帧生成线程策略 |
| `maxAnimationFrames` | `600` | 动画最大帧数 |
| `viewAnimatedMaps` | `true` | 服务器默认是否播放动画（玩家偏好可覆盖） |
| `debugLogging` | `false` | 调试日志 |
| `invisibleFrame.maxConversionsPerSplash` | `8` | 一次隐身药水最多转化数量 |
| `invisibleFrame.glowEmptyFrames` | `true` | 空隐形框显示发光效果 |
| `checkForUpdates` | `false` | 启动时是否自动检查更新 |
| `updateCheckUrl` | `""` | 更新检查 JSON 地址 |
| `uploadService.enabled` | `false` | 是否启用内置上传服务 |
| `uploadService.host` | `0.0.0.0` | 监听地址 |
| `uploadService.port` | `8163` | 监听端口 |
| `uploadService.publicUrl` | `""` | 对外访问地址（生成上传链接用） |
| `permissions` | 见上表 | 权限模式映射 |

---

## 数据存储

```
config/imageframe/
├── config.json              # 配置
├── state.nbt                # 下一个图片编号 / 假地图 ID 计数器
├── invisible_frames.json    # 隐形展示框位置
├── lang/                    # 语言文件（en_us.json / zh_cn.json / 自定义）
├── players/<uuid>.json      # 玩家偏好
├── upload/                  # 内置上传服务接收的图片
└── images/
    └── <编号>/
        ├── meta.nbt         # 名称、地址、尺寸、抖动、作者、标记、动画序列、叠加底图…
        └── colors/
            ├── t0.bin       # 静态/叠加图：每格 16384 字节（128×128 调色板索引）
            └── t0_d0.bin    # 动画图：每格每帧一份
```

删除图片会同时删除对应目录；修改配置后执行 `/imageframe reload`，重读磁盘数据执行 `/imageframe resync`。

---

## 本地化

模组自带简体中文（`zh_cn`，默认）与英文（`en_us`）文本：聊天消息、`/imageframe info` 与 `list` 的字段名、
地图物品说明（名称/图片编号/创建者）、上传服务网页、模组描述均已汉化。

- 服务器默认语言：配置 `language`。
- 玩家个人语言：`/imageframe preference language en_us|zh_cn`。
- 可编辑语言文件：`config/imageframe/lang/zh_cn.json`、`en_us.json`；也可以新增其他语言代码，修改后 `/imageframe reload` 生效。

## 与原插件 / 平台的差异

**已完整实现**：原插件的全部命令分支（`create`、`overlay`、`clone`、`playback`、`select`、`marker`、
`refresh`、`rename`、`info`、`list`、`delete`、`purge`、`setaccess`、`get`、`admindelete`、`adminsetcreator`、
`adminmigrate`、`preference`、`giveinvisibleframe`、`storagemigrate`、`update`、`reload`、`resync`），
以及 URL/本地图片、多格、GIF 动画、组合地图、选区、标记、隐形展示框、上传服务、玩家偏好、语言文件、
权限映射、数据持久化与迁移。

**平台差异（Fabric 没有对应生态，因此以等价方式实现或替换）**：

- 权限：Bukkit 权限节点 → 配置内 `all/op/none` 三档映射（OP 判定使用原版 OP 列表）。
- 上传服务：原插件依赖外部网页服务；本模组改为**内置 HTTP 上传服务**（可用反向代理暴露）。
- 更新检查：原插件查询 SpigotMC/Modrinth；本模组使用可配置 JSON 地址（默认关闭）。
- 存储后端：原插件支持 file/MySQL/SQLite 与 `storagemigrate` 后端切换；本模组为单一文件后端，
  `storagemigrate` 实现为“从另一个存储目录导入”，`adminmigrate` 支持从原插件文件后端的真实数据迁移。
- 与保护类插件（领地/锁箱）的交互检查：Fabric 无统一 API，未实现；可用原版权限或保护模组自行限制。
- PlaceholderAPI / 插件 API 事件 / bStats 统计：属 Bukkit 生态，未实现（无对应功能需求）。
- 动画实现：在服务端预计算全部帧，通过“假地图 ID + 实体元数据逐 tick 切换”下发；网络开销略高于原插件客户端方案。
- 管理员的 `其他玩家:图片名` 前缀写法：本实现按全局名称解析图片（忽略前缀），未支持替他人操作的前缀语法。
- `list` 的控制台用法、`adminsetcreator` 的离线玩家名等细节与原插件略有差异。

---

## 构建

需要 JDK 25。

```bash
./gradlew build          # Windows: gradlew.bat build
```

产物：`build/libs/imageframe-fabric-<version>.jar`（另有 `-sources.jar`）。

本仓库在离线环境（Gradle 9.7.1 + Loom 1.18.4 缓存）下可构建；首次构建需联网下载 Fabric Loom / Minecraft 依赖。

### 验证记录

- `gradle build` 通过；单元测试覆盖命令树结构、PNG/GIF 解码、调色板/抖动/缩放、颜色解析、
  标记类型映射、更新版本比较。
- Fabric 26.2 开发服务器实机验证：
  - 静态 1×1 / 2×2、动画 GIF、组合地图、`get`、`list`、`info`、`rename`、`refresh`、`delete`、`playback`、`setaccess`、`marker`；
  - 图层叠加（主手地图 + `OVERLAY` 类型持久化）、重启后恢复；
  - 玩家偏好（`viewAnimatedMaps`、`language`）逐玩家生效且重启保留；
  - 隐形展示框（`invisible` / `glowing`、数量、目标玩家）；
  - 内置上传服务端到端：生成链接 → 浏览器/HTTP 上传 → 自动创建图片；
  - `adminmigrate` 从原插件格式导入（保留地图 ID、标记、创建者）；`storagemigrate` 目录导入；
  - `update` 检查、`reload`（语言/配置/上传服务重载）、`resync`；
  - 服务器优雅关闭并重启后，图片、叠加、玩家偏好、隐形框数据全部恢复。

---

## 许可与致谢

- 本项目以 **GPL-3.0-only** 发布，见 `LICENSE`。
- 功能设计与交互参考 [ImageFrame](https://github.com/LOOHP/ImageFrame)（LOOHP，GPL-3.0-only）；
  本项目为独立实现，未复制其源码。
- 调色板使用原版 `MapColor` 256 色调色板；动画/假地图机制参考原插件公开行为。
