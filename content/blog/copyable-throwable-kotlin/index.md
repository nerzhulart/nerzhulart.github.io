---
title: "CopyableThrowable in Kotlin: Preserving Stack Traces Across Coroutine Boundaries"
date: 2025-11-30
tags: ["kotlin", "coroutines", "exceptions", "debugging"]
draft: false
canonicalURL: "https://nerzhulart.github.io/blog/copyable-throwable-kotlin/"
---

When working with Kotlin coroutines, you often need to pass exceptions between different threads or coroutines. A common scenario is `CompletableDeferred` - you complete it exceptionally in one thread, then `await()` throws that exception in another thread. The problem is you lose the stack trace from the thread where you call `await()`. This makes debugging painful because you can't see the full execution path.

This article explores why this happens, why the obvious solution creates more problems, and how to preserve complete stack traces elegantly.

**Note on examples**: The code examples are deliberately simplified to clearly demonstrate the problem and solution. Real production code would have additional complexity, error handling, and context that isn't shown here for clarity.

## The Problem: Lost Stack Traces

Consider a typical async processing scenario where one coroutine prepares data and another waits for results:

```kotlin
class DataProcessor {
    private val result = CompletableDeferred<String>()

    fun startProcessing() {
        thread(name = "worker-thread") {
            try {
                // Some processing that fails
                val data = fetchDataFromSource()
                result.complete(data)
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
    }

    suspend fun awaitResult(): String {
        return result.await()  // Exception thrown here
    }
}

// Usage
suspend fun handleData() {
    val processor = DataProcessor()
    processor.startProcessing()

    try {
        val data = processor.awaitResult()
        println("Got data: $data")
    } catch (e: Exception) {
        // Where did this exception come from?
        e.printStackTrace()
    }
}
```

When `fetchDataFromSource()` throws an exception, the stack trace looks like this:

```
java.sql.SQLException: Connection timeout
    at DatabaseClient.fetchDataFromSource(DatabaseClient.kt:45)
    at DataProcessor$startProcessing$1.invoke(DataProcessor.kt:12)
    at kotlin.concurrent.ThreadsKt$thread$thread$1.run(Thread.kt:30)
```

You see the worker thread where the exception was created, but nothing about the coroutine that called `await()`. The stack trace from `handleData()` is completely missing. You know the exception happened, but you can't see which coroutine was waiting for this result or what led to that `await()` call.

This becomes a real debugging problem in complex systems. You see an exception about a failed database connection, but you don't know which user request triggered it, which business operation was waiting for that data, or what the call chain looked like in the consuming coroutine.

## The Naive Solution: Exception Wrapping

The straightforward approach is catching and rewrapping every exception:

```kotlin
suspend fun awaitResult(): String {
    return try {
        result.await()
    } catch (e: Exception) {
        // Wrap to preserve current stack trace
        throw IllegalStateException("Failed to get result", e)
    }
}
```

This works but creates problems. You need to remember to wrap exceptions at every boundary where they cross threads. Miss one place and you lose debugging information again. The exception chain becomes noisy with all these wrapper exceptions. And you're writing boilerplate for something that should be handled by the framework.

You might think `runCatching` and `Result<T>` solve this. They don't. When you wrap code in `runCatching`, it captures the exception instance as-is and stores it in a `Result`. If you pass that `Result` to another thread and call `getOrThrow()`, it simply re-throws the original exception object with its old stack trace. No new stack trace is captured. You lose the context of where `getOrThrow()` was called, just like with unwrapped exceptions. `Result` is convenient for error handling, but it doesn't magically preserve stack traces across thread boundaries.

More importantly, manual wrapping leaks implementation details. The caller now sees `IllegalStateException` wrapping the real error, making exception handling awkward. Should they catch the wrapper? The cause? Both? The exception type no longer accurately represents what actually failed.

You might think about using `addSuppressed()` to preserve the original exception type while adding context. Just rethrow the original exception after calling `addSuppressed()` with a new exception containing the current stack trace. But this doesn't solve the problem - `addSuppressed()` is designed for recording exceptions that happened during cleanup or resource closing, not for capturing stack traces from different execution contexts. The suppressed exception chain doesn't give you the same clean picture as a proper cause chain, and exception handlers don't typically look at suppressed exceptions when making decisions.

## The Elegant Solution: CopyableThrowable

Kotlin coroutines solve this with the `CopyableThrowable` interface. When an exception implements this interface, the coroutines library automatically creates a copy that includes both the original stack trace and the new one from where it's rethrown:

```kotlin
class DataFetchException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause), CopyableThrowable<DataFetchException> {

    override fun createCopy(): DataFetchException {
        return DataFetchException(message ?: "Data fetch failed", this)
    }
}

class DataProcessor {
    private val result = CompletableDeferred<String>()

    fun startProcessing() {
        thread(name = "worker-thread") {
            try {
                val data = fetchDataFromSource()
                result.complete(data)
            } catch (e: Exception) {
                // Throw CopyableThrowable instead
                result.completeExceptionally(
                    DataFetchException("Failed to fetch data", e)
                )
            }
        }
    }

    suspend fun awaitResult(): String {
        return result.await()
    }
}
```

Now when you call `await()`, the coroutines library detects that `DataFetchException` implements `CopyableThrowable`. It calls `createCopy()`, which creates a new exception with the original as the cause. The new exception captures the stack trace from the awaiting coroutine, while the original exception in the cause chain preserves the worker thread stack trace.

The result is a complete picture. You see where `await()` was called, what coroutine context it was in, and what the call chain looked like. Then you follow the cause chain to see where the original exception was thrown and what failed there. All without manual wrapping or boilerplate.

## How It Works

The magic happens inside coroutines internals. When you call `result.await()` and the `CompletableDeferred` contains an exception, the library checks if that exception implements `CopyableThrowable`. If it does, instead of throwing the original exception directly, it calls `createCopy()` to get a fresh instance.

The typical implementation pattern is:

```kotlin
override fun createCopy(): MyException {
    return MyException(message ?: "Default message", this)
}
```

The key is passing `this` as the cause. This creates a cause chain: the new exception wraps the original one. The new exception captures the current stack trace automatically when it's created. The original exception with its stack trace is preserved in the cause.

When you print the stack trace, you see both. First, the stack trace from where `await()` was called. Then in the "Caused by" section, the original stack trace from the worker thread:

```
DataFetchException: Failed to fetch data
    at DataProcessor.awaitResult(DataProcessor.kt:23)
    at MainKt$handleData$1.invokeSuspend(Main.kt:46)
    at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)
    ... coroutine machinery frames ...
Caused by: DataFetchException: Failed to fetch data
    at DataProcessor$startProcessing$1.invoke(DataProcessor.kt:12)
    at kotlin.concurrent.ThreadsKt$thread$thread$1.run(Thread.kt:30)
Caused by: java.sql.SQLException: Connection timeout
    at DatabaseClient.fetchDataFromSource(DatabaseClient.kt:45)
    at DataProcessor$startProcessing$1.invoke(DataProcessor.kt:12)
```

This gives you the complete execution path across thread boundaries. You see the `await()` call at line 23, the `handleData()` function at line 46, then follow the cause chain to the worker thread where it failed, and finally the original `SQLException` that started it all.

## Understanding COROUTINE_BOUNDARY

When debugging coroutine stack traces, you might notice special frames marked with `COROUTINE_BOUNDARY`. These are artificial stack frames that the coroutines library injects to mark where one coroutine context ends and another begins.

```
DataFetchException: Failed to fetch data
    at DataProcessor.awaitResult(DataProcessor.kt:23)
    at MainKt$handleData$1.invokeSuspend(Main.kt:46)
    at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)
    at _COROUTINE._BOUNDARY._(CoroutineDebugging.kt)
    at DataProcessor$startProcessing$1.invoke(DataProcessor.kt:12)
```

The `_COROUTINE._BOUNDARY._` frame doesn't represent actual code execution. It's inserted by the kotlinx-coroutines-debug agent or when running with `-Dkotlinx.coroutines.debug` flag. This marker helps you understand that the stack trace crossed a coroutine boundary - the frames above it belong to one coroutine context, and the frames below it belong to another.

This becomes especially useful in complex async scenarios where multiple coroutines interact. You can quickly identify where execution jumped between different coroutine contexts by looking for these boundary markers. Combined with `CopyableThrowable`, these markers give you a complete map of how the exception traveled through your async code.

Note that you only see these markers when running with coroutine debugging enabled. In production without debugging enabled, the stack traces are cleaner but you lose this extra context about boundary crossings.

**Performance impact**: Enabling coroutine debugging has significant overhead. The kotlinx-coroutines-debug agent instruments every coroutine to capture creation stack traces, track coroutine hierarchies, and inject boundary markers. This can slow down your application considerably - not just exception handling, but all coroutine operations. Use it during development and debugging, but never in production. The good news is that `CopyableThrowable` itself has minimal overhead - the copying only happens when exceptions actually cross boundaries, and no special debugging mode is needed for it to work.

## When to Use CopyableThrowable

Not every exception needs to implement `CopyableThrowable`. Use it when your exception crosses coroutine or thread boundaries and debugging context is important.

Perfect candidates are domain exceptions that represent business-level failures. A `PaymentProcessingException` that might be thrown in a background worker and awaited by a request handler should be copyable. You want to know both which payment processing step failed and which user request was waiting for it.

Infrastructure exceptions that propagate across service boundaries also benefit. An exception representing a failed database query, a timeout connecting to an external API, or a deserialization error should preserve the full context when crossing thread boundaries.

Don't bother with `CopyableThrowable` for purely local exceptions that stay within one coroutine scope. If an exception is thrown and caught in the same suspend function, there's no thread boundary crossing and no lost stack traces. The extra interface just adds noise.

Also skip it for exceptions that are part of normal control flow. If you're using exceptions for non-exceptional cases (which itself is debatable), making them copyable adds overhead with no debugging benefit.

## Where CopyableThrowable Works

The `CopyableThrowable` mechanism isn't limited to `CompletableDeferred`. It works across all coroutine primitives that transfer exceptions between contexts:

- **async/await** - when a coroutine launched with `async` fails and you call `await()` on its `Deferred`
- **Channel** - when an exception is thrown in a producer coroutine and caught by the consumer
- **Flow** - when an exception is thrown in the flow producer and caught during collection in another coroutine

Any place where coroutines machinery catches an exception in one coroutine context and rethrows it in another, `CopyableThrowable` gets a chance to preserve both stack traces. This typically happens when exceptions cross suspension points that involve different coroutine scopes or when they're stored and retrieved later (like with `Deferred`).

## Built-in Copyable Exceptions

Several standard exceptions from kotlinx.coroutines already implement `CopyableThrowable`:

- **CancellationException** and its subclasses - these are copyable by default since cancellation often crosses coroutine boundaries
- **TimeoutCancellationException** - thrown by `withTimeout` and copyable to preserve both the timeout point and where it was awaited

But most exceptions from the standard library and JDK don't implement `CopyableThrowable`. A regular `IOException`, `SQLException`, or `IllegalArgumentException` won't automatically preserve stack traces across boundaries.

## Handling Non-Copyable Exceptions

When you catch a JDK exception or third-party exception that doesn't implement `CopyableThrowable`, you have several options:

The cleanest approach is wrapping it in your domain exception that is copyable:

```kotlin
class DataProcessor {
    private val result = CompletableDeferred<String>()

    fun startProcessing() {
        thread(name = "worker-thread") {
            try {
                val data = fetchDataFromSource()
                result.complete(data)
            } catch (e: SQLException) {
                // Wrap JDK exception in copyable domain exception
                result.completeExceptionally(
                    DataFetchException("Database error", e)
                )
            } catch (e: IOException) {
                result.completeExceptionally(
                    DataFetchException("Network error", e)
                )
            }
        }
    }
}
```

This gives you the benefits of `CopyableThrowable` while translating infrastructure exceptions into domain terms. The original `SQLException` is preserved as the cause, and the `DataFetchException` provides the cross-boundary stack trace.

If you need to preserve the exception hierarchy to satisfy existing catch blocks, you can create copyable wrappers that extend specific JDK exception types:

```kotlin
class CopyableIOException(
    message: String?,
    cause: Throwable? = null
) : IOException(message, cause), CopyableThrowable<CopyableIOException> {

    override fun createCopy(): CopyableIOException {
        return CopyableIOException(message, this)
    }
}

// Usage
try {
    val data = readFromNetwork()
    result.complete(data)
} catch (e: IOException) {
    result.completeExceptionally(
        CopyableIOException(e.message, e)
    )
}
```

This approach works when existing code catches specific exception types. `CopyableIOException` extends `IOException`, so `catch (e: IOException)` blocks work unchanged while you get the benefits of preserved stack traces across boundaries. The original exception is still accessible as the cause.

For exceptions you don't control and can't wrap (like from third-party libraries deep in the call stack), the stack trace loss is unavoidable. In these cases, consider adding structured logging at the boundary where you know the exception might cross threads. Log the full stack trace before completing the deferred, so you have both traces in your logs even if the exception object only carries one.

## Comparison with Exception Wrapping

The difference between `CopyableThrowable` and manual wrapping becomes clear in exception handling code:

```kotlin
// With manual wrapping
try {
    processor.awaitResult()
} catch (e: IllegalStateException) {
    // Is this our wrapper or a real IllegalStateException?
    when (val cause = e.cause) {
        is DataFetchException -> handleDataError(cause)
        is NetworkException -> handleNetworkError(cause)
        else -> handleUnknownError(e)
    }
}

// With CopyableThrowable
try {
    processor.awaitResult()
} catch (e: DataFetchException) {
    // The actual exception type is preserved
    handleDataError(e)
} catch (e: NetworkException) {
    handleNetworkError(e)
}
```

With manual wrapping, you catch generic wrapper exceptions and inspect causes. With `CopyableThrowable`, you catch the actual exception types. The exception semantics are preserved while the stack traces are enhanced. This makes exception handling cleaner and more type-safe.

## Conclusion

Stack traces across coroutine boundaries are essential for debugging distributed async operations. Manual exception wrapping works but creates boilerplate and obscures exception types. `CopyableThrowable` provides a clean framework-level solution that preserves both stack traces and exception semantics.

When you define domain exceptions that cross coroutine boundaries, implementing `CopyableThrowable` is a small investment that pays off the first time you need to debug a production issue. The complete stack trace with both throwing and awaiting contexts can mean the difference between quickly identifying a problem and spending hours trying to reproduce it.

Is your team's exception handling strategy ready for debugging async failures, or are you still losing critical stack traces at coroutine boundaries?
