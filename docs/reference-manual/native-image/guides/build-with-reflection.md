---
layout: ni-docs
toc_group: how-to-guides
link_title: Configure Native Image with the Tracing Agent
permalink: /reference-manual/native-image/guides/configure-with-tracing-agent/
redirect_from:
  - /reference-manual/native-image/guides/build-with-reflection/
---

# Configure Native Image with the Tracing Agent

To build a native executable for a Java application that uses Java reflection, dynamic proxy objects, JNI, or class path resources, you should either provide the `native-image` tool with a JSON-formatted metadata file or precompute metadata in the code.

You can create configuration file(s) by hand, but a more convenient approach is to generate the configuration using the Tracing Agent (from now on, the agent). 
This guide demonstrates how to configure `native-image` with the agent. 
The agent generates the configuration for you automatically when you run an application on a JVM.

To learn how to build a native executable with the metadata precomputed in the code, [see the documentation](../ReachabilityMetadata.md).

The example application in this guide makes use of reflection.
The `native-image` tool can only partially detect application elements accessed through the Java Reflection API.
Therefore, you need to explicitly provide details about the classes, methods, and fields accessed reflectively.

## Example with No Configuration

### Prerequisite 
Make sure you have installed a GraalVM JDK.
The easiest way to get started is with [SDKMAN!](https://sdkman.io/jdks#graal).
For other installation options, visit the [Downloads section](https://www.graalvm.org/downloads/).

1. Save the following source code in a file named _ReflectionExample.java_:
    ```java
    import java.lang.reflect.Method;
    
    class StringReverser {
        static String reverse(String input) {
            return new StringBuilder(input).reverse().toString();
        }
    }
    
    class StringCapitalizer {
        static String capitalize(String input) {
            return input.toUpperCase();
        }
    }
    
    public class ReflectionExample {
        public static void main(String[] args) throws ReflectiveOperationException {
            if (args.length == 0) {
                System.err.println("You must provide the name of a class, the name of its method and input for the method");
                return;
            }
            String className = args[0];
            String methodName = args[1];
            String input = args[2];
    
            Class<?> clazz = Class.forName(className);
            Method method = clazz.getDeclaredMethod(methodName, String.class);
            Object result = method.invoke(null, input);
            System.out.println(result);
        }
    }
    ```
    This Java application uses command-line arguments to determine the operation to be performed.

2. Compile the example and then run each command below.
    ```shell
    javac ReflectionExample.java
    ```
    ```shell
    java ReflectionExample StringReverser reverse "hello"
    ```
    ```shell
    java ReflectionExample StringCapitalizer capitalize "hello"
    ```
    The output of each command should be `"olleh"` and `"HELLO"`, respectively. (An exception is thrown if you provide any other string to identify the class or method.)

3. Create a native executable, as follows:
    ```shell
    native-image ReflectionExample
    ```

4. Run the resulting native executable, using the following command:
    ```bash
    ./reflectionexample StringReverser reverse "hello"
    ```
    You should see an exception, similar to:
    ```
    Exception in thread "main" java.lang.ClassNotFoundException: StringReverser
        at org.graalvm.nativeimage.builder/com.oracle.svm.core.hub.ClassForNameSupport.forName(ClassForNameSupport.java:190)
        ...
        at ReflectionExample.main(ReflectionExample.java:68)
    ```
    This shows that, from its static analysis, the `native-image` tool was unable to determine that class `StringReverser` is used by the application and therefore did not include it in the native executable. 

## Example with Configuration

The following steps demonstrate how to use the agent, and its output, to create a native executable that relies on reflection and requires configuration.

1. Create a directory named _META-INF/native-image/_ in the working directory:
    ```shell
    mkdir -p META-INF/native-image
    ```

2. Run the application with the agent enabled, as follows:
    ```shell
    java -agentlib:native-image-agent=config-output-dir=META-INF/native-image ReflectionExample StringReverser reverse "hello"
    ```
    This command creates a file named _rechability-metadata.json_ containing the name of the class `StringReverser` and its `reverse()` method.
    ```json
    {
    "reflection": [
        {
          "type": "StringReverser",
          "methods": [
            {
              "name": "reverse",
              "parameterTypes": [
                  "java.lang.String"
              ]
            }
          ]
        }
      ]
    }
    ```

3. Build a native executable:
    ```shell
    native-image ReflectionExample
    ```
    The `native-image` tool automatically uses the metadata file in the _META-INF/native-image/_ directory.
    However, we recommend that the _META-INF/native-image/_ directory is on the class path, either via a JAR file or using the `-cp` option. (This avoids confusion for IDE users where a directory structure is defined by the IDE itself.)

4. Test your executable.
    ```shell
    ./reflectionexample StringReverser reverse "hello"
    olleh
    ```
    ```shell
    ./reflectionexample StringCapitalizer capitalize "hello"
    ```

    You should see again an exception, similar to:
    ```
    Exception in thread "main" java.lang.ClassNotFoundException: StringCapitalizer
        at org.graalvm.nativeimage.builder/com.oracle.svm.core.hub.ClassForNameSupport.forName(ClassForNameSupport.java:190)
        ...
        at ReflectionExample.main(ReflectionExample.java:68)
    ```
    Neither the Tracing Agent nor the `native-image` tool can ensure that the configuration file is complete.
    The agent observes and records which program elements are accessed using reflection when you run the application. 
    In this case, the `native-image` tool has not been configured to include references to class `StringCapitalizer`.

5. Update the configuration to include class `StringCapitalizer`.
    You can manually edit the _reachability-metadata.json_ file or re-run the Tracing Agent to update the existing configuration file using the `config-merge-dir` option, as follows:
    ```shell
    java -agentlib:native-image-agent=config-merge-dir=META-INF/native-image ReflectionExample StringCapitalizer capitalize "hello"
    ```

    This command updates the _reachability-metadata.json_ file to include the name of the class `StringCapitalizer` and its `capitalize()` method.
    ```json
    {
      "reflection": [
        {
          "type": "StringCapitalizer",
          "methods": [
            {
              "name": "capitalize",
              "parameterTypes": [
                "java.lang.String"
              ]
            }
          ]
        },
        {
          "type": "StringReverser",
          "methods": [
            {
              "name": "reverse",
              "parameterTypes": [
                "java.lang.String"
              ]
            }
          ]
        }
      ]
    }
    ```

6. Rebuild the native executable and run it.
    ```shell
    native-image ReflectionExample
    ```
    ```shell
    ./reflectionexample StringCapitalizer capitalize "hello"
    ```
   
   The application should now work as intended.

### Related Documentation

* [Assisted Configuration with Tracing Agent](../AutomaticMetadataCollection.md#tracing-agent)
* [Reachability Metadata: Reflection](../ReachabilityMetadata.md#reflection)