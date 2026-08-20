# mcgimtrash

mcgimtrash 是一个面向 Paper 26.2 的地面掉落物管理插件。它定期收集已加载世界中的掉落物，并将可容纳的物品保存到全服共享的垃圾桶 GUI 中，玩家可以在物品被周期清空前取回。

## 功能

- 每 30 分钟扫描一次所有已加载世界中的掉落物实体。
- 清扫前 5 分钟、60 秒、30 秒和 10 秒分别发送全服提醒。
- 清扫完成后显示收集的物品总件数，并提供可点击的 `[打开垃圾桶]`。
- 提供 30 页、每页 6 x 9 格的共享垃圾桶；左右上角用于翻页，其余共 1560 个槽位用于存放物品。
- 玩家只能从垃圾桶取出物品，不能向其中放入物品；拖拽、双击收集、快捷栏交换、创造模式克隆和 Bundle 操作等入口均受限制。
- 垃圾桶容量不足时，只收走能够容纳的部分，其余物品继续留在地面。
- 每完成 10 次清扫后保留当前垃圾桶内容，直到第 11 次清扫开始时清空旧内容并进入新周期。
- 清扫时间、周期计数和垃圾桶内容会持久化。状态文件使用校验、临时文件原子替换和备份恢复；存储写入失败时会暂停清扫与 GUI 访问。

## 安装

1. 从仓库的 Releases 页面按运行环境选择下载：
   - `mcgimtrash-*-jdk25.jar`：标准 Paper/Purpur 26.2 环境，推荐选择；
   - `mcgimtrash-*-jdk21.jar`：仅用于服务端实现本身可在 Java 21 启动，并且提供本插件所需 Paper 26.2 API 的兼容环境。
2. 将 JAR 放入服务端的 `plugins` 目录。
3. 重启服务端。

插件首次启用后，第一次清扫会安排在约 30 分钟后。玩家也可以使用 `/mcgimtrash` 打开共享垃圾桶。

运行数据保存在 `plugins/mcgimtrash/trash-state.bin`，上一份有效状态保存在 `trash-state.bin.bak`。升级或迁移服务端前，建议在正常关闭服务端后备份整个 `plugins/mcgimtrash` 目录。

## 兼容性

当前代码基于 Paper API `26.2.build.87-stable` 编译。Release 同时提供 Java 21（class major 65）和 Java 25（class major 69）字节码版本。

| 环境 | 兼容情况 |
| --- | --- |
| Paper 26.2 | 已开发和实测的目标环境 |
| Purpur 26.2 | Purpur 基于 Paper，通常可以直接运行，但发布前未逐构建测试 |
| 原生 Spigot | 不支持 |
| 低于 26.2 的 Paper/Purpur | `api-version: 26.2` 会阻止插件加载 |
| 高于 26.2 的 Paper/Purpur | 一般具有向后兼容性，但仍应在升级前测试 |

插件使用了以下 Paper 接口，因此当前 JAR 不能视为通用 Spigot 插件：

- Adventure `Component` 消息、点击事件和悬停事件；
- `Inventory.close()` 和 `Inventory.getHolder(boolean)`；
- `ItemStack.of()`、`ItemStack.isEmpty()`；
- `ItemStack.serializeItemsAsBytes()` 和 `ItemStack.deserializeItemsFromBytes()`；
- 接受 Adventure `Component` 的 GUI 标题、物品名称和 lore 接口。

标准 Paper 26.2 API 自身使用 Java 25 字节码，因此常规 Paper/Purpur 26.2 服务端仍需要 Java 25，应该下载 `jdk25` 资产。`jdk21` 资产确实使用 Java 21 字节码，但它不会让原本要求 Java 25 的服务端改为支持 Java 21；它只为能够在 Java 21 启动、同时兼容所需 Paper 26.2 API 的下游实现保留。Java 25 JVM 也可以加载 `jdk21` 资产，但常规环境建议下载与服务端 Java 主版本一致的构建。

Paper 的原生物品序列化数据通常可以随服务端升级，但不保证能够降级读取；已经在新版本服务端保存过的状态文件不应直接交给更旧的服务端使用。

## 构建

由于 Paper 26.2 API 本身使用 Java 25 字节码，两种目标版本都使用 JDK 25 编译器构建。Maven 的 `release` 参数决定最终插件字节码版本：

```bash
# Java 21 字节码（class major 65）
mvn --batch-mode --no-transfer-progress -Dmaven.compiler.release=21 clean package

# Java 25 字节码（class major 69）
mvn --batch-mode --no-transfer-progress -Dmaven.compiler.release=25 clean package
```

每条命令的构建结果均位于 `target/mcgimtrash-1.0.0.jar`。GitHub Actions 会分别重命名为带有 `jdk21` 和 `jdk25` 后缀的 Release 资产。

## 自动发布

推送到 `main` 分支或手动运行 GitHub Actions 工作流时，仓库会自动构建 Java 21 和 Java 25 两种字节码版本，并验证主类的 class major 分别为 65 和 69。随后工作流会创建 GitHub Release，并将两个 JAR 作为独立资产上传。每次发布的 tag 和 Release 标题都由独立的随机 UUID 生成，不使用需要人工维护的语义化版本号。

## 许可证

本项目使用 [MIT License](LICENSE)。
