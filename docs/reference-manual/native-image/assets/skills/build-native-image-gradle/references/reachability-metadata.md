# Missing Reachability Metadata (Gradle)

Use this guide when native-image fails because reflection, resources, serialization, or JNI entries are missing.

## Detect missing metadata

```groovy
graalvmNative {
    binaries.all {
        buildArgs.add('--exact-reachability-metadata')
        runtimeArgs.add('-XX:MissingRegistrationReportingMode=Warn')
    }
}
```

## Resolution workflow

### Run the tracing agent

```bash
./gradlew generateMetadata -Pcoordinates=<library-coordinates> -PagentAllowedPackages=<condition-packages>
```

### If agent-collected metadata is still incomplete, add manual config

Create `META-INF/native-image/<project-groupId>/manual-metadata/` with only the files needed. Native Image automatically picks up metadata from this location.

For metadata layout and file semantics, see the [Reachability Metadata documentation](https://www.graalvm.org/latest/reference-manual/native-image/metadata/).

Minimal `reflect-config.json` example:

```json
[
  {
    "condition": {
      "typeReachable": "com.example.Condition"
    },
    "name": "com.example.Type",
    "methods": [
      {
        "name": "<init>",
        "parameterTypes": []
      }
    ]
  }
]
```

## Rebuild and verify

```bash
./gradlew nativeCompile
./gradlew nativeTest
```
