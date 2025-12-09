---
title: "When NOT to Use suspend in Kotlin: The Reporting API Anti-Pattern"
date: 2025-12-01
tags: ["kotlin", "coroutines", "performance", "architecture"]
draft: false
canonicalURL: "https://nerzhulart.github.io/blog/suspend-function-antipattern/"
---

When you start working with Kotlin coroutines, there's a temptation to mark everything as `suspend`. After all, suspend functions compose nicely, the compiler helps you, and it feels like you're writing "proper async code". And while `suspend` may mean "asynchronous", but "asynchronous" doesn't mean "cheap to call repeatedly". It means "this function can suspend execution and create backpressure". Using it incorrectly creates performance problems that are hard to spot until they hit production.

This article explores a common anti-pattern: using `suspend` for fire-and-forget APIs where it creates unwanted backpressure and hidden performance costs.

## The Problem: Suspend All The Things

Consider a typical change tracking system. You have file changes happening, and you want to collect them for later processing:

```kotlin
// Anti-pattern: reporting API is suspend
interface FileChangesListener {
    suspend fun fileChanged(file: Path)
    suspend fun filesChanged(files: List<Path>)
}

class FileChangeTracker(
    private val diffCollector: ChangeCollector
) : FileChangesListener {

    override suspend fun fileChanged(file: Path) {
        val group = createFileChange(file)
        diffCollector.addChanges(group)
    }

    override suspend fun filesChanged(files: List<Path>) {
        files.forEach { fileChanged(it) }
    }
}

// The collector is also suspend
interface ChangeCollector {
    suspend fun addChanges(group: FileChange)
}

class ChangeCollectorImpl : ChangeCollector {
    private val _changes = MutableStateFlow<List<FileChange>>(emptyList())
    private val _state = MutableStateFlow(ProcessingState.IDLE)

    override suspend fun addChanges(group: FileChange) {
        _changes.emit(_changes.value + group)  // suspend
        _state.emit(ProcessingState.PROCESSING)  // suspend

        invalidateChanges()  // suspend - but does heavy work!
    }

    private suspend fun invalidateChanges() {
        // Expensive O(n) work on every call
        val allChanges = _changes.value
        for (change in allChanges) {
            computeDiff(change)        // CPU-intensive
            updateIndex(change)        // more work
            notifySubscribers(change)  // even more work
        }
    }
}
```

At first glance this looks reasonable. Functions are `suspend`, they compose, everything compiles. But there's a critical performance bug hidden in the design.

When you call `filesChanged(listOf(file1, file2, file3, ...))`, here's what happens:
- `fileChanged(file1)` is called, which calls `addChanges(group1)`
- `addChanges` emits to the flow (instant) and calls `invalidateChanges()`
- `invalidateChanges` scans ALL changes and does expensive work for each
- Only after this completes, we move to `fileChanged(file2)`
- `addChanges` runs again, `invalidateChanges` scans ALL changes again (now including file1)
- This repeats for every file

If you add 100 files, you do O(n²) work. Adding one file triggers full analysis of all files. The next file triggers analysis of all files plus the previous one. The third file analyzes all three. The cost grows quadratically.

The worst part is that `suspend` creates the illusion that this is fine. The function signature says "I might suspend", which developers interpret as "this won't block threads, so it's safe to call many times". But `suspend` doesn't mean "won't block" or "cheap". It means the function CAN suspend, but in this case `invalidateChanges()` does expensive CPU-intensive computation before any actual suspension. You're not blocking a thread, but you're blocking the coroutine. And because the entire call chain is suspend, every caller up the stack suspends and waits for this expensive computation to finish.

When you read the implementation carefully, the problem becomes obvious. Let's look at what `addChanges()` actually does:
- Updates `_changes` flow - even if you call `emit()`, `MutableStateFlow.emit()` returns instantly, it's equivalent to assignment. The "proper" flow API is an illusion here.
- Updates `_state` flow - same thing, instant return
- `invalidateChanges()` - does expensive computation synchronously

The flow emissions don't need `suspend` - `MutableStateFlow.emit()` never actually suspends, so you can use `tryEmit()` or direct assignment instead. But what about `invalidateChanges()`? This is where thinking about data flow upfront pays off.

If we're already using `StateFlow` to hold changes, we have a reactive stream. Instead of processing on every add, we can:

1. **Process on demand**: When someone actually needs the results (via some API), run invalidation then. The caller who needs the result waits; reporters don't.

2. **Process reactively with cancellation**: Collect the changes flow in a background coroutine using `collectLatest`. When new changes arrive faster than processing completes, the previous `invalidateChanges()` gets cancelled immediately - no wasted CPU on stale data:

```kotlin
scope.launch {
    _changes.collectLatest { changes ->
        invalidateChanges(changes)  // cancelled if new changes arrive
    }
}
```

Either approach removes the need for `suspend` in `addChanges()`. Reporters just update the flow and return immediately. Processing happens elsewhere, on its own schedule, with proper cancellation support.

The `suspend` modifier in the original code serves no purpose except to create unwanted backpressure. Every caller waits for expensive processing they don't care about. The signature says `suspend fun addChanges()` but should have been `fun addChanges()` from the start.

## Why This Happens: The suspend Temptation

When you mark a fire-and-forget API as `suspend`, you give the implementation too much freedom. The developer implementing it sees `suspend` and thinks "I can call any suspend functions I want". And they do - without thinking whether those calls are necessary or what their cost is.

The same O(n²) problem can happen in synchronous code, of course. But with `suspend`, there's an illusion that it doesn't matter because "everything is asynchronous anyway". The developer calls `invalidateChanges()` on every add, thinking "it's suspend, so it won't block anything important". But the caller still waits. The coroutine still executes sequentially. The quadratic cost is still there.

Even if `invalidateChanges()` doesn't do heavy computation before suspending - even if it honestly suspends immediately on I/O or network calls - the problem remains. The caller waits for that I/O. Call it 100 times, wait for 100 I/O operations sequentially. The `suspend` modifier doesn't make the wait disappear; it just moves where the wait happens.

The `suspend` keyword creates a false sense of safety. Developers see it and think "this is async, so performance doesn't matter here". But async doesn't mean free. The caller still pays the cost of waiting for whatever the implementation decides to do.

## The Trade-off: Backpressure vs Memory

When designing fire-and-forget APIs, you face a fundamental choice: **backpressure** or **buffering**.

**Backpressure** is a flow control mechanism where a slow consumer signals to a fast producer to slow down. With `suspend`, this happens naturally - the caller waits until the operation completes. If processing is slow, the caller slows down too. This protects you from overwhelming the system, but it slows down the producer (potentially freezing the UI).

**Buffering** means accepting events without waiting and storing them for later processing. This keeps the producer fast, but if events arrive faster than you can process them, the buffer grows. Without limits, you risk OOM.

A `suspend` function that returns `Unit` is still returning something meaningful: **completion**. The caller waits for that completion. Remove `suspend`, and you remove that waiting - but now you need another strategy to handle the flood.

When you make a fire-and-forget API non-suspend, you must think about what happens when events arrive faster than processing:

- **Bounded buffer with drop**: Use a channel with `BufferOverflow.DROP_OLDEST` or `DROP_LATEST`. Events get lost, but memory stays bounded.
- **Conflation**: Use `Channel.CONFLATED` or `MutableStateFlow`. Only the latest value matters; intermediate values are discarded.
- **Merging**: Combine multiple events into one. Instead of processing each file change separately, batch them into groups.
- **Sampling**: Process only every Nth event, or one event per time window.

The question isn't just "should this be suspend?" It's "what happens under load?"

```kotlin
// Backpressure: caller waits if buffer full
val channel = Channel<FileChange>(capacity = 10)
channel.send(change)  // suspends when full

// Drop oldest: never blocks, may lose events
val channel = Channel<FileChange>(10, BufferOverflow.DROP_OLDEST)
channel.trySend(change)  // always succeeds, drops old if full

// Conflation: only latest matters
val state = MutableStateFlow<List<FileChange>>(emptyList())
state.value = state.value + change  // overwrites, never blocks
```

The right choice depends on your domain. Can you afford to lose events? Can you afford to slow down the producer? Can you merge events without losing information? There's no universal answer, but you must ask the question.

## The Correct Approach: Separate Reporting from Processing

Reporting APIs should be regular functions. Processing should happen in batches or with debouncing:

```kotlin
// Good: reporting API is a regular function
interface FileChangesListener {
    fun fileChanged(file: Path)
    fun filesChanged(files: List<Path>)
}

class FileChangeTracker(
    private val diffCollector: ChangeCollector
) : FileChangesListener {

    override fun fileChanged(file: Path) {
        val group = createFileChange(file)
        diffCollector.addChanges(group)
    }

    override fun filesChanged(files: List<Path>) {
        files.forEach { fileChanged(it) }
    }
}

// The collector uses regular functions for adding, processes in batches
interface ChangeCollector {
    fun addChanges(group: FileChange)
}

class ChangeCollectorImpl(
    private val scope: CoroutineScope
) : ChangeCollector {
    private val _changes = MutableStateFlow<List<FileChange>>(emptyList())
    private val _state = MutableStateFlow(ProcessingState.IDLE)

    init {
        // Process changes reactively - cancels previous processing if new changes arrive
        scope.launch {
            _changes.collectLatest { changes ->
                if (changes.isNotEmpty()) {
                    _state.value = ProcessingState.PROCESSING
                    invalidateChanges(changes)
                    _state.value = ProcessingState.IDLE
                }
            }
        }
    }

    override fun addChanges(group: FileChange) {
        _changes.value = _changes.value + group
    }

    private suspend fun invalidateChanges(changes: List<FileChange>) {
        for (change in changes) {
            ensureActive()  // check for cancellation
            computeDiff(change)
            updateIndex(change)
        }
    }
}
```

Now when you call `filesChanged(listOf(file1, file2, file3, ...))`, here's what happens:
- `fileChanged(file1)` updates the flow, `collectLatest` starts `invalidateChanges()`
- `fileChanged(file2)` updates the flow, `collectLatest` **cancels** the previous processing and restarts
- `fileChanged(file3)` updates the flow, cancels again and restarts
- All `addChanges()` calls return immediately - no waiting
- Only the final `invalidateChanges()` runs to completion with all three files

The `ensureActive()` check inside `invalidateChanges()` cooperates with cancellation - when new changes arrive, the in-progress loop exits early instead of wasting CPU on stale data.

The complexity drops from O(n²) to O(n). Adding 100 files triggers one analysis of 100 files, not 100 analyses.

The key insight is separating the reporting interface from the processing implementation. Callers report changes through a regular function. The implementation batches them and processes asynchronously on its own schedule.

## Alternative: Explicit Batch Processing

If you have a natural batch boundary, you can make it explicit. The caller controls when processing happens and gets the result:

```kotlin
interface ChangeCollector {
    fun addChanges(change: FileChange)
    suspend fun processBatch(): ProcessingResult
}

// Note: real implementation should be thread-safe, this one only demonstrates the domain logic
class ChangeCollectorImpl : ChangeCollector {
    private val pendingChanges = mutableListOf<FileChange>()

    override fun addChanges(change: FileChange) {
        pendingChanges.add(change)
    }

    override suspend fun processBatch(): ProcessingResult {
        if (pendingChanges.isEmpty()) return ProcessingResult.empty()

        val batch = pendingChanges.toList()
        pendingChanges.clear()

        return analyzeChanges(batch)
    }
}

// Usage
suspend fun filesChanged(files: List<Path>): ProcessingResult {
    files.forEach { file ->
        collector.addChanges(createFileChange(file))
    }
    return collector.processBatch()  // caller waits and gets result
}
```

This makes the batch boundary explicit. Adding is fast and non-blocking. Processing is `suspend` because the caller genuinely needs the result - and that's a valid reason for `suspend`.

This approach works when you have clear batch boundaries - user actions, transaction boundaries, or request handling. For continuous streams without obvious batches, debouncing is cleaner.

## When to Use suspend

Use `suspend` when you need one of these:

**You need the result**: The caller must wait for the operation to complete because they need the return value. Database queries, network calls, file reads - the caller can't proceed without the data.

**You need backpressure**: You want to slow down callers when processing can't keep up. A bounded channel that suspends on `send()` when full. A rate-limited API client that suspends when you hit the limit. The suspension is the feature, not a side effect.

**You're waiting for completion**: The caller needs to know when an async operation finishes, even if there's no return value. Waiting for a job to complete, waiting for a transaction to commit, waiting for a file to be written.

You likely don't need `suspend` for:

**Fire-and-forget operations**: Reporting events, logging, sending notifications. The caller doesn't care when or if processing happens. Use regular functions.

**Simple data operations**: Adding to a list, emitting to `StateFlow`, putting in a map. These operations complete immediately. Making them `suspend` just because you might process the data later is wrong. `MutableStateFlow.value = x` never suspends. `MutableStateFlow.tryEmit(x)` never suspends. If you need `emit()` which can suspend, ask yourself if you really need backpressure or if `tryEmit()` would work.

**Calling suspend functions without waiting**: If you're just launching a coroutine or updating a `MutableStateFlow`, you're not actually suspending. The function returns immediately. Don't make it `suspend`. For `MutableStateFlow`, prefer direct assignment (`value = x`) over `emit()` - it's clearer and doesn't require a coroutine context. For channels, be careful: `trySend()` can fail and drop your event if the buffer is full. Either check the result and handle failures (at least log them), or make sure your buffer strategy (`CONFLATED`, `DROP_OLDEST`, unlimited) is intentional and you understand the trade-offs.

## Conclusion

Before adding `suspend` to a function, ask: does the caller need to wait? Does the caller need the result? Do I need backpressure here?

If the answer is no, don't use `suspend`. Fire-and-forget APIs should be regular functions. Let the implementation handle async processing without forcing callers to participate.

When you see a codebase where everything is `suspend`, ask why. Does each function genuinely need to suspend the caller, or is it just "we're using coroutines and it compiles"?
