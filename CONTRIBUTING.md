# Contributing to Permitter

## Build and check a change

Use a full JDK 17 or later and Maven. From the repository root:

```sh
mvn clean package
java integration/CompileChecks.java
```

The compiler checks build an annotated library JAR and compile small consumers against it. For a rule change, add a consumer that exercises the new behavior and one that verifies the closest exception or fallback. Check the exit status, diagnostic kind, message and source location. CI runs these checks on JDK 17, 21, 25 and 27.

## Where changes belong

| Directory | Contents |
| --- | --- |
| `permitter/` | Public annotations, their targets, retention and defaults |
| `permitter-plugin/` | javac integration, metadata lookup, caller matching and diagnostics |
| `integration/` | Compiler regression checks using the JDK |

Write all code comments and Javadoc in English. Both shipped artifacts use JDK APIs only. If a change needs an external dependency, explain what it provides and which alternatives you considered. Document changes to the public contract in the README.

## Issues and pull requests

For a compiler issue, include the annotation, a minimal caller, the compiler options, the JDK version and the actual diagnostic. For a pull request, describe the user-visible change and list the checks you ran. Both artifacts follow the same semantic versioning policy.
