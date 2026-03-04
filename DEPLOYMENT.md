# 部署教程

这是一份面向自托管用户的部署教程，目标是让你在自己的服务器上从零部署一个全新的站点。

本文默认：
- 你使用 Linux 服务器（推荐 Ubuntu 22.04+）
- 你有自己的域名
- 你不会复用任何他人的 `.env`、数据库、Nginx、证书或支付配置

## 部署方式选择

项目支持两种常见部署方式：

1. 源码构建部署
- 直接在服务器拉取仓库源码
- 使用仓库内的 [docker-compose.yml](docker-compose.yml) 构建并启动
- 包含内置 PostgreSQL 容器
- 适合第一次部署、二次开发、自建环境

2. 镜像部署
- 使用预构建镜像和 [docker-compose.prod.yml](docker-compose.prod.yml)
- 需要你自己准备数据库
- 适合已有镜像仓库、希望快速上线的场景

如果你只是第一次在自己的机器上部署，优先推荐“源码构建部署”。

## 一、部署前准备

请先准备：

- 一台服务器（2C2G 起步，建议 4G 内存）
- 一个域名，并把 `A` 记录指向你的服务器公网 IP
- 已安装 Docker 和 Docker Compose 插件
- 可以使用 `sudo`

安装 Docker（Ubuntu 示例）：

```bash
sudo apt update
sudo apt install -y docker.io docker-compose-plugin
sudo systemctl enable --now docker
```

确认安装成功：

```bash
docker --version
docker compose version
```

## 二、拉取项目

```bash
git clone git@github.com:Nook-TT/orion-key2.git
cd orion-key2
```

如果你不用 SSH，也可以用 HTTPS：

```bash
git clone https://github.com/Nook-TT/orion-key2.git
cd orion-key2
```

## 三、配置环境变量

复制模板：

```bash
cp .env.example .env
```

然后编辑 `.env`：

```bash
nano .env
```

至少要改这些值：

```env
# 内置数据库（源码构建时使用）
POSTGRES_DB=orionkey
POSTGRES_USER=orionkey
POSTGRES_PASSWORD=请改成你自己的强密码

# 后端数据库连接
DB_URL=jdbc:postgresql://db:5432/orionkey
DB_USERNAME=orionkey
DB_PASSWORD=请与上面保持一致

# 安全（必须修改）
JWT_SECRET=用 openssl rand -base64 48 生成
PASSWORD_PLAIN=false

# 站点公开域名（必须修改）
NEXT_PUBLIC_BASE_URL=https://your-domain.com

# 生产环境禁止 mock 回退
NEXT_PUBLIC_ENABLE_MOCK_FALLBACK=false

# 邮件（可选）
MAIL_ENABLED=false
MAIL_SITE_URL=https://your-domain.com
MAIL_HOST=smtp.example.com
MAIL_PORT=465
MAIL_USERNAME=your@email.com
MAIL_PASSWORD=your_password
```

如果你要启用“发货后自动发邮件”，把邮件配置补完整即可。推荐 Gmail 示例：

```env
MAIL_ENABLED=true
MAIL_SITE_URL=https://your-domain.com
MAIL_HOST=smtp.gmail.com
MAIL_PORT=465
MAIL_USERNAME=your@gmail.com
MAIL_PASSWORD=你的16位应用专用密码
```

生成 JWT 密钥：

```bash
openssl rand -base64 48
```

注意：
- 不要提交 `.env`
- 不要复用示例密码
- 不要把任何支付商户密钥写进公开仓库

### 邮件发件（Gmail 示例）

这个项目已经内置邮件发件能力。配置好 SMTP 后，订单在自动发货完成时会把卡密内容发到用户下单时填写的邮箱。

如果你使用 Gmail：

1. 先开启 Google 账号两步验证（2-Step Verification）
2. 打开 Google 应用专用密码页面：

```text
https://myaccount.google.com/apppasswords
```

3. 创建一个新的应用专用密码（App Password）
4. Google 会给你一组 16 位密码
5. 把这组 16 位密码填到 `.env` 的 `MAIL_PASSWORD`

注意：
- `MAIL_PASSWORD` 不能填你的 Gmail 登录密码
- 这类传统 SMTP 登录应使用 16 位应用专用密码
- 建议保持 `MAIL_PORT=465`，因为项目默认走 SSL SMTP
- 如果你后续重置或撤销了应用专用密码，需要同步更新 `.env`

## 四、方式 A：源码构建部署（推荐）

### 1. 启动容器

```bash
docker compose up -d --build
```

查看状态：

```bash
docker compose ps
```

正常情况下会有三个服务：
- `db`
- `api`
- `web`

### 2. 初始化数据库扩展和基础数据

首次部署建议执行一次：

```bash
docker compose exec db psql -U <DB_USER> -d <DB_NAME> -c "CREATE EXTENSION IF NOT EXISTS pgcrypto;"
docker compose exec -T db psql -U <DB_USER> -d <DB_NAME> < apps/api/src/main/resources/data.sql
```

把 `<DB_USER>` 和 `<DB_NAME>` 替换成你 `.env` 里的实际值，例如：

```bash
docker compose exec db psql -U orionkey -d orionkey -c "CREATE EXTENSION IF NOT EXISTS pgcrypto;"
docker compose exec -T db psql -U orionkey -d orionkey < apps/api/src/main/resources/data.sql
```

说明：
- `data.sql` 会写入站点基础配置和演示数据
- 不会再创建默认管理员账号

## 五、方式 B：镜像部署（使用预构建镜像）

如果你使用镜像方式：

1. 先准备你自己的 PostgreSQL 数据库
2. 把 `.env` 里的数据库连接改成外部数据库地址
3. 如有需要，填入 `API_IMAGE` 和 `WEB_IMAGE`
4. 启动：

```bash
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
```

初始化数据库（外部数据库示例）：

```bash
psql "postgresql://<DB_USER>:<DB_PASSWORD>@<DB_HOST>:5432/<DB_NAME>" -c "CREATE EXTENSION IF NOT EXISTS pgcrypto;"
psql "postgresql://<DB_USER>:<DB_PASSWORD>@<DB_HOST>:5432/<DB_NAME>" -f apps/api/src/main/resources/data.sql
```

## 六、手动创建第一个管理员

这个项目不会自动创建管理员，所以你必须手动插入一个后台账号。

推荐直接用 PostgreSQL 生成 BCrypt 密码：

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO users (
  id,
  created_at,
  updated_at,
  email,
  is_deleted,
  password_hash,
  points,
  role,
  username
) VALUES (
  gen_random_uuid(),
  NOW(),
  NOW(),
  'admin@example.com',
  0,
  crypt('ChangeMe123!', gen_salt('bf', 10)),
  0,
  'ADMIN',
  'admin'
);
```

你可以这样执行（源码构建内置数据库示例）：

```bash
docker compose exec db psql -U orionkey -d orionkey
```

执行后：
- 用户名：`admin`
- 密码：`ChangeMe123!`

首次登录后请立刻在后台修改密码。

## 七、配置 Nginx 反向代理

安装 Nginx：

```bash
sudo apt install -y nginx
```

新建站点配置：

```bash
sudo nano /etc/nginx/sites-available/your-domain.com
```

示例配置：

```nginx
server {
    listen 80;
    server_name your-domain.com;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

启用配置：

```bash
sudo ln -s /etc/nginx/sites-available/your-domain.com /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx
```

说明：
- 这里把所有流量都转发给 `127.0.0.1:3000`
- 前端会处理页面访问，并通过内部配置访问后端 `api`
- `api` 和 `db` 不需要直接暴露到公网

## 八、配置 SSL（Let’s Encrypt）

安装 Certbot：

```bash
sudo apt install -y certbot python3-certbot-nginx
```

申请证书：

```bash
sudo certbot --nginx -d your-domain.com
```

测试自动续期：

```bash
sudo certbot renew --dry-run
```

如果你使用 Cloudflare：
- DNS 先确保解析正确
- Cloudflare SSL 模式建议使用 `Full (strict)`

## 九、登录后台并完成业务配置

部署完成后，先访问：

```text
https://your-domain.com/admin
```

然后完成这些初始化工作：

1. 用你手动创建的管理员登录
2. 立即修改管理员密码
3. 修改站点名称、公告、页脚等基础配置
4. 配置支付渠道（没有支付配置前不要接真实订单）
5. 删除演示商品和测试卡密，换成你自己的商品和卡密库存

## 十、部署后验证

建议至少检查这些：

```bash
docker compose ps
curl -I http://127.0.0.1:3000
curl http://127.0.0.1:8083/api/categories
```

浏览器侧检查：

1. 首页是否能打开
2. 商品列表是否正常显示
3. 后台是否能登录
4. 下单流程是否可走通
5. 支付完成后是否能正常自动发货

## 十一、常见问题

### 1. 打开页面正常，但后台接口报错

优先检查：
- `.env` 里的 `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`
- PostgreSQL 是否可连接
- 是否执行过 `CREATE EXTENSION IF NOT EXISTS pgcrypto;`

### 2. 访问域名是 502 / 504

优先检查：
- `docker compose ps`
- `web` 容器是否在运行
- Nginx 是否把流量转发到 `127.0.0.1:3000`

### 3. 登录成功后仍然跳回登录页

优先检查：
- `JWT_SECRET` 是否为空
- 浏览器本地存储是否被禁用
- 反向代理是否正确保留了 `Host` / `X-Forwarded-*` 头

### 4. 已支付但没有自动发货

优先检查：
- 支付回调地址是否正确
- 支付渠道签名配置是否正确
- 商品卡密库存是否充足
- 后端日志里是否有发货失败或缺货提示

## 十二、后续升级

升级前请先阅读：

- [UPGRADE.md](UPGRADE.md)

建议升级流程：

1. 备份数据库和 `uploads/`
2. 更新代码或镜像
3. 比对新的 `.env.example` 是否新增配置项
4. 重建并重启容器
5. 回归测试下单、支付、发货、后台登录

---

如果你准备长期维护自己的版本，建议：
- 保留一个私有分支存放你自己的部署配置
- 不要把 `.env`、Nginx、证书、支付密钥提交到公开仓库
- 每次升级先在测试环境验证，再上线生产环境
