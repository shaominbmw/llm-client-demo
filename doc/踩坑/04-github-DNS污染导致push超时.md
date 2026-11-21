# 踩坑 04｜github.com 能解析但连不上，git push 超时

**日期**：2026-11-21　**项目**：llm-client-demo　**严重度**：高

## 现象

`git push` 直接失败：

```
fatal: 发送请求时出错。
fatal: 基础连接已经关闭: 接收时发生错误。
fatal: 无法从传输连接中读取数据: 由于连接方在一段时间后没有正确答复或连接的主机没有反应。
fatal: could not read Username for 'https://github.com'
```

而且浏览器里能正常打开 GitHub（账号也登录着、仓库也建好了），偏偏命令行不行。

## 排查过程

**第一步：分清是「连不上」还是「没权限」**

```powershell
curl.exe -sS -o NUL -w "code=%{http_code} tcp=%{time_connect}s tls=%{time_appconnect}s" --max-time 15 https://github.com
# 结果：code=000  tcp=0.000000s  tls=0.000000s  → TCP 都没建起来
```

`tcp=0` 说明**连接阶段就失败了**，跟认证无关。

**第二步：对比不同子域名**

| 域名 | 结果 |
|---|---|
| `github.com` | ❌ 连不上 |
| `api.github.com` | ✅ 200，1.4s |
| `codeload.github.com` | ✅ 301，0.96s |
| `ssh.github.com:443` | ✅ 通 |

结论：不是「GitHub 被整体墙了」，而是**单个域名的 IP 被污染**。

**第三步：查解析结果**

```powershell
Resolve-DnsName github.com -Type A
# → 20.6.7.17   （测试 443 不通，21 秒超时）
```

**第四步：试其他官方 IP**

```powershell
curl.exe -sS -o NUL -w "%{http_code} tls=%{time_appconnect}s" --max-time 8 `
  --resolve github.com:443:20.27.177.113 https://github.com
# → 200 tls=0.114596s   ✅
```

## 解决

把 `github.com` 指到一个可用的官方 IP。但这台机器上 **`C:\Windows\System32\drivers\etc` 里根本没有 `hosts` 文件**，所以先新建：

```
20.27.177.113   github.com
```

然后：

```powershell
ipconfig /flushdns
```

验证：

```powershell
curl.exe -sS -o NUL -w "code=%{http_code} tls=%{time_appconnect}s total=%{time_total}s" https://github.com
# → code=200 tls=0.154876s total=0.485530s
```

再 `git push`，一次成功。

## 以后怎么避免

1. **`api.github.com` 通 ≠ `github.com` 通**，要分开测
2. 遇到「能 ping 通但连不上 443」，先怀疑 DNS 被污染，别怀疑代理/防火墙
3. 备用 IP（本机实测可用）：

| IP | 结果 |
|---|---|
| `20.27.177.113` | ✅ 当前在用 |
| `20.200.245.247` | ✅ 备用 |
| `4.237.22.38` | ✅ 备用 |

IP 失效了就换一个，改 hosts 那行 + `ipconfig /flushdns`。

## 一句话总结

> 「浏览器能打开」和「命令行能连通」经常是两回事——浏览器可能走了不同的 DNS 或代理。
> 排查时用 `curl -w` 把 **dns / tcp / tls** 三段耗时打出来，一眼就知道卡在哪一层。
