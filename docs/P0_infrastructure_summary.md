# Vantix — Phase 0: Nền tảng & Hạ tầng (Hoàn thành)

> **Trạng thái:** ✅ Hoàn thành
> **Liên quan:** `00_roadmap_phases_v1.md` (mục P0)
> **Mục đích tài liệu:** Ghi lại chính xác những gì đã dựng ở Phase 0 — bao gồm cả các quyết định phát sinh trong lúc code khác với giả định ban đầu trong roadmap — để dùng làm nền tảng cho P1 trở đi mà không cần đọc lại toàn bộ lịch sử thảo luận.

---

## 1. Thông tin dự án

| Mục | Giá trị |
|---|---|
| `groupId` | `com.dev` |
| `artifactId` gốc | `vantix` |
| `version` | `0.0.1-SNAPSHOT` |
| Kiến trúc mã nguồn | Monorepo, mỗi service là một Maven module (trừ 2 service Python) |
| Java version | 21 |

---

## 2. Cấu trúc thư mục thực tế

```
vantix/
├── pom.xml                              ← aggregator + dependencyManagement tập trung
├── common-lib/
│   ├── commonlib-api-response/          ← ApiResponse, ErrorCode, CommonErrorCode
│   ├── commonlib-security/              ← GatewayHeaderAuthFilter
│   └── commonlib-kafka/                 ← cấu hình Producer/Consumer dùng chung
├── services/
│   ├── api-gateway/                     ← Spring Cloud Gateway (WebFlux)
│   ├── user-service/
│   ├── product-service/
│   ├── order-service/
│   ├── payment-service/
│   ├── notification-service/
│   ├── bot-detection-service/           ← Python/FastAPI, ngoài Maven reactor
│   └── assistant-service/               ← Python/FastAPI, ngoài Maven reactor
├── docker-compose.yml                   ← hạ tầng dev
├── docker-compose.prod.yml              ← khung để trống, dành cho P9
├── .env                                 ← biến môi trường (không commit)
├── .gitignore
└── .github/workflows/ci.yml
```

---

## 3. Quyết định công nghệ — có điều chỉnh so với roadmap ban đầu

Trong lúc dựng P0, một phát hiện quan trọng đã làm thay đổi version so với dự tính lúc lập roadmap: **dòng Spring Boot 3.3.x đã hết vòng đời hỗ trợ bảo mật (OSS support kết thúc từ giữa 2025)**, kể cả bản 3.5.x cũng đã hết hỗ trợ OSS. Vì dự án hoàn toàn mới, không có gánh nặng code cũ, đã quyết định nhảy thẳng lên dòng còn được vá lỗi.

| Thành phần | Version | Ghi chú |
|---|---|---|
| Spring Boot | **4.1.x** | Thay cho dự tính ban đầu 3.3.4 (đã EOL, có CVE nghiêm trọng chưa vá) |
| Spring Cloud | **2025.1.2** | Bắt buộc đi cùng Boot 4.1.x — version Spring Cloud cũ hơn sẽ crash lúc khởi động do cơ chế tự kiểm tra tương thích của Spring Cloud |
| Lombok | **1.18.42** | Bản cũ hơn (1.18.30) gây lỗi `ExceptionInInitializerError: TypeTag :: UNKNOWN` do không tương thích với JDK thật đang dùng để compile |
| Gateway starter | `spring-cloud-starter-gateway-server-webflux` | **Không phải** `spring-cloud-starter-gateway` — artifact cũ đã bị xóa hoàn toàn khỏi BOM kể từ Spring Cloud 2025.1.0 |

---

## 4. Cấu trúc `pom.xml` gốc (root)

- **Không kế thừa** `spring-boot-starter-parent` — chỉ là aggregator (`packaging=pom`) import BOM `spring-boot-dependencies` vào `dependencyManagement`.
- Khai `spring-boot-starter-test` trực tiếp trong `<dependencies>` (ngoài `dependencyManagement`) để mọi module con tự động kế thừa, không cần khai lại.
- `spring-boot-maven-plugin` **chỉ nằm trong `<pluginManagement>`** (kèm `executions: repackage`), **không** khai trực tiếp trong `<build><plugins>` của root — tránh việc plugin này bị áp nhầm lên 3 module `commonlib-*` (là thư viện, không có main class).
- `maven-compiler-plugin` cấu hình `annotationProcessorPaths` cho Lombok áp dụng chung cho mọi module.
- Nguyên tắc quản lý version dependency ngoài BOM Spring: **chỉ đưa lên `dependencyManagement` của root khi có từ 2 module trở lên cùng cần**; nếu chỉ một module cần (ví dụ SDK Stripe ở P4), khai version ngay tại module đó.

---

## 5. Common-lib — chỉ dựng sườn, đúng phạm vi P0

| Artifact | Nội dung hiện tại | Dependency chính |
|---|---|---|
| `commonlib-api-response` | `ApiResponse<T>`, `ErrorCode` (interface), `CommonErrorCode` (enum) | `spring-web` (chỉ lấy `HttpStatus`, không dùng `spring-boot-starter-web` để tránh kéo Tomcat/Jackson vào mọi service) |
| `commonlib-security` | `GatewayHeaderAuthFilter` (đọc `X-User-Id`/`X-User-Role`, set vào `SecurityContext`) | `spring-boot-starter-security` |
| `commonlib-kafka` | Cấu hình Producer/Consumer dùng chung | `spring-kafka` |

Đúng theo phạm vi P0: **chưa viết logic nghiệp vụ nào**, chỉ đủ dependency và cấu trúc để P1 trở đi cắm vào dùng ngay. Không có `main` class trong 3 module này vì chúng là thư viện (`packaging=jar` mặc định), không phải app chạy được.

**Nguyên tắc rule-of-3 đã áp dụng khi quyết định tách 3 artifact này:** cả `ApiResponse`, `GatewayHeaderAuthFilter`, và cấu hình Kafka đều được từ 3 service trở lên cần dùng (5 service nghiệp vụ Java), nên đủ điều kiện tách thành common-lib riêng ngay từ đầu — tránh sao chép code giữa các service.

---

## 6. Danh sách service, port, database

| Service | Port | Context path                  | Database | Ngôn ngữ |
|---|---|-------------------------------|---|---|
| api-gateway | 8999 | — (routing layer)             | — (stateless) | Java/Spring Cloud Gateway |
| notification-service | 9000 | `/api`                        | `notification_service` | Java/Spring Boot |
| order-service | 9001 | `/api`                        | `order_service` | Java/Spring Boot |
| payment-service | 9002 | `/api`                        | `payment_service` | Java/Spring Boot |
| product-service | 9003 | `/api`                        | `product_service` | Java/Spring Boot |
| user-service | 9004 | `/api`                        | `user_service` | Java/Spring Boot |
| bot-detection-service | 9005 | `/api/bot-detection`          | — (chưa cần ở P0) | Python/FastAPI |
| assistant-service | 9006 | `/api/assistant`              | — (stateless) | Python/FastAPI |
---

## 7. Cấu hình từng service Java (mẫu áp dụng cho cả 5 service nghiệp vụ)

```yaml
server:
  port: <port riêng>
  servlet:
    context-path: /api

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/<tên_database>
    username: ${POSTGRES_USER}
    password: ${POSTGRES_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate   # bắt buộc khi dùng Flyway — không để Hibernate tự sửa schema
    show-sql: true
  flyway:
    enabled: true
    locations: classpath:db/migration
```
---

## 8. `api-gateway` — routing

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: user-service
              uri: http://localhost:9004
              predicates:
                - Path=/api/users/**,/api/auth/**
            - id: product-service
              uri: http://localhost:9003
              predicates:
                - Path=/api/products/**,/api/events/**
            - id: order-service
              uri: http://localhost:9001
              predicates:
                - Path=/api/orders/**
            - id: payment-service
              uri: http://localhost:9002
              predicates:
                - Path=/api/payments/**
            - id: notification-service
              uri: http://localhost:9000
              predicates:
                - Path=/api/notifications/**
            - id: bot-detection-service
              uri: http://localhost:9005
              predicates:
                - Path=/api/bot-detection/**
            - id: assistant-service
              uri: http://localhost:9006
              predicates:
                - Path=/api/assistant/**
```

> Đường dẫn `Path` cụ thể cho từng service cần rà lại khi viết API thật ở P1–P7, danh sách trên là khung tham khảo dựng lúc P0.

**Xác thực JWT tại Gateway:** mô hình Trust-The-Gateway giữ nguyên như thiết kế đã thống nhất — Gateway verify JWT, inject `X-User-Id`/`X-User-Role`, các service downstream dùng `GatewayHeaderAuthFilter` (từ `commonlib-security`) để đọc header và set `SecurityContext`, không tự verify JWT lại. Việc verify JWT thật sự tại Gateway sẽ code ở P1 cùng lúc với `user-service`.

---

## 9. Bot-detection-service & Assistant-service (Python)

Khung tối thiểu, đúng phạm vi P0 (chỉ cần chạy lên và trả `/health`):

```
services/<tên-service>/
├── app/
│   ├── __init__.py
│   └── main.py
├── requirements.txt
└── Dockerfile
```

```python
#from fastapi import FastAPI, APIRouter

app = FastAPI(
    title="{tên service}",
    docs_url="/api/{domain}/docs",
    redoc_url="/api/{domain}/redoc",
    openapi_url="/api/{domain}/openapi.json"
)

api_router = APIRouter(prefix="/api/{domain}")


@api_router.get("/health")
def health_check():
    return {"status": "UP"}

app.include_router(api_router)
```

```
# requirements.txt
fastapi==0.115.0
uvicorn[standard]==0.32.0
```

Chưa có logic ML (Isolation Forest) hay tích hợp LLM — đó là phạm vi của P6 và P7.

---

## 10. Docker Compose — hạ tầng dev

| Service | Image | Port | Ghi chú |
|---|---|---|---|
| postgres | `postgres:14-alpine3.23` | 5432 | Healthcheck bằng `pg_isready` |
| pgadmin | `dpage/pgadmin4:9.18.0` | 8080 | |
| redis | `redis:alpine3.23` | 6379 | Healthcheck bằng `redis-cli ping` |
| redis-insight | `redis/redisinsight:3.2` | 5540 | |
| kafka | `apache/kafka:4.3.1` | 9092 (host), 9093 (docker network) | KRaft combined mode, dual listener HOST/DOCKER |
| kafka-ui | `provectuslabs/kafka-ui:latest` | 8085 | Kết nối qua `kafka:9093` (listener nội bộ docker network, không dùng `localhost:9092`) |

**Kafka — điểm cần nhớ:**
- `KAFKA_CLUSTER_ID` được **sinh và cố định thủ công** (không để tự sinh), qua lệnh:
  ```bash
  docker run --rm apache/kafka:4.3.1 /opt/kafka/bin/kafka-storage.sh random-uuid
  ```
  Giá trị lưu trong `.env`, dạng UUID mã hóa Base64 URL-safe không padding (22 ký tự, gồm chữ/số và `-`/`_`).
- Có `healthcheck` riêng cho Kafka (dùng `kafka-broker-api-versions.sh`) — cần thiết để các service Java dùng `depends_on: condition: service_healthy` không start sớm hơn Kafka thật sự sẵn sàng.
- Nếu `docker-compose down -v` (xóa volume), cần sinh lại cluster ID nếu muốn giữ nhất quán, vì volume cũ chứa dữ liệu đã format theo cluster ID cũ.

---

## 11. CI (GitHub Actions)

```yaml
name: Java CI

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main, develop]
  workflow_dispatch:

permissions:
  contents: read

jobs:
  build-and-test:
    name: Build and Test
    runs-on: ubuntu-latest
    timeout-minutes: 15

    steps:
      - name: Checkout source code
        uses: actions/checkout@v4

      - name: Set up Java 21
        uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: "21"
          cache: maven

      - name: Build and run tests
        run: mvn --batch-mode --no-transfer-progress clean verify
```

**Giới hạn hiện tại:** job này chỉ build được các module Java (root `mvn clean verify` không chạm tới `bot-detection-service`/`assistant-service` vì hai service Python nằm ngoài Maven reactor). **Việc còn để dành:** thêm job riêng dùng `actions/setup-python@v5` cho 2 service Python khi chúng có code thật (từ P6, P7 trở đi) — không chặn tiến độ hiện tại.

---

## 12. Checklist hoàn thành Phase 0 (đối chiếu với roadmap gốc)

| Tiêu chí | Trạng thái |
|---|---|
| `docker-compose up` chạy sạch, mọi hạ tầng healthy | ✅ |
| Khung rỗng cho cả 8 service | ✅ |
| Mỗi service Java gọi `/actuator/health` trực tiếp thành công | ✅ |
| Mỗi service Python gọi `/health` trực tiếp thành công | ✅ |
| Gọi được health check của từng service **qua Gateway** | ✅ |
| CI build + test tự động trên PR/push | ✅ (Java only — Python để P6/P7) |
| common-lib có cấu trúc 3 artifact đúng rule-of-3, chưa cần code | ✅ |

**Phase 0 đạt đủ tiêu chí hoàn thành.**

---

## 13. Việc để dành cho các phase sau (không phải backlog lỗi, chỉ là ngoài phạm vi P0)

- CI cho 2 service Python (khi có code thật ở P6/P7).
- `docker-compose.prod.yml` — hiện để trống, sẽ viết ở P9.
- Đường dẫn `Path` cụ thể ở route Gateway — sẽ rà lại khi có API thật từng service (P1 trở đi).
- Quyết định giữ hay bỏ tính năng OTP xác thực thiết bị mới ở `user-service` — vẫn đang treo, xem lại sau khi thiết kế bot-detection ở P6.
- JWT verify thật tại Gateway — code cùng lúc với P1 (`user-service`).

---

*Hết tài liệu Phase 0. Bước tiếp theo: P1 — User & Auth.*