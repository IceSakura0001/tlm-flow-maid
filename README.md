# 诸艺皆通：自定义女仆任务

**Maid of Many Trades: Custom Tasks**（项目标识：`tlm-flow-maid`）

为女仆拖拽编写任务流程：移动、存取物、采集、攻击/巡逻、饲养/繁殖，以及调用女仆原有工作能力。

**本项目是酒石酸（TartaricAcid）东方女仆 Touhou Little Maid 的非官方衍生附属模组，必须前置安装母模组，不能独立运行。** 原作者与许可详见 [NOTICE.md](NOTICE.md)。

## 下载

[GitHub 1.6.3 Alpha 下载](https://github.com/IceSakura0001/tlm-flow-maid/releases/tag/v1.6.3-alpha) · [Modrinth 项目（已提交审核）](https://modrinth.com/mod/maid-of-many-trades)

## 安装要求

| 项目 | 支持版本 |
| --- | --- |
| Minecraft Java | 1.20.1 |
| Forge | 47.2.0+，47.x |
| Java | 17 |
| 必装前置 [Touhou Little Maid](https://modrinth.com/mod/touhou-little-maid) | **1.5.3-forge+mc1.20.1** |

客户端和服务器安装相同附属模组版本。将 JAR 放入 mods，移除旧版或重复附属 JAR。本项目不包含母模组。

## 当前版本：1.6.3-road-experimental（Alpha）

- 拖拽流程图、手动画线、条件判断、循环、分组移动、缩放平移、自动排版。
- 中文名称联想、准星选位置/容器、存取物、装备工具、物品耐久/命名/附魔适配。
- 工作时长与数量等结束标准；服务端权限与执行校验、任务保存。
- 实验性通用筑路/拆障：真实消耗圆石；拆障需主人批准；禁拆区域和方块保护。
- 窄路减速、边缘支撑检查、阶梯居中、转向后起跳。

54 项必需服务端 GameTest 已通过；玩家反馈当前版本可用。不同整合包、外力和复杂地形仍需测试，**不保证绝不跌落**。交易和通用使用动作尚未实现，编辑器会阻止包含不支持动作的任务执行。AI 自然语言生成尚未实现。

筑路默认关闭，在流程图“设置 → 通用移动”开启，并提供圆石和适用工具。局部规划最多水平16格、上下6格、24步；不会强制加载区块。已建道路和已批准完成的拆障不会自动回滚，建议备份存档后体验 Alpha。

## 从源码构建

准备 Java 17，运行 `powershell -ExecutionPolicy Bypass -File fetch-tlm.ps1` 下载并校验官方母模组编译依赖（只保存在本地 libs，不随仓库分发），然后运行 `./gradlew build`（Windows：`gradlew.bat build`）。产物在 build/libs。

运行服务端测试：`gradlew.bat runGameTestServer -x downloadAssets`。测试类不包含在发布 JAR 中。

## 开发署名

本项目的附属模组代码完全由 **ChatGPT** 编写。功能构思、需求设计与游戏测试由 **IceSakura0001** 提供。此说明仅指本项目自行编写的代码，不包括 Minecraft、Forge、东方女仆、Gradle 及其他第三方代码或工具；其版权与署名保持原样。

All add-on project code was written entirely by **ChatGPT**. Concept, requirements and in-game testing were provided by **IceSakura0001**. This statement does not apply to upstream projects or third-party dependencies and tooling.

## 许可与致谢

附属代码 MIT。母模组归酒石酸 / TartaricAcid 及其贡献者所有：代码 MIT，资源 CC BY-NC-SA 4.0；[完整声明](NOTICE.md)。本项目不代表母模组官方。

---

An unofficial visual task-programming add-on for **Touhou Little Maid by TartaricAcid**. Requires Minecraft 1.20.1, Forge 47.2.0+ (47.x), Java 17 and **Touhou Little Maid 1.5.3 for Forge** on both client and server. Current release is an **experimental alpha**. Build roads with real cobblestone, request owner approval before clearing obstacles, and use a drag-and-drop editor to compose tasks. No parent-mod binary or assets are bundled. Add-on code: MIT; upstream code: MIT; upstream assets: CC BY-NC-SA 4.0.
