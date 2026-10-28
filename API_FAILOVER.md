# HougTime 接口容灾与备用源方案

> 生成时间：2026-10-11 · 触发需求：「红果接口多扒几个出来，开源接口已挂，保证软件后续能正常用」

---

## 一、结论速览

| 层级 | 方案 | 状态 | 落地位置 |
|---|---|---|---|
| **L1 域名级容灾** | App 签名接口 6 个等价域名自动降级 | ✅ **已实现并编译** | `sign/HostPool.java` + `NetClient.appPost()` |
| **L2 免签名兜底** | 网页版 `hongguoduanju.com` 详情/搜索（App 接口空体时回退） | ✅ 已有（沿用） | `NetClient.WEB_SITE` |
| **L3 第二数据源** | 参考源 10 个短剧站源，5 个实测存活（饭果/星果可直接用） | 📋 方案就绪，待接入 | 本文档第四节 |
| **L4 第三方聚合** | 网搜开源/聚合接口 | ❌ **全部已挂**（见第五节） | — |

**核心发现**：字节系「红果 App 签名接口」天然存在**多个等价域名**，且 **X-Gorgon 签名只绑定 path+query+body，不绑定 host** —— 主域名挂了换域名即可，签名完全通用。这条容灾今天就已落地，无需接第三方。

---

## 二、L1 域名级容灾（已实现）

### 2.1 实测矩阵（JVM + okhttp，双接口验证）

| 域名 | DNS | 弹幕接口 | 分类接口 |
|---|---|---|---|
| `api5-normal-sinfonlineb.fqnovel.com` | ✅ 119.96.48.103 | ✅ 200 code=0 | ✅ 200 code=0 (64KB) |
| `api5-normal-sinfonlinea.fqnovel.com` | ✅ 119.96.48.104 | ✅ 200 code=0 | ✅ 200 code=0 (64KB) |
| `api5-normal-sinfonlinec.fqnovel.com` | ✅ 119.96.48.103 | ✅ 200 code=0 | ✅ 200 code=0 (64KB) |
| `api5-normal-lf.fqnovel.com` | ✅ 119.96.48.104 | ✅ 200 code=0 | ✅ 200 code=0 (64KB) |
| `api5-normal-lq.fqnovel.com` | ✅ 119.96.48.102 | ✅ 200 code=0 | ✅ 200 code=0 (64KB) |
| `api5-normal-hl.fqnovel.com` | ✅ 119.96.48.103 | ✅ 200 code=0 | ✅ 200 code=0 (64KB) |
| `api5-normal-sinfonline1.fqnovel.com` | ❌ NXDOMAIN | — | — |
| `api5-normal-sinfonline2.fqnovel.com` | ❌ NXDOMAIN | — | — |
| `api5-normal-sinfonlinea1.fqnovel.com` | ❌ NXDOMAIN | — | — |
| `api5-normal-sinfonlineb1.fqnovel.com` | ❌ NXDOMAIN | — | — |
| `api5-normal-sinfonline.fqnovel.com` | ❌ NXDOMAIN | — | — |

**规律**：`sinfonlinea / sinfonlineb / sinfonlinec` 三姊妹 + `lf / lq / hl` 三兄弟 = 6 个可用；带数字后缀（`1/2/a1/b1`）和裸 `sinfonline` 均不存在。

### 2.2 实现要点

- 新增 `app/src/main/java/com/houg/tv/sign/HostPool.java`：6 域名池，AtomicInteger 记住当前首选，`reportSuccess` 提升 / `reportFailure` 轮转。
- `SignedRequest` 新增 4 参构造 `(path, body, comment, baseUrl)`，保留原 2/3 参构造（默认走域名池首选），**签名算法零改动**。
- `NetClient.appPost()`：改为循环尝试，最多 3 个域名；`HTTP≠200`、空体、异常 均触发换域名；成功即固化该域名。日志新增 `host=` 字段便于追踪。
- 上限 3 次是刻意的：OkHttp 超时 15s/20s，6 个全试最坏 90s+ 会拖死播放链；3 次（主域名挂时最坏 ~30s）是体验与容灾的平衡点。

### 2.3 效果

- 主域名 `sinfonlineb` 若某天 NXDOMAIN 或返回非 200，App **自动切到 a/c/lf/lq/hl**，用户无感。
- 只要 6 个里有 1 个活着，分类/取流/弹幕全部照常。

---

## 三、L2 免签名网页版兜底（已沿用）

`https://hongguoduanju.com`（`www.` 返回 301 到裸域，App 用裸域正确）。现状：详情接口 App 版返回空 body 时自动回退网页版 `_ROUTER_DATA`（此前 65 集详情就是这样拿到的），**这条链路本身也是一层容灾**。搜索全程走网页版免签名。

---

## 四、L3 第二数据源候选（参考源 10 站源实测）

来自真果鉴 Go 源码 `provider_duanju.go` 的站源注册表，逐个实测存活与接口形态：

| 站名 | 域名 | HTTP | 接口形态 | 是否需要签名 | 可用性 |
|---|---|---|---|---|---|
| **饭果** | `xifan-api-cn.youlishipin.com` | 200 | `GET /xifan/search/getSearchList`、`/xifan/drama/getDuanjuInfo` | 否 | ✅ **实测返回真实剧集数据**（「超级女婿」） |
| **星果** | `read.api.duodutek.com` | 200 | `GET /novel-api/app/pageModel/getResourceById`、`.../book/getChapterList`（token 写死） | 否（token 内置） | ✅ 实测 `code:0` 有效结构（当日样本空） |
| 河果 | `www.kuaikaw.cn` | 200 | `POST /seo/video/6007` | 否 | 🟡 活，需 POST |
| 花果 | `www.zywest263.com` | 200→301 | macCMS 变体 | 否 | 🟡 活，接口路径需再逆 |
| 伍果 | `www.duanju55.com` | 200 | macCMS | 否 | 🟡 活，`/api.php` 返回 `closed`，需再逆 |
| 观果 | `api.drama.9ddm.com` | 200 | `GET /drama/home/search` | 否 | 🟡 活但直连 403，需 `clientInfo` 头签名 |
| 芽果 | `app.whjzjx.cn` | 503 | `POST` + `u.shytkjgs.com` 登录 | 需登录 | ❌ 当前 503 |
| 猫果 | `api-store.qmplaylet.com` | 404 | `api-store`/`api-read` + 头签名 | 是 | ❌ 根路径 404 |
| 牛果 | `ccc.chaojichaojichanga.com:35620` | 404 | AES-ECB 解密响应 | 否 | ❌ 404 |
| 皮果 | `ptt.red` | 403 | macCMS + 浏览器 | 否 | ❌ 403 |

### 推荐优先级

1. **饭果（首选第二源）**：唯一实测**直接吐真实剧集数据**的源，接口是纯 GET + 免签名，HTML 里带 `duanjuVo`（title / coverImageUrl / duanjuId / episodeList / playUrl），字段结构与 HougTime 现有 `Drama/Episode` 模型几乎一对一，接入成本最低。
2. **星果（次选）**：接口活、`code:0`、token 内置免签名，但当日样本 datalist 为空，需换 resourceId 复测数据量。
3. **观果/河果/花果/伍果**：域名活，但要么需再逆向接口路径/头签名，要么需 POST 构造，作为第三梯队待命。

---

## 五、L4 第三方开源/聚合接口 —— 已挂（避坑清单）

用户原话「有些开源的接口已经挂了」，实测确认：

| 接口 | 结果 |
|---|---|
| `api.cenguigui.cn/api/duanju.html` | ❌ `{"code":404,"msg":"接口已经关闭"}` |
| `api-v2.cenguigui.cn/api/duanju/api.php` | ❌ 返回空 |
| `www.52api.cn/api/hg_duanju` | ❌ `{"code":500,"msg":"系统错误"}` |
| `github.com/xxlllq/hongguo` | ⚠️ 活（2026-06 更新），但**必须 Frida + 模拟器**做签名预言机，HougTime 自己已实现签名，用不上 |
| `github.com/yinjg1997/hongguo` | ⚠️ 同上型，签名依赖模拟器 |
| FQWeb 番茄共享 API（`reading.api-fanqienovel.sunianyun.live` 等） | ❌ 2024 年讨论区已大量报失效，且是**番茄小说**非红果短剧 |

**教训**：第三方「免费聚合接口」生命周期极短（典型几个月），不适合作为生产依赖。真正稳的是**字节自有域名池**（L1）和**参考源已验证的短剧站源**（L3）。

---

## 六、备份与升级路径

```
今天：L1 域名池（6 域名自动降级）+ L2 网页版兜底      ← 已上线
下一步：接 L3 饭果为「第二数据源」，分类页可切换/自动兜底
再下一步：星果 fallback；观果/河果/花果/伍果 视需要逆向
```

**若 6 个域名全部失效**（概率极低）→ 说明字节整体改域，届时从 `HostPool.java` 替换域名列表即可（单文件、单数组），再叠加 L3 第二数据源提供跨站冗余。
