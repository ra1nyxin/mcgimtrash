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

1. 从仓库的 Releases 页面下载最新发布中的 `mcgimtrash-*.jar`。
2. 将 JAR 放入服务端的 `plugins` 目录。
3. 重启服务端。

插件首次启用后，第一次清扫会安排在约 30 分钟后。玩家也可以使用 `/mcgimtrash` 打开共享垃圾桶。

运行数据保存在 `plugins/mcgimtrash/trash-state.bin`，上一份有效状态保存在 `trash-state.bin.bak`。升级或迁移服务端前，建议在正常关闭服务端后备份整个 `plugins/mcgimtrash` 目录。

## 兼容性

当前代码基于 Paper API `26.2.build.87-stable` 编译，字节码目标为 Java 25。

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

服务端必须使用 Java 25 或更高版本。Paper 的原生物品序列化数据通常可以随服务端升级，但不保证能够降级读取；已经在新版本服务端保存过的状态文件不应直接交给更旧的服务端使用。

## 构建

需要 Java 25 和 Maven：

```bash
mvn --batch-mode --no-transfer-progress clean package
```

构建结果位于 `target/mcgimtrash-1.0.0.jar`。

## 自动发布

推送到 `main` 分支或手动运行 GitHub Actions 工作流时，仓库会自动执行 Maven 构建并创建 GitHub Release。每次发布的 tag 和 Release 标题都由独立的随机 UUID 生成，不使用需要人工维护的语义化版本号；构建出的 JAR 会作为 Release 资产上传。

## 许可证

本项目使用 [MIT License](LICENSE)。
