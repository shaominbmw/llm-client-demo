# 踩坑 01｜PowerShell 写 UTF-8 带 BOM，javac 报「非法字符 '\ufeff'」

**日期**：2026-11-21　**项目**：llm-client-demo　**严重度**：中

## 现象

用 PowerShell 批量改了一个 `.java` 文件后，`mvn test` 直接编译失败：

```
[ERROR] LlmConfig.java:[1,1] 非法字符: '\ufeff'
[ERROR] LlmConfig.java:[1,10] 需要 class、interface、enum 或 record
[ERROR] 共 15 errors
```

奇怪的是：文件看起来完全正常，第一行就是 `package com.wuxi.ai.llm;`。

## 原因

`\ufeff` 是 **BOM（Byte Order Mark，字节序标记）**，Unicode 字符 U+FEFF，编码成字节是 `EF BB BF`。

根因是这一行：

```powershell
Set-Content $file -Encoding utf8
```

**PowerShell 5.1 的 `-Encoding utf8` 默认会写入 BOM**（注意：PowerShell 7+ 才改成不带 BOM）。

而 **javac 不识别 BOM**，会把它当成源码里的第一个字符，于是报「非法字符」。

用十六进制看一眼就明白了：

```powershell
Format-Hex -Path .\LlmConfig.java -Count 3
# 输出开头是 EF BB BF 就是有 BOM
```

## 解决

三种都行：

```powershell
# 方案 1：.NET API，显式指定不带 BOM 的 UTF8
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($path, $content, $utf8NoBom)

# 方案 2：PowerShell 7+
Set-Content $file -Encoding utf8NoBOM

# 方案 3：直接在 IDE 里改（最省事，IDEA/VSCode 默认不带 BOM）
```

## 以后怎么避免

1. **改 Java 源码一律用 IDE 或编辑器的写文件工具**，不要用 shell 重定向
2. 如果非要用脚本改，统一封装一个 `Write-Utf8NoBom` 函数，禁止裸用 `Set-Content -Encoding utf8`
3. 项目里可以加一条检查：CI 里扫一遍所有源文件有没有 BOM

## 为什么记这个

BOM 问题在国内 Windows 环境非常常见，而且**报错信息极具误导性**——它说「第 1 行第 10 列需要 class」，你会去怀疑 package 语句，根本想不到是看不见的三个字节。

知道这件事的人，30 秒解决；不知道的人，可能折腾一晚上。
