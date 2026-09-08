# LingShu 项目工作约束

## 开发环境

- 项目专用 Conda 环境名称为 `lingshu-dev`。
- 环境路径为 `D:\anaconda\envs\lingshu-dev`。
- 所有 Java、Maven 和 Python 命令必须在该环境中执行。
- 非交互命令统一使用：
  `D:\anaconda\Scripts\conda.exe run --no-capture-output -n lingshu-dev <command>`
- 不得使用系统全局 `java`、`mvn`、`python` 或其他 Conda 环境。
- 新增环境依赖时同步更新 `environment.yml`。
- Maven 命令统一通过 `scripts/mvn.ps1` 执行，使依赖缓存固定在 D 盘环境目录。

## 容器与数据

- Redis、PostgreSQL/pgvector、Kafka、Nacos、Prometheus 和 Grafana 使用 Docker Compose 管理。
- 项目数据根目录固定为 `E:\LingShuData`。
- PostgreSQL、Redis、模型、数据集和日志分别使用根目录下的 `postgres`、`redis`、`models`、`datasets`、`logs` 子目录。
- `D:\Docker` 仅代表 Docker 安装位置，不作为项目或数据存储目录。
- 不在项目所在的 C 盘下载或导入模型、数据集、压测数据、数据库快照等大批量数据。
- Docker 持久化卷必须绑定到 `E:\LingShuData` 对应子目录。

## 开发原则

- 先完成可测试的纵向调用链，再逐步引入缓存、预算、消息队列和配置中心。
- 外部模型调用必须可替换为本地桩实现，单元测试和默认构建不得依赖真实 API Key。
- API Key 只能通过环境变量或未提交的本地配置注入，禁止写入源码。
