# 踩坑 03｜IDEA 里读不到刚设置的环境变量

**日期**：2026-11-21　**项目**：llm-client-demo　**严重度**：中

## 现象

命令行里明明能读到：

```powershell
echo $env:DEEPSEEK_API_KEY   # 正常输出 sk-...
mvn -q exec:java             # 调用成功
```

但同样在 IDEA 里点 Run，程序打印：

```
⚠ 没有找到 API Key，先设置环境变量再运行
```

代码一模一样，环境变量也已经 `setx` 过了。

## 原因

**Windows 进程在启动那一刻，会复制一份环境变量快照**，之后系统里再怎么改，老进程都看不到。

时间线对上了：

| 事件 | 时间 |
|---|---|
| IDEA 启动 | 21:37:59 |
| 执行 `setx DEEPSEEK_API_KEY ...` | 约 21:55 |

IDEA 出生时这个变量还不存在，所以它这一辈子都读不到。

## 解决

**完全退出 IDEA 再打开**（`File → Exit`，不是点右上角关窗口）。

如果还不行，说明连 `explorer.exe` 也拿着旧快照，那就：

```powershell
# 1. 新开一个 PowerShell（它已读到新变量）
echo $env:DEEPSEEK_API_KEY

# 2. 从这个窗口启动 IDEA，让它继承新环境
& "D:\MySoftWare\IntelliJ-IDEA-Community\bin\idea64.exe"
```

## 以后怎么避免

1. **先配好环境变量，再开 IDE**——顺序反了就得重启
2. `setx` 之后**必须新开终端**验证一次 `echo $env:XXX`，确认有值再跑程序
3. 可以写个自检提示，启动时把「变量是否存在 + 长度」打出来（别打完整值）

## ⚠️ 一个反模式：别用 Run Configuration 存 key

看起来很方便：`Run → Edit Configurations → Environment variables` 填 key。

**不要这么做。** 因为 IDEA 会把明文写进：

```
.idea\runConfigurations\Demo.xml
```

而这个路径**默认不在 `.gitignore` 里**，一 `git add .` 就泄露了。

如果非要用，先把这条加进 `.gitignore`（本项目已经加了）：

```gitignore
.idea/runConfigurations/
.env
*.local
```
