# 应用路径前缀 /erp（URL 结构说明）

> 2026-11-20 落地（commit `e7483f5`）。应用不再挂在域名根路径，统一以 `/erp` 为前缀；模块切换时地址栏路径随之变化（history 模式路由）。

## 1. 背景与动机

- 与后续同域部署的其他系统（报表、门户等）预留共存空间，避免根路径被单一应用独占。
- 让 URL 自带应用标识，便于网关按前缀分流、日志按前缀聚合。

## 2. URL 契约

| 地址 | 行为 |
|---|---|
| `/` | 302 跳转到 `/erp/`（dev 由 Vite 完成，prod 由 nginx 完成） |
| `/erp/` | 应用入口（未登录被路由守卫重定向到 `/erp/login`） |
| `/erp/login` | 登录页 |
| `/erp/system/user`、`/erp/basedata/product` … | 各模块页，可深链直达、可刷新 |
| `/erp/任意未知路径` | 路由表 `/:pathMatch(.*)*` 回退到 `/erp/` |
| `/api/**` | **不变**。后端接口不带 `/erp` 前缀，dev 走 Vite proxy、prod 走 nginx 反代到 Spring Boot |

登录守卫的 `redirect` 查询参数保存的是应用内 fullPath（如 `/system/user`），由 router base 自动拼出 `/erp/system/user`，无需调用方感知前缀。

## 3. 实现（代码地图）

| 位置 | 职责 |
|---|---|
| `frontend/vite.config.ts` | `base: '/erp/'`——dev 服务与 build 产物资源路径的唯一来源 |
| `frontend/src/router/index.ts` | `createWebHistory(import.meta.env.BASE_URL)`——router base 跟随 vite base，**改前缀只改 vite.config 一处** |
| `frontend/nginx.conf` | `location /erp/ { alias /usr/share/nginx/html/; try_files $uri $uri/ /erp/index.html; }`；`location = / { return 302 /erp/; }`；其余路径回退 `/erp/index.html` |
| `frontend/src/api/http.ts` | `baseURL: '/api'` 保持根路径，与前端前缀解耦 |

nginx 用 `alias` 而非 `root`：`root` 会把 `/erp/assets/x.js` 映射到 `html/erp/assets/x.js`（不存在），`alias` 才把 `/erp/` 剥掉映射到静态根目录。这是本方案最容易写错的一处。

## 4. 验证记录（2026-11-20）

- dev：`/` → 302 `/erp/`；`/erp/`、`/erp/login`、`/erp/system/user` 均 200（深链与刷新正常）。
- prod 构建：`dist/index.html` 资源引用为 `/erp/assets/…`，`npm run build` 通过。

## 5. 已知边界

- Docker 生产镜像必须用本版 `nginx.conf` 重新构建；沿用旧镜像会出现资源 404 白屏。
- 若未来网关把应用挂到别的前缀（如 `/erp2/`），改 `vite.config.ts` 的 `base` 即可，router 与 nginx 需同步（nginx 的 location/alias 是字面量，不读环境变量）。
- 浏览器书签/外链里的旧根路径（`/system/user`）会落到路由回退进首页，不会自动映射到对应模块——如需兼容需另行加重定向表。
