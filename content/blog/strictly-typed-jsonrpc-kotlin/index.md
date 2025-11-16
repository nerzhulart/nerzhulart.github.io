---
title: "Strictly Typed JSON-RPC in Kotlin"
date: 2025-11-16
tags: ["kotlin", "jsonrpc", "sdk"]
draft: false
---

Throughout my career, I've primarily worked with two main programming languages - C# and Kotlin (plus some Java). Both languages provide quite flexible and powerful support for Generic types, and I've always been drawn to maximizing these features to make code more convenient, universal, and safer, while maintaining good readability and maintainability.

Recently, I started developing a Kotlin SDK for the [Agent Client Protocol](https://agentclientprotocol.com/). To put it briefly, this protocol is designed for universal integration of various AI coding agents into any IDE, such as the [Zed editor](https://zed.dev/) (whose authors created this protocol) or our JetBrains IDE family.

The protocol closely resembles the [Model Context Protocol](https://modelcontextprotocol.io/) in structure and entities, and like MCP, it's based on the [JsonRPC specification](https://www.jsonrpc.org/specification).

In this article, I'll walk through the evolution from simple string-based JsonRPC method calls to a sophisticated type-safe system. We'll start with naive implementations that work but are error-prone, explore the problems they create, and then build up a solution using Kotlin's powerful type system. By the end, you'll see how generic types, upper bounds, and extension functions can transform a brittle API into an elegant, compile-time safe interface that eliminates entire classes of runtime errors.

## What is JsonRPC?

JsonRPC is a low-level protocol for connecting interacting parties, which can be used to create higher-level domain protocols. As the name suggests, it uses JSON as the data format. The main entities in JsonRPC are RPC methods and data objects that are passed to them or returned from them.

There are two types of methods: notification methods and remote procedure call methods with return values. The only difference between them is that notifications don't return any value.

For this article, we'll focus on request-response methods, which form the core of most JsonRPC APIs. The same patterns can be applied to notifications as well.

A request contains the request ID and the method to be called:

JsonRPC request contains required fields `jsonrpc`, `method` and `id`, plus an optional `params` field:

```json
{
  "jsonrpc": "2.0",
  "method": "initialize", 
  "params": {
    "protocolVersion": "1.0.0",
    "clientCapabilities": {}
  },
  "id": 1
}
```

JsonRPC notification is the same request but without the `id` field, meaning the client doesn't expect a response:

```json
{
  "jsonrpc": "2.0",
  "method": "session/cancel",
  "params": {
    "sessionId": "session_123"
  }
}
```

JsonRPC response contains a `result` field on success or `error` field on failure:

```json
{
  "jsonrpc": "2.0", 
  "result": {
    "protocolVersion": "1.0.0",
    "serverCapabilities": {},
    "authMethods": []
  },
  "id": 1
}
```

In practice, domain-specific classes are embedded as payload within JsonRPC messages. For this article, we'll work directly with these domain classes, as the JsonRPC wrapper is handled by the infrastructure:

```kotlin
@Serializable
data class InitializeRequest(
    val protocolVersion: ProtocolVersion,
    val clientCapabilities: ClientCapabilities = ClientCapabilities()
) : AcpRequest

@Serializable
data class InitializeResponse(
    val protocolVersion: ProtocolVersion,
    val serverCapabilities: ServerCapabilities,
    val authMethods: List<AuthMethod>? = null
) : AcpResponse
```

## Naive Implementation: Method Calls by Name

The simplest and most obvious way to organize JsonRPC method calls is to create functions that accept a method name as a string and parameters as JSON:

```kotlin
class SimpleRpcClient {
    suspend fun callMethod(methodName: String, params: JsonElement?): JsonElement {
        // send request and get response
        return sendRequestRaw(methodName, params)
    }
}

// Usage:
val client = SimpleRpcClient()

// Call initialize method
val initRequest = InitializeRequest(
    protocolVersion = "1.0.0", 
    clientCapabilities = ClientCapabilities()
)
val requestJson = Json.encodeToJsonElement(InitializeRequest.serializer(), initRequest)
val responseJson = client.callMethod("initialize", requestJson)
val response = Json.decodeFromJsonElement(InitializeResponse.serializer(), responseJson)

// Call authenticate method
val authRequest = AuthenticateRequest(methodId = "oauth")
val authRequestJson = Json.encodeToJsonElement(AuthenticateRequest.serializer(), authRequest)
val authResponseJson = client.callMethod("authenticate", authRequestJson)
val authResponse = Json.decodeFromJsonElement(AuthenticateResponse.serializer(), authResponseJson)
```

Similarly, setting up handlers for incoming requests looks like this:

```kotlin
class SimpleRpcServer {
    private val handlers = mutableMapOf<String, suspend (JsonElement?) -> JsonElement>()
    
    fun setHandler(methodName: String, handler: suspend (JsonElement?) -> JsonElement) {
        handlers[methodName] = handler
    }
}

// Usage for setting up handlers:
val server = SimpleRpcServer()

// Handler for initialize
server.setHandler("initialize") { paramsJson ->
    val params = Json.decodeFromJsonElement(InitializeRequest.serializer(), paramsJson ?: JsonNull)
    
    // process request
    val response = InitializeResponse(
        protocolVersion = params.protocolVersion,
        serverCapabilities = ServerCapabilities(),
        authMethods = listOf()
    )
    
    Json.encodeToJsonElement(InitializeResponse.serializer(), response)
}

// Handler for authenticate  
server.setHandler("authenticate") { paramsJson ->
    val params = Json.decodeFromJsonElement(AuthenticateRequest.serializer(), paramsJson ?: JsonNull)
    
    val response = AuthenticateResponse(success = true)
    Json.encodeToJsonElement(AuthenticateResponse.serializer(), response)
}
```

## Problems with the Naive Approach

While this approach works, it has several serious drawbacks:

The first problem is **lack of type safety**. It's easy to make mistakes with method names or confuse types:

```kotlin
// Easy to misspell method name
val response = client.callMethod("initilize", params) // typo!

// Easy to confuse types - this compiles but is logically wrong
val initRequest = InitializeRequest(/* ... */)
val responseJson = client.callMethod("authenticate", Json.encodeToJsonElement(InitializeRequest.serializer(), initRequest)) // Wrong request type for authenticate method!
val response = Json.decodeFromJsonElement(AuthenticateResponse.serializer(), responseJson) // Runtime error likely
```

The second problem is **the need to remember correct serializers** for each type:

```kotlin
// Every method requires manually specifying serializers
val initRequest = InitializeRequest(/* ... */)
val requestJson = Json.encodeToJsonElement(InitializeRequest.serializer(), initRequest)

val authRequest = AuthenticateRequest(/* ... */)  
val authRequestJson = Json.encodeToJsonElement(AuthenticateRequest.serializer(), authRequest)

val sessionRequest = NewSessionRequest(/* ... */)
val sessionRequestJson = Json.encodeToJsonElement(NewSessionRequest.serializer(), sessionRequest)


```

The third problem is **lack of connection between request and response**:

```kotlin
// Nothing guarantees we use the correct response type for a specific request
val initRequest = InitializeRequest(/* ... */)
val requestJson = Json.encodeToJsonElement(InitializeRequest.serializer(), initRequest)
val responseJson = client.callMethod("initialize", requestJson)

// Can accidentally use wrong response type
val wrongResponse = Json.decodeFromJsonElement(AuthenticateResponse.serializer(), responseJson)
```

Another problem is **code duplication**:
```kotlin
// Have to write the same boilerplate code for each call
val initRequestJson = Json.encodeToJsonElement(InitializeRequest.serializer(), initRequest)
val initResponseJson = client.callMethod("initialize", initRequestJson)  
val initResponse = Json.decodeFromJsonElement(InitializeResponse.serializer(), initResponseJson)

val authRequestJson = Json.encodeToJsonElement(AuthenticateRequest.serializer(), authRequest)
val authResponseJson = client.callMethod("authenticate", authRequestJson)  
val authResponse = Json.decodeFromJsonElement(AuthenticateResponse.serializer(), authResponseJson)

// Same boilerplate for each handler
server.setHandler("initialize") { paramsJson ->
    val params = Json.decodeFromJsonElement(InitializeRequest.serializer(), paramsJson ?: JsonNull)
    val response = InitializeResponse(/* ... */)
    Json.encodeToJsonElement(InitializeResponse.serializer(), response)
}

server.setHandler("authenticate") { paramsJson ->
    val params = Json.decodeFromJsonElement(AuthenticateRequest.serializer(), paramsJson ?: JsonNull)
    val response = AuthenticateResponse(success = true)
    Json.encodeToJsonElement(AuthenticateResponse.serializer(), response)
}
```

A separate category of problems concerns **handlers**:
```kotlin
// Handler receives weakly typed JsonElement
server.setHandler("initialize") { paramsJson -> // paramsJson: JsonElement?
    // Need to manually deserialize
    val params = Json.decodeFromJsonElement(InitializeRequest.serializer(), paramsJson ?: JsonNull)
    
    // Can make mistake with deserialization type
    val wrongParams = Json.decodeFromJsonElement(AuthenticateRequest.serializer(), paramsJson ?: JsonNull) // Error!
    
    // Result also needs manual serialization
    val response = InitializeResponse(/* ... */)
    Json.encodeToJsonElement(InitializeResponse.serializer(), response)
}

// No guarantee of correspondence between method name and parameter types
server.setHandler("initialize") { paramsJson ->
    // Can accidentally process as different type
    val params = Json.decodeFromJsonElement(AuthenticateRequest.serializer(), paramsJson ?: JsonNull)
    // ...
}
```

Finally, the compiler cannot help with checks:
```kotlin
// Compiler cannot verify correctness:
server.setHandler("initilize") { /* ... */ } // typo in method name
server.setHandler("initialize") { params ->
    // wrong deserialization type  
    Json.decodeFromJsonElement(AuthenticateRequest.serializer(), params)
    // ...
}
```

All these problems make code error-prone and difficult to maintain.

## Solution: Strongly Typed Methods with AcpMethod

To solve these problems, the ACP SDK uses an elegant approach based on each method defining its input parameters and result type directly in its declaration. This allows the compiler to automatically infer types and guarantee their correctness.

### Basic Architecture

The foundation of the solution is the abstract `AcpMethod` class, which contains all necessary method metadata:

```kotlin
open class AcpMethod(val methodName: MethodName) {

    open class AcpRequestResponseMethod<TRequest: AcpRequest, TResponse: AcpResponse>(
        method: String,
        val requestSerializer: KSerializer<TRequest>,
        val responseSerializer: KSerializer<TResponse>
    ) : AcpMethod(MethodName(method))
}
```

The key idea is that each concrete method is defined as a singleton object that contains all necessary types and serializers:

```kotlin
object AgentMethods {
    object Initialize : AcpRequestResponseMethod<InitializeRequest, InitializeResponse>(
        "initialize", 
        InitializeRequest.serializer(), 
        InitializeResponse.serializer()
    )
    
    object Authenticate : AcpRequestResponseMethod<AuthenticateRequest, AuthenticateResponse>(
        "authenticate", 
        AuthenticateRequest.serializer(), 
        AuthenticateResponse.serializer()
    )
}
```

### Simple Generic Types Without Bounds

The first step toward improvement is introducing generic parameters without upper bounds. This allows methods to be type-safe without being tied to specific base types:

```kotlin
open class RpcMethod<TRequest, TResponse>(
    val methodName: String,
    val requestSerializer: KSerializer<TRequest>,
    val responseSerializer: KSerializer<TResponse>
)

object SimpleMethods {
    object Initialize : RpcMethod<InitializeRequest, InitializeResponse>(
        "initialize",
        InitializeRequest.serializer(),
        InitializeResponse.serializer()
    )
}
```

This already gives us automatic type inference and type safety, but doesn't provide additional capabilities yet.

Now we can create a typed call method that works with these method objects:

```kotlin
suspend fun <TRequest, TResponse> callTypedMethod(
    method: RpcMethod<TRequest, TResponse>,
    request: TRequest
): TResponse {
    val requestJson = Json.encodeToJsonElement(method.requestSerializer, request)
    val responseJson = sendRequestRaw(method.methodName, requestJson)
    return Json.decodeFromJsonElement(method.responseSerializer, responseJson)
}
```

Usage becomes much cleaner:

```kotlin
// Before: manual serialization and method name strings
val initRequest = InitializeRequest(protocolVersion = "1.0.0", clientCapabilities = ClientCapabilities())
val requestJson = Json.encodeToJsonElement(InitializeRequest.serializer(), initRequest)
val responseJson = client.callMethod("initialize", requestJson)
val response = Json.decodeFromJsonElement(InitializeResponse.serializer(), responseJson)

// After: type-safe method objects
val initRequest = InitializeRequest(protocolVersion = "1.0.0", clientCapabilities = ClientCapabilities())
val response = client.callTypedMethod(SimpleMethods.Initialize, initRequest)
// response is automatically inferred as InitializeResponse!

val authRequest = AuthenticateRequest(methodId = "oauth")
val authResponse = client.callTypedMethod(SimpleMethods.Authenticate, authRequest)
// authResponse is automatically inferred as AuthenticateResponse!
```

The compiler now guarantees that we pass the correct request type for each method, and the response type is automatically inferred.

Similarly, we can create typed handlers:

```kotlin
fun <TRequest, TResponse> setTypedHandler(
    method: RpcMethod<TRequest, TResponse>,
    handler: suspend (TRequest) -> TResponse
) {
    handlers[method.methodName] = { paramsJson ->
        val params = Json.decodeFromJsonElement(method.requestSerializer, paramsJson ?: JsonNull)
        val response = handler(params)
        Json.encodeToJsonElement(method.responseSerializer, response)
    }
}
```

Handler setup becomes type-safe:

```kotlin
// Before: weakly typed handlers with manual serialization
server.setHandler("initialize") { paramsJson ->
    val params = Json.decodeFromJsonElement(InitializeRequest.serializer(), paramsJson ?: JsonNull)
    val response = InitializeResponse(
        protocolVersion = params.protocolVersion,
        serverCapabilities = ServerCapabilities(),
        authMethods = listOf()
    )
    Json.encodeToJsonElement(InitializeResponse.serializer(), response)
}

// After: strongly typed handlers
server.setTypedHandler(SimpleMethods.Initialize) { params: InitializeRequest ->
    // params is automatically typed as InitializeRequest
    // return type must be InitializeResponse - compiler checks this!
    InitializeResponse(
        protocolVersion = params.protocolVersion,
        serverCapabilities = ServerCapabilities(),
        authMethods = listOf()
    )
}

server.setTypedHandler(SimpleMethods.Authenticate) { params: AuthenticateRequest ->
    // params is automatically typed as AuthenticateRequest
    AuthenticateResponse(success = true)
}
```

The compiler guarantees type safety: handlers automatically receive the correct parameter type and must return the correct response type.

**Important advantage**: Since type parameters are specified in the method objects themselves, we don't need to explicitly provide type arguments when calling `callTypedMethod` or `setTypedHandler`. The compiler automatically infers them from the method declaration:

```kotlin
// No need to write: callTypedMethod<InitializeRequest, InitializeResponse>(...)
val response = client.callTypedMethod(SimpleMethods.Initialize, initRequest)
//                                   ↑ compiler infers <InitializeRequest, InitializeResponse> from this

// No need to write: setTypedHandler<InitializeRequest, InitializeResponse>(...)
server.setTypedHandler(SimpleMethods.Initialize) { params ->
//                     ↑ compiler infers <InitializeRequest, InitializeResponse> from this
    InitializeResponse(/* ... */)
}
```

This makes the API both type-safe and concise - you get all the benefits of strong typing without verbose syntax.

**Why not use reified generics?**

Attentive readers might wonder why we don't use `inline` functions with `reified` generics for `callTypedMethod` and `setTypedHandler`, which could eliminate the need for explicit serializers:

```kotlin
// Alternative approach with reified generics
inline suspend fun <reified TRequest, reified TResponse> callTypedMethod(
    methodName: String, 
    request: TRequest
): TResponse {
    val requestJson = Json.encodeToJsonElement(serializer<TRequest>(), request)
    val responseJson = sendRequestRaw(methodName, requestJson)
    return Json.decodeFromJsonElement(serializer<TResponse>(), responseJson)
}
```

While this approach initially seems cleaner, we chose explicit serializers for two important reasons my colleague pointed out:

1. **Inline propagation** - `inline` functions "infect" the codebase when you want to build generic APIs on top of these methods, forcing all such wrapper functions to also become `inline`, which quickly spreads throughout the API surface
2. **Startup performance** - In our experience with IDE plugins, Kotlin's heavier metadata loading for classes with reified generics can impact application startup times, which is particularly important for development tools

These performance and architectural considerations led us to prefer explicit serializer parameters, keeping the API clean and startup times fast.

### Adding Upper Bounds to Access Properties

The next step is adding upper bounds that allow access to certain properties of requests and responses:

```kotlin
open class AcpMethod<TRequest : AcpRequest, TResponse : AcpResponse>(
    val methodName: String,
    val requestSerializer: KSerializer<TRequest>,
    val responseSerializer: KSerializer<TResponse>
)
```

Now we can write generic handlers that work with any method:

```kotlin
fun <TRequest : AcpRequest, TResponse : AcpResponse> setHandlerWithLogging(
    method: AcpMethod<TRequest, TResponse>,
    handler: suspend (TRequest) -> TResponse
) {
    server.setTypedHandler(method) { request ->
        // We can access base properties like 'id' from any request
        println("Processing ${method.methodName} with request ID: ${request.id}")
        handler(request)
    }
}
```

Upper bounds `TRequest : AcpRequest` and `TResponse : AcpResponse` guarantee that:
- All requests have base properties like `id`
- All responses have common AcpResponse properties
- The compiler can verify type compatibility
- We can write generic utility functions that work with any request/response type

### Specialized Types with Additional Constraints

The most interesting part begins when we add additional upper bounds to gain more specific capabilities. Consider `AcpSessionRequestResponseMethod`:

```kotlin
interface AcpWithSessionId {
    val sessionId: SessionId
}

open class AcpSessionRequestResponseMethod<TRequest, TResponse : AcpResponse>(
    method: String,
    requestSerializer: KSerializer<TRequest>,
    responseSerializer: KSerializer<TResponse>
) : AcpRequestResponseMethod<TRequest, TResponse>(method, requestSerializer, responseSerializer)
    where TRequest : AcpRequest, TRequest : AcpWithSessionId
```

Notice the `where TRequest : AcpRequest, TRequest : AcpWithSessionId` construct — these are multiple upper bounds. They mean that `TRequest` must simultaneously:
- Inherit `AcpRequest` (base request properties)
- Implement `AcpWithSessionId` (have `sessionId` property)

This allows us to access `sessionId` directly from method parameters:

```kotlin
// Examples of requests with sessionId:
@Serializable
data class PromptRequest(
    val sessionId: SessionId,
    val content: List<ContentBlock>
) : AcpRequest, AcpWithSessionId

@Serializable  
data class ReadTextFileRequest(
    val sessionId: SessionId,
    val path: String,
    val line: Int? = null
) : AcpRequest, AcpWithSessionId

// Definition of session methods
object ClientMethods {
    object SessionPrompt : AcpSessionRequestResponseMethod<PromptRequest, PromptResponse>(
        "session/prompt", 
        PromptRequest.serializer(), 
        PromptResponse.serializer()
    )
    
    object FsReadTextFile : AcpSessionRequestResponseMethod<ReadTextFileRequest, ReadTextFileResponse>(
        "fs/read_text_file",
        ReadTextFileRequest.serializer(), 
        ReadTextFileResponse.serializer()
    )
}
```

### Advantages of Specialized Types

Thanks to multiple upper bounds, we can:

**Create session-aware handlers** that automatically resolve sessions:
```kotlin
fun <TRequest, TResponse : AcpResponse> setSessionHandler(
    method: AcpSessionRequestResponseMethod<TRequest, TResponse>,
    handler: suspend (Session, TRequest) -> TResponse
) where TRequest : AcpRequest, TRequest : AcpWithSessionId {
    server.setTypedHandler(method) { request ->
        // Automatically extract sessionId and resolve session
        val sessionId = request.sessionId // Compiler knows this property exists!
        val session = sessionManager.getSession(sessionId) 
            ?: throw SessionNotFoundException("Session $sessionId not found")
        
        // Call handler with resolved session and original request
        handler(session, request)
    }
}

// Usage example:
server.setSessionHandler(ClientMethods.SessionPrompt) { session, request ->
    // Handler receives resolved Session object and typed request
    // No need to manually extract sessionId or look up session!
    session.processPrompt(request.content)
    PromptResponse(/* ... */)
}

server.setSessionHandler(ClientMethods.FsReadTextFile) { session, request ->
    // Same pattern works for any session method
    val content = session.fileSystem.readFile(request.path, request.line)
    ReadTextFileResponse(content = content)
}
```




This architecture allows creating a flexible and type-safe hierarchy of methods, where each level adds new capabilities while maintaining full type compatibility.

### The Magic of operator fun invoke

One interesting technique we can use is defining operator fun invoke for methods through extension functions:

```kotlin
suspend operator fun <TRequest: AcpRequest, TResponse: AcpResponse> 
AcpMethod.AcpRequestResponseMethod<TRequest, TResponse>.invoke(
    rpc: RpcMethodsOperations, 
    request: TRequest
): TResponse {
    return rpc.sendRequest(this, request)
}

fun <TRequest : AcpRequest, TResponse : AcpResponse> RpcMethodsOperations.sendRequest(
    method: AcpMethod.AcpRequestResponseMethod<TRequest, TResponse>,
    request: TRequest?
): TResponse {
    val params = request?.let { ACPJson.encodeToJsonElement(method.requestSerializer, request) }
    val responseJson = this.sendRequestRaw(method.methodName, params)
    return ACPJson.decodeFromJsonElement(method.responseSerializer, responseJson)
}
```

Thanks to this, method calls become simple and type-safe expressions:

```kotlin
// Before: lots of boilerplate code and potential errors
val initRequest = InitializeRequest(protocolVersion = "1.0.0", clientCapabilities = ClientCapabilities())
val requestJson = Json.encodeToJsonElement(InitializeRequest.serializer(), initRequest)
val responseJson = client.callMethod("initialize", requestJson)
val response = Json.decodeFromJsonElement(InitializeResponse.serializer(), responseJson)

// After: concise and type-safe call
val response = AcpMethod.AgentMethods.Initialize(protocol, InitializeRequest(
    protocolVersion = "1.0.0", 
    clientCapabilities = ClientCapabilities()
))
```

**Note**: While this approach provides elegant syntax, there's a limitation with Kotlin support in IDEA - unfortunately, it doesn't provide autocompletion or parameter hints when using `invoke` operators with complex generic parameters. This appears to be related to the complexity of type inference with generics, while regular operators work fine.

### Conclusion

The evolution from naive string-based JsonRPC calls to strongly typed method objects demonstrates the power of Kotlin's type system. By encapsulating method names, parameter types, and serializers in singleton objects, we achieved:

- **Full type safety** - the compiler prevents mismatched request/response types
- **Automatic type inference** - no need to specify generic parameters explicitly  
- **Specialized constraints** - multiple upper bounds enable session-aware handlers
- **Eliminated boilerplate** - serialization happens automatically
- **Localized complexity** - method declarations concentrate all complexity in one place, replacing scattered error-prone code with centralized type-safe definitions
- **Better IDE experience** - refactoring and find usages work seamlessly

While there are some limitations (like reduced IDE support for generic `operator fun invoke`), the benefits far outweigh the drawbacks. Developers can focus on business logic rather than protocol mechanics, while getting maximum compile-time safety from Kotlin's type system.

---

The complete implementation of this approach can be found in the [ACP Kotlin SDK](https://github.com/agentclientprotocol/acp-kotlin-sdk) repository, which I'm actively developing and maintaining.
