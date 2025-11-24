---
title: "Non-Domain Primitive Orphaning in Kotlin: Why Context Loss Breaks Your Architecture"
date: 2025-11-24
tags: ["kotlin", "coroutines", "architecture", "patterns"]
draft: false
canonicalURL: "https://nerzhulart.github.io/blog/primitive-orphaning-kotlin/"
---

In Kotlin's concurrent programming ecosystem, `Channel` and `Flow` are powerful high-level primitives for communication between coroutines. However, despite being sophisticated abstractions in their own right, they remain primitives when viewed from the perspective of domain modeling. I've observed a recurring anti-pattern that makes code harder to understand and maintain: **passing channels and flows as standalone parameters through multiple layers of abstraction**.

This article explores why treating channels and flows as independent entities, rather than as properties of domain objects, leads to architectural problems and how to fix it.

**Note on examples**: The code examples in this article are deliberately simplified and abstracted to clearly demonstrate the architectural problems. Real production code would have additional complexity, error handling, and business logic that isn't shown here for clarity.

## The Anti-Pattern: Channel and Flow Orphaning

### The Channel Example

Consider this common scenario - you have an event processing system where events are published to a channel:

```kotlin
class EventBus {
    private val _events = Channel<Event>(Channel.UNLIMITED)
    val events: ReceiveChannel<Event> = _events
    
    fun publishEvent(event: Event) {
        _events.trySend(event)
    }
}
```

The anti-pattern emerges when this channel starts traveling through your codebase as a standalone parameter:

```kotlin
// Anti-pattern: Channel gets passed around independently
class EventService(private val eventChannel: ReceiveChannel<Event>) {
    
    fun processEvents() {
        // Channel is now disconnected from its owner
        eventHandler.handleEvents(eventChannel)
    }
}

class EventHandler {
    fun handleEvents(channel: ReceiveChannel<Event>) {
        // Channel gets passed deeper
        eventLogger.logEvents(channel)
        eventAnalyzer.analyzeEvents(channel)
    }
}

class EventLogger(private val storedChannel: ReceiveChannel<Event>) {
    // Channel is stored as a field, completely divorced from its origin
    
    suspend fun start() {
        for (event in storedChannel) {
            log("Event received: $event")
        }
    }
}
```

The same anti-pattern applies to `Flow` - passing flows as standalone parameters suffers from identical ownership and context problems. But let's examine an even more complex case with nested flows like `StateFlow<Flow<Event>>` to illustrate how it completely obscures domain relationships:

```kotlin
// Anti-pattern: Nested flows hide domain relationships
class EventBusManager {
    // What does this actually represent?
    val activeBus: StateFlow<Flow<Event>> = /* ... */
}

class EventProcessor(private val nestedEvents: StateFlow<Flow<Event>>) {
    suspend fun process() {
        nestedEvents.collect { eventFlow ->  // What is eventFlow? Where does it come from?
            eventFlow.collect { event ->      // Lost all semantic context
                processEvent(event)
            }
        }
    }
}

// Usage - completely unclear what's happening
val processor = EventProcessor(eventBusManager.activeBus)
```

The problems are severe: **complete semantic loss** - `StateFlow<Flow<Event>>` tells you nothing about domain relationships. **You lose access to owner properties** - no way to get context like bus names or call lifecycle methods.

## Why This Matters: Core Problems with Stream Orphaning

### 1. Lost Context and Ownership

When you encounter `ReceiveChannel` or `Flow` parameters deep in your code, several critical questions become impossible to answer:

- Who created this channel/flow?
- Who owns it?
- What does it represent in the domain model?

```kotlin
class SomeDeepClass(private val mysteriousChannel: Channel<Event>) {
    // Where does this channel come from? 
    // Is it shared with other components?
    // What happens if I close it?
    
    suspend fun doSomething() {
        // Should I close this channel when done? Who knows!
        // DANGER: Calling close() or cancel() may kill the producer
        // and break other consumers sharing this channel
        mysteriousChannel.close() // This could be catastrophic!
    }
}

class AnotherDeepClass(private val mysteriousFlow: Flow<User>) {
    // Even worse questions for flows:
    // Is this flow hot or cold?
    // Does it replay previous values?
    // How many subscribers can it handle?
    // What triggers emissions?
    
    suspend fun doSomething() {
        mysteriousFlow.collect { user ->
            // If this throws an exception, what happens to other collectors?
            // Should I handle backpressure? Is it even possible here?
            processUser(user)
        }
    }
}
```

### 2. Refactoring Becomes Nearly Impossible

One of the most significant problems with orphaned reactive streams is that they make refactoring extremely difficult and dangerous. This is particularly problematic because channels, flows, and similar streaming primitives are sophisticated, high-level constructs with complex behavioral semantics (buffering, backpressure, hot/cold behavior, lifecycle management) - yet they remain primitives, not domain entities. This combination makes refactoring even more treacherous: when you encounter a streaming parameter deep in your codebase, you must understand not only its domain relationships but also its intricate behavioral contracts:

```kotlin
class SomeComplexService(private val channel: ReceiveChannel<Event>) {
    // You want to refactor this class, but critical questions remain unanswered:
    // - Can I change the channel type? Will it break the producer?
    // - Can I add buffering? Does the producer expect unbuffered behavior?
    // - Can I close this channel when done? Who else might be reading?
    // - If I need to add error handling, where should exceptions propagate?
    
    suspend fun processData() {
        // Any change here might break unknown parts of the system
        for (event in channel) {
            // What if I need to change how events are processed?
            // I don't know what assumptions the producer makes
        }
    }
}
```

The fundamental refactoring problem is **unclear ownership**. When you encounter an orphaned channel parameter, you face a critical question: does this class own the channel, or does it belong to someone else?

```kotlin
// Refactoring dilemma: who owns this channel?
class EventProcessor(private val eventChannel: ReceiveChannel<Event>) {
    // CRITICAL QUESTIONS during refactoring:
    // 1. Does this class OWN the channel? 
    //    - If YES: I can move the channel creation inside the class
    //    - If NO: I must keep it as a dependency
    // 2. Is this channel created specifically for this class instance?
    //    - If YES: Safe to internalize and change its configuration  
    //    - If NO: Other consumers might depend on current behavior
    // 
    // These questions require studying much more code because 
    // the low-level primitive has "leaked" through abstraction boundaries
}
```

This is exactly like exposing a private or internal `ConcurrentMap` property from any manager-like class - it's obvious that encapsulation is broken. The low-level implementation detail (the channel) has leaked through architectural boundaries, forcing you to understand the entire ownership graph before making any changes.

**The archaeology problem**: To safely refactor, you must trace back through potentially many layers of code to discover who created the channel, how it's configured, and what other components depend on it. This detective work is entirely unnecessary when domain objects properly encapsulate their reactive streams.

### 3. Semantic Drift and Naming Confusion

When channels are passed through multiple layers, their parameter names often change, creating semantic drift that obscures their true meaning:

```kotlin
class EventBus {
    private val _events = Channel<Event>(Channel.UNLIMITED)
    // Clear name: "events"
    val events: ReceiveChannel<Event> = _events
}

// As the channel gets passed deeper, names start to drift:
class EventService(
    // Still reasonable: "eventChannel" 
    private val eventChannel: ReceiveChannel<Event>
) {
    fun processEvents() {
        eventHandler.handleEvents(eventChannel)
    }
}

class EventHandler {
    // Generic: "channel" - lost semantic meaning
    fun handleEvents(channel: ReceiveChannel<Event>) {
        eventLogger.logEvents(channel)
        eventAnalyzer.analyzeEvents(channel)
    }
}

class EventLogger(
    // Confusing: "inputStream" - completely different semantics!
    private val inputStream: ReceiveChannel<Event>
) {
    suspend fun start() {
        for (event in inputStream) {
            log("Received: $event")
        }
    }
}

class EventAnalyzer {
    // Even worse: "dataSource" - generic and meaningless
    suspend fun analyzeEvents(dataSource: ReceiveChannel<Event>) {
        for (event in dataSource) {
            analyze(event)
        }
    }
}
```

**The deeper the channel travels, the more its name changes:**
- `events` → `eventChannel` → `channel` → `inputStream` → `dataSource` → `input` → `data`

This creates a "broken telephone" effect where each layer introduces its own interpretation, gradually eroding the original semantic meaning until it becomes impossible to understand what the parameter represents.

### A Radical Analogy: Collection Primitives

To illustrate the broader problem of stream orphaning, imagine if we applied the same logic to collections and passed their primitive components instead of the collections themselves:

```kotlin
// Absurd: Passing collection internals instead of collection
class ListProcessor {
    fun processItems(
        items: Array<String>,    // Internal storage
        size: Int,              // Collection size
        capacity: Int           // Collection capacity
    ) {
        // Lost all connection to the original List
        // No clear ownership or lifecycle management
        for (i in 0 until size) {
            process(items[i])
        }
    }
}

// Sensible: Passing the collection itself
class ListProcessor {
    fun processItems(list: List<String>) {
        // Clear ownership: list manages its own internals
        for (item in list) {
            process(item)
        }
    }
}
```

The same principle applies to channels - they are implementation details of domain objects, not independent entities that should be passed around freely.

## The Solution: Preserve Ownership Context

Now let's return to our examples and see how to implement them correctly. The key principle is simple: instead of passing orphaned channels and flows, pass the owning domain object and access streams through it.

### Solution: Pass Domain Objects, Not Raw Streams

```kotlin
// Good: Pass the owner, access channel through it
class EventService(private val eventBus: EventBus) {
    
    fun processEvents() {
        // Channel access is contextual
        eventHandler.handleEvents(eventBus)
    }
}

class EventHandler {
    fun handleEvents(eventBus: EventBus) {
        // Clear ownership: eventBus owns the events channel
        eventLogger.logEvents(eventBus)
        eventAnalyzer.analyzeEvents(eventBus)
    }
}

class EventLogger {
    suspend fun logEvents(eventBus: EventBus) {
        // Channel access is explicit and contextual
        for (event in eventBus.events) {
            log("Event from ${eventBus.name}: $event")
        }
    }
}
```

**Note**: In production code, you should use interfaces for better testability and dependency inversion, but for simplicity in these examples we use concrete classes.

For the nested flow example, instead of `StateFlow<Flow<Event>>` with raw nested streams, we place domain entities in the flow chain - this immediately makes the relationships clear:

```kotlin
// Good: The same EventBusManager, but properly exposing domain objects
class EventBusManager {
    private val _activeBus = MutableStateFlow<EventBus?>(null)
    // Instead of: val activeBus: StateFlow<Flow<Event>>
    // Expose the domain object itself:
    val activeBus: StateFlow<EventBus?> = _activeBus.asStateFlow()
    
    fun setActiveBus(eventBus: EventBus) {
        _activeBus.value = eventBus
    }
}

class EventBus(val name: String) {
    private val _events = MutableSharedFlow<Event>()
    val events: SharedFlow<Event> = _events.asSharedFlow()
    
    fun publishEvent(event: Event) {
        _events.tryEmit(event)
    }
}

// Good: Keep domain relationships explicit
class EventProcessor(private val eventBusManager: EventBusManager) {
    suspend fun process() {
        // Track the currently active bus (domain object)
        eventBusManager.activeBus.collectLatest { bus ->
            if (bus != null) {
                // Access the stream through the domain object
                bus.events.collect { event ->
                    // Clear context: we know which bus produced this event
                    processEvent(event, fromBus = bus.name)
                }
            }
        }
    }
}
```

**Key insight**: `Flow<Flow<T>>` and similar nested structures are almost always code smells indicating that you've flattened away important domain relationships. When you see nested reactive types, it usually means the "outer flow" represents changing ownership or changing sources of the "inner flow" data - exactly the kind of domain relationships you should preserve explicitly rather than hide behind generic type abstractions.

### Additional Benefits: Code Discovery and Documentation

There's another crucial advantage that's often overlooked: **discoverability of semantics and ownership**. When you see an `EventBus` parameter, you can immediately navigate to its definition to understand both the signal's semantics and its lifecycle management. The documentation for `EventBus` explains not just what events it carries, but how it manages them, when they're emitted, and what the lifecycle guarantees are.

More importantly, the reader understands that `EventBus` is the actual owner and creator of the signal. The ownership code is concentrated in one place - the `EventBus` class - making it easy to understand how the channel is created, configured, and managed. You don't need to trace through a complex chain of method calls to figure out where the channel came from.

In contrast, when signals flow "anonymously" as raw channels or flows, discovering their origin becomes an archaeological exercise. You have to trace backwards through multiple layers of method calls, trying to figure out who created the channel, what its configuration is, and who's responsible for its lifecycle. The ownership and creation logic is scattered across the call chain rather than concentrated in a single, discoverable location.

This concentration of ownership also makes the code more self-documenting. Instead of having channel management logic spread across multiple classes, it's centralized in the owner type, making it easier for new team members to understand the system's architecture.

**Domain types as semantic anchors**: Even when parameter names drift across layers, domain types like `EventBus` provide crucial semantic context that generic types like `ReceiveChannel<Event>` completely lack. The domain type immediately communicates purpose, expected behavior, and available operations, while the primitive type provides no domain context whatsoever.

## Related Problem: Exposing Mutable Interfaces

Let's examine another closely related problem: exposing mutable interfaces for channels and flows instead of read-only ones.

### The Anti-Pattern: Mutable Interface Exposure

```kotlin
// Anti-pattern: Exposing mutable state
class EventBus {
    val events: Channel<Event>  // Full Channel - can read AND write
    val currentState: MutableStateFlow<State>  // Mutable - external code can modify
}

// External code can bypass domain logic:
class SomeConsumer(private val eventBus: EventBus) {
    fun doSomething() {
        eventBus.currentState.value = corruptedState  // Bypassed validation!
        eventBus.events.send(maliciousEvent)  // Should this be allowed?
    }
}
```

**Problems**: External code can modify state directly, bypassing business logic, validation, and proper lifecycle management.

**The solution is simple: expose read-only interfaces** and control mutation through domain methods:

```kotlin
// Good: Read-only interfaces with controlled mutation
class EventBus {
    private val _events = Channel<Event>()
    private val _currentState = MutableStateFlow<State>(initialState)
    
    // Expose read-only interfaces
    val events: ReceiveChannel<Event> = _events
    val currentState: StateFlow<State> = _currentState.asStateFlow()
    
    // Controlled mutation through domain methods
    fun publishEvent(event: Event) {
        _events.trySend(event)
    }
    
    fun updateState(newState: State) {
        if (isValidTransition(newState)) {
            _currentState.value = newState
        }
    }
}
```

**Key point about `asStateFlow()`**: This method creates a true read-only wrapper that cannot be cast back to `MutableStateFlow`. Simply exposing `MutableStateFlow` as `StateFlow` interface still allows casting:

```kotlin
// Dangerous - can be cast back:
val state: StateFlow<T> = mutableStateFlow  // BAD

// Safe - cannot be cast back:  
val state: StateFlow<T> = mutableStateFlow.asStateFlow()  // GOOD
```

The principle is simple: expose reactive streams through read-only interfaces and control mutation through domain methods.



## When Channel Passing Might Be Acceptable

This isn't an iron-clad rule. There are several scenarios where passing channels directly is perfectly reasonable and doesn't cause the problems we've discussed.

Passing channels to **private methods within the same class** is completely fine. The channel doesn't "leak" beyond the class that owns it, so the ownership context remains crystal clear. You're essentially passing implementation details between different parts of the same component.

If you're building a complex system with multiple internal classes that work together closely, they can safely share channels when they're part of a **cohesive, controlled environment**. When their relationships are well-defined within your module or package, the ownership context is clear from the system design.

Framework utilities and **simple flow transformations** are fine because **ownership passes through cleanly** - the caller retains ownership, and the utility just transforms data without obscuring relationships.

At the same abstraction level, immediate producer-consumer relationships work well. When a producer creates a channel specifically for a consumer, and the handoff happens at the same architectural layer, **ownership transfer is clear and immediate**.

The real problem emerges when channels cross **architectural boundaries** - between different layers, modules, or domains where ownership and context can be lost. Within controlled scopes like private methods, internal APIs, or framework utilities, the risks are completely manageable.

## Conclusion

Stream orphaning mirrors broader design principles about abstraction and ownership. Just as we wouldn't pass individual array elements and size counters instead of collections, we shouldn't pass reactive streams independently of their domain context.

The solution is straightforward: **treat channels and flows as implementation details** of domain objects, not as independent entities. Pass the objects that own reactive streams, access streams through their owners, and keep lifecycle management where it belongs. Design your APIs to make ownership clear from the call site, and use raw stream passing sparingly - only for framework code or direct producer-consumer relationships.

When you preserve these ownership relationships, you get code that's more maintainable, safely refactorable, and semantically consistent. Names don't drift across layers, debugging becomes easier with preserved context, and testing feels natural because object boundaries make sense.

While we've focused on Kotlin's Channel and Flow throughout this article, these principles apply broadly across languages and frameworks. Whether you're working with reactive streams, async primitives, or similar constructs in other ecosystems, the core insight remains the same: preserve ownership context and avoid orphaning your communication mechanisms.

Remember: channels and flows are communication mechanisms, not business entities. Keep them tied to the domain objects they serve, and your concurrent code will be much more comprehensible and maintainable.

The next time you see a `ReceiveChannel<T>` or `Flow<T>` parameter traveling through multiple layers of your application, ask yourself: "What domain object owns this channel/flow, and shouldn't I be passing that instead?"