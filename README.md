# netapi-bilibili

给 AllMusic 4.0 使用的 B 站音乐源 API jar。它把 B 站视频里的音频轨解析成直链，交给 AllMusic 播放，适合 PV、音乐视频、翻唱等视频点歌场景。

## 功能

- 用 B 站 WBI 签名搜索视频
- 支持 `BV`、`av`、B 站视频链接
- 自动获取匿名 `buvid` 设备指纹
- 解析 DASH 音频流，自动选择最高带宽的音频
- 视频信息与音频直链带短时缓存
- 不需要登录即可播放大部分公开视频

暂不支持歌词、B 站歌单、登录后的高音质音频。

## 播放流服务

AllMusic 客户端不能直接播放 B 站返回的 fMP4 分片音频，所以插件会在服务器上把音频下载并重封装成标准 M4A，再通过本地 HTTP 服务交给客户端播放。

首次播放前会在 AllMusic 数据目录生成：

```text
netapi.json
netapi-cache/
```

默认配置：

```json
{
  "enabled": true,
  "bind-host": "0.0.0.0",
  "port": 28990,
  "public-host": "localhost",
  "cache-dir": "netapi-cache",
  "cache-max-age-hours": 24,
  "max-cache-files": 20
}
```

- 单机或 OneJar 联机：默认 `localhost` 即可。
- 独立服务器：把 `public-host` 改成客户端能访问的服务器 IP 或域名，并在防火墙放行 `port`。
- 首次播放需要下载和重封装，会有几秒等待；之后播放同一视频会直接使用缓存。

## 构建

需要 JDK 21。

```powershell
gradle build
```

构建产物：

```text
build/libs/netapi-1.0-SNAPSHOT.jar
```

## 安装

1. 把构建出的 jar 放进 AllMusic 4.0 的 API 目录。AllMusic 官方说明是 `allmusic/api`；如果服务端实际生成的是 `allmusic_server/api`，就放到该目录。
2. 不要把 jar 放进 `plugins`，它不是 Paper 插件，而是 AllMusic 加载的音乐源。
3. 编辑 AllMusic 的 `config.json`，把默认音乐源改成：

```json
{
  "defaultApi": "bilibili"
}
```

4. 执行 `/music reload`，控制台出现 `注册音乐API：bilibili` 即加载成功。

## 使用

```text
/music search 歌名
/music searchapi bilibili 歌名
/music bilibili BV1xx411c79H
/music https://www.bilibili.com/video/BV1xx411c79H
```

也可以直接使用带 `BV` 或 `av` 的链接点歌。

## 实现说明

- 搜索接口：`api.bilibili.com/x/web-interface/wbi/search/type`
- 视频信息接口：`api.bilibili.com/x/web-interface/view`
- 音频直链接口：`api.bilibili.com/x/player/playurl`
- API ID：`bilibili`
- 主类：`io.github.Baiyang521.netapi.BiliApiMain`
