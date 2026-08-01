# AGENTS.md — Agent Instructions

## Repo State

This is a **fresh Android Studio template** — nearly all project code is unwritten.
This file is the **project specification**. Build toward the contract it describes;
do not assume any described structure already exists.

## Current Build Config

- AGP 9.2.1, Gradle 9.4.1
- compileSdk 36 (AGP 9 syntax: `release(36) { minorApiLevel = 1 }`), minSdk 26
- Version catalog: `gradle/libs.versions.toml`
- Single `:app` module (`settings.gradle.kts`)
- **No** AIDL plugin, View Binding, or multi-module structure declared yet

## Commands

```bash
# Build everything
./gradlew assembleDebug

# Verify real process separation (service runs in separate process)
adb shell ps | grep binderlearning

# Watch the full IPC flow in Logcat
adb logcat | grep -E "Binder|CalculatorService|CalculatorClient"
```

## Required Structural Changes (in order)

1. Restructure into 3 modules: `aidl/`, `app-service/`, `app-client/`
2. Add `com.android.library` + `android.aidl` plugins to `aidl/` build script
3. Add `android.application` + View Binding to `app-service/` and `app-client/`
4. Wire module dependencies: `app-client` → `aidl`, `app-service` → `aidl`
5. Declare service with `android:process=":service"` in manifest

## Key Technical Points to Follow

- **Process boundary**: Service must use `android:process=":service"` to guarantee real IPC
- **Threading**: AIDL methods execute on Binder thread pool, **not** service main thread — log `Thread.currentThread().name` to prove it
- **RemoteException**: Every generated Proxy method can throw it — catch at every call site, never let propagate
- **Divide-by-zero**: Return 0 and log — must not crash service process
- **Comments**: Every Binder/AIDL code path needs inline explanatory comments tracing the IPC flow (Proxy → Parcel → `/dev/binder` → kernel → Stub → implementation)

## Constraints (do NOT use)

Hilt, Dagger, Koin, Jetpack Compose, Coroutines, Flow, RxJava, MVVM, Clean Architecture

Only: Kotlin, XML layouts, View Binding, Android SDK APIs

## Spec-to-Reality Gap

| What AGENTS.md describes | Current reality |
|---|---|
| 3 modules: app-client, app-service, aidl | Single `:app` module only |
| ICalculatorService.aidl | Does not exist |
| CalculatorService (Bound Service) | Does not exist |
| Client Activity with UI | Default template only |
| View Binding | Not declared |
| Service manifest with `:service` process | Not configured |

---

# Learning Roadmap (preserved below — project specification)

---

# AGENT.md

## Project

Android Binder IPC Learning Project - Project 1: Calculator Service

## Objective

This project is a learning exercise to understand Android's Binder IPC mechanism through AIDL. The goal is **not** to build a production-ready calculator, but to gain a solid understanding of:

* Bound Services
* Binder IPC
* AIDL
* Stub and Proxy
* Service lifecycle
* Cross-process communication
* Binder threading model
* Exception and death handling across IPC
* Android architecture fundamentals

The implementation should prioritize readability and educational value over abstraction or optimization.

---

# Background

The developer has several years of Android application experience but comes from a fintech/mobile-app background with little Android Platform or Android Automotive experience.

The developer is preparing for a Full Stack Developer (AOSP / Android Automotive) interview. This project should explicitly connect what is learned here to how Android Automotive is actually built — specifically:

* **CarService** — the system service that mediates access to vehicle functions, itself built on Binder/AIDL.
* **VHAL (Vehicle HAL)** — increasingly implemented as an **AIDL-based HAL** (replacing the older HIDL approach), meaning the exact Stub/Proxy pattern practiced here is the same pattern used to talk to real vehicle hardware abstraction layers.

When explaining code, relate concepts back to familiar client-server architecture where appropriate, and where relevant, back to this automotive context.

For example:

* AIDL interface ≈ API contract
* Binder Proxy ≈ Retrofit client
* Binder Driver ≈ transport layer (like a network socket, but kernel-mediated shared memory instead of TCP/IP)
* Service ≈ backend service
* CarService ≈ a "backend" that itself talks to hardware through AIDL, the same way this project's client talks to CalculatorService

---

# Project Structure

Create a multi-module Android Studio project.

Suggested structure:

```text
BinderLearning/
│
├── app-client/
│
├── app-service/
│
└── aidl/
```

Where:

### app-client

Contains:

* Activity
* UI
* Service binding
* Calls AIDL methods
* Displays returned values

### app-service

Contains:

* Bound Service
* Calculator implementation
* AIDL Stub implementation

### aidl

Contains shared AIDL interfaces.

Only place IPC contracts here.

---

# Process Separation (Required, Not Optional)

Splitting code into `app-client` and `app-service` Gradle modules does **not** by itself create two OS processes. If both modules are compiled into a single APK and installed normally, method calls may resolve locally and Binder marshalling is bypassed entirely — defeating the purpose of this project.

To guarantee a real process boundary, do ONE of the following (state clearly in code comments which was chosen and why):

**Option A — Same APK, separate process (simpler to build/test):**
Declare the service with a distinct process name in the manifest:

```xml
<service
    android:name=".CalculatorService"
    android:process=":service"
    android:exported="true" />
```

The `:service` prefix tells Android to run this component in a separate process within the same app, which is still a genuine process boundary — Binder still marshals every call across it.

**Option B — Two separate installed APKs (closer to real-world AOSP system services):**
`app-client` and `app-service` are built and installed as independent applications, communicating only through an explicit `Intent` action + package name, since they don't share a process or classpath at runtime.

Either is acceptable for this project, but the choice must be explicit and the doc/comments must explain that this is what makes the exercise meaningful.

---

# Functional Requirements

Implement a remote calculator service.

Operations:

```kotlin
add(a, b)

subtract(a, b)

multiply(a, b)

divide(a, b)
```

Use integer values initially.

Division should safely handle divide-by-zero — but note that "safely handle" at the Binder layer has two layers:
1. The service should not crash its own process on divide-by-zero.
2. The client must handle the possibility that ANY call, not just divide, can throw `RemoteException` (see Exception Handling section below) — this is different from a normal in-process function call and should not be treated the same way.

---

# UI

Client application should contain:

Input A

Input B

Buttons

* Add
* Subtract
* Multiply
* Divide

Result TextView

Connection status

Example:

```
Service Connected

10 + 5 = 15
```

---

# AIDL

Create:

```text
ICalculatorService.aidl
```

Expose four methods.

Keep the interface intentionally simple.

Do NOT introduce Parcelable objects until a later iteration. When that iteration happens, introduce and explain `in` / `out` / `inout` directionality keywords, since they only matter once non-primitive types cross the boundary.

---

# Service

Implement:

```text
CalculatorService
```

The service should:

* extend Service
* expose Binder using generated Stub
* log every incoming IPC request
* log the identity of the thread handling each call (see Threading Model below)
* return calculation results
* handle divide-by-zero without crashing the service process

Example log:

```
Client requested add(10,5)
Handled on thread: Binder:1234_2
Returning 15
```

---

# Client

The client should:

Bind using:

```kotlin
bindService(...)
```

Handle:

* onServiceConnected()
* onServiceDisconnected()
* RemoteException on every AIDL call site (try/catch, not left to propagate as a crash)

Disable calculator buttons until the service is connected.

---

# Threading Model (Required Learning Point)

This is one of the most commonly asked Binder interview questions and must not be skipped.

Explain and prove, with actual logged evidence:

* Incoming Binder calls on the service side execute on a **thread from the Binder thread pool**, NOT on the service's main thread.
* This means a slow AIDL method does not freeze the service's UI (if it had one) — but it CAN block the calling client if called synchronously from the client's main thread.
* Log `Thread.currentThread().name` inside every AIDL method implementation to make this visible in Logcat, rather than just asserting it.
* As a deliberate exercise, add an artificial `Thread.sleep()` inside one method (e.g. `divide`), call it from the client's main thread, and observe/log the effect — then discuss what the fix would be (calling from a background thread) without necessarily implementing a full async solution, since Coroutines/RxJava are out of scope for this project.

---

# Exception & Death Handling (Required Learning Point)

Explain, and reflect in code comments:

* Every generated AIDL Proxy method can throw `android.os.RemoteException` — this is checked and must be handled, unlike a normal Kotlin function call.
* Common causes: the service process crashed, was killed by the system (low memory), or was never connected.
* Introduce `IBinder.linkToDeath()` conceptually (a comment explaining it is sufficient for this project; full implementation optional): it lets a client register a callback for when the remote process dies, which is how system services detect a crashed HAL or service without polling.
* Tie this back to automotive: if a vehicle HAL process crashes, `linkToDeath` semantics are part of how the rest of the system avoids silently hanging forever waiting for a response that will never come.

---

# Logging

Add Logcat statements throughout the Binder flow.

For example:

Client

```
Binding to CalculatorService...
Calling add()
Received result = 15
```

Service

```
Client connected
Executing add() on thread Binder:1234_2
Returning result
```

The logs should clearly demonstrate the IPC lifecycle, including thread identity and any exception paths triggered during testing.

---

# Code Comments (Required — This Is How the Developer Learns)

Every non-trivial piece of generated or hand-written Binder-related code must be explained via inline comments, not just working silently. Specifically:

* In the `.aidl` file: comment on what Stub and Proxy the compiler will generate from this, and where to find them (build output directory).
* In `CalculatorService.kt`: comment on why `onBind()` returns a `Stub` instance, and what object is actually being handed across the process boundary (a Binder token, not the real Kotlin object).
* In the client's `ServiceConnection`: comment on what `IBinder` actually is at this point (a local Proxy wrapping a remote reference), and how calling `.add()` on it differs mechanically from calling `.add()` on a plain local object — i.e., trace the path: client calls Proxy method → data written to a `Parcel` → transaction sent through `/dev/binder` → kernel driver copies data into the service process's address space → Stub's `onTransact()` dispatches to the real implementation → result written back the same way.
* Anywhere `Thread.currentThread().name` is logged: comment on why this proves cross-thread (and cross-process) execution.
* Anywhere `RemoteException` is caught: comment on what real-world condition would trigger this catch block.

The comments should read like a running explanation a mentor would give while pair programming, not just restating what the line of code obviously does.

---

# Development Style / Milestones

Implement the project incrementally, in this order. Do not skip ahead.

1. **Define the AIDL contract** (`ICalculatorService.aidl`) — explain what gets generated from it before writing any implementation.
2. **Build the empty Service skeleton** with `onBind()` returning a `Stub`, unimplemented method bodies — explain what "exposing a Binder" actually means at this stage.
3. **Wire up the manifest for real process separation** (Option A or B above) — verify via `adb shell ps` or Logcat PID that client and service are genuinely different processes before proceeding.
4. **Implement the four operations** with logging inside each — explain thread identity here.
5. **Build the client UI** (inputs, buttons, result TextView, connection status) — no service calls yet, just layout + view binding.
6. **Implement `bindService()` / `ServiceConnection`** — explain `onServiceConnected` / `onServiceDisconnected` and what the received `IBinder` represents.
7. **Wire buttons to AIDL calls**, with `RemoteException` handling — verify end-to-end logs show the full round trip.
8. **Threading proof exercise** — add the artificial delay, observe/log blocking behavior, discuss (not necessarily implement) the fix.
9. **Divide-by-zero and RemoteException edge cases** — verify the service does not crash and the client handles failure gracefully.
10. **Wrap-up review** — developer should be able to explain, unaided, everything in Success Criteria below.

After each milestone: explain what was built, why it exists, and how Binder uses it internally, before moving to the next.

---

# Constraints

Do NOT use:

* Hilt
* Dagger
* Koin
* Jetpack Compose
* Coroutines
* Flow
* RxJava
* MVVM
* Clean Architecture

This project intentionally keeps the architecture simple so Binder concepts remain the focus.

Use:

* Kotlin
* XML layouts
* View Binding
* Android SDK APIs

---

# Success Criteria

By the end of this project, the developer should be able to confidently explain, unaided:

* What Binder is and why Android needs it (vs. e.g. Unix pipes, sockets, or shared memory alone)
* What AIDL does and what code it generates
* How Stub and Proxy communicate, in terms of Parcels and `/dev/binder`
* What happens during `bindService()`, step by step
* What happens when a remote method is called, from client call site to service execution and back
* How data crosses process boundaries (marshalling/unmarshalling via Parcel)
* Why Android uses IPC instead of direct object references
* **Which thread executes an incoming Binder call, and why that matters**
* **Why `RemoteException` exists and what real conditions cause it**
* **How this exact mechanism (Stub/Proxy over Binder) maps onto CarService and AIDL-based VHAL in Android Automotive**

The primary goal is understanding Android Platform concepts, not simply completing the application.