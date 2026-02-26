# Native Image Testing

## Contents
- JUnit dependencies
- Running native tests
- Custom test suites

## JUnit dependencies

```groovy
dependencies {
    testImplementation 'org.junit.jupiter:junit-jupiter-api:5.8.1'
    testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.8.1'
    testImplementation 'junit:junit:4.13.2'
}

test {
    useJUnitPlatform()
}
```

## Running native tests

```bash
./gradlew nativeTest
```

Output binary: `build/native/nativeTestCompile/<imageName>`


## Custom test suites

Register additional test binaries for integration tests or other test source sets:

```groovy
graalvmNative {
    registerTestBinary("integTest") {
        usingSourceSet(sourceSets.integTest)
        forTestTask(tasks.named('integTest'))
    }
}
```

This creates two tasks:
- `nativeIntegTestCompile` — builds the native test binary
- `nativeIntegTest` — runs it
