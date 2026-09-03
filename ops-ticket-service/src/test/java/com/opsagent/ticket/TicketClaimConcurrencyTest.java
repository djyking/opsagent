package com.opsagent.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 验证多个运维人员并发接单时只有一个请求能够成功。
 *
 * @author heyu
 * @since 2026/8/14
 */
@SpringBootTest(
        properties =
                "ops.security.secret=opsagent-concurrency-test-secret-20260814-at-least-32-bytes")
@ActiveProfiles("smoke")
class TicketClaimConcurrencyTest {
    private static final int WORKER_COUNT = 8;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private TicketMapper tickets;

    @Test
    void onlyOneOperatorCanClaimTheSameTicketVersion() throws Exception {
        long ticketId = createTicket();
        CountDownLatch ready = new CountDownLatch(WORKER_COUNT);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(WORKER_COUNT);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int worker = 0; worker < WORKER_COUNT; worker++) {
                long operatorId = 100L + worker;
                results.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    return tickets.claim(ticketId, operatorId, 0);
                                }));
            }

            ready.await();
            start.countDown();
            int successCount = 0;
            for (Future<Integer> result : results) successCount += result.get();

            assertThat(successCount).isOne();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT version FROM ticket WHERE id=?", Integer.class, ticketId))
                    .isOne();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM ticket WHERE id=?", String.class, ticketId))
                    .isEqualTo("ASSIGNED");
        } finally {
            executor.shutdownNow();
        }
    }

    private long createTicket() {
        jdbc.update(
                "INSERT INTO ticket(ticket_no,title,description,priority,status,creator_id,version,deleted)"
                        + " VALUES(?,?,?,?,?,?,0,0)",
                "OPS-CONCURRENCY-001",
                "并发接单演练",
                "八名运维人员同时抢占同一条待处理工单。",
                "HIGH",
                "CREATED",
                3L);
        return jdbc.queryForObject(
                "SELECT id FROM ticket WHERE ticket_no='OPS-CONCURRENCY-001'", Long.class);
    }
}
