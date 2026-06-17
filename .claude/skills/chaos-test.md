---
name: chaos-test
applies-to: test-agent
related-design-doc:
  - design/22-execution-engine-roadmap.md
---

# Chaos Testing with Testcontainers

How to write a Testcontainers-based chaos test that validates the execution
engine's fault-tolerance (visibility timeout reaper, task re-claim, build
recovery).

## Steps

### 1. Annotate with `@Testcontainers`

```java
@Testcontainers
class WorkerCrashRecoveryTest {

    @Container
    static PostgreSQLContainer<?> pg =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("releaseflow_test");
```

### 2. Start a real QueueProcessor

```java
    DataSource ds = createDataSource(pg);
    FlywayMigrator.migrate(ds);  // apply schema
    QueueProcessor processor = new QueueProcessor(ds);
    processor.start();
```

### 3. Start a mock worker

```java
    MockWorker worker = new MockWorker(ds);
    worker.start();  // polls rf_task_queue, completes tasks
```

### 4. Mid-test: kill the worker

Simulate a crash by interrupting the worker thread:

```java
    // Wait until the worker has claimed step 2
    await().atMost(10, SECONDS).until(() -> worker.claimedCount() >= 2);

    // Kill it
    worker.crashNow();  // Thread.interrupt() + close connections
```

### 5. Assert: visibility timeout reaper reclaims the task

```java
    // The reaper runs every 60 s by default; for tests, configure 2 s
    await().atMost(15, SECONDS).until(() ->
        taskQueueDao.countByStatus("PENDING") >= 1
    );
```

### 6. Assert: another worker picks it up

```java
    MockWorker worker2 = new MockWorker(ds);
    worker2.start();

    await().atMost(10, SECONDS).until(() ->
        worker2.completedCount() >= 1
    );
```

### 7. Assert: build eventually succeeds

```java
    await().atMost(30, SECONDS).until(() ->
        buildDao.getStatus(buildNumber) == BuildStatus.SUCCESS
    );
```

### 8. Variant: controller crash

Stop the `QueueProcessor`, restart it, assert the build resumes:

```java
    processor.stop();
    Thread.sleep(2000);
    processor.start();

    await().atMost(30, SECONDS).until(() ->
        buildDao.getStatus(buildNumber) == BuildStatus.SUCCESS
    );
```

## Worked Example — 3-Step Pipeline, Worker Killed After Step 2

```java
@Test
void workerCrashMidBuild_recoversViaVisibilityTimeout() {
    // Arrange: create a 3-step linear pipeline
    int buildNumber = triggerBuild("step1 -> step2 -> step3");

    // Act: let the worker complete steps 1 and 2, then crash
    await().until(() -> worker.completedCount() >= 2);
    worker.crashNow();

    // Assert: step 2's task returns to PENDING after visibility timeout
    await().atMost(15, SECONDS).until(() ->
        taskQueueDao.findByNodeId(buildNumber, "step2").status() == "PENDING"
            || taskQueueDao.findByNodeId(buildNumber, "step3").status() == "PENDING"
    );

    // Act: start a replacement worker
    MockWorker worker2 = new MockWorker(ds);
    worker2.start();

    // Assert: build completes successfully
    await().atMost(30, SECONDS).until(() ->
        buildDao.getStatus(buildNumber) == BuildStatus.SUCCESS
    );

    assertThat(flowNodeDao.allByBuild(buildNumber))
        .extracting(FlowNode::status)
        .containsOnly("COMPLETED");
}
```

## Dependencies

Add to `pom.xml` in `<dependencies>` with `<scope>test</scope>`:

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <version>${testcontainers.version}</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>${testcontainers.version}</version>
    <scope>test</scope>
</dependency>
```
