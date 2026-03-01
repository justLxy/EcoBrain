# EcoBrain 插件说明（给未来的自己）

这份文档不是“营销文案”，是给你以后回头维护项目时看的。  
重点是：**插件到底怎么跑、为什么这么设计、改参数会发生什么**。

---

## 1. 这个插件在做什么

EcoBrain 是一个动态市场插件，不是固定价格商店。  
核心思路：

- 玩家把物品卖给系统，系统记录库存
- 系统根据库存变化自动调整价格（AMM 曲线）
- 玩家买卖行为反过来影响库存和价格
- AI 定时微调参数，让市场不要长期失衡
- 熔断机制在极端行情时直接冻结交易，防止崩盘/被刷

一句话：它更像“交易所做市池”，不是传统 `items.yml` 价目表。

---

## 2. 你最关心的定价公式

### 当前价格

`P_current = BasePrice * (TargetInventory / CurrentInventory)^k`

- `BasePrice`：基准价（IPO 或 AI 调整后）
- `TargetInventory`：库存平衡点（价格锚点，不是硬上限）
- `CurrentInventory`：当前库存（代码中至少按 1 计算，防止除零）
- `k`：价格敏感度（越大越“陡”）

### 批量卖出（有滑点）

玩家一次卖 `N` 个，系统不是 `当前价 * N`，而是逐个累加：

`Sum(i=1..N) BasePrice * (TargetInventory / (CurrentInventory + i))^k`

含义：每卖进来一个，库存都会增大，后面的单价会继续下降。

### 批量买入（镜像滑点）

玩家一次买 `N` 个时，库存逐步减少，价格逐步提高。

---

## 3. 物品是怎么识别的（NBT 兼容）

插件没有用 `Material` 当主键。  
识别链路是：

1. `ItemStack` 完整序列化成 Base64（保留 NBT/元数据）
2. 对 Base64 做 SHA-256
3. 用 `item_hash` 作为数据库主键

这套方案能区分带复杂 NBT 的物品（比如 RPGItems / MythicMobs 自定义物品）。

---

## 4. IPO 机制（零配置上架）

没有 `items.yml` 手动录入。  
某个物品第一次被玩家卖出时会触发 IPO：

- 写入 `ecobrain_items`
- 使用 `config.yml` 里的 `economy.ipo.*` 作为初始参数
- 首次库存 = 这次卖出的数量（最小按 1）

所以你只要正常运营，市场会自动“学会”有哪些物品。

---

## 5. 玩家命令与行为

主命令：`/ecobrain`（别名 `/eb`）

- `sell <数量>`：卖主手物品
- `buy <数量>`：按主手模板买同类物品
- `bulk`：打开批量出售 GUI
- `market [页码]`：查看市场大盘
- `reload`：热更新 `config.yml`（需要 `ecobrain.admin`）
- `admin <clear|freeze|unfreeze> <hash>`：管理操作（需要 `ecobrain.admin`）

> 注意：`buy` 目前是“主手模板匹配”模式，不是按 hash 参数购买。

---

## 6. 批量出售 GUI 防刷逻辑

界面规则：

- 大小 54 格
- `0~44`：玩家可放待售物品
- `45`：确认出售
- `53`：取消并退回
- `45~53` 底栏有占位物

防刷点：

- 禁止往底栏放物品（点击/拖拽都拦截）
- ESC 或关闭界面时，`0~44` 物品会退回
- 结算使用会话幂等标记，防止重复点击重入
- 批量结算在异步线程计算与落库，主线程只做背包/经济操作

---

## 7. 熔断器怎么工作

交易前会做风控检查：

1. 该物品是否已冻结
2. 库存是否低于 `critical-inventory`
3. 当前价相对“当日开盘价”涨跌是否超过 `daily-limit-percent`

触发后会写冻结标记，后续买卖直接拒绝。

---

## 8. AI 在做什么（简化版）

AI 每隔 `ai.schedule-hours` 小时跑一次：

1. 收集市场状态（物品数量、总库存、库存失衡、均价、成交量）
2. DQN 选择动作（涨/跌）
3. 用奖励函数评估：  
   `R = w1*TransactionVolume - w2*InflationDelta - w3*InventoryImbalance`
4. 训练网络并调整 `base_price` 与 `k_factor`

为了防止 AI 乱调，参数调整有硬限制（比如每轮最大 ±5%、k 的上下限）。

---

## 9. 线程模型（为什么不卡主线程）

原则：**重计算和 IO 放异步，Bukkit 强相关操作留主线程**。

- 异步：SQLite、批量滑点计算、AI 训练、统计查询
- 主线程：背包扣发、GUI 打开关闭、Vault 实际扣款发款调用点

这块是插件稳定性的基础，后续改代码不要破坏这个边界。

---

## 10. 数据库结构（SQLite）

数据库文件：`plugins/EcoBrain/ecobrain.db`

### `ecobrain_items`

- `item_hash`（主键）
- `item_base64`
- `base_price`
- `k_factor`
- `target_inventory`
- `current_inventory`

### `ecobrain_risk`

- `item_hash`（主键）
- `day_open_price`
- `day_key`
- `is_frozen`

### `ecobrain_trade_stats`

- `id`
- `item_hash`
- `trade_type`
- `quantity`
- `total_price`
- `created_at`

---

## 11. 配置文件怎么用（重点）

配置路径：`plugins/EcoBrain/config.yml`  
改完执行：`/ecobrain reload`

调参建议（统一一套参数，不分层）：

- 想让价格更稳：降低 `k-factor`、降低 `target-inventory`
- 想让稀有物更贵：提高 `k-factor` 或提高 `target-inventory`
- 想减少误触发熔断：提高 `daily-limit-percent`
- 想让交易更顺滑：适当降低 `trade.cooldown-ms`

别一次改太多，推荐每次改 1~2 个参数，观察半天到一天。

---

## 12. 热更新范围（`/ecobrain reload`）

当前支持热更新：

- IPO 参数（`base-price` / `target-inventory` / `k-factor`）
- 交易冷却时间
- 熔断阈值
- 批量出售 GUI 样式（标题、材质、名字、lore）
- AI 调度参数（会重启 AI 定时任务）

说明：

- 经验回放池容量配置会在下次完整重启时最干净地应用。
- reload 失败会返回错误消息，不会静默吞掉。

---

## 13. 常见问题

### Q1：为什么有些物品特别贵？

通常是库存太低，而 `target-inventory` 或 `k-factor` 偏高。  
先把 `k-factor` 降到 `0.6~0.8` 再观察。

### Q2：为什么很多东西都不值钱？

通常是库存长期远高于目标库存。  
要么降低该类物品输入速度，要么调低 `target-inventory`。

### Q3：管理员 `clear` 是干嘛的？

`/ecobrain admin clear <hash>` 会删除该物品的市场档案（含统计/风控记录），相当于重置这件物品。

---

## 14. 维护提醒（给未来自己）

- 任何“看起来方便”的同步数据库操作，都要先想想 TPS
- 改交易流程时，优先保证“扣物品/扣钱/落库”的顺序一致性
- GUI 相关改动一定要回归“关闭退回”和“重入防刷”
- 新增命令尽量走统一权限：`ecobrain.admin`

如果以后想扩展成多市场、多币种，优先重构 `repository + service` 边界，不要直接在命令里堆逻辑。

