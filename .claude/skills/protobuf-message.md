---
name: protobuf-message
applies-to: worker-agent
related-design-doc:
  - design/25-agent-protocol.md
---

# Adding Protobuf Messages

How to add a new Protobuf message type for the ReleaseFlow agent protocol.

## Steps

### 1. Define in the `.proto` file

Edit `src/main/proto/releaseflow/agent/v1/messages.proto`:

```protobuf
syntax = "proto3";
package releaseflow.agent.v1;

option java_multiple_files = true;
option java_package = "io.adaptiq.titan.agent.v1";

message StepRequest {
  string task_id      = 1;
  int32  build_number = 2;
  string node_id      = 3;
  string command       = 4;
  map<string, string> env = 5;
  // New field — never reuse a retired field number
  string docker_image  = 6;
}
```

### 2. Run code generation

```bash
mvn generate-sources -pl . -am
```

The `protobuf-maven-plugin` writes generated Java classes to
`target/generated-sources/protobuf/java/` (and
`target/generated-sources/protobuf/grpc-java/` for service stubs).

### 3. Use in the DB (JSON encoding)

Store Protobuf messages in PostgreSQL `jsonb` columns using the canonical
Protobuf-JSON mapping:

```java
import com.google.protobuf.util.JsonFormat;

// Serialize
String json = JsonFormat.printer()
    .omittingInsignificantWhitespace()
    .print(stepRequest);

// Deserialize
StepRequest.Builder builder = StepRequest.newBuilder();
JsonFormat.parser().merge(json, builder);
StepRequest parsed = builder.build();
```

### 4. Use in gRPC (binary encoding)

Over gRPC the native Protobuf binary wire format is used automatically — no
extra serialization code needed.

### 5. Versioning rules

| Do | Don't |
|----|-------|
| Add new fields with the next available field number | Remove or renumber existing fields |
| Mark deprecated fields with `[deprecated = true]` | Change a field's type |
| Use `reserved` to retire field numbers | Reuse a retired field number |

## Worked Example — Adding `docker_image` to `StepRequest`

**Before:**

```protobuf
message StepRequest {
  string task_id      = 1;
  int32  build_number = 2;
  string node_id      = 3;
  string command       = 4;
  map<string, string> env = 5;
}
```

**After:**

```protobuf
message StepRequest {
  string task_id      = 1;
  int32  build_number = 2;
  string node_id      = 3;
  string command       = 4;
  map<string, string> env = 5;
  string docker_image  = 6;  // optional container image for execution
}
```

Then regenerate:

```bash
mvn generate-sources
```

Worker code can now read `request.getDockerImage()`. Existing serialised
messages without the field will return `""` (the proto3 default).
