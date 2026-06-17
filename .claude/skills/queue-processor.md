---
name: queue-processor
applies-to: engine-agent
related-design-doc:
  - design/23-schema-spec.md
  - design/24-interface-contracts.md
---

# QueueProcessor Tick Handler Pattern

How to write a `QueueProcessor` tick handler that claims and dispatches tasks
from `rf_task_queue`.

## Steps

### 1. Claim a batch of tasks

Use a single atomic statement — CTE + `UPDATE ... RETURNING`:

```sql
WITH claimable AS (
  SELECT id
  FROM rf_task_queue
  WHERE status = 'PENDING'
    AND scheduled_at <= now()
  ORDER BY priority, scheduled_at
  LIMIT :batch_size
  FOR UPDATE SKIP LOCKED
)
UPDATE rf_task_queue
SET status      = 'CLAIMED',
    claimed_at   = now(),
    claimed_by   = :processor_id,
    visibility_timeout = now() + interval '5 minutes'
WHERE id IN (SELECT id FROM claimable)
RETURNING *;
```

### 2. Dispatch by type

For each claimed task, inspect `task_type`:

| task_type | Action |
|-----------|--------|
| `ORCHESTRATE` | Advance the DAG — determine next runnable nodes |
| `EXECUTE` | Already claimed by a worker via gRPC; skip (or push to worker queue) |
| `CANCEL` | Cancel the build — mark nodes as CANCELLED, notify workers |

### 3. ORCHESTRATE handler

1. Read `rf_flow_nodes` for the `build_number`.
2. Walk the DAG: find nodes whose predecessors are all `COMPLETED`.
3. For each newly-runnable node, insert a `STEP` message into `rf_task_queue`.
4. If all nodes are terminal (`COMPLETED` / `SKIPPED` / `CANCELLED`), mark the
   build as finished.

### 4. Commit after each task

Do **not** hold a single transaction across the entire batch. Commit (or
release the savepoint) after processing each task so a failure in task N does
not roll back tasks 1…N-1.

### 5. Log metrics

| Metric | Type | Description |
|--------|------|-------------|
| `rf_task_claim_total` | counter | Tasks claimed per tick, labelled by `task_type` |
| `rf_queue_dwell_seconds` | histogram | `claimed_at - scheduled_at` |

## Worked Example — Processing a `START_PIPELINE` Message

```java
void handleStartPipeline(TaskRow task) {
    // 1. Parse the pipeline model from the task payload
    PipelineModel model = PipelineModel.fromJson(task.payload());

    // 2. Create rf_flow_nodes rows for every node in the model
    List<FlowNode> nodes = model.toFlowNodes(task.buildNumber());
    flowNodeDao.insertAll(nodes);

    // 3. Determine root nodes (no predecessors)
    List<FlowNode> roots = nodes.stream()
        .filter(n -> n.predecessors().isEmpty())
        .toList();

    // 4. Push STEP messages for each root node
    for (FlowNode root : roots) {
        taskQueueDao.enqueue(TaskRow.step(
            task.buildNumber(),
            root.nodeId(),
            root.command()
        ));
    }

    // 5. Mark this task as COMPLETED
    taskQueueDao.complete(task.id());
}
```
