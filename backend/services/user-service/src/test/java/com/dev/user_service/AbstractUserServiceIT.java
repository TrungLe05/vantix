package com.dev.user_service;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

/**
 * Nền chung cho integration test của user-service (profile "test", xem application-test.yaml).
 * Secret sinh ngẫu nhiên mỗi lần chạy để repo không chứa chuỗi giống secret.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractUserServiceIT {

    @DynamicPropertySource
    static void randomSecrets(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret-key", () -> UUID.randomUUID() + "-" + UUID.randomUUID());
        registry.add("app.otp.hmac-secret", () -> UUID.randomUUID().toString());
        registry.add("app.run-config.default-admin.password", () -> UUID.randomUUID().toString());
        registry.add("app.run-config.default-staff.password", () -> UUID.randomUUID().toString());
        registry.add("app.run-config.default-buyer.password", () -> UUID.randomUUID().toString());
    }

    /** Chạy cùng lúc {@code threads} lần một tác vụ (cùng xuất phát qua latch), trả kết quả từng lần. */
    protected static <T> List<Outcome<T>> runConcurrently(int threads, Callable<T> task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    outcomes.add(new Outcome<>(future.get(), null));
                } catch (java.util.concurrent.ExecutionException e) {
                    outcomes.add(new Outcome<>(null, e.getCause()));
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    protected record Outcome<T>(T value, Throwable error) {
        public boolean succeeded() {
            return error == null;
        }
    }
}
