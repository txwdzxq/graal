# Missing Reachability Metadata (Maven)

Use this guide when native-image fails because reflection, resources, serialization, or JNI entries are missing.

## Detect missing metadata

```xml
<configuration>
  <buildArgs>
    <buildArg>--exact-reachability-metadata</buildArg>
  </buildArgs>
  <runtimeArgs>
    <runtimeArg>-XX:MissingRegistrationReportingMode=Warn</runtimeArg>
  </runtimeArgs>
</configuration>
```

## Resolution workflow

### Run the tracing agent

`metadataCopy` copies metadata collected during the agent test run into your project resources.

Configure metadata copy:

```xml
<agent>
  <enabled>true</enabled>
  <metadataCopy>
    <disabledStages>
      <stage>main</stage>
    </disabledStages>
    <merge>true</merge>
    <outputDirectory>META-INF/native-image</outputDirectory>
  </metadataCopy>
</agent>
```

```bash
./mvnw -Pnative -Dagent=true test
./mvnw -Pnative native:metadata-copy
./mvnw -Pnative package
```


### If agent-collected metadata is still incomplete, add manual config

Create `META-INF/native-image/<project-groupId>/manual-metadata/` with only the files you need. Native Image automatically picks up metadata from this location.

For metadata layout and file semantics, see the [Reachability Metadata documentation](https://www.graalvm.org/latest/reference-manual/native-image/metadata/).

Minimal `reflect-config.json` example:

```json
[
  {
    "name": "com.example.MyClass",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  }
]
```

## Rebuild and verify

```bash
./mvnw -Pnative package
./mvnw -Pnative test
```

If a library still fails after repository + agent + manual entries, capture the exact missing symbol from the error output and add only that entry.
